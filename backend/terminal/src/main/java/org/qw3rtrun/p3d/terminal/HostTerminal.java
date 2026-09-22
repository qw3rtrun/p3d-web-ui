package org.qw3rtrun.p3d.terminal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.terminal.msg.MetaData;
import org.qw3rtrun.p3d.terminal.msg.Replay;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.Math.min;
import static java.lang.String.format;

@Slf4j
@RequiredArgsConstructor
public class HostTerminal {

    private static final Logger TRAFFIC_LOGGER = LoggerFactory.getLogger(HostTerminal.class.getName() + "#traffic");

    private final Supplier<Flux<String>> inbound;
    private final Function<Publisher<String>, Mono<Void>> outbound;

    private Sinks.Many<Replay.Message> replays = Sinks.unsafe().many().replay().latest();

    private CommandProcessor outSide;
    private ReplaySubscriber inSide;

    public Mono<Void> start(Publisher<String> gcodes) {
        this.inSide = new ReplaySubscriber();
        this.outSide = new CommandProcessor(gcodes);
        inbound.get()
                .as(ReplyFlux::new)
                .subscribe(inSide);
        return outbound.apply(outSide);
    }

    public void stop() {
        outSide.cancel();
        inSide.handleCancel();
    }

    public Flux<Replay.Message> messageFlux() {
        return replays.asFlux();
    }

    public void onOk(Replay.Ok ok) {
        outSide.handleBackpressure(ok);
        publishPayload(ok);
    }

    /**
     * An {@code ok} is an acknowledgement, and it is sometimes also a reply.
     *
     * <p>{@code M105}, {@code M109} and {@code M190} all answer on the {@code ok} itself -
     * {@code ok T:45.26 /45.00 B:25.00 /0.00} - so an {@code ok} that is taken only as a window
     * credit and then dropped takes the answer with it. That is what happened before: every
     * temperature the host ever saw came from an {@code M155} auto-report, which arrives on a line
     * of its own, and an {@code M105} asked on demand was answered into the void.
     *
     * <p>The whole line goes out, {@code ok} included, because that prefix is what tells
     * {@code OkTemperatureRs} from {@code BareTemperatureRs} downstream - whether the report is an
     * acknowledgement or an unsolicited auto-report is exactly the distinction those two classes
     * exist to keep, and stripping the prefix here would erase it.
     *
     * <p>Two kinds of {@code ok} are held back. A bare {@code ok} carries nothing to decode, and an
     * advanced {@code ok} - {@code ok P15 B3 N100}, the ones {@code meta} was parsed out of - carries
     * only the window credit this method was already given. Publishing either would put a reply on
     * the bus that says nothing a subscriber did not already know.
     */
    private void publishPayload(Replay.Ok ok) {
        if (ok.payload().isEmpty() || ok.meta() != null) {
            return;
        }
        replays.tryEmitNext(new Replay.Message(ok.raw()));
    }

    void onBusy(String reason) {
        outSide.sentWithoutAck = 0;
    }

    void onResend(int lineNumber) {
        // TODO Unsupported yet
    }

    void onWait() {
        // TODO Unsupported yet
    }

    public static class ReplyFlux extends FluxOperator<String, Replay> {

        private final Flux<Replay> flux;

        public ReplyFlux(Flux<String> source) {
            super(source);
            this.flux = source
                    .flatMap(s -> Flux.fromStream(s.chars().mapToObj(i -> (char) i).map(Object::toString)))
                    .bufferUntil("\n"::equals)
                    .map(l -> String.join("", l).trim())
                    .doOnNext((in) -> TRAFFIC_LOGGER.debug("<- {}", in))
                    .map(Replay::parse);
        }

        @Override
        public void subscribe(CoreSubscriber<? super Replay> actual) {
            flux.subscribe(actual);
        }
    }

    public class CommandProcessor implements Processor<String, String>, Subscription {

        Publisher<String> gcode;
        Subscription subscription;
        Subscriber<? super String> subscriber;
        private final Requested subscriptionRequested = new Requested();
        private final Requested requestedFromGCode = new Requested();
        private volatile int sentWithoutAck = 0;
        private MetaData backpressure = new MetaData("", 1, 1, 0);
        private final Supplier<String> report = () -> format("(s=%s, r=%s w=%d b=%s)", subscriptionRequested, requestedFromGCode, sentWithoutAck, backpressure);

        public CommandProcessor(Publisher<String> gCode) {
            this.gcode = gCode;
        }

        public void handleBackpressure(Replay.Ok ok) {
            sentWithoutAck--;
            if (ok.meta() != null) {
                backpressure = ok.meta();
            }
            log.info("OUT: backpressure applied: {} {}", ok.raw(), report.get());
            outSide.processRequest();
        }

        long canProcess() {
            return min(subscriptionRequested.get(), backpressure.blockQueue() - sentWithoutAck - requestedFromGCode.get());
        }

        void processRequest() {
            long canProcess = canProcess();
            if (canProcess > 0) {
                requestedFromGCode.inc(canProcess);
                subscriptionRequested.dec(canProcess);
                log.info("Process request {} of {} but {}", canProcess, subscriptionRequested, report.get());
                subscription.request(canProcess);
            }
        }

        @Override
        public void subscribe(Subscriber<? super String> s) {
            gcode.subscribe(this);
            subscriber = s;
            subscriber.onSubscribe(this);
        }

        @Override
        public void request(long n) {
            subscriptionRequested.inc(n);
            log.info("Subscription Request {} {}", n, report.get());
            processRequest();
        }

        @Override
        public void cancel() {
            subscriptionRequested.reset();
            log.info("Subscription Cancel {}", report.get());
            subscription.cancel();
            subscriber.onComplete();
        }

        @Override
        public void onSubscribe(Subscription s) {
            log.info("OUT onSubscription {}", s);
            this.subscription = s;
        }

        @Override
        public void onNext(String gCode) {
            sentWithoutAck += 1;
            requestedFromGCode.dec();
            log.info("GCode Next {} {}", gCode, report.get());
            TRAFFIC_LOGGER.debug("-> {}", gCode);
            subscriber.onNext(gCode + "\n");
            processRequest();
        }

        @Override
        public void onError(Throwable t) {
            log.warn("GCode Error {}", report.get(), t);
            subscriber.onError(t);
        }

        @Override
        public void onComplete() {
            log.info("GCode Complete {}", report.get());
            //subscriber.onComplete();
        }
    }

    public class ReplaySubscriber implements Subscriber<Replay> {

        private Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            this.subscription = s;
            subscription.request(Long.MAX_VALUE);
        }

        public void handleCancel() {
            if (subscription != null) {
                subscription.cancel();
            }
            subscription = null;
        }

        @Override
        public void onNext(Replay replay) {
            log.info("IN: onNext({})", replay);
            switch (replay) {
                case Replay.Ok ok -> onOk(ok);
                case Replay.Message msg -> replays.tryEmitNext(msg);
                case Replay.Wait _ -> onWait();
                case Replay.Busy busy -> onBusy(busy.payload());
                case Replay.Resend resend -> onResend(resend.number());
                case Replay.Error error -> replays.tryEmitError(new MachineError(error.message()));
            }
        }

        @Override
        public void onError(Throwable t) {
            log.error("IN: onError()", t);
            replays.tryEmitError(t);
        }

        @Override
        public void onComplete() {
            log.warn("IN: onComplete()");
            replays.tryEmitComplete();
        }
    }

}


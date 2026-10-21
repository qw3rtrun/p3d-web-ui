package org.qw3rtrun.p3d.terminal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.protocol.AdvancedOkRs;
import org.qw3rtrun.p3d.g.protocol.BusyRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import org.qw3rtrun.p3d.g.protocol.OkRs;
import org.qw3rtrun.p3d.g.protocol.ResendRs;
import org.qw3rtrun.p3d.g.protocol.SimpleOkRs;
import org.qw3rtrun.p3d.g.protocol.WaitRs;
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
import reactor.core.publisher.SynchronousSink;

import java.util.concurrent.atomic.AtomicInteger;
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

    private Sinks.Many<GRs<?>> replays = Sinks.unsafe().many().replay().latest();

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

    /**
     * The replies worth telling a subscriber about, already decoded.
     *
     * <p>Typed values and not text, because the line was decoded once on the way in - by
     * {@code :gcode}'s {@code MarlinRsDecoder}, the one reply model in this repository. The copy
     * that used to live in {@code terminal.msg.Replay} is gone: it had drifted from the real wire
     * format in three places, and {@code PrinterReactor} was decoding the text a second time
     * anyway, so every line was being read by two parsers that disagreed.
     *
     * <p>Not every received line reaches here. The ones that drive the protocol and say nothing a
     * subscriber did not already learn from the send window are consumed: a bare and an advanced
     * {@code ok}, {@code wait}, and {@code Resend}. Everything else is published - except a line no
     * decoder claimed, which is dropped with a {@code #traffic} debug line, because {@code GRs} has
     * no value for "unread" and the raw text has no consumer.
     *
     * <p><strong>This flux terminates when the connection does, and at no other time.</strong> An
     * {@code onError} here means the inbound side died - the socket, or the decoder - and not that
     * the printer reported a fault. A printer fault is an {@code ErrorRs} value like any other
     * reply; see {@link ReplaySubscriber#onNext}.
     */
    public Flux<GRs<?>> messageFlux() {
        return replays.asFlux();
    }

    public void onOk(OkRs<?> ok) {
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
     * <p>Two kinds of {@code ok} are held back. {@link SimpleOkRs} carries nothing to decode, and
     * {@link AdvancedOkRs} - {@code ok N2 P15 B3} - carries only the window credit this method was
     * already given. Publishing either would put a reply on the bus that says nothing a subscriber
     * did not already know.
     *
     * <p>Everything else answering to {@link OkRs} goes out whole, and the {@code ok} survives as a
     * <em>type</em> rather than as a prefix on a string: {@code OkTemperatureRs} is the answer to
     * an {@code M105} and {@code BareTemperatureRs} is an unsolicited auto-report, and those two
     * classes exist to keep exactly that distinction.
     */
    private void publishPayload(OkRs<?> ok) {
        if (ok instanceof SimpleOkRs || ok instanceof AdvancedOkRs) {
            return;
        }
        emit(ok);
    }

    /**
     * The machine cannot take commands right now, so nothing is in flight that it will acknowledge.
     *
     * <p>Both spellings arrive here. The RepRap protocol's bare {@code busy: processing} is
     * {@code SimpleBusyRs}; Marlin writes the identical notice through its echo channel as
     * {@code echo:busy: processing}, which is {@code BusyNotice}, and both answer to
     * {@link BusyRs}. Matching the interface is the whole point of it being one: the parser this
     * replaced claimed the bare form only, so on a real Marlin this never fired and the send window
     * was never told the printer had stalled.
     */
    void onBusy(BusyRs<?> busy) {
        outSide.clearInFlight();
        emit(busy);
    }

    /**
     * The printer wants a line back, and this host has none to give it.
     *
     * <p>{@code Resend: n} is spec 8.5 step 5: the firmware rejected a numbered block - a bad
     * checksum, or a gap in the sequence - and discards everything that follows until it sees
     * {@code n} again. Answering it means replaying the exact bytes of block {@code n} and of every
     * block sent after it.
     *
     * <p><strong>This host cannot, because it does not number what it sends.</strong>
     * {@link CommandProcessor#onNext} writes the G-code text as it arrived - no {@code N} field, no
     * {@code *} checksum - so Marlin's sequence and checksum checks never apply to it and a normal
     * session never provokes a resend. One that does arrive is about a numbered line a caller
     * hand-fed through the queue, and the host has not kept it.
     *
     * <p>Closing the gap means numbering, checksumming, acknowledging and replaying outbound
     * blocks. {@code :gcode}'s {@code GSendWindow} is exactly that machine and must not be built a
     * second time here - but the transport cannot reach it as things stand: {@code GSendWindow.send}
     * takes a {@code GCommand}, this pipeline carries {@code String} from {@code GSender} onwards,
     * and {@code :gcode}'s main source set has no text-to-command path to bridge the two. The
     * numbering itself has to happen here and nowhere earlier, because {@link PublisherQueue}
     * reorders by {@link org.qw3rtrun.p3d.terminal.msg.Priority} and a number assigned upstream of
     * it would reach the wire out of sequence. The skill's debt list carries the rest.
     *
     * <p>Logged and not ignored. There is no recovery to attempt - a host that cannot replay the
     * block cannot repair the sequence either - but this is the moment a print stops being what the
     * caller asked for, and it should not pass in silence. Nothing is published: a {@code Resend:}
     * is protocol traffic the transport owns, and once the replay loop exists it will say nothing a
     * subscriber needs, exactly like an advanced {@code ok}.
     */
    void onResend(int lineNumber) {
        log.warn("IN: Resend: {} - the printer asked for a line back; this host does not number what"
                + " it sends and cannot replay it, so the session is out of sync from here", lineNumber);
    }

    void onWait() {
        // TODO Unsupported yet
    }

    /**
     * Puts a reply on the bus, and says so when it did not go.
     *
     * <p>{@code tryEmitNext} answers with an {@link Sinks.EmitResult} and fails silently when it is
     * not {@code OK} - a dropped reply here is a temperature reading that never reaches the UI, so
     * it is worth a line in the log rather than nothing at all.
     */
    private void emit(GRs<?> reply) {
        Sinks.EmitResult result = replays.tryEmitNext(reply);
        if (result.isFailure()) {
            log.warn("IN: reply dropped ({}): {}", result, reply);
        }
    }

    /**
     * Kills the bus, which is reserved for the transport dying.
     *
     * <p>One caller: {@link ReplaySubscriber#onError}. A fault the <em>printer</em> reported is a
     * value and goes through {@link #emit} - putting it here terminated the sink and every
     * subscriber of {@link #messageFlux()} with it, over a checksum the printer itself recovered
     * from on the next line.
     */
    private void emitError(Throwable t) {
        Sinks.EmitResult result = replays.tryEmitError(t);
        if (result.isFailure()) {
            log.warn("IN: error dropped ({})", result, t);
        }
    }

    public static class ReplyFlux extends FluxOperator<String, GRs<?>> {

        private final Flux<GRs<?>> flux;

        public ReplyFlux(Flux<String> source) {
            super(source);
            this.flux = source
                    .flatMap(s -> Flux.fromStream(s.chars().mapToObj(i -> (char) i).map(Object::toString)))
                    .bufferUntil("\n"::equals)
                    .map(l -> String.join("", l).trim())
                    .doOnNext((in) -> TRAFFIC_LOGGER.debug("<- {}", in))
                    .handle(ReplyFlux::decode);
        }

        /**
         * One received line as the reply it says it is, or nothing when no decoder claims it.
         *
         * <p>{@code handle} and not {@code map}, because {@code MarlinRsDecoder} answers with null
         * for a line outside the set it reads - the boot banner, an {@code M503} settings dump, a
         * calibration report - and a {@code Flux} may not carry a null. Null is the normal case and
         * not an error: the line goes to the traffic logger and is dropped, which is what the layer
         * above did with it before.
         */
        private static void decode(String line, SynchronousSink<GRs<?>> sink) {
            GRs<?> reply = MarlinRsDecoder.INSTANCE.decode(line);
            if (reply == null) {
                TRAFFIC_LOGGER.debug("<- (unread) {}", line);
                return;
            }
            sink.next(reply);
        }

        @Override
        public void subscribe(CoreSubscriber<? super GRs<?>> actual) {
            flux.subscribe(actual);
        }
    }

    public class CommandProcessor implements Processor<String, String>, Subscription {

        Publisher<String> gcode;
        Subscription subscription;
        Subscriber<? super String> subscriber;
        private final Requested subscriptionRequested = new Requested();
        private final Requested requestedFromGCode = new Requested();
        /**
         * Commands written to the printer that it has not acknowledged yet.
         *
         * <p>A {@link Requested} and not a counter of its own. It is not demand, but it is the same
         * discipline - a non-negative saturating count, incremented on one thread and decremented
         * on another - and the rule this module works to is that the accounting is <em>one</em>
         * mechanism rather than half of one. It was a {@code volatile int} with {@code += 1} in
         * {@link #onNext}, {@code --} in {@link #handleBackpressure} and {@code = 0} in
         * {@link HostTerminal#onBusy}: {@code volatile} publishes a write, it does not make a
         * read-modify-write atomic, and the first two of those run on different threads.
         */
        private final Requested sentWithoutAck = new Requested();
        /**
         * The printer's own buffer counts, as the last advanced {@code ok} reported them.
         *
         * <p>An {@link AdvancedOkRs} and not a transport-local copy of the same three numbers. The
         * record that used to hold them here read the {@code N} field in a position Marlin does not
         * write it in, so on a numbered line it matched nothing and the window never widened.
         *
         * <p>Starts at one block, which is the window a printer that reports no counts at all gets:
         * one command out, and nothing more until it is acknowledged.
         *
         * <p>{@code volatile} because it is written from the inbound thread and read by
         * {@link #canProcess()} on whichever thread is draining. A reference write is atomic on its
         * own, so a snapshot is all the window needs and no lock is involved.
         */
        private volatile AdvancedOkRs backpressure = new AdvancedOkRs(1, 1, null);
        /**
         * The drain guard: zero when no thread is in {@link #processRequest()}, and otherwise the
         * number of calls that still have to be accounted for.
         */
        private final AtomicInteger wip = new AtomicInteger();
        private final Supplier<String> report = () -> format("(s=%s, r=%s w=%s b=%s)", subscriptionRequested, requestedFromGCode, sentWithoutAck, backpressure);

        public CommandProcessor(Publisher<String> gCode) {
            this.gcode = gCode;
        }

        public void handleBackpressure(OkRs<?> ok) {
            sentWithoutAck.dec();
            if (ok instanceof AdvancedOkRs advanced) {
                backpressure = advanced;
            }
            log.info("OUT: backpressure applied: {} {}", ok.encode(), report.get());
            outSide.processRequest();
        }

        /** Nothing is in flight that the printer will acknowledge, so the window is all of it. */
        void clearInFlight() {
            sentWithoutAck.reset();
        }

        long canProcess() {
            return min(subscriptionRequested.get(),
                    backpressure.getBlockQueue() - sentWithoutAck.get() - requestedFromGCode.get());
        }

        /**
         * Moves as much of the subscriber's demand upstream as the printer currently has room for.
         *
         * <p>A drain loop, and the only place the three counters are moved against each other.
         * Atomic counters make each one safe on its own; they do not make
         * {@code canProcess() -> inc/dec -> request(n)} one step, and that sequence has to be one
         * step or two threads - a {@code request(n)} from the subscriber and an {@code ok} off the
         * netty loop - can each read the same room and each claim it, asking the source for twice
         * what the printer can hold.
         *
         * <p>So {@code wip} serialises the section the Reactive Streams way rather than a lock: the
         * first caller in runs the loop, a caller that arrives while it is running leaves a mark
         * and returns, and the thread inside goes round again to pick the mark up. That also keeps
         * re-entrancy honest - {@link #onNext} calls this from inside {@code subscription.request},
         * and the nested call now defers to the loop already on the stack instead of interleaving
         * with it.
         */
        void processRequest() {
            if (wip.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            for (; ; ) {
                long canProcess = canProcess();
                if (canProcess > 0) {
                    requestedFromGCode.inc(canProcess);
                    subscriptionRequested.dec(canProcess);
                    log.info("Process request {} of {} but {}", canProcess, subscriptionRequested, report.get());
                    subscription.request(canProcess);
                }
                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    return;
                }
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
            sentWithoutAck.inc();
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

    public class ReplaySubscriber implements Subscriber<GRs<?>> {

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

        /**
         * Routes one decoded reply: half of it into the send window, half onto the bus.
         *
         * <p>Matched on the family interfaces rather than on concrete classes, because that is what
         * they are for. {@link OkRs} covers the bare, the advanced and the temperature-carrying
         * {@code ok} - all three are acknowledgements and all three free a slot. {@link BusyRs}
         * covers the bare {@code busy:} and Marlin's {@code echo:busy:}. A reply type added to
         * {@code :gcode} under either interface arrives here already routed.
         *
         * <p>The arms are not a partition and have to be read as a sequence: {@code OkTemperatureRs}
         * is an {@link OkRs} first and a temperature report second, which is the acknowledgement
         * the window is waiting for.
         *
         * <p><strong>{@code ErrorRs} has no arm, deliberately.</strong> It falls to the default and
         * is published as a value, because on a Marlin an error is routine protocol traffic and the
         * printer carries on afterwards:
         *
         * <pre>
         * &gt;&gt; N3 M105*1
         * &lt;&lt; Error:checksum mismatch, Last Line: 2
         * &lt;&lt; Resend: 3
         * &lt;&lt; ok
         * </pre>
         *
         * <p>This used to call {@code tryEmitError}, which terminates the {@code Sinks.Many}
         * behind {@link HostTerminal#messageFlux()} - so one mistyped checksum killed the reply bus
         * for the rest of the session, for every current and future subscriber, while the printer
         * went on answering normally. A terminal signal on that flux now means one thing only: the
         * inbound side is gone, which is {@link #onError} and {@link #onComplete} below.
         *
         * <p>Which errors are fatal is not decided here. {@code :gcode} keeps the machine's own
         * words as text on purpose - the spec sorts errors into recoverable, non-fatal and
         * shut-the-machine-down on prose it only gives as examples - and that is a decision for the
         * host, which can now see every error it needs to make it.
         */
        @Override
        public void onNext(GRs<?> reply) {
            log.info("IN: onNext({})", reply);
            switch (reply) {
                case OkRs<?> ok -> onOk(ok);
                case BusyRs<?> busy -> onBusy(busy);
                case WaitRs _ -> onWait();
                case ResendRs resend -> onResend(resend.getLineNumber());
                default -> emit(reply);
            }
        }

        @Override
        public void onError(Throwable t) {
            log.error("IN: onError()", t);
            emitError(t);
        }

        @Override
        public void onComplete() {
            log.warn("IN: onComplete()");
            Sinks.EmitResult result = replays.tryEmitComplete();
            if (result.isFailure()) {
                log.warn("IN: completion dropped ({})", result);
            }
        }
    }

}

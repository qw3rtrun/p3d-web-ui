package org.qw3rtrun.p3d.terminal;

import ch.qos.logback.classic.pattern.ContextNameConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.g.code.GCode;
import org.qw3rtrun.p3d.g.decoder.*;
import org.qw3rtrun.p3d.g.event.AdvancedOkReceivedEvent;
import org.qw3rtrun.p3d.g.event.GEvent;
import org.qw3rtrun.p3d.g.event.OKReceivedEvent;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.Connection;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.tcp.TcpClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Arrays.asList;

@Slf4j
@RequiredArgsConstructor
public class Terminal {

    private volatile long subscriptionRequested = 0;
    private volatile long requestedFromGCode = 0;
    private volatile int sentWithoutAck = 0;
    private volatile int knownEmptyBufferSpace = 1;

    private final Supplier<String> report = () -> format("(s=%d, r=%d w=%d b=%d)", subscriptionRequested, requestedFromGCode, sentWithoutAck, knownEmptyBufferSpace);

    public Connection connection;

    public List<GProcessor> processors = new CopyOnWriteArrayList<>();

    private Sinks.Many<GEvent> eventStream = Sinks.unsafe().many().multicast().onBackpressureBuffer();

    public void connect() {
        if (connection == null) {
            connection =
                    TcpClient.create()
                            .host("localhost")
                            .port(8099)
                            .doOnConnect(cfg -> System.out.println("onConnect " + cfg))
                            .doOnConnected(cfg -> System.out.println("onConnected " + cfg))
                            .doOnDisconnected(cfg -> System.out.println("onDisconnected " + cfg))
                            .doOnResolveError((c, exp) -> exp.printStackTrace())
                            .connectNow();
            connection.inbound().receive()
                    .asString()
                    .as(BufferedStringFlux::new)
                    .asLines()
                    .as(GEventFlux::new)
                    .asGEvent()
                    .doOnNext(this::interceptOk)
                    .log()
                    .doOnNext(g -> eventStream.tryEmitNext(g))
                    .doOnComplete(() -> eventStream.tryEmitComplete())
                    .doOnError(e -> eventStream.tryEmitError(e))
                    .subscribe();
        }
    }

    public Flux<GEvent> inbound() {
        return eventStream.asFlux();
    }

    public Mono<Void> send(GCode gCode) {
        return send(Mono.just(gCode));
    }

    public Mono<Void> send(GCode first, GCode... last) {
        return send(Flux.just(first).concatWith(Flux.just(last)));
    }

    public Mono<Void> send(Publisher<? extends GCode> gCode) {
        var processor = new GProcessor(gCode);
        return connection.outbound()
                .sendString(processor)
                .then();
    }

    private void interceptOk(GEvent event) {
        if (event instanceof OKReceivedEvent ok) {
            handleOk(ok);
        }
    }

    public void handleOk(OKReceivedEvent ok) {
        sentWithoutAck--;
        if (ok instanceof AdvancedOkReceivedEvent adv) {
            knownEmptyBufferSpace = adv.blockQueue();
        }
        log.info("Event Ok received {} {}", ok, report.get());
        processRequest();
    }

    void processRequest() {
        processors.forEach(GProcessor::processRequest);
    }

    public static class BufferedStringFlux extends FluxOperator<String, String> {

        public BufferedStringFlux(Flux<String> source) {
            super(source);
        }

        @Override
        public void subscribe(CoreSubscriber<? super String> actual) {
            source.subscribe(actual);
        }

        public Flux<String> endsWith(String ending) {
            return source
                    .flatMap(s -> Flux.fromStream(s.chars().mapToObj(i -> (char) i).map(Object::toString)))
                    .bufferUntil(ending::equals)
                    .map(l -> String.join("", l));
        }

        public Flux<String> asLines() {
            return endsWith("\n")
                    .map(String::trim);
        }
    }

    public static class GEventFlux extends FluxOperator<String, String> {

        private final CompositeDecoder decoder = new CompositeDecoder(asList(
                new OkDecoder(),
                new TemperatureReportedDecoder(),
                new CapabilityReportDecoder(),
                new FirmwareReportDecoder(),
                new UnknownStringDecoder()
        ));

        public GEventFlux(Flux<String> source) {
            super(source);
        }

        @Override
        public void subscribe(CoreSubscriber<? super String> actual) {
            source.subscribe(actual);
        }

        public Flux<GEvent> asGEvent() {
            return source
                    .map(decoder::decode)
                    .filter(Optional::isPresent)
                    .map(Optional::get);
        }
    }

    public class GProcessor implements Processor<GCode, String>, Subscription {

        Publisher<? extends GCode> gcode;

        Subscription subscription;

        Subscriber<? super String> subscriber;

        long canProcess() {
            return min(subscriptionRequested, knownEmptyBufferSpace - sentWithoutAck - requestedFromGCode);
        }

        public void register() {
            log.info("Register Processor");
            processors.add(this);
        }

        public void unregister() {
            log.info("Unregister Processor");
            processors.remove(this);
        }

        void processRequest() {
            long canProcess = canProcess();
            if (canProcess > 0) {
                requestedFromGCode += canProcess;
                subscriptionRequested -= canProcess;
                log.info("Process request {}, {}", canProcess, report.get());
                subscription.request(canProcess);
            }
        }

        public GProcessor(Publisher<? extends GCode> gCode) {
            this.gcode = gCode;
        }

        @Override
        public void subscribe(Subscriber<? super String> s) {
            gcode.subscribe(this);
            subscriber = s;
            subscriber.onSubscribe(this);
        }

        @Override
        public void request(long n) {
            subscriptionRequested += n;
            log.info("Subscription Request {} {}", n, report.get());
            processRequest();
        }

        @Override
        public void cancel() {
            subscriptionRequested = 0;
            log.info("Subscription Cancel {}", report.get());
            subscription.cancel();
            unregister();
        }

        @Override
        public void onSubscribe(Subscription s) {
            log.info("GCode onSubscription {}", s);
            this.subscription = s;
            register();
        }

        @Override
        public void onNext(GCode gCode) {
            sentWithoutAck += 1;
            requestedFromGCode--;
            log.info("GCode Next {} {}", gCode, report.get());
            subscriber.onNext(gCode.encode() + "\n");
            processRequest();
        }

        @Override
        public void onError(Throwable t) {
            log.warn("GCode Error {}", report.get(), t);
            subscriber.onError(t);
            unregister();
        }

        @Override
        public void onComplete() {
            log.info("GCode Complete {}", report.get());
            subscriber.onComplete();
            unregister();
        }
    }
}


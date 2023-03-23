package org.qw3rtrun.p3d.terminal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.g.event.GEvent;
import org.qw3rtrun.p3d.g.decoder.CompositeDecoder;
import org.qw3rtrun.p3d.g.decoder.OkDecoder;
import org.qw3rtrun.p3d.g.decoder.TemperatureReportedDecoder;
import org.qw3rtrun.p3d.g.decoder.UnknownStringDecoder;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;

import java.util.Optional;

import static java.util.Arrays.asList;

@Slf4j
@RequiredArgsConstructor
public class ReactiveTerminal {

    public Mono<Connection> connecting() {
        return TcpClient.create()
                    .host("localhost")
                    .port(8099)
                    .doOnConnect(cfg -> System.out.println("onConnect " + cfg))
                    .doOnConnected(cfg -> System.out.println("onConnected " + cfg))
                    .doOnDisconnected(cfg -> System.out.println("onDisconnected " + cfg))
                    .doOnResolveError((c, exp) -> exp.printStackTrace())
                    .connect()
                    .log(this.getClass().getSimpleName() + "#connect")
                    .cast(Connection.class);
    }

    public Flux<String> rawInbound(Connection con) {
        return con.inbound().receive()
                .asString()
                .as(BufferedStringFlux::new)
                .asLines()
                .log(this.getClass().getSimpleName() + "#inbound");
    }

    public Flux<GEvent> inbound(Connection con) {
        return con.inbound().receive()
                .asString()
                .as(BufferedStringFlux::new)
                .asLines()
                .as(GEventFlux::new)
                .asGEvent()
                .log(this.getClass().getSimpleName() + "#inbound");
    }

    public Mono<Void> outbound(Flux<String> sending, Connection con) {
        return con.outbound().sendString(sending
                .map(String::trim)
                .log(this.getClass().getSimpleName() + "#outbond")
                .map(str -> str + "\n")
        ).then();
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

}


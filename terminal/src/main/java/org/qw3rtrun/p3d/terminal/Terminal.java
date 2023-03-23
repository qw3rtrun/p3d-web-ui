package org.qw3rtrun.p3d.terminal;

import lombok.RequiredArgsConstructor;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;

@RequiredArgsConstructor
public class Terminal {

    private Connection connection;

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
        }
    }

    public Mono<? extends Connection> connecting() {
        return TcpClient.create()
                .host("localhost")
                .port(8099)
                .doOnConnect(cfg -> System.out.println("onConnect " + cfg))
                .doOnConnected(cfg -> System.out.println("onConnected " + cfg))
                .doOnDisconnected(cfg -> System.out.println("onDisconnected " + cfg))
                .doOnResolveError((c, exp) -> exp.printStackTrace())
                .connect();
    }

    public void disconnect() {
        if (connection != null) {
            connection.dispose();
        }
        connection = null;
    }

    public Flux<String> inbound() {
        return connection.inbound().receive().asString();
    }

    public Flux<String> inbound(Connection con) {
        return con.inbound().receive()
                .asString()
                .as(BufferedStringFlux::new)
                .asLines()
                .log();
    }

    public void outbound(Flux<String> send) {
        connection.outbound().sendString(send).then().subscribe();
    }

    public class BufferedStringFlux extends FluxOperator<String, String> {

        public BufferedStringFlux(Flux<? extends String> source) {
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
            return endsWith("\n");
        }
    }

}


package org.qw3rtrun.p3d.terminal;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;

@Slf4j
public class TerminalManager {

    private final TcpClient client;

    public TerminalManager(@NonNull ConnectionDetails connectionDetails) {
        this.client = TcpClient.create()
                .host(connectionDetails.host())
                .port(connectionDetails.port())
                .doOnConnect(cfg -> System.out.println("TCPClient doOnConnect " + cfg))
                .doOnConnected(cfg -> System.out.println("TCPClient doOnConnected " + cfg))
                .doOnDisconnected(cfg -> System.out.println("TCPClient doOnDisconnected " + cfg))
                .doOnResolveError((c, exp) -> exp.printStackTrace());
    }

    public Mono<HostTerminal> connect() {
        return client
                .connect()
                .log(this.getClass().getSimpleName() + "#connect")
                .cast(Connection.class)
                .map(conn -> new HostTerminal(
                        () -> conn.inbound().receive()
                                .asString(),
                        (p) -> conn.outbound()
                                .sendString(p).then()
                ));
    }
}


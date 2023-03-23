package org.qw3rtrun.p3d.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.terminal.ReactiveTerminal;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

@Slf4j
@RequiredArgsConstructor
public class WSGCodeHandler implements WebSocketHandler {

    private final ReactiveTerminal terminal;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("handle()");
        return terminal.connecting()
                .flatMap(connection -> connect(session, connection)
                );
    }

    private Mono<Void> connect(WebSocketSession session, Connection connection) {
        Mono<Void> send = sending(session, connection);
        Mono<Void> receive = receiving(session, connection);

        return Mono.zip(send, receive).then();
    }

    private Mono<Void> sending(WebSocketSession session, Connection conn) {
        log.info("sending()");
        return session.send(
                terminal.inbound(conn)
                        .map(Object::toString)
                        .map(session::textMessage)
        );
    }

    private Mono<Void> receiving(WebSocketSession session, Connection conn) {
        log.info("receiving()");
        return terminal.outbound(
                session.receive()
                        .doOnComplete(conn::dispose)
                        .map(WebSocketMessage::getPayloadAsText),
                conn
        );
    }
}

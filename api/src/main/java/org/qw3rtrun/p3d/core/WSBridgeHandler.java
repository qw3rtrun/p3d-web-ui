package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.terminal.ReactiveTerminal;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class WSBridgeHandler implements WebSocketHandler {

    private final ReactiveTerminal terminal;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("handle()");
        return terminal.connecting()
                .flatMap(connection -> connect(session)
                );
    }

    private Mono<Void> connect(WebSocketSession session) {
        Mono<Void> send = sending(session);
        Mono<Void> receive = receiving(session);

        return Mono.zip(send, receive).then();
    }

    private Mono<Void> sending(WebSocketSession session) {
        log.info("sending()");
        return session.send(
                terminal.rawInbound()
                        .map(session::textMessage)
        );
    }

    private Mono<Void> receiving(WebSocketSession session) {
        log.info("receiving()");
        return terminal.outbound(
                session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
        );
    }
}
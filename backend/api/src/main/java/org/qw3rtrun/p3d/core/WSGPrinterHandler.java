package org.qw3rtrun.p3d.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class WSGPrinterHandler implements WebSocketHandler {

    private final PrinterReactorManager reactorManager;

    private final ObjectMapper mapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("handle()");
        var id = (UUID) session.getAttributes().get("id");
        var printer = reactorManager.getReactor(id);
        return session.send(
                printer.updates()
                        .map(value -> {
                            try {
                                return mapper.writeValueAsString(value);
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .map(session::textMessage)
        );
    }

}

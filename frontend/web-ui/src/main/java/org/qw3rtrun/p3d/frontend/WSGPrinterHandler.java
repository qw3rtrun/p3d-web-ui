package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.PrinterReactorManager;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
        var printer = reactorManager.openReactor(id);
        return session.send(
                printer.updates()
                        .<String>handle((value, sink) -> {
                            try {
                                sink.next(mapper.writeValueAsString(value));
                            } catch (JacksonException e) {
                                sink.error(new RuntimeException(e));
                            }
                        })
                        .map(session::textMessage)
        );
    }

}

package org.qw3rtrun.p3d.frontend;

import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.core.PrinterReactorManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The frontend submodel's beans: the web-facing half of what used to be {@code ApiApplication}.
 *
 * <p>Both of them are on their way out and are carried over only so that this move stays a move.
 * The WebSocket mapping is the sole consumer of a handler that cannot work - it reads a session
 * attribute nothing writes, and no client connects to it, because the UI uses SSE. The stub
 * management service answers every query with {@code null} under {@code @ConditionalOnMissingBean},
 * which turns a misconfigured farm into an empty one instead of a failure. Plan item A4 deletes
 * both.
 */
@Configuration
public class FrontendConfiguration {

    @Bean
    public HandlerMapping webSocketHandlerMapping(PrinterReactorManager manager, ObjectMapper mapper) {
        Map<String, WebSocketHandler> map = Map.of("/ws-printer/*", new WSGPrinterHandler(manager, mapper));

        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(1);
        handlerMapping.setUrlMap(map);
        return handlerMapping;
    }

    @Bean
    @ConditionalOnMissingBean
    public MachineManagementService stubMachineManagementService() {
        return new MachineManagementService() {
            @Override
            public Descriptor fetch(UUID uuid) {
                return null;
            }

            @Override
            public Descriptor add(String name, ConnectionDetails connection) {
                return null;
            }

            @Override
            public Stream<Descriptor> fetchAll() {
                return Stream.empty();
            }

            @Override
            public void delete(UUID uuid) {
            }

            @Override
            public void disconnect(UUID uuid) {
            }

            @Override
            public void connect(UUID uuid) {
            }
        };
    }
}

package org.qw3rtrun.p3d.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qw3rtrun.p3d.core.model.PrinterAggregate;
import org.qw3rtrun.p3d.core.service.MachineDescriptionManager;
import org.qw3rtrun.p3d.core.service.PrinterAggregateManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }

    @Bean
    public HandlerMapping webSocketHandlerMapping(PrinterReactorManager manager, ObjectMapper mapper) {
        Map<String, WebSocketHandler> map = new HashMap<>();
        map.put("/ws-printer/*", new WSGPrinterHandler(manager, mapper));

        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(1);
        handlerMapping.setUrlMap(map);
        return handlerMapping;
    }

    @Bean
    @DependsOn
    public PrinterReactorManager printer(MachineDescriptionManager descriptionManager, PrinterAggregateManager aggregateManager) {
        return new PrinterReactorManager(descriptionManager, aggregateManager);
    }

    @Bean
    public static PrinterAggregateManager stubStateManager() {
        return new PrinterAggregateManager() {
            @Override
            public PrinterAggregate loadState(UUID uuid) {
                return new PrinterState(uuid);
            }

        };
    }

//    @Bean
//    @Order()
//    @ConditionalOnMissingBean(PrinterManager.class)
//    public PrinterManager stubPrinterManager() {
//        return new PrinterManager() {
//            @Override
//            public Printer fetch(UUID uuid) {
//                throw new UnsupportedOperationException("Manager Stub doesn't support Printer fetch");
//            }
//
//            @Override
//            public Printer add(String name, PrinterConnection connection) {
//                throw new UnsupportedOperationException("Manager Stub doesn't support Printer add");
//            }
//
//            @Override
//            public Stream<Printer> fetchAll() {
//                return Stream.empty();
//            }
//
//            @Override
//            public void delete(UUID uuid) {
//            }
//        };
//    }
}

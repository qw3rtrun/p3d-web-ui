package org.qw3rtrun.p3d.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qw3rtrun.p3d.core.model.Printer;
import org.qw3rtrun.p3d.core.model.PrinterConnection;
import org.qw3rtrun.p3d.core.service.PrinterManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

import java.util.*;
import java.util.stream.Stream;

@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }

    @Bean
    public HandlerMapping webSocketHandlerMapping(PrinterReactor printer, ObjectMapper mapper) {
        Map<String, WebSocketHandler> map = new HashMap<>();
//        map.put("/ws-emitter", new WSBridgeHandler(new ReactiveTerminal()));
//        map.put("/ws-gcode", new WSGCodeHandler(new ReactiveTerminal()));
        map.put("/ws-printer", new WSGPrinterHandler(printer, mapper));

        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(1);
        handlerMapping.setUrlMap(map);
        return handlerMapping;
    }

    @Bean
    public PrinterReactor printer() {
        return new PrinterReactor(new PrinterState());
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

package org.qw3rtrun.p3d.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

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

}

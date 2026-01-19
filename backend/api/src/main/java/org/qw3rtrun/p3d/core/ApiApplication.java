package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.core.bus.CommandQueryBus;
import org.qw3rtrun.p3d.core.bus.CommandQueryGateway;
import org.qw3rtrun.p3d.core.bus.CommandQueryService;
import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import org.qw3rtrun.p3d.core.model.Descriptor;
import org.qw3rtrun.p3d.core.service.MachineManagementService;
import org.qw3rtrun.p3d.core.service.PrinterAggregateManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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
    public CommandQueryGateway gateway(List<CommandQueryService<?, ?>> services) {
        return new CommandQueryBus(services);
    }


    @Bean
    @DependsOn
    public PrinterReactorManager printer(CommandQueryGateway managementService, PrinterAggregateManager aggregateManager) {
        return new PrinterReactorManager(managementService, aggregateManager);
    }

    @Bean
    public static PrinterAggregateManager stubStateManager() {
        return PrinterState::new;
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

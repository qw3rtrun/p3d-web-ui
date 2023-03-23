package org.qw3rtrun.p3d.api;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.g.G;
import org.qw3rtrun.p3d.g.code.GCode;
import org.qw3rtrun.p3d.g.event.GEvent;
import org.qw3rtrun.p3d.g.event.TemperatureReport;
import org.qw3rtrun.p3d.terminal.ReactiveTerminal;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.qw3rtrun.p3d.g.code.AutoReportHotendTemperature.m155;
import static reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST;

@Slf4j
public class PrinterReactor {

    private Scheduler executor = Schedulers.single();
    private PrinterState printer;
    private Sinks.Many<TemperatureReport> updates;
    private Sinks.Many<String> codes = Sinks.unsafe().many().multicast().onBackpressureBuffer();

    private ReactiveTerminal terminal = new ReactiveTerminal();

    private Map<Class<?>, Consumer<Object>> invokers = new HashMap<>();

    public PrinterReactor(PrinterState printer) {
        this.printer = printer;
        updates = Sinks.many().replay().latestOrDefault(printer.getTemperature());
        this.printer.setEmitter(this::onEvent);
        this.printer.setG(new G(str -> codes.emitNext(str, FAIL_FAST)));
        collectInvokers();
        connect().log(this.getClass().getSimpleName() + "#printer").subscribe();
        printer.onConnected();
    }

    private void collectInvokers() {
        Arrays.stream(PrinterState.class.getDeclaredMethods())
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> GCode.class.isAssignableFrom(method.getParameterTypes()[0])
                        || GEvent.class.isAssignableFrom(method.getParameterTypes()[0]))
                .forEach(method -> {
                    log.info("register invoker {}", method);
                    invokers.put(method.getParameterTypes()[0], obj -> {
                        try {
                            method.invoke(printer, obj);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        } catch (InvocationTargetException e) {
                            throw new RuntimeException(e);
                        }
                    });
                });
    }

    public Mono<Void> connect() {
        log.info("connect()");
        return terminal.connecting()
                .flatMap(this::connect);
    }

    private Mono<Void> connect(Connection connection) {
        Mono<Void> events = gEvent(connection);
        Mono<Void> gcodes = gCode(connection);

        return Mono.zip(events, gcodes)
                .then();
    }

    private Mono<Void> gEvent(Connection conn) {
        log.info("gEvent()");
        return terminal.inbound(conn)
                .publishOn(executor)
                .doOnNext(this::invoke)
                .then();
    }

    private Mono<Void> gCode(Connection conn) {
        log.info("receiving()");
        return terminal.outbound(
                codes.asFlux(),
                conn
        );
    }

    public Mono<TemperatureReport> state() {
        return Mono.defer(() -> Mono.just(printer.getTemperature())).publishOn(executor);
    }

    public Flux<TemperatureReport> updates() {
        return updates.asFlux()
                .publishOn(executor)
                .doOnNext(rep -> log.info("<- {}", rep));
    }

    public <T extends GCode> Mono<Void> handle(Mono<T> setTemp) {
        return setTemp
                .publishOn(executor)
                .doOnNext(cmd -> log.info("-> {}", cmd))
                .doOnNext(this::invoke)
                .then();
    }

    public void invoke(Object message) {
        if (!invokers.containsKey(message.getClass())) {
            Arrays.stream(message.getClass().getInterfaces())
                    .filter(type -> invokers.containsKey(type))
                    .findFirst()
                    .ifPresent(type -> invokers.put(message.getClass(), invokers.get(type)));
        }
        invokers.getOrDefault(message.getClass(), m -> log.warn("No invoker for {}", m))
                .accept(message);
    }

    private void onEvent(TemperatureReport report) {
        updates.emitNext(report, FAIL_FAST);
    }
}

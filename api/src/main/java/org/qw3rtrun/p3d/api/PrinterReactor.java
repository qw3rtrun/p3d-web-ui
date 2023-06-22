package org.qw3rtrun.p3d.api;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.dto.ConnectCmd;
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

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

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
        collectInvokers();
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

    public Mono<Void> handleConnectCmd(Mono<ConnectCmd> connectCmd) {
        return connectCmd.
                doOnSuccess(this::handleConnectCmd)
                .then();
    }

    public void handleConnectCmd(ConnectCmd cmd) {
        if (cmd.connect() && !terminal.isConnected()) {
            connect();
        } else if (!cmd.connect() && terminal.isConnected()) {
            disconnect();
        }
    }

    public void connect() {
        log.info("connect()");
        terminal.connecting()
                .publishOn(executor)
                .flatMap(v -> handleConnect())
                .log(this.getClass().getSimpleName() + "#printer")
                .doOnTerminate(printer::onDisconnected)
                .subscribe();
    }

    public void disconnect() {
        log.info("disconnect()");
        terminal.disconnecting();
    }

    private Mono<Void> handleConnect() {
        codes = Sinks.unsafe().many().multicast().onBackpressureBuffer();
        Mono<Void> events = gEvent();
        Mono<Void> gcodes = gCode();
        printer.onConnected(new G(str -> codes.emitNext(str, FAIL_FAST)));
        return Mono.zip(events, gcodes)
                .then();
    }

    private Mono<Void> gEvent() {
        log.info("gEvent()");
        return terminal.inbound()
                .publishOn(executor)
                .doOnNext(this::invoke)
                .then();
    }

    private Mono<Void> gCode() {
        log.info("receiving()");
        return terminal.outbound(
                codes.asFlux()
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

    public <T extends GCode> Mono<Void> handle(Mono<T> command) {
        return command
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

package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.msg.*;
import org.qw3rtrun.p3d.g.G;
import org.qw3rtrun.p3d.g.code.GCode;
import org.qw3rtrun.p3d.g.decoder.*;
import org.qw3rtrun.p3d.terminal.HostTerminal;
import org.qw3rtrun.p3d.terminal.PublisherQueue;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import org.qw3rtrun.p3d.terminal.msg.Replay;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static java.util.Arrays.asList;
import static reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST;

@Slf4j
public class PrinterReactor {

    //TODO
    private final CompositeDecoder decoder = new CompositeDecoder(asList(
            new WaitReceivedDecoder(),
            new OkDecoder(),
            new TemperatureReportedDecoder(),
            new CapabilityReportDecoder(),
            new FirmwareReportDecoder(),
            new UnknownStringDecoder()
    ));

    private final TerminalManager manager;
    private final PrinterState printer;

    private HostTerminal terminal;

    private Scheduler executor = Schedulers.single();
    private Sinks.Many<EventMessage> updates;

    private PublisherQueue<String, Priority> commandQueue = new PublisherQueue<>();

    private Map<Class<?>, Consumer<Object>> invokers = new HashMap<>();

    public PrinterReactor(TerminalManager manager, PrinterState printer) {
        this.manager = manager;
        this.printer = printer;
        updates = Sinks.many().replay().latestOrDefault(new EventMessage(printer.getId(), new MachineOfflineEvent()));
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
        printer.handle(cmd);
        if (cmd.connect() && terminal == null) {
            connect();
        } else if (!cmd.connect() && terminal != null) {
            disconnect();
        }
    }

    public void connect() {
        log.info("connect()");
        manager.connect()
                .publishOn(executor)
                .flatMap(v -> onConnected(v))
                .log(this.getClass().getSimpleName() + "#printer")
                .doOnTerminate(printer::onOffline)
                .subscribe();
    }

    public void disconnect() {
        log.info("disconnect()");
        terminal.stop();
        terminal = null;
        printer.onOffline();
        //terminal.disconnecting();
    }

    private Mono<Void> onConnected(HostTerminal terminal) {
        commandQueue = new PublisherQueue<>();
        this.terminal = terminal;
        Mono<Void> events = gEvent();
        Mono<Void> gcodes = gCode();
        printer.onOnline(new G(str -> commandQueue.addPublisher(Priority.REGULAR, Mono.just(str))));
        return Mono.zip(events, gcodes)
                .then();
    }

    private Mono<Void> gEvent() {
        log.info("gEvent()");
        return terminal.messageFlux()
                .publishOn(executor)
                .map(Replay.Message::raw)
                .map(decoder::decode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .doOnNext(this::invoke)
                .then();
    }

    private Mono<Void> gCode() {
        log.info("receiving()");
        return terminal.start(Flux.from(commandQueue));
    }

    public Mono<TemperatureReport> state() {
        return Mono.defer(() -> Mono.just(printer.getTemperature())).publishOn(executor);
    }

    public Flux<EventMessage> updates() {
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

    private void onEvent(GEvent report) {
        updates.emitNext(new EventMessage(printer.getId(), report), FAIL_FAST);
    }
}

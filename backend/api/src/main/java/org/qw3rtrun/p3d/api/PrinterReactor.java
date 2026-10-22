package org.qw3rtrun.p3d.api;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.msg.*;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.terminal.GSender;
import org.qw3rtrun.p3d.terminal.HostTerminal;
import org.qw3rtrun.p3d.terminal.PublisherQueue;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Arrays.asList;
import static reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST;

@Slf4j
public class PrinterReactor {

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
                .filter(method -> GRq.class.isAssignableFrom(method.getParameterTypes()[0])
                        || GEvent.class.isAssignableFrom(method.getParameterTypes()[0]))
                .forEach(method -> {
                    log.info("register invoker {}", method);
                    invokers.put(method.getParameterTypes()[0], obj -> {
                        try {
                            method.invoke(printer, obj);
                        } catch (IllegalAccessException | InvocationTargetException e) {
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
                .flatMap(this::onConnected)
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
        printer.onOnline(new GSender(this::enqueue));
        return Mono.zip(events, gcodes)
                .then();
    }

    /**
     * The sink {@link GSender} writes into.
     *
     * <p>A method reference rather than a lambda on purpose: {@code GSender} has both a
     * {@code Consumer<String>} and a Kotlin {@code (String) -> Unit} constructor, and an
     * implicitly-typed lambda whose body returns a value is potentially compatible with each, so
     * {@code new GSender(str -> …)} does not compile. An exact {@code void} method reference picks
     * the {@code Consumer} overload, which is the one that exists for Java callers.
     */
    private void enqueue(String gcode) {
        commandQueue.addPublisher(Priority.REGULAR, Mono.just(gcode));
    }

    /**
     * The replies from the printer, invoked on {@link PrinterState}.
     *
     * <p>No decoding here any more. {@link HostTerminal} hands over {@code GRs} values that
     * {@code MarlinRsDecoder} already read off the wire, so the line is parsed exactly once. This
     * used to take raw text from a {@code Replay.Message} and run {@code MarlinRsDecoder} over it -
     * which meant two parsers saw every line, the transport's own and this one, and they did not
     * agree about {@code ok N2 P15 B3} or {@code echo:busy:}.
     *
     * <p>A line no decoder claims never arrives. That is the normal case and not an error - the
     * {@code M503} settings dump, the boot banner, every calibration report - and the transport
     * logs it on its traffic logger where the bytes are.
     */
    private Mono<Void> gEvent() {
        log.info("gEvent()");
        return terminal.messageFlux()
                .publishOn(executor)
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

    public <T extends GRq<?>> Mono<Void> handle(Mono<T> command) {
        return command
                .publishOn(executor)
                .doOnNext(cmd -> log.info("-> {}", cmd))
                .doOnNext(this::invoke)
                .then();
    }

    public void invoke(Object message) {
        if (!invokers.containsKey(message.getClass())) {
            findInvoker(message.getClass())
                    .ifPresent(invoker -> invokers.put(message.getClass(), invoker));
        }
        invokers.getOrDefault(message.getClass(), m -> log.warn("No invoker for {}", m))
                .accept(message);
    }

    /**
     * The invoker registered for the nearest supertype of {@code type}, breadth-first.
     *
     * <p>The whole supertype graph and not just {@code getInterfaces()}. A reply class now reaches
     * its event interface through its own family interface - {@code OkTemperatureRs} implements
     * {@code TemperatureRs}, and it is {@code TemperatureRs} that implements
     * {@code TemperatureReportedEvent} - so a one-level search finds {@code TemperatureRs} and
     * {@code OkRs}, neither of which has an invoker, and every temperature report is dropped with
     * "No invoker for". The classes this replaced each declared the event interface directly,
     * which is why one level was enough before and is not now.
     */
    private Optional<Consumer<Object>> findInvoker(Class<?> type) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            Consumer<Object> invoker = invokers.get(current);
            if (invoker != null) {
                return Optional.of(invoker);
            }
            queue.addAll(asList(current.getInterfaces()));
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }
        }
        return Optional.empty();
    }

    private void onEvent(GEvent report) {
        updates.emitNext(new EventMessage(printer.getId(), report), FAIL_FAST);
    }
}

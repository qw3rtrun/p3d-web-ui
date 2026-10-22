package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.msg.*;
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

import static reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST;

@Slf4j
public class PrinterReactor {

    private final TerminalManager manager;
    private final PrinterState printer;

    private HostTerminal terminal;

    private Scheduler executor = Schedulers.single();
    private Sinks.Many<EventMessage> updates;

    private PublisherQueue<String, Priority> commandQueue = new PublisherQueue<>();

    public PrinterReactor(TerminalManager manager, PrinterState printer) {
        this.manager = manager;
        this.printer = printer;
        updates = Sinks.many().replay().latestOrDefault(new EventMessage(printer.getId(), new MachineOfflineEvent()));
        this.printer.setEmitter(this::onEvent);
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
    }

    /**
     * Releases everything this reactor holds, for good.
     *
     * <p>Not the same thing as {@link #disconnect()}, which is a state the printer can come back
     * from: after {@code stop} the reactor is finished, its subscribers have been told so, and the
     * manager has let go of it. It is what a deleted printer gets.
     *
     * <p>Safe on a reactor that never connected - which is the normal case for one built by a
     * lookup and never used - because {@code disconnect} dereferences a terminal that would not be
     * there.
     *
     * <p>The sink is completed rather than abandoned. A subscriber of {@link #updates()} is
     * typically a browser holding an SSE connection; leaving the sink hanging leaves the browser
     * waiting for a machine that no longer exists, and {@code Sinks.Many} keeps every one of them
     * referenced until it terminates.
     */
    public void stop() {
        log.info("stop()");
        if (terminal != null) {
            disconnect();
        }
        Sinks.EmitResult result = updates.tryEmitComplete();
        if (result.isFailure()) {
            log.warn("the update stream did not complete ({})", result);
        }
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
                .doOnNext(printer::onEvent)
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
                .doOnNext(printer::onCommand)
                .then();
    }

    private void onEvent(GEvent report) {
        updates.emitNext(new EventMessage(printer.getId(), report), FAIL_FAST);
    }
}

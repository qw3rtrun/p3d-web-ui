package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.reactivestreams.Publisher;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MCapabilityReportedEvent;
import org.qw3rtrun.p3d.api.msg.MConnectionChangedEvent;
import org.qw3rtrun.p3d.api.msg.MFirmwareReportedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.terminal.GSender;
import org.qw3rtrun.p3d.terminal.HostTerminal;
import org.qw3rtrun.p3d.terminal.PublisherQueue;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
public class PrinterReactor {

    /**
     * How long a thread will spin waiting for the update sink to be free.
     *
     * <p>{@code FAIL_FAST} was correct while every emission came from the one {@code executor}
     * thread. B3 added two more sources - a failed attempt is announced from whichever scheduler
     * the backoff ran on, and a dropped link from Netty's - so two emissions can genuinely race,
     * and failing fast there would silently drop a state change rather than report one late.
     */
    private static final Sinks.EmitFailureHandler SERIALISE =
            Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1));

    private final TerminalManager manager;
    private final PrinterState printer;
    private final ConnectionPolicy policy;

    private HostTerminal terminal;

    /** Where replies and state changes are handled, so the aggregate is touched by one thread. */
    private final Scheduler executor;

    /** Where the backoff between connection attempts is timed. A virtual one in tests. */
    private final Scheduler retries;

    private final Sinks.Many<EventMessage> updates;

    /**
     * The running attempt chain, or null when nothing is dialling.
     *
     * <p>This is what makes a disconnect <em>stop</em> rather than merely be recorded. A retry is a
     * subscription holding a timer, so cancelling the intent means disposing it; without this
     * handle a machine told to disconnect mid-backoff would come back a few seconds later, and the
     * only trace would be a connection nobody asked for.
     */
    private Disposable attempts;

    private PublisherQueue<String, Priority> commandQueue = new PublisherQueue<>();

    /**
     * Where facts about the machine itself go: to the roster, which keeps them.
     *
     * <p>{@code core} is the only module that can learn what a printer is, because learning it
     * means asking the printer. {@code printer-management} is where static facts about a machine
     * belong. So this is the seam where the one hands to the other - as an event, because a
     * submodel does not call another.
     */
    private final ApplicationEventPublisher roster;

    public PrinterReactor(TerminalManager manager, PrinterState printer) {
        this(manager, printer, event -> {
        });
    }

    public PrinterReactor(TerminalManager manager, PrinterState printer, ApplicationEventPublisher roster) {
        this(manager, printer, roster, ConnectionPolicy.standard(), Schedulers.single(), Schedulers.parallel());
    }

    public PrinterReactor(TerminalManager manager, PrinterState printer, ApplicationEventPublisher roster,
                          ConnectionPolicy policy, Scheduler executor, Scheduler retries) {
        this.roster = roster;
        this.manager = manager;
        this.printer = printer;
        this.policy = policy;
        this.executor = executor;
        this.retries = retries;
        this.updates = Sinks.many().replay()
                .latestOrDefault(new EventMessage(printer.getId(), printer.connectionAsEvent()));
        this.printer.setEmitter(this::onEvent);
    }

    public Mono<Void> handleConnectCmd(Mono<ConnectCmd> connectCmd) {
        return connectCmd.
                doOnSuccess(this::handleConnectCmd)
                .then();
    }

    /**
     * The one place a connect command is acted on.
     *
     * <p>It used to be two: this method decided whether to dial by looking at {@code terminal},
     * while {@link PrinterState#handle(ConnectCmd)} kept a {@code connected} flag of its own and
     * carried a {@code //TODO}. The aggregate now owns the intent and this owns the machinery, and
     * both branches are unconditional because {@link #connect()} and {@link #disconnect()} are each
     * safe to call in any state - which is what the old {@code terminal == null} test was standing
     * in for, badly: with retries there are states where no terminal exists and a connection is
     * very much in progress.
     */
    public void handleConnectCmd(ConnectCmd cmd) {
        printer.handle(cmd);
        if (cmd.connect()) {
            connect();
        } else {
            disconnect();
        }
    }

    /**
     * Dial the machine, and keep dialling it while a connection is wanted.
     *
     * <p><strong>The retry wraps the whole conversation and not just the dial.</strong> Everything
     * from {@code manager.connect()} to the end of {@link #onConnected} is one chain, so a link
     * that drops after an hour re-enters it exactly where a refused connection does - which is what
     * makes "reconnect a machine that dropped" the same code path as "try again", rather than a
     * second mechanism that would need its own backoff and its own bugs.
     *
     * <p><strong>A completed conversation is turned into an error on purpose.</strong>
     * {@code onConnected} completes when the socket closes cleanly, and a clean close is still the
     * connection being gone; left as a completion it would fall straight out of the chain and the
     * printer would sit in {@code ONLINE} forever with nothing attached.
     *
     * <p><strong>The failure is announced from {@code doBeforeRetry} and not from a
     * {@code doOnError}.</strong> That is what keeps the attempt numbers on the event stream
     * honest: {@code doBeforeRetry} runs only once the filter has passed and another dial is
     * genuinely going to happen, so a machine never announces that it is backing off towards an
     * attempt that will never be made. The two ways the series can end instead reach the
     * {@code doOnError} below - the attempts ran out, or a disconnect withdrew the intent - and
     * both mean {@code OFFLINE}.
     *
     * <p>{@code maxAttempts} on the policy counts the first dial, which Reactor's
     * {@code Retry.backoff} does not - it counts retries - hence the minus one.
     */
    public void connect() {
        if (attempts != null && !attempts.isDisposed()) {
            log.debug("already connecting to {}", printer.getId());
            return;
        }
        log.info("connecting to {}", printer.getId());
        attempts = manager.connect()
                .publishOn(executor)
                .flatMap(this::onConnected)
                .then(Mono.defer(() -> Mono.error(new ClosedByPeer("the machine closed the connection"))))
                .retryWhen(Retry.backoff(Math.max(0, policy.maxAttempts() - 1), policy.firstBackoff())
                        .maxBackoff(policy.maxBackoff())
                        .scheduler(retries)
                        .filter(cause -> printer.wantsToBeConnected())
                        .doBeforeRetry(signal -> printer.onOffline(describe(signal.failure()))))
                .doOnError(cause -> printer.onGaveUp(describe(cause)))
                .subscribe(ignored -> {
                }, cause -> log.warn("gave up on {}: {}", printer.getId(), describe(cause)));
    }

    /**
     * Stop, and stop trying.
     *
     * <p>Idempotent and safe in every state, including the two the old version could not survive:
     * mid-backoff, where there is no terminal to stop, and never-connected, where
     * {@code terminal.stop()} was a {@code NullPointerException}.
     */
    public void disconnect() {
        log.info("disconnecting from {}", printer.getId());
        if (attempts != null) {
            attempts.dispose();
            attempts = null;
        }
        if (terminal != null) {
            terminal.stop();
            terminal = null;
        }
    }

    /**
     * Releases everything this reactor holds, for good.
     *
     * <p>Not the same thing as {@link #disconnect()}, which is a state the printer can come back
     * from: after {@code stop} the reactor is finished, its subscribers have been told so, and the
     * manager has let go of it. It is what a deleted printer gets.
     *
     * <p>The sink is completed rather than abandoned. A subscriber of {@link #updates()} is
     * typically a browser holding an SSE connection; leaving the sink hanging leaves the browser
     * waiting for a machine that no longer exists, and {@code Sinks.Many} keeps every one of them
     * referenced until it terminates.
     */
    public void stop() {
        log.info("stop()");
        disconnect();
        Sinks.EmitResult result = updates.tryEmitComplete();
        if (result.isFailure()) {
            log.warn("the update stream did not complete ({})", result);
        }
    }

    private Mono<Void> onConnected(HostTerminal terminal) {
        if (this.terminal != null) {
            // A redial: the dead one still holds a reply subscriber and a send window.
            this.terminal.stop();
        }
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

    /**
     * Feeds a job's blocks to this machine at {@code BACKGROUND}, behind everything interactive.
     *
     * <p>The returned {@code Mono} completes when the source is exhausted and errors when it fails,
     * which is {@code PublisherQueue.addPublisher}'s answer and the thing plan item C3's
     * prerequisite added for exactly this caller.
     *
     * <p><strong>{@code BACKGROUND} is what makes a print interruptible.</strong> A temperature
     * query or an emergency stop is queued at a higher priority and the queue drains that first,
     * then hands its remaining demand back here - so the job resumes at the block it had reached
     * rather than being restarted or dropped.
     *
     * <p>Refused when there is no connection: the queue is built by {@link #onConnected} and a feed
     * into a machine that is not there would sit forever looking like a print.
     */
    public Mono<Void> feed(Publisher<String> blocks) {
        var queue = commandQueue;
        if (queue == null || terminal == null) {
            return Mono.error(new IllegalStateException(
                    "printer " + printer.getId() + " is not connected, so nothing can be fed to it"));
        }
        return queue.addPublisher(Priority.BACKGROUND, blocks);
    }

    /**
     * Sends a transfer that may not be interleaved with anything - an SD upload.
     *
     * <p><strong>{@code M28} turns the wire into a file sink</strong>: every line after it is
     * written into the file on the card rather than executed, until {@code M29}. So a command from
     * any other source that reaches the wire in between is <em>stored in the G-code</em>, and
     * nothing on either end reports it - the printer takes it happily and the fault surfaces much
     * later as a line of nonsense mid-print.
     *
     * <p>{@code REALTIME} and <strong>exclusive</strong>, which are two different guarantees and
     * both are needed. The priority keeps it at the head of the queue; exclusivity is what covers
     * the case priority cannot, a source that was handed demand <em>before</em> the transfer was
     * queued and still holds it. {@code REALTIME} is also the slot with only {@code EMERGENCY}
     * above it, so an emergency stop is the one thing that can take the wire away - and taking it
     * away aborts the transfer, because a half-written file cannot be resumed.
     *
     * <p>The returned {@code Mono} completes when the transfer is exhausted, errors when it fails,
     * and errors with {@code PublisherQueue.Preempted} when an emergency stop took the connection.
     */
    public Mono<Void> uploadToCard(Publisher<String> transfer) {
        var queue = commandQueue;
        if (queue == null || terminal == null) {
            return Mono.error(new IllegalStateException(
                    "printer " + printer.getId() + " is not connected, so nothing can be written to its card"));
        }
        return queue.addExclusivePublisher(Priority.REALTIME, transfer);
    }

    /**
     * Puts one of the host's own facts about this machine on its event stream.
     *
     * <p><strong>Not an aggregate event</strong>, which is what {@link #onEvent} otherwise carries,
     * and the distinction is deliberate: {@code SdUploadProgressed} is a report about work
     * <em>this host is doing to</em> a machine, not a change in what the machine is. The aggregate
     * has no opinion on how far a file transfer has got and should not be made to hold one.
     *
     * <p>It goes on this stream rather than the application bus - which is where a job's progress
     * goes - because an upload is about one machine's card, and the page watching for it is already
     * subscribed here.
     */
    public void announce(Object event) {
        onEvent(event);
    }

    /**
     * Puts a fixed sequence of blocks in front of whatever is printing.
     *
     * <p>{@code REGULAR}, so it preempts a job feeding at {@code BACKGROUND} - which is the whole
     * point: a cancellation that queued behind the rest of the file would cool the machine down
     * some hours after it was asked for.
     *
     * <p><strong>Not the user command path.</strong> {@code PrinterState.onCommand} is where a
     * request from a person is routed and refused if it makes no sense; this is a sequence the
     * host decided on and has to be able to send as a unit. Bare blocks, per the transport's
     * contract - no numbering, no checksum, no comment.
     */
    public void interject(List<String> blocks) {
        var queue = commandQueue;
        if (queue == null || terminal == null) {
            log.warn("{} is not connected, so {} was not sent", printer.getId(), blocks);
            return;
        }
        queue.addPublisher(Priority.REGULAR, Flux.fromIterable(blocks));
    }

    /**
     * How many blocks the firmware has not acknowledged yet.
     *
     * <p>The gap between what a job has sent and what the machine has taken - at most the send
     * window, because the window will not pull another block until one comes back. Plan item C4
     * reports it rather than hiding it, so that "sent" is never mistaken for "printed".
     *
     * <p>Read off the last {@code BlockAcknowledged}, which is the only place the count exists: the
     * send window is inside the transport and no subscriber can see one.
     */
    public int blocksInFlight() {
        return printer.getBlocksInFlight();
    }

    /** Passes an edited spec to the aggregate - see {@code PrinterReactorManager.redeclare}. */
    public void declared(MachineSpec spec) {
        printer.declared(spec);
    }

    /**
     * The last hotend reading, or nothing at all.
     *
     * <p><strong>Empty is an answer.</strong> A machine nothing has connected to has not reported a
     * temperature, and this used to say so with {@code (-1, -1, -1)} - a reading that cannot be told
     * apart from one the firmware really sent, in a record that had no way to say "unknown". The
     * reply model from {@code :gcode} does have one, so the absence travels as an absence.
     */
    public Mono<HeaterReading> state() {
        return Mono.defer(() -> Mono.justOrEmpty(printer.getTemperature())).publishOn(executor);
    }

    /** Where the machine's connection is, answered without opening the stream. */
    public Mono<ConnectionStatus> connection() {
        return Mono.defer(() -> Mono.just(printer.getConnection())).publishOn(executor);
    }

    public Flux<EventMessage> updates() {
        return updates.asFlux()
                .publishOn(executor)
                .doOnNext(rep -> log.info("<- {}", rep));
    }

    /**
     * One application command, as the Marlin commands that carry it, applied as a single task.
     *
     * <p><strong>The whole list goes to the aggregate in one hop on purpose.</strong> A relative
     * move is {@code G91}, the move, then {@code G90}, and emitting those as three elements would
     * make them three tasks on this executor - so another request's command could be scheduled
     * between them and be interpreted in relative mode. One {@code doOnNext} over the list makes
     * the batch atomic with respect to everything else this printer is asked to do.
     */
    public Mono<Void> handle(List<GRq<?>> commands) {
        return Mono.just(commands)
                .publishOn(executor)
                .doOnNext(batch -> batch.forEach(cmd -> log.info("-> {}", cmd)))
                .doOnNext(batch -> batch.forEach(printer::onCommand))
                .then();
    }

    /**
     * One event from the aggregate: to this machine's own subscribers, to the farm, to the roster.
     *
     * <p>The farm hop is the application event bus rather than a second sink handed in here, which
     * keeps plan item D2's farm view from having to be wired into every reactor as it is built -
     * and the bus is what this repository's skill calls the one dispatch mechanism. It carries
     * perhaps one or two messages a second per machine: a temperature auto-report and whatever the
     * aggregate decided was worth announcing, not every acknowledged block.
     */
    private void onEvent(Object report) {
        var message = new EventMessage(printer.getId(), report);
        updates.emitNext(message, SERIALISE);
        roster.publishEvent(message);
        report(report);
    }

    /**
     * Passes on the things the roster wants to remember about a machine.
     *
     * <p>Firmware and capabilities are only reached when the aggregate decided the report
     * <em>changed</em> something, because that is what {@link PrinterState} emits on - so a printer
     * auto-reporting the same capabilities every connect does not write to the database every
     * connect.
     *
     * <p>The connection state is the B3 addition, and it is what makes the roster's persisted
     * column <em>derived</em> rather than a second opinion. Nothing else can tell the roster this:
     * knowing whether a machine is connected means holding the socket, which only this module does.
     */
    private void report(Object event) {
        switch (event) {
            case FirmwareInfoRs firmware -> roster.publishEvent(new MFirmwareReportedEvent(
                    printer.getId(), firmware.getKeyValues(), LocalDateTime.now()));
            case CapabilityRs capability -> roster.publishEvent(new MCapabilityReportedEvent(
                    printer.getId(), capability.getCapability(), capability.getEnabled(), LocalDateTime.now()));
            case ConnectionStateChanged change -> roster.publishEvent(new MConnectionChangedEvent(
                    printer.getId(), change.to(), change.reason(), LocalDateTime.now()));
            default -> {
            }
        }
    }

    private static String describe(Throwable cause) {
        if (cause instanceof ClosedByPeer) {
            return cause.getMessage();
        }
        var root = cause.getCause() == null ? cause : cause.getCause();
        return root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    /**
     * The conversation ended without anybody failing.
     *
     * <p>A value in the shape of an exception, which is what the retry operator understands. It
     * never leaves this class: {@link #describe} unwraps it back into the sentence it carries, so
     * what a user sees on the event stream is a reason and not a stack trace.
     */
    static final class ClosedByPeer extends RuntimeException {
        ClosedByPeer(String message) {
            super(message, null, false, false);
        }
    }
}

package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MCapabilityReportedEvent;
import org.qw3rtrun.p3d.api.msg.MConnectionChangedEvent;
import org.qw3rtrun.p3d.api.msg.MFirmwareReportedEvent;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.terminal.GSender;
import org.qw3rtrun.p3d.terminal.HostTerminal;
import org.qw3rtrun.p3d.terminal.PublisherQueue;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import org.reactivestreams.Publisher;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One printer's running conversation: connection, command queue and reply stream, each handled on
 * this printer's own pair of single-threaded schedulers ({@link PrinterThreads}, one pair per
 * instance since #54 - the earlier {@code Schedulers.single()} returned the JVM-wide cached
 * scheduler, not a new one, so one slow handler anywhere stalled the whole farm).
 *
 * <h3>Threading</h3>
 * {@code executor} is where the aggregate is touched; {@code listeners} is where whatever its
 * decisions cost (a roster listener's {@code @Transactional} JPA write) is charged, so neither
 * blocks the other. {@code attempts} (the running retry chain) is reached from three threads - an
 * HTTP thread via {@link #handleConnectCmd}, this printer's own executor, and the listener thread
 * (a republished {@code MRegisteredEvent} reaching {@code PrinterReactorManager.onRegistered}) - so
 * it is {@code volatile}; that is the happens-before only, not atomicity, so a concurrent connect
 * and disconnect can still both decide to act (left open; see the skill's known-debt list).
 * {@code streamEnded} is a plain field because {@link Outbox} calls its consumer from one thread at
 * a time, which supplies the happens-before it needs. {@code stopped}, {@code liveSubscribers} and
 * {@code subscribersGone} coordinate shutdown - see below.
 *
 * <h3>The update sink</h3>
 * {@code updates} is a safe multicast {@code directBestEffort} sink fed only through
 * {@link #outbox}, never written to directly or made {@code unsafe()}: three threads can genuinely
 * emit at once (this executor, the backoff scheduler, netty's on a dropped link), and a safe sink
 * <em>refuses</em> a concurrent emission ({@code FAIL_NON_SERIALIZED}) rather than serialising it -
 * routed through {@link Outbox} that result becomes unreachable. It used to be fed with
 * {@code EmitFailureHandler.busyLooping}, which spins on whichever thread emitted and throws once
 * the spin runs out; the throw skipped {@link #report}, so a firmware fact was lost permanently
 * (not merely late - {@code PrinterState.on(FirmwareInfoRs)} suppresses an identical later
 * {@code M115}), and the handler's own javadoc warns against caching it, which a {@code static
 * final} field does, expiring its deadline one second after the class loads.
 *
 * It replays nothing: a late subscriber of {@link #updates()} gets {@link PrinterState#snapshot()}
 * (current facts) rather than the sink's last message (history presented as news - an earlier job's
 * {@code BlocksAbandoned} handed to a watcher that just opened the page). The live half is
 * subscribed before the snapshot is read (see {@link CatchUp}), at a cost of at most one repeated
 * value.
 *
 * <h3>Shutdown</h3>
 * {@link #stop()} is idempotent ({@code stopped}), disconnects, and parks - does not necessarily
 * publish - a {@code StreamEnd} on the same outbox as every other emission, so it cannot terminate
 * the sink underneath an event another thread is mid-publishing ({@code FAIL_TERMINATED}).
 * Whichever thread actually drains that marker completes the sink and only then disposes
 * {@code executor} and {@code listeners} via {@link #disposeWhenIdle}, never
 * {@code scheduler.dispose()} directly: that calls {@code shutdownNow()}, discarding the
 * {@code onComplete} task {@code tryEmitComplete()} just handed to a subscriber's
 * {@code publishOn} worker on that same scheduler, which leaves an SSE browser on a deleted
 * printer waiting forever. {@code PrinterReactorManagerTest.aReleasedReactorCompletesItsUpdateStream}
 * and {@code PrinterReactorThreadingTest.aStopThatRacesAnEmissionStillCompletesTheUpdateStream} pin
 * the two halves of this.
 *
 * {@link #awaitSubscribersGone(Duration)} is the stronger guarantee {@link #stop()} alone cannot
 * give: Spring Boot's own graceful shutdown sampled a parked-but-undelivered {@code StreamEnd} as
 * idle and severed the SSE response mid-write. {@link #liveSubscribers} counts a subscriber gone on
 * completion, error or cancellation; {@link #subscribersGone} completes from whichever of
 * {@link #stop()} (nobody was watching) or the last subscriber's {@code doFinally} (somebody was)
 * runs second, and never throws on timeout, so one stuck browser cannot fail a farm-wide shutdown.
 *
 * <h3>Connect and retry</h3>
 * {@link #connect()} and {@link #disconnect()} are both safe to call in any state, including
 * mid-backoff and never-connected. {@link #connect()} wraps the whole conversation -
 * {@code manager.connect()} through {@code onConnected} - in one {@code retryWhen}, so a dropped
 * link re-enters where a refused connection would. A clean close is turned into a
 * {@link ClosedByPeer} error on purpose, or the chain completes and leaves the printer
 * {@code ONLINE} with nothing attached. The failure is announced from {@code doBeforeRetry}, not
 * {@code doOnError}, so a machine never announces backing off towards an attempt it will not make;
 * {@code policy.maxAttempts()} counts the first dial, so {@code Retry.backoff} (which counts only
 * retries) gets {@code maxAttempts - 1}. {@link PrinterState#wantsToBeConnected()} gates the start
 * of a series, not just its continuation - without that gate,
 * {@code ConnectivityIntegrationTest.disconnectingClosesTheSocket} spent thirty seconds waiting for
 * {@code OFFLINE} on a machine a queued roster announcement kept redialling.
 *
 * <h3>The command queue</h3>
 * Four priorities share one {@link PublisherQueue}: a job feeds at {@code BACKGROUND} (preemptible
 * by anything else); a cancellation interjects at {@code REGULAR} so it does not queue behind the
 * rest of a file; an SD upload takes the queue exclusively at {@code REALTIME}, because {@code M28}
 * turns the wire into a file sink until {@code M29} and anything interleaved is silently written
 * into the file rather than run; only {@code EMERGENCY} can take the wire from an upload, aborting
 * it (a half-written file cannot resume). {@link #handle(List)} applies one command's whole Marlin
 * translation as a single task, because a relative move is {@code G91}/move/{@code G90} and
 * splitting it across three tasks would let another request interleave in relative mode.
 *
 * <h3>Replies</h3>
 * {@link #gEvent()} takes already-decoded {@code GRs} values from {@link HostTerminal} - no second
 * parser here, which used to disagree with the transport's own about lines like
 * {@code ok N2 P15 B3} and {@code echo:busy:}. An unclaimed line (an {@code M503} dump, the boot
 * banner) is normal and logged on the transport's own traffic logger. {@link #onEvent} fans one
 * event out three ways, each a non-blocking offer that cannot fail: {@link #toFarm} (droppable - a
 * missed dashboard update is a refresh) and {@link #toRoster} (lossless - a missed fact about the
 * machine is gone for good), both via {@link Handoff} on the shared {@code listeners} thread, so a
 * WARN about a dropped farm event can also mean a stuck JPA write; then this reactor's own
 * subscribers via {@link #emit}. All three see the decision in the order it was made, because the
 * roster's persisted connection state depends on a single-consumer queue delivering it in order.
 */
@Slf4j
public class PrinterReactor {

    // 256 because that is publishOn's own prefetch - the bound this outbox replaces.
    private static final int FARM_STREAM_BACKLOG = 256;

    private static final int SUBSCRIBER_BACKLOG = 256;

    private final TerminalManager manager;
    private final PrinterState printer;
    private final ConnectionPolicy policy;

    private HostTerminal terminal;

    private final Scheduler executor;

    private final Scheduler listeners;

    private final Scheduler retries;

    private final Sinks.Many<EventMessage> updates = Sinks.many().multicast().directBestEffort();

    private final Outbox<Parked> outbox = new Outbox<>(this::publish);

    private boolean streamEnded;

    /**
     * One thing waiting for the update sink: a message, or the end of the stream. Sealed so
     * {@link #publish} is an exhaustive switch with no {@code default}.
     */
    private sealed interface Parked {

        record Update(EventMessage message) implements Parked {
        }

        record StreamEnd() implements Parked {
        }
    }

    private volatile Disposable attempts;

    private final AtomicBoolean stopped = new AtomicBoolean();

    private final AtomicInteger liveSubscribers = new AtomicInteger();

    private final CompletableFuture<Void> subscribersGone = new CompletableFuture<>();

    private final Handoff<EventMessage> toFarm;

    private final Handoff<Object> toRoster;

    private PublisherQueue<String, Priority> commandQueue = new PublisherQueue<>();

    public PrinterReactor(TerminalManager manager, PrinterState printer) {
        this(manager, printer, event -> {
        });
    }

    public PrinterReactor(TerminalManager manager, PrinterState printer, ApplicationEventPublisher roster) {
        this(manager, printer, roster, ConnectionPolicy.standard(), Schedulers.single(), Schedulers.parallel());
    }

    /**
     * Kept working for tests: {@code Schedulers.immediate()} drains the listener hand-off inline,
     * so a test can assert what the roster was told without awaiting it.
     */
    public PrinterReactor(TerminalManager manager, PrinterState printer, ApplicationEventPublisher roster,
                          ConnectionPolicy policy, Scheduler executor, Scheduler retries) {
        this(manager, printer, roster, policy, executor, Schedulers.immediate(), retries);
    }

    public PrinterReactor(TerminalManager manager, PrinterState printer, ApplicationEventPublisher roster,
                          ConnectionPolicy policy, Scheduler executor, Scheduler listeners, Scheduler retries) {
        this.manager = manager;
        this.printer = printer;
        this.policy = policy;
        this.executor = executor;
        this.listeners = listeners;
        this.retries = retries;
        this.toFarm = Handoff.droppable("the farm stream of " + printer.getId(), FARM_STREAM_BACKLOG,
                listeners, roster::publishEvent);
        this.toRoster = Handoff.lossless("the roster's facts about " + printer.getId(),
                listeners, roster::publishEvent);
        this.printer.setEmitter(this::onEvent);
    }

    public Mono<Void> handleConnectCmd(Mono<ConnectCmd> connectCmd) {
        return connectCmd.
                doOnSuccess(this::handleConnectCmd)
                .then();
    }

    public void handleConnectCmd(ConnectCmd cmd) {
        printer.handle(cmd);
        if (cmd.connect()) {
            connect();
        } else {
            disconnect();
        }
    }

    public void connect() {
        if (!printer.wantsToBeConnected()) {
            log.info("{} has not been asked to be connected, so it is not being dialled",
                    printer.getId());
            return;
        }
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
     * Releases everything this reactor holds, for good - unlike {@link #disconnect()}, which is a
     * state the printer can come back from. Completes the sink (a subscriber is typically a
     * browser's SSE connection; {@code Sinks.Many} keeps every subscriber referenced until it
     * terminates) and also completes {@link #subscribersGone} directly when nobody was subscribed,
     * since nothing else ever would.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        log.info("stop()");
        disconnect();
        emit(new Parked.StreamEnd());
        toFarm.close();
        toRoster.close();
        if (liveSubscribers.get() == 0) {
            subscribersGone.complete(null);
        }
    }

    private void subscriberGone() {
        if (liveSubscribers.decrementAndGet() == 0 && stopped.get()) {
            subscribersGone.complete(null);
        }
    }

    /**
     * Bounded, non-throwing wait for every subscriber of {@link #updates()} to have actually
     * received this reactor's terminal signal - stronger than {@link #stop()} alone promises; must
     * be called after it, never before. See the class javadoc's Shutdown section.
     *
     * @param limit how long to wait
     * @return {@code true} if every subscriber had gone within {@code limit}, {@code false} on timeout
     */
    public boolean awaitSubscribersGone(Duration limit) {
        try {
            subscribersGone.get(limit.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException timedOut) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException impossible) {
            // subscribersGone is only ever completed with a value, never exceptionally.
            throw new AssertionError(impossible);
        }
    }

    // not scheduler.dispose(): see the class javadoc's Shutdown section.
    private static void disposeWhenIdle(Scheduler scheduler) {
        try {
            scheduler.schedule(scheduler::dispose);
        } catch (RejectedExecutionException alreadyGone) {
            scheduler.dispose();
        }
    }

    private Mono<Void> onConnected(HostTerminal terminal) {
        if (this.terminal != null) {
            // a redial: the dead one still holds a reply subscriber and a send window.
            this.terminal.stop();
        }
        commandQueue = new PublisherQueue<>();
        this.terminal = terminal;
        Mono<Void> events = gEvent();
        printer.onOnline(new GSender(this::enqueue));
        // defer: Mono.zip happens to subscribe index 0 first, keeping the socket read behind it.
        return Mono.zip(events, Mono.defer(this::gCode))
                .then();
    }

    // a method reference, not a lambda: GSender also has a Kotlin (String)->Unit constructor, and
    // an implicitly-typed lambda is ambiguous between the two.
    private void enqueue(String gcode) {
        commandQueue.addPublisher(Priority.REGULAR, Mono.just(gcode));
    }

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

    public Mono<Void> feed(Publisher<String> blocks) {
        var queue = commandQueue;
        if (queue == null || terminal == null) {
            return Mono.error(new IllegalStateException(
                    "printer " + printer.getId() + " is not connected, so nothing can be fed to it"));
        }
        return queue.addPublisher(Priority.BACKGROUND, blocks);
    }

    public Mono<Void> uploadToCard(Publisher<String> transfer) {
        var queue = commandQueue;
        if (queue == null || terminal == null) {
            return Mono.error(new IllegalStateException(
                    "printer " + printer.getId() + " is not connected, so nothing can be written to its card"));
        }
        return queue.addExclusivePublisher(Priority.REALTIME, transfer);
    }

    /** Puts a host-side fact (not an aggregate event) on this machine's own stream, e.g. upload progress. */
    public void announce(Object event) {
        onEvent(event);
    }

    public void interject(List<String> blocks) {
        var queue = commandQueue;
        if (queue == null || terminal == null) {
            log.warn("{} is not connected, so {} was not sent", printer.getId(), blocks);
            return;
        }
        queue.addPublisher(Priority.REGULAR, Flux.fromIterable(blocks));
    }

    // bounded by the send window; read off the last BlockAcknowledged, since the window itself is
    // internal to the transport and no subscriber can see it.
    public int blocksInFlight() {
        return printer.getBlocksInFlight();
    }

    /** Passes an edited spec to the aggregate - see {@code PrinterReactorManager.redeclare}. */
    public void declared(MachineSpec spec) {
        printer.declared(spec);
    }

    /** The last hotend reading, or empty if nothing has reported yet - not a sentinel. */
    public Mono<HeaterReading> state() {
        return Mono.defer(() -> Mono.justOrEmpty(printer.getTemperature())).publishOn(executor);
    }

    /** Where the machine's connection is, answered without opening the stream. */
    public Mono<ConnectionStatus> connection() {
        return Mono.defer(() -> Mono.just(printer.getConnection())).publishOn(executor);
    }

    /**
     * Whether there is a link right now - the aggregate's own answer, read as a snapshot from the
     * caller's thread. A link dropping between this answer and the first block is not this
     * method's to cover; it still surfaces as {@code SdUploadProgressed(FAILED)} on the stream.
     */
    public boolean linked() {
        return printer.getConnection().linked();
    }

    public Flux<EventMessage> updates() {
        return CatchUp.snapshotThenLive("the update stream of " + printer.getId(),
                        this::snapshot, updates.asFlux(), SUBSCRIBER_BACKLOG)
                .publishOn(executor)
                .doOnNext(rep -> log.info("<- {}", rep))
                .doOnSubscribe(subscription -> liveSubscribers.incrementAndGet())
                .doFinally(signal -> subscriberGone());
    }

    /** Built from volatile references to immutable values, so reading it does not queue behind replies. */
    public Flux<EventMessage> snapshot() {
        return Flux.defer(() -> Flux.fromIterable(printer.snapshot()))
                .map(fact -> new EventMessage(printer.getId(), fact));
    }


    public Mono<Void> handle(List<GRq<?>> commands) {
        return Mono.just(commands)
                .publishOn(executor)
                .doOnNext(batch -> batch.forEach(cmd -> log.info("-> {}", cmd)))
                .doOnNext(batch -> batch.forEach(printer::onCommand))
                .then();
    }

    private void onEvent(Object report) {
        var message = new EventMessage(printer.getId(), report);
        toFarm.emit(message);
        report(report);
        emit(new Parked.Update(message));
    }

    private void emit(Parked parked) {
        outbox.emit(parked);
    }

    /**
     * One parked item onto the sink - {@link Outbox} only, so {@link #streamEnded} can stay plain.
     * Also where this machine's two schedulers are disposed, strictly after
     * {@code tryEmitComplete()} on whichever thread actually drains the {@code StreamEnd}: see the
     * class javadoc's Shutdown section for why disposing any earlier loses the completion.
     */
    private void publish(Parked parked) {
        switch (parked) {
            case Parked.StreamEnd _ -> {
                if (streamEnded) {
                    return;
                }
                streamEnded = true;
                Sinks.EmitResult result = updates.tryEmitComplete();
                if (result.isFailure()) {
                    log.warn("the update stream did not complete ({})", result);
                }
                disposeWhenIdle(listeners);
                disposeWhenIdle(executor);
            }
            case Parked.Update(EventMessage message) -> {
                if (streamEnded) {
                    // a reply still in flight when the printer was released is normal.
                    log.debug("the update stream has ended, so {} goes nowhere", message);
                    return;
                }
                Sinks.EmitResult result = updates.tryEmitNext(message);
                // nobody watching is the normal case for a machine no page has open.
                if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                    log.warn("event dropped ({}): {}", result, message);
                }
            }
        }
    }

    // lossless: emitted only when PrinterState decided the report changed something, and
    // timestamped here, on the aggregate's own thread, so it says when the machine said it rather
    // than when a busy listener got round to it.
    private void report(Object event) {
        switch (event) {
            case FirmwareInfoRs firmware -> toRoster.emit(new MFirmwareReportedEvent(
                    printer.getId(), firmware.getKeyValues(), LocalDateTime.now()));
            case CapabilityRs capability -> toRoster.emit(new MCapabilityReportedEvent(
                    printer.getId(), capability.getCapability(), capability.getEnabled(), LocalDateTime.now()));
            case ConnectionStateChanged change -> toRoster.emit(new MConnectionChangedEvent(
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

    /** A clean close, shaped as an exception so the retry operator understands it; never escapes this class. */
    static final class ClosedByPeer extends RuntimeException {
        ClosedByPeer(String message) {
            super(message, null, false, false);
        }
    }
}

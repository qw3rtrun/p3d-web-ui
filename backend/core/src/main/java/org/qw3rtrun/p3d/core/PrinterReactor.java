package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MCapabilityReportedEvent;
import org.qw3rtrun.p3d.api.msg.MConnectionChangedEvent;
import org.qw3rtrun.p3d.api.msg.MFirmwareReportedEvent;
import org.qw3rtrun.p3d.api.msg.TerminalTraffic;
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
 * blocks the other.
 *
 * <p><strong>Everything that moves the aggregate goes through {@code executor}, in both
 * directions</strong> (#80), and so does every query of it bar the three named below (#138).
 * Replies take {@code publishOn(executor)} in {@link #gEvent()};
 * commands take it in {@link #handle(List)}, {@link #handleConnectCmd(Mono)} and - as a scheduled
 * task, since their callers are listeners that must not wait - {@link #handleConnectCmd(ConnectCmd)},
 * {@link #declared}, and {@link #stop()}'s own disconnect. {@link #state()} and
 * {@link #connection()} take {@code subscribeOn(executor)}, which is not interchangeable with
 * {@code publishOn}: they were {@code Mono.defer(...).publishOn(executor)} and a deferred supplier
 * runs on whichever thread <em>subscribes</em>, so both read the aggregate from the caller's thread
 * while this paragraph's own structure claimed otherwise. {@code connect()} and
 * {@code disconnect()} are private so that stays true. The retry chain's hooks need nothing extra
 * and that is worth knowing rather than guessing: {@code filter}, {@code doBeforeRetry} and
 * {@code doOnError} run
 * inside the retry companion on whichever thread delivered the failure, and
 * {@code publishOn(executor)} sits between the dial and the {@code retryWhen}, so that thread is
 * this machine's own ({@code PrinterReactorThreadingTest} asserts it by name).
 *
 * <p>So {@code attempts} is a plain field and its "is an attempt already in flight" check-then-act
 * is serialised rather than merely visible - it was {@code volatile}, which bought the
 * happens-before and not the atomicity, and a concurrent connect and disconnect could both decide
 * to act. <strong>Three things are answered from the caller's thread, deliberately, and they are
 * the whole list</strong>: {@link #blocksInFlight()} and {@link #linked()}, both single volatile
 * reads a progress timer and an upload make often enough that queueing them behind a reply backlog
 * would be the wrong trade, and {@link #snapshot()}, built from volatile references so a subscriber
 * attaching does not wait for the machine to be quiet. What puts a read on that list is answering
 * <em>synchronously</em> to something on a hot path; {@link #connection()} and {@link #state()}
 * hand back a {@code Mono} nobody can get a value out of without going through {@code executor}
 * regardless, and are asked once per page load and once per five-second scheduling sweep.
 * <strong>And {@code terminal} and {@code commandQueue} have no edge
 * at all</strong>: their writes are confined to {@code executor}, but {@link #feed},
 * {@link #uploadToCard}, {@link #binaryTransfer} and {@link #interject} read them from whatever
 * thread is feeding a job or an upload, so
 * a reader can see a link that has just gone (or miss one that has just come up) - the same staleness
 * {@link #linked()} already says it does not cover, and not something this class can close alone.
 *
 * <p>{@code streamEnded} is a plain field because {@link Outbox} calls its consumer from one thread
 * at a time, which supplies the happens-before it needs. {@code stopped}, {@code liveSubscribers}
 * and {@code subscribersGone} coordinate shutdown - see below.
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
 * <h3>The terminal sink (#135)</h3>
 * {@code traffic} is the one sink here that <strong>does</strong> replay - {@code replay().limit(256)},
 * not {@code directBestEffort()} - and it contradicts the paragraph above on purpose. That paragraph
 * is about <em>facts about the machine now</em>, where a stale value is a lie. A console's content
 * <em>is</em> its scrollback: a chat window that is empty when you open it is useless, and a page
 * opened mid-session would otherwise show nothing until the operator typed something.
 *
 * <p>Two properties keep #85's defect from coming back with the replay, and <strong>a new stream
 * that wants one has to answer both</strong>. {@code TerminalTraffic} carries {@code at} (epoch
 * millis), so a reader can tell a replayed line from a live one; and <strong>nothing decides
 * anything on a replayed line</strong> - this stream is separate from {@link #updates()}, so no
 * watcher that acts on a machine's events can see it.
 *
 * <p>It is fed through {@link #outbox} like {@code updates}, and the second emitter is real rather
 * than theoretical: {@link #onConnected} builds a <em>new</em> {@link HostTerminal} on every redial
 * and observes it, while the old terminal's {@code stop()} has cancelled its halves but a reply
 * already in flight on the old netty loop can still reach the old drain loop. Two terminals is two
 * drain loops is two threads into one safe sink, which refuses rather than serialises.
 *
 * <p><strong>A console line must not reach {@link PrinterState#onEvent}, {@link #toFarm} or
 * {@code updates}</strong>, and routing it to {@code onEvent} is the obvious-looking thing. It is a
 * live bug: that method's {@code default} arm calls {@code heard()}, which moves the connection
 * lifecycle, so a {@code SENT} line would make <em>the host's own writing</em> count as the machine
 * having said something - a fault cleared by the news of this host talking to itself. The outbound
 * half, {@link #sendRaw}, likewise does not cross the aggregate; {@code PrinterControl.sendRaw}'s
 * javadoc is the authority for why, and it is the whole of that exception's list.
 *
 * <h3>Shutdown</h3>
 * {@link #stop()} is idempotent ({@code stopped}), disconnects, and parks - does not necessarily
 * publish - a {@code StreamEnd} on the same outbox as every other emission, so it cannot terminate
 * either sink underneath an event another thread is mid-publishing ({@code FAIL_TERMINATED}).
 * Whichever thread actually drains that marker completes both sinks and only then disposes
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
 * completion, error or cancellation, and counts a {@link #traffic()} subscriber alongside an
 * {@link #updates()} one - a console is an SSE response like any other; {@link #subscribersGone}
 * completes from whichever of
 * {@link #stop()} (nobody was watching) or the last subscriber's {@code doFinally} (somebody was)
 * runs second, and never throws on timeout, so one stuck browser cannot fail a farm-wide shutdown.
 *
 * <h3>The goodbye (#127)</h3>
 * A session that started auto-reports has to turn them off <em>before</em> the socket shuts, or the
 * machine goes on emitting a temperature a second into a closed socket until it is power-cycled.
 * {@link #sayGoodbye(Duration)} asks the aggregate ({@link PrinterState#quiesce()}) and hands back a
 * bounded, never-failing wait for the last block to have reached the send window;
 * {@link #quiesce(Duration)} is the same thing callable from any thread. The waiting is what was
 * missing: {@code applyConnectCmd} used to enqueue {@code M155 S0} and call {@code disconnect()} in
 * the same task, with nothing in between, and no test ever asserted the block arrived.
 *
 * <p>Both callers send the goodbye while the link is still up and before the connection state moves,
 * because {@link PrinterState#onCommand} refuses everything once the aggregate is no longer linked.
 * {@link PrinterState#quiesce()} itself moves no connection state, deliberately - a shutdown that
 * ended every machine's <em>intent</em> to be connected would come back up with the whole farm
 * offline and nothing redialling it.
 *
 * <h3>Connect and retry</h3>
 * {@code connect()} and {@code disconnect()} are both safe to call in any state, including
 * mid-backoff and never-connected, and are private: {@link #handleConnectCmd(ConnectCmd)} is the
 * only way to either, which is what makes "both decisions are taken on one thread, and the one
 * taken second stands" true rather than nearly true. {@code connect()} wraps the whole conversation -
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

    /**
     * How much scrollback the console keeps - see the class javadoc's <em>terminal sink</em> section
     * for why this one replays where {@link #updates} does not.
     *
     * <p>256 is the figure this repository has already chosen twice for a backlog
     * ({@code HostTerminal.BACKLOG}, {@link #SUBSCRIBER_BACKLOG}) and is {@code publishOn}'s own
     * prefetch: a legible console and bounded memory, times however many machines are running.
     */
    private static final int TRAFFIC_BACKLOG = 256;

    private final TerminalManager manager;
    private final PrinterState printer;
    private final ConnectionPolicy policy;

    private HostTerminal terminal;

    private final Scheduler executor;

    private final Scheduler listeners;

    private final Scheduler retries;

    private final Sinks.Many<EventMessage> updates = Sinks.many().multicast().directBestEffort();

    /** The console's scrollback - see the class javadoc's <em>terminal sink</em> section. */
    private final Sinks.Many<EventMessage> traffic = Sinks.many().replay().limit(TRAFFIC_BACKLOG);

    private final Outbox<Parked> outbox = new Outbox<>(this::publish);

    private boolean streamEnded;

    /**
     * One thing waiting for a sink: a message for the monitor, a line for the console, or the end of
     * both. Sealed so {@link #publish} is an exhaustive switch with no {@code default}.
     *
     * <p><strong>One outbox and a third arm, not a second {@link Outbox}.</strong> The end of the
     * stream is why: {@code StreamEnd} completes both sinks and then disposes this machine's two
     * schedulers, and that disposal has to be strictly after <em>every</em> completion. Two drain
     * loops could not promise that ordering between themselves - each would need its own marker, and
     * whichever drained first would dispose the schedulers the other still needs - which is the
     * failure mode the class javadoc's Shutdown section records being paid for once already. It also
     * costs one queue and one {@code AtomicInteger} per machine rather than two, which is a farm of
     * forty rather than one.
     */
    private sealed interface Parked {

        record Update(EventMessage message) implements Parked {
        }

        record Traffic(EventMessage message) implements Parked {
        }

        record StreamEnd() implements Parked {
        }
    }

    // the running retry chain. Plain, and confined to executor: connect() and disconnect() are
    // private and the only way to either is through the executor (#80), so the check-then-act on
    // this field - "is an attempt already in flight" - is serialised rather than merely visible.
    private Disposable attempts;

    private final AtomicBoolean stopped = new AtomicBoolean();

    private final AtomicInteger liveSubscribers = new AtomicInteger();

    private final CompletableFuture<Void> subscribersGone = new CompletableFuture<>();

    private final Handoff<EventMessage> toFarm;

    private final Handoff<Object> toRoster;

    private PublisherQueue<String, Priority> commandQueue = new PublisherQueue<>();

    // the completion of the most recent block the aggregate handed to the queue, or null.
    // PublisherQueue.addPublisher's Mono "completes when the source is exhausted", i.e. when the
    // queue has taken the block and the send window has written it - what sayGoodbye() waits on,
    // and which this used to discard.
    //
    // It is NOT a flush signal and this comment used to call it one (#129). Written means handed to
    // reactor-netty's outbound, which is two event-loop tasks short of the socket; the socket not
    // closing in between is a guarantee of the transport's own close
    // (TerminalManager.closeAfterFlush), not of this Mono. What this one is for is the other half -
    // not closing before the send window has had room to write the goodbye at all.
    //
    // Plain, and confined to executor: enqueue() is reached only from the aggregate's GSender and
    // the aggregate is only ever touched on this machine's own thread (#80).
    // feed/uploadToCard/interject/sendRaw use commandQueue directly and never pass through here.
    private Mono<Void> lastEnqueued;

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

    /**
     * Completes once the aggregate has <em>applied</em> the command, not once it is queued - a 200
     * from the endpoint therefore means the dial or the hang-up is already decided, the same
     * promise {@link #handle(List)} makes for a {@code GRq}.
     */
    public Mono<Void> handleConnectCmd(Mono<ConnectCmd> connectCmd) {
        return connectCmd
                .publishOn(executor)
                .doOnNext(this::applyConnectCmd)
                .then();
    }

    /** Fire-and-schedule: a caller already on {@code executor} must never wait for it. */
    public void handleConnectCmd(ConnectCmd cmd) {
        onExecutor(() -> applyConnectCmd(cmd));
    }

    // on executor, always: see the class javadoc's Threading section.
    private void applyConnectCmd(ConnectCmd cmd) {
        if (cmd.connect()) {
            printer.handle(cmd);
            connect();
            return;
        }
        // The order is the design. The goodbye goes first, while the link is still up and the
        // aggregate will still send it - printer.handle(cmd) takes the connection to OFFLINE and
        // onCommand refuses everything after that, so a goodbye sent later is a WARN and no bytes.
        // Then the intent comes down, before this returns, because handleConnectCmd(Mono) promises
        // the endpoint's 200 means the hang-up is already decided. Then the socket closes, once the
        // goodbye is on the wire or the budget has run out - which is a task of its own on this
        // thread rather than the end of this one, so "the hang-up is decided" and "the socket is
        // shut" are now two observable moments where they used to be one.
        var flush = sayGoodbye(GOODBYE_BUDGET);
        printer.handle(cmd);
        flush.doFinally(signal -> onExecutor(this::closeUnlessWantedAgain))
                // an error arm like connect()'s, rather than the no-argument subscribe() whose
                // default routes a failure through onErrorDropped. doFinally has already queued the
                // close by then, so the socket shuts either way.
                .subscribe(ignored -> {
                }, cause -> log.info("the goodbye of {} ended in {}", printer.getId(), cause.toString()));
    }

    /**
     * Shuts the socket, unless somebody asked for this machine again while it was saying goodbye.
     *
     * <p>The gate is the same one {@code connect()} makes before it dials, for the mirror-image
     * reason: closing the socket is now a task queued behind the goodbye's flush rather than part of
     * the same one, so a connect applied in that window would have its brand-new dial disposed by
     * the previous disconnect catching up. The window is at most {@link #GOODBYE_BUDGET}.
     */
    private void closeUnlessWantedAgain() {
        if (printer.wantsToBeConnected()) {
            log.info("{} was asked for again while it was saying goodbye, so the socket stays",
                    printer.getId());
            return;
        }
        disconnect();
    }

    /**
     * How long a hang-up or a shutdown will wait for this machine's goodbye to be <em>written</em>.
     *
     * <p>One second: the goodbye is two or three short blocks, and the only thing that can delay it
     * is a send window full of a print job's blocks, which drains as each {@code ok} returns. A
     * machine that cannot manage it in a second is a machine whose socket is already useless, and
     * holding a disconnect open for longer buys nothing.
     *
     * <p>That reasoning was incomplete rather than wrong (#129): it bounds how long the send window
     * may take, and says nothing about how long {@code reactor-netty} takes to put a written block
     * on the socket. No budget could - the answer is an ordering and not a duration, and it belongs
     * to the transport's own close rather than here. See {@link #lastEnqueued}.
     */
    private static final Duration GOODBYE_BUDGET = Duration.ofSeconds(1);

    /**
     * Sends this session's goodbye and answers with a bounded, never-failing wait for it to have
     * reached the wire. <strong>Must be called on this machine's own thread</strong> - it reads and
     * writes {@link #lastEnqueued} and drives the aggregate; {@link #quiesce(Duration)} is the form
     * for any other caller.
     *
     * <p>Waiting on the <em>last</em> block is enough because {@link PublisherQueue} is FIFO within
     * one priority, so the earlier off-switches are necessarily ahead of it. A timeout is swallowed:
     * a machine that will not take its goodbye must not fail a disconnect or a shutdown.
     */
    private Mono<Void> sayGoodbye(Duration limit) {
        lastEnqueued = null;
        printer.quiesce();
        var flush = lastEnqueued;
        lastEnqueued = null;
        if (flush == null) {
            return Mono.empty();
        }
        return flush.timeout(limit)
                .onErrorResume(cause -> {
                    log.info("{} did not take its goodbye within {}; closing anyway",
                            printer.getId(), limit);
                    return Mono.empty();
                });
    }

    /**
     * The same, from any thread - schedules the goodbye on this machine's own thread first. The
     * shutdown path, where the caller is {@code PrinterReactorManager.releaseAll()} on the shutdown
     * thread and every printer's goodbye is started before any wait begins.
     *
     * <p>Never errors, including when this machine's thread has already been disposed: a goodbye
     * from a {@code SmartLifecycle.stop()} that came out as an exception would be a shutdown failing
     * over a machine that by definition has nothing left to say.
     */
    public Mono<Void> quiesce(Duration limit) {
        // defer, so printer.quiesce() runs where subscribeOn puts the subscription - this machine's
        // own thread - rather than on the shutdown thread that composed this.
        return Mono.defer(() -> sayGoodbye(limit))
                .subscribeOn(executor)
                .onErrorResume(cause -> {
                    // a disposed scheduler refuses the subscription outright; a queue that will take
                    // no more blocks errors the flush. Neither is worth failing a shutdown over.
                    log.info("{} said no goodbye: {}", printer.getId(), cause.toString());
                    return Mono.empty();
                });
    }

    /**
     * Runs {@code task} on this machine's aggregate thread, inline if it is already that thread's
     * turn ({@code Schedulers.immediate()}, the test seam) and inline as a last resort if the
     * scheduler has gone - a task refused after {@code stop()} would otherwise be a disconnect or a
     * spec edit dropped in silence.
     */
    private void onExecutor(Runnable task) {
        try {
            executor.schedule(task);
        } catch (RejectedExecutionException alreadyGone) {
            log.debug("{} has stopped, so its own thread ran nothing; doing it here", printer.getId());
            task.run();
        }
    }

    private void connect() {
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

    /**
     * Shuts this session down, <strong>terminal first and dial chain second</strong>.
     *
     * <p>The order is load-bearing (#129). Disposing {@code attempts} cancels the dial chain, and a
     * live session's {@code Mono.zip(events, gCode)} hangs off it - so the cancel reaches
     * {@code reactor-netty}'s outbound, which answers a cancel by <em>discarding</em> whatever it has
     * not written yet. Done first, that throws away the goodbye {@link #sayGoodbye} just wrote:
     * measured 18-20 times in 20. {@code HostTerminal.stop()} flushes and closes the socket and
     * waits for it, so after it there is nothing left to discard - and closing the socket is itself
     * what ends both halves, so the dispose below is only still here for a dial or a retry backoff
     * that has no terminal yet.
     */
    private void disconnect() {
        log.info("disconnecting from {}", printer.getId());
        if (terminal != null) {
            terminal.stop();
            terminal = null;
        }
        if (attempts != null) {
            attempts.dispose();
            attempts = null;
        }
    }

    /**
     * Releases everything this reactor holds, for good - unlike {@code disconnect()}, which is a
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
        // queued, not called: disconnect() writes attempts and terminal, which only this machine's
        // thread may touch. Submitted before the StreamEnd below, so the FIFO executor runs it
        // ahead of the disposal that marker eventually schedules.
        onExecutor(this::disconnect);
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
        // before the zip is composed, so the console sees this session's opening handshake: the
        // observer is a field read inside HostTerminal's own drain loop, not a subscription.
        terminal.observeTraffic(this::onTraffic);
        Mono<Void> events = gEvent();
        printer.onOnline(new GSender(this::enqueue));
        // defer: Mono.zip happens to subscribe index 0 first, keeping the socket read behind it.
        return Mono.zip(events, Mono.defer(this::gCode))
                .then();
    }

    // a method reference, not a lambda: GSender also has a Kotlin (String)->Unit constructor, and
    // an implicitly-typed lambda is ambiguous between the two.
    private void enqueue(String gcode) {
        lastEnqueued = commandQueue.addPublisher(Priority.REGULAR, Mono.just(gcode));
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

    /**
     * Hands a whole file to the transport's Marlin Binary Protocol writer and reports back how much
     * of it the printer has acknowledged - <strong>cumulative payload bytes</strong>, not a delta,
     * so a reader sets rather than adds. Cold, completes on success or on {@code cancel}, errors
     * with the transfer's own reason text.
     *
     * <p>Not part of {@link #uploadToCard}'s queue and must be composed inside one: packets bypass
     * the numbered ASCII path entirely, so the only thing keeping an {@code M105} out of the middle
     * of a packet is the exclusive publisher this stream sits in the middle of (see
     * {@code SdUploader.binaryTransferOf}).
     *
     * @param data the entire file, held in memory - see {@code SdUploader}'s ceiling
     */
    public Flux<Long> binaryTransfer(String cardName, byte[] data, Mono<Void> cancel) {
        var host = terminal;
        if (host == null) {
            return Flux.error(new IllegalStateException(
                    "printer " + printer.getId() + " is not connected, so nothing can be written to its card"));
        }
        return host.binaryTransfer(cardName, data, cancel);
    }

    /**
     * What this machine reported itself to be - the roster's seed until an {@code M115} replaces it,
     * never null. A volatile read from the caller's thread, like {@link #linked()}, so a capability
     * check costs nothing and can go in front of a blocking store lookup.
     */
    public MachineProfile profile() {
        return printer.getProfile();
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

    /**
     * Blocks taken by the send window and not yet acknowledged - read off the last
     * {@code BlockAcknowledged}, since the window itself is internal to the transport and no
     * subscriber can see it.
     *
     * <p>One of three members answered from the caller's thread rather than the aggregate's, and
     * deliberately so: it is a {@code volatile int} the aggregate <em>publishes</em> rather than a
     * question asked of it, its one caller is {@code JobFeeder.checkpoint} on a progress timer, and
     * routing it through {@code executor} would put a progress poll behind every reply queued ahead
     * of it. See the class javadoc's Threading section for the other two.
     */
    public int blocksInFlight() {
        return printer.getBlocksInFlight();
    }

    /**
     * Passes an edited spec to the aggregate - see {@code PrinterReactorManager.onSpecDeclared}.
     * Queued on this machine's thread rather than applied on the roster's, so the edit is ordered
     * against this machine's commands instead of racing them; the caller is a listener inside a
     * transaction, so it must not wait, and it does not need to: the executor is single-threaded
     * and FIFO, so a command issued after the {@code PUT} has answered is queued behind this.
     */
    public void declared(MachineSpec spec) {
        onExecutor(() -> printer.declared(spec));
    }

    /**
     * The last hotend reading, or empty if nothing has reported yet - not a sentinel.
     *
     * <p>{@code fromCallable} + {@code subscribeOn}, never {@code defer} + {@code publishOn} (#138):
     * a deferred supplier runs on whichever thread <em>subscribes</em>, so that form read the
     * aggregate on the caller's thread and moved only where the answer was delivered. It costs
     * nothing to do properly, because {@code publishOn} already put the answer behind this
     * machine's queue - and the value is fresher for it, describing the machine when the task ran
     * rather than when the backlog in front of it started.
     */
    public Mono<HeaterReading> state() {
        return Mono.fromCallable(printer::getTemperature).subscribeOn(executor);
    }

    /** Where the machine's connection is, answered without opening the stream; scheduled like {@link #state()}. */
    public Mono<ConnectionStatus> connection() {
        return Mono.fromCallable(printer::getConnection).subscribeOn(executor);
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

    /**
     * The console: every line that crossed the wire, in both directions, each a
     * {@code TerminalTraffic} in an {@link EventMessage}. Opens with up to
     * {@link #TRAFFIC_BACKLOG} lines of scrollback and then stays live until {@link #stop()}.
     *
     * <p><strong>No {@code publishOn(executor)}, unlike {@link #updates()} two methods above</strong>,
     * and the asymmetry is the design rather than an omission. Every message on {@code updates()} has
     * crossed the aggregate, so it is already on this machine's own thread and the hop only keeps it
     * there; nothing here has crossed it, and putting a console line on the aggregate's thread would
     * queue a diagnostic behind every reply being applied - the exact queueing this feature exists to
     * avoid. A line is handed straight from the transport's drain loop to whoever is watching.
     *
     * <p>Counted in {@link #liveSubscribers} like a monitor subscriber, so
     * {@link #awaitSubscribersGone(Duration)} covers a browser holding a console too: an SSE response
     * severed mid-write by graceful shutdown is a bug this class already carries the scar of.
     */
    public Flux<EventMessage> traffic() {
        return traffic.asFlux()
                .doOnSubscribe(subscription -> liveSubscribers.incrementAndGet())
                .doFinally(signal -> subscriberGone());
    }

    /**
     * Puts one already-checked line in the send queue, bypassing the aggregate - the Terminal tab's
     * typed line, and the one outbound path that does not cross {@link PrinterState}. See
     * {@code PrinterControl.sendRaw}, which is where every gate this line passed through lives.
     *
     * <p>{@code REGULAR}, the priority an interactive command and a temperature poll already use: a
     * typed line should interleave with a running print (which feeds at {@code BACKGROUND}) and must
     * <em>not</em> take the wire from an SD upload (which holds it at {@code REALTIME}), so
     * {@code PublisherQueue.headThatMayRun()} leaves the transfer alone with no new code.
     *
     * <p><strong>The write is deliberately not waited on, and this returns as soon as the line is
     * queued.</strong> {@code PublisherQueue.add} appends the source and calls its drain loop
     * synchronously, so the enqueue has genuinely happened by the time this returns; the {@code Mono}
     * it hands back is only the completion of the <em>write</em>, and waiting on that would be an
     * unbounded hang rather than a slow answer - there is no timeout anywhere on this path, so a
     * machine that has stopped acknowledging stalls the send window and the request never finishes,
     * leaving an operator unable to tell "my line was refused" from "the machine is wedged". What
     * went out is observable where it belongs: {@link #traffic()} carries the framed {@code SENT}
     * line the instant the window writes it, which is better evidence than a 200 and arrives whether
     * or not anybody kept a request open. It also keeps this route no stricter than the aggregate's,
     * where {@link #handle(List)} acknowledges a command once the aggregate has applied it and never
     * waits for the wire at all.
     *
     * <p>That completion is subscribed with a log-only arm rather than dropped on the floor: it is a
     * {@code Sinks.One} that errors when the queue terminates mid-source - the ordinary end of a
     * session - and an unsubscribed error becomes an {@code onErrorDropped}. The shape is
     * {@code applyConnectCmd}'s goodbye flush, for the same reason.
     *
     * @param line the exact bytes to send, without an {@code N} field or checksum - the send window
     *             frames it and reports the framed block on {@link #traffic()}
     * @return false when there is no link to type at, which is the only way an enqueue fails here;
     *         the caller turns that into a refusal rather than this throwing into a REST call
     */
    public boolean sendRaw(String line) {
        var queue = commandQueue;
        if (queue == null || terminal == null) {
            return false;
        }
        queue.addPublisher(Priority.REGULAR, Mono.just(line))
                .subscribe(ignored -> {
                }, cause -> log.info("the line typed at {} ended in {}",
                        printer.getId(), cause.toString()));
        return true;
    }

    /**
     * One line the transport saw, onto the console and nowhere else.
     *
     * <p>Package-private as a test seam: driving the console otherwise needs a socket, and the
     * assertion worth having - that a line reaches {@link #traffic()} and does <em>not</em> reach
     * {@link #updates()} - is about this method's one decision.
     */
    void onTraffic(TerminalTraffic line) {
        emit(new Parked.Traffic(new EventMessage(printer.getId(), line)));
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
                // and the console, for the same reason: a browser holding the terminal of a deleted
                // printer has to be told rather than left waiting. Before the schedulers below,
                // which is the ordering the class javadoc's Shutdown section argues for.
                Sinks.EmitResult console = traffic.tryEmitComplete();
                if (console.isFailure()) {
                    log.warn("the terminal stream did not complete ({})", console);
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
            // No FAIL_ZERO_SUBSCRIBER arm: a replaying sink takes an emission with nobody attached,
            // which is the whole point of it being one, so any failure here says something real.
            case Parked.Traffic(EventMessage message) -> {
                if (streamEnded) {
                    log.debug("the terminal stream has ended, so {} goes nowhere", message);
                    return;
                }
                Sinks.EmitResult result = traffic.tryEmitNext(message);
                if (result.isFailure()) {
                    log.warn("terminal line dropped ({}): {}", result, message);
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

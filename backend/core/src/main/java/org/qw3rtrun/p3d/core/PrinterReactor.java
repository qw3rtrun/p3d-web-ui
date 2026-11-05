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

@Slf4j
public class PrinterReactor {

    /**
     * How many farm-stream messages may be waiting on the listener thread before one is dropped.
     *
     * <p>At the one or two messages a second a machine actually produces - a temperature
     * auto-report and whatever the aggregate thought worth announcing, not every acknowledged
     * block - this is minutes of backlog. A browser or a dashboard that is behind by minutes is
     * not behind, it is gone.
     *
     * <p>It is 256 because that is {@code publishOn}'s prefetch, which is the bound this replaces.
     * The difference is what happens at the bound: {@code publishOn} overflowing raised an
     * {@code onError} and <em>terminated the reply stream for that session</em>, so a slow
     * consumer took the machine's whole conversation with it. This drops one message and counts it.
     */
    private static final int FARM_STREAM_BACKLOG = 256;

    /**
     * How far one subscriber of {@link #updates()} may fall behind before it loses a message - the
     * same bound, for the same reason, one browser at a time. See {@link CatchUp}.
     */
    private static final int SUBSCRIBER_BACKLOG = 256;

    private final TerminalManager manager;
    private final PrinterState printer;
    private final ConnectionPolicy policy;

    private HostTerminal terminal;

    /**
     * Where replies and state changes are handled, so the aggregate is touched by one thread.
     *
     * <p><strong>This machine's own thread, and that is the whole of #54.</strong> It used to be
     * {@code Schedulers.single()}, which is not a new single thread but the JVM-wide shared one -
     * so a query about printer A queued behind printer B's decoded replies, and one slow handler
     * anywhere stalled the farm. {@link PrinterThreads} hands each reactor its own, and this
     * reactor disposes it once {@link #stop()}'s {@code StreamEnd} has been published - see
     * {@link #publish}.
     */
    private final Scheduler executor;

    /**
     * Where this machine's listeners are called, so that none of them is charged to the aggregate.
     *
     * <p>A roster listener is {@code @Transactional} and blocking - it writes a firmware report, a
     * capability or a connection state into the database - and until #54 it ran inline on
     * {@link #executor}, which was shared. A single thread, because a single consumer is the whole
     * of the ordering guarantee: what this aggregate decided reaches its listeners in the order it
     * decided it. See {@link PrinterThreads}.
     */
    private final Scheduler listeners;

    /** Where the backoff between connection attempts is timed. A virtual one in tests. */
    private final Scheduler retries;

    /**
     * This machine's live stream, touched by exactly one thread at a time - see {@link Outbox}.
     *
     * <p>A <em>safe</em> {@code Sinks.Many} does not serialise for its caller; it <strong>refuses</strong>
     * a concurrent emission with {@code FAIL_NON_SERIALIZED}. The emitters here are genuinely several
     * threads - the aggregate's {@link #executor}, the backoff scheduler on a failed attempt, and
     * netty's on a dropped link - so a collision is a question of load and not of correctness.
     *
     * <p>It is kept safe and fed through an outbox, rather than being made {@code unsafe()}: the
     * sink's subscriber list is what an unsafe sink would leave unguarded, and the drain loop costs
     * one atomic per event.
     *
     * <p><strong>Multicast, and it replays nothing.</strong> It used to be
     * {@code replay().latestOrDefault(currentConnectionState)}, which handed a new subscriber
     * <em>whatever was said last</em> - the connection state if nothing had happened, but otherwise
     * a temperature, a position or a {@code BlocksAbandoned} from a job that was already over,
     * presented as news. A late subscriber now gets the aggregate's
     * {@link PrinterState#snapshot() snapshot} instead, which is facts rather than history - see
     * {@link #updates()}.
     *
     * <p>{@code directBestEffort} because nothing is lost by it: the only subscriber of this sink
     * is {@link CatchUp}'s feed, which requests everything and keeps the bounded per-subscriber
     * buffer itself.
     */
    private final Sinks.Many<EventMessage> updates = Sinks.many().multicast().directBestEffort();

    /**
     * Messages decided on but not yet put on {@link #updates}, oldest first.
     *
     * <p>What makes the sink safe to feed from several threads: one that finds another already
     * emitting parks its message here and leaves, instead of being refused by the sink.
     */
    private final Outbox<Parked> outbox = new Outbox<>(this::publish);

    /**
     * Set once the stream has terminated, and read only inside {@link #publish}.
     *
     * <p>A plain field and not a volatile one: {@link Outbox} calls its consumer from one thread at
     * a time through an {@link AtomicInteger}, so the hand-off from one draining thread to the next
     * carries the happens-before this needs.
     */
    private boolean streamEnded;

    /**
     * One thing waiting for the update sink: a message, or the end of the stream.
     *
     * <p>A sealed interface so {@link #publish} is an exhaustive switch with no {@code default} -
     * a third kind of parked item would fail the build rather than fall through a hole.
     */
    private sealed interface Parked {

        record Update(EventMessage message) implements Parked {
        }

        /**
         * The end of the stream, parked so that it cannot overtake a message in front of it.
         *
         * <p>{@link #stop()} used to call {@code tryEmitComplete} straight from the caller's thread
         * while a drain might still have had an event to publish - which terminates the sink under
         * it, and that event is then refused with {@code FAIL_TERMINATED}. A terminal signal is the
         * last thing on this stream, so it queues like everything else.
         *
         * <p>It carries the machine's two threads with it: whichever thread publishes this one
         * disposes {@link #executor} and {@link #listeners} immediately afterwards, because that
         * is the only place in the class where {@code tryEmitComplete()} is known to have run.
         * See {@link #publish}.
         */
        record StreamEnd() implements Parked {
        }
    }

    /**
     * The running attempt chain, or null when nothing is dialling.
     *
     * <p>This is what makes a disconnect <em>stop</em> rather than merely be recorded. A retry is a
     * subscription holding a timer, so cancelling the intent means disposing it; without this
     * handle a machine told to disconnect mid-backoff would come back a few seconds later, and the
     * only trace would be a connection nobody asked for.
     *
     * <p><strong>Volatile because three different threads write it and there is no lock.</strong>
     * {@link #connect()} and {@link #disconnect()} both read and write it, and after #54 they are
     * reached from: an HTTP thread, through {@code handleConnectCmd}; this machine's own
     * {@link #executor}; and now the <em>listener</em> thread, because
     * {@code MachineManagementImpl.on(MFirmwareReportedEvent)} is {@code @Transactional} and
     * re-publishes {@code MRegisteredEvent}, which {@code PrinterReactorManager.onRegistered}
     * consumes and turns into a {@code connect()}. Before the listener hand-off that last one
     * arrived on the aggregate's thread, so two of the three were the same.
     *
     * <p>Volatile buys the happens-before and not atomicity: {@code connect()}'s
     * "is an attempt already in flight" is still a check-then-act and two threads can still both
     * decide to dial. That is a wider problem than #54 - closing it means deciding what a
     * concurrent connect and disconnect <em>mean</em> - and is deliberately left open rather than
     * half-fixed here.
     */
    private volatile Disposable attempts;

    /**
     * Set by the first {@link #stop()}, so a second one is not a second disposal.
     *
     * <p>Stopping this reactor shuts two schedulers down. Running it twice would schedule a
     * disposal on a scheduler that has already gone, which is a
     * {@code RejectedExecutionException} on a path whose whole job is to leave nothing behind.
     *
     * <p>It is load-bearing for {@link #publish} too, and for a second reason: because exactly one
     * caller gets past it, exactly one {@code StreamEnd} is ever parked, so the arm that disposes
     * those two schedulers is reached once and cannot be short-circuited by the
     * {@code streamEnded} guard in front of it.
     */
    private final AtomicBoolean stopped = new AtomicBoolean();

    /**
     * How many subscribers of {@link #updates()} are attached right now.
     *
     * <p><strong>Genuinely concurrent, unlike {@link #streamEnded}.</strong> {@link #streamEnded}
     * is safe as a plain field because {@link Outbox} hands it to one thread at a time; there is no
     * such serialisation here. Several browsers can subscribe and unsubscribe on their own netty
     * threads at once, so this is an {@link AtomicInteger}. {@code doOnSubscribe} in
     * {@link #updates()} increments it on whichever thread completes that subscriber's subscription
     * handshake; {@code doFinally} decrements it on whichever thread delivers that subscriber's
     * terminal signal - completion, error, <strong>or cancellation</strong>. Counting cancellation
     * is what makes a browser that simply goes away count as gone rather than as a subscriber this
     * reactor would otherwise wait on until its budget runs out.
     */
    private final AtomicInteger liveSubscribers = new AtomicInteger();

    /**
     * Completed once every subscriber of {@link #updates()} has actually seen this reactor's
     * terminal signal - not merely once {@link #stop()} has parked one - and {@link #stop()} has
     * been called at least once. See {@link #awaitSubscribersGone(Duration)}.
     *
     * <p><strong>A {@code CompletableFuture} because two edges legitimately race to complete it.</strong>
     * {@link #stop()} completes it directly when nobody was subscribed at the moment it ran -
     * otherwise a machine nobody was watching would make a caller wait out its whole budget for a
     * subscriber that was never going to arrive. The last subscriber's {@code doFinally} completes
     * it the other way, when it leaves after a {@link #stop()} that found subscribers still
     * attached. Both check the same two facts - the subscriber count and whether {@link #stopped}
     * is set - so both can be true in the same instant on different threads.
     * {@code CompletableFuture.complete(null)} is idempotent and tells its loser so with a
     * {@code false} return that neither caller here needs to look at, which is the whole reason this
     * is the right type for a race whose two winners agree.
     */
    private final CompletableFuture<Void> subscribersGone = new CompletableFuture<>();

    /**
     * This machine's events, on their way to the farm-wide stream - <strong>droppable</strong>.
     *
     * <p>Together with {@link #toRoster} this is the seam where {@code core} hands a fact to
     * whoever keeps it. {@code core} is the only module that can learn what a printer is, because
     * learning it means asking the printer; {@code printer-management} is where static facts about
     * a machine belong. It crosses as an event, because a submodel does not call another - the
     * {@code ApplicationEventPublisher} is not held as a field any more, only as the two
     * hand-offs' consumer, so there is no way left to publish one of these inline by accident.
     *
     * <p>A dashboard missing a temperature reading is a refresh. See {@link #onEvent} for why the
     * two hand-offs have opposite overflow policies and {@link #FARM_STREAM_BACKLOG} for what the
     * bound buys.
     */
    private final Handoff<EventMessage> toFarm;

    /**
     * The roster's facts about this machine - <strong>never dropped</strong>.
     *
     * <p>Each of these is a state change, and together they are what makes
     * {@code printer_entity.connection_state} <em>derived</em> rather than a second opinion. A
     * dropped one is not a late update, it is a fact about the machine that is gone for good.
     */
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
     * The six-argument form, kept working and kept <strong>synchronous</strong>.
     *
     * <p>Nothing in production calls this any more - {@code PrinterReactorManager} goes through
     * {@link PrinterThreads} - so a direct caller is a test, and what a test wants from the
     * listener side is an inline hand-off. {@code Schedulers.immediate()} gives it one: a parked
     * item is drained on the thread that offered it, so a test can assert what the roster was told
     * on the line after the emission instead of having to await it. Every existing test here
     * passes {@code Schedulers.immediate()} or a virtual-time scheduler through this constructor
     * and stays exactly as deterministic as it was.
     */
    public PrinterReactor(TerminalManager manager, PrinterState printer, ApplicationEventPublisher roster,
                          ConnectionPolicy policy, Scheduler executor, Scheduler retries) {
        this(manager, printer, roster, policy, executor, Schedulers.immediate(), retries);
    }

    /**
     * One machine's runtime, on one machine's threads.
     *
     * <p>{@code executor} is where the aggregate is touched and {@code listeners} is where the
     * consequences are charged - see {@link PrinterThreads} for why those are two schedulers and
     * why each is a single thread. Both are this reactor's to dispose; stopping it does, from
     * {@link #publish} once the terminal signal is on the sink.
     */
    public PrinterReactor(TerminalManager manager, PrinterState printer, ApplicationEventPublisher roster,
                          ConnectionPolicy policy, Scheduler executor, Scheduler listeners, Scheduler retries) {
        this.manager = manager;
        this.printer = printer;
        this.policy = policy;
        this.executor = executor;
        this.listeners = listeners;
        this.retries = retries;
        // Both hand-offs on the same listener scheduler, deliberately: one thread per printer, not
        // two. The drop policy on the farm queue is exactly what covers the case that costs - a
        // slow @Transactional roster write holding the only listener thread while the machine goes
        // on auto-reporting. So the WARN that the farm queue logs does not only mean "a browser
        // stopped reading": it can equally mean a JPA write is stuck, and a farm whose dashboards
        // are dropping messages is worth looking at the database for.
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
     *
     * <p><strong>The intent is the first gate, and it is what makes "safe to call in any state"
     * actually true.</strong> {@link PrinterState#wantsToBeConnected()} is already the authority
     * the retry filter below consults, so a series that is running stops the moment the intent is
     * withdrawn; until now the <em>start</em> of a series consulted nothing. The difference showed
     * up as a printer that was told to disconnect and dialled straight back out of {@code OFFLINE}:
     * {@link #disconnect()} disposes {@link #attempts} and nulls it, so the check below finds
     * nothing in flight and a caller arriving a moment later is made perfectly welcome. The caller
     * that arrives is {@code PrinterReactorManager.onRegistered}, on this machine's listener
     * thread, carrying a roster announcement that was queued before the disconnect was asked for.
     *
     * <p>It is logged rather than silent because the cost of the silence was measured:
     * {@code ConnectivityIntegrationTest.disconnectingClosesTheSocket} spent thirty seconds waiting
     * for a machine to report {@code OFFLINE} that was being redialled out of it, and the log said
     * only that the farm was connecting.
     */
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
     *
     * <p>The completion goes through the outbox like any other emission, so it cannot terminate the
     * sink underneath an event another thread is still publishing - see {@link Parked.StreamEnd}.
     *
     * <p><strong>And this method disposes nothing, deliberately.</strong> It <em>parks</em> a
     * {@code StreamEnd}; it does not necessarily publish one, because {@link Outbox#emit} drains
     * only when no other thread is already draining, and on a machine being deleted in the middle
     * of a conversation the aggregate's own thread usually is. Returning the two threads is
     * the business of whichever thread drains that marker, and it happens in {@link #publish}, on
     * the statement after {@code tryEmitComplete()}. <strong>Moving it back up here reinstates the
     * bug {@link #disposeWhenIdle} exists to prevent, one thread later</strong>; {@link #publish}
     * spells out how.
     *
     * <p>The two {@code close()} calls stay here and stay last, which costs a window of a few
     * instructions: when the drain does run inline, the schedulers are disposed inside
     * {@code emit} and the hand-offs are closed just after, so a roster fact offered by another
     * thread in between is rejected by a dead worker rather than refused by a closed queue.
     * The item is not published either way, and putting the closes first would only start that
     * window earlier.
     *
     * <p><strong>Also completes {@link #subscribersGone} here when nobody was subscribed.</strong>
     * Checked last, after everything above: a machine with no watcher at all has nothing for
     * {@link #awaitSubscribersGone(Duration)}'s caller to wait for, and without this check it would
     * wait out its whole budget anyway. A subscriber that attaches in the narrow window between the
     * check above and this one is still covered - it is counted by then, so this check sees it and
     * leaves completion to its own {@code doFinally} instead.
     *
     * @see #disposeWhenIdle(Scheduler)
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

    /**
     * One subscriber of {@link #updates()} has terminated - by completion, by error, or by
     * cancellation - and {@link #liveSubscribers} is told so.
     *
     * <p>Completes {@link #subscribersGone} when this was the last one <em>and</em> {@link #stop()}
     * has already run. Checking {@link #stopped} here, rather than completing on the first zero
     * unconditionally, is what stops an ordinary subscriber coming and going before any shutdown
     * from completing a future nobody has asked about yet.
     */
    private void subscriberGone() {
        if (liveSubscribers.decrementAndGet() == 0 && stopped.get()) {
            subscribersGone.complete(null);
        }
    }

    /**
     * Waits, bounded, for every subscriber of {@link #updates()} to have actually received this
     * reactor's terminal signal - not merely for {@link #stop()} to have parked one.
     *
     * <p><strong>Why "parked" is not enough, read off a real failing run.</strong> {@link #stop()}
     * only parks a {@code StreamEnd} in the {@link #outbox}; on a machine whose {@link #listeners}
     * thread is mid-drain of a connect handshake - a firmware report and every capability line - the
     * marker queues behind that real traffic, and {@code tryEmitComplete()} has not run by the time
     * {@code stop()} returns. A caller that proceeds on {@code stop()} returning alone has not
     * waited for anything: it is exactly what let a farm-wide shutdown tear the web server down
     * while a browser's SSE response was still mid-write, which Spring Boot's own
     * {@code GracefulShutdown.doShutdown} then sampled as idle and severed rather than letting it
     * finish. This method is the fix - it does not return {@code true} until the subscriber itself
     * has seen the end.
     *
     * <p><strong>Must be called after {@link #stop()}, never before.</strong> Before it, nothing
     * will ever complete {@link #subscribersGone}, and this would simply spend the whole of
     * {@code limit} waiting for something that was never going to happen.
     *
     * <p><strong>Never throws on timeout, and that is deliberate.</strong> A subscriber that will
     * never go - a half-dead TCP connection that neither completes nor is cancelled - must not be
     * allowed to hold up the rest of the farm's shutdown. Timing out here is reported to the caller
     * as a plain {@code false} so it can log the machine and move on, not escalated into an
     * exception that would make one stuck browser fail the whole release.
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

    /**
     * Shuts a scheduler down <em>after</em> whatever is already queued on it has run.
     *
     * <p><strong>Not {@code scheduler.dispose()}, and the next reader will want to simplify it to
     * that.</strong> Disposing a {@code DelegateServiceScheduler} calls {@code shutdownNow()} on
     * its executor, which discards the queue. Its caller has just put two kinds of work on these
     * schedulers that have not run yet - the {@code onComplete} that {@code tryEmitComplete}
     * handed to each subscriber's {@code publishOn} worker, and the roster facts still parked in
     * the lossless hand-off - so an immediate {@code shutdownNow} throws both away. The first of
     * those is pinned by {@code PrinterReactorManagerTest.aReleasedReactorCompletesItsUpdateStream},
     * which is a browser holding an SSE connection to a printer that has just been deleted: with a
     * direct dispose it waits forever.
     *
     * <p><strong>Queueing behind the completion only helps if the completion is already
     * queued</strong>, which is why this is called from {@link #publish} and not from
     * {@link #stop()}. {@code PrinterReactorThreadingTest.aStopThatRacesAnEmissionStillCompletesTheUpdateStream}
     * is that distinction as a test.
     *
     * <p>Scheduling the disposal as the last task on the scheduler itself is what orders it. These
     * are single-threaded executors, so everything already submitted runs, then this runs, then
     * the thread exits.
     *
     * <p>{@code RejectedExecutionException} means the scheduler is already gone, in which case
     * there is nothing queued to protect and a direct dispose is the honest answer. With
     * {@code Schedulers.immediate()} - what the six-argument constructor hands in, so what every
     * test gets - {@code schedule} runs the task inline and {@code dispose()} is an empty method,
     * so the test seam is unaffected either way.
     */
    private static void disposeWhenIdle(Scheduler scheduler) {
        try {
            scheduler.schedule(scheduler::dispose);
        } catch (RejectedExecutionException alreadyGone) {
            scheduler.dispose();
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
        printer.onOnline(new GSender(this::enqueue));
        // Mono.defer, so that reading the socket begins at subscription like every other step.
        // gCode() calls terminal.start(...) eagerly, and start() begins reading as a side effect of
        // being called - so assembling the chain used to read the socket before anything had
        // subscribed to it. That is correct today only because Mono.zip happens to subscribe index
        // 0 first; defence in depth rather than the fix for anything observed.
        return Mono.zip(events, Mono.defer(this::gCode))
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

    /**
     * Whether there is a link to the machine right now.
     *
     * <p><strong>The precondition {@link #feed} and {@link #uploadToCard} already impose, asked
     * before the work is started rather than discovered inside it.</strong> Discovering it inside
     * is what made an upload to a disconnected printer answer <em>200</em> and then fail a moment
     * later as {@code SdUploadProgressed(FAILED)} - so a page drew a progress panel, with a
     * denominator and a cancel button, for a transfer that never began. A caller that can refuse
     * the request outright should.
     *
     * <p><strong>It is the aggregate's own answer</strong>, not a second opinion read off
     * {@code terminal == null}. {@code MachineConnectionState.isLinked()}'s javadoc calls that
     * state "what decides whether a command can be sent at all"; keeping a parallel truth beside
     * it is the four-booleans arrangement the connection enum was introduced to end.
     *
     * <p><strong>It is a snapshot, and reading it from the caller's thread is deliberate.</strong>
     * Since #54 the aggregate is touched by the printer's own thread, and this read is the same one
     * {@link #connection()} already makes from whatever thread subscribes. The link can drop
     * between this answer and the first block, and that case is <em>not this method's to
     * cover</em>: it still arrives as {@code SdUploadProgressed(FAILED)} on the stream. The value
     * answers the request; the event answers the transfer.
     */
    public boolean linked() {
        return printer.getConnection().linked();
    }

    /**
     * Where this machine is now, then everything it does next.
     *
     * <p><strong>A snapshot and not a replay.</strong> A subscriber - a browser opening a printer's
     * page, or a job watching its machine - is handed the aggregate's {@link #snapshot()} first:
     * the connection state, the profile, the card, the last temperature. So a page draws the printer as it
     * is the moment it attaches, rather than a blank until the next auto-report, and a watcher is
     * never handed a stale event from before it existed as if it had just happened.
     *
     * <p>The connection state is always first, which is what a subscriber can wait for to know
     * the subscription is live.
     *
     * <p>The live half is listened to before the snapshot is read - see {@link CatchUp} for why
     * that order, and what it costs, which is at most one repeated value.
     *
     * <p><strong>Counted, for {@link #awaitSubscribersGone(Duration)}.</strong> {@code doOnSubscribe}
     * and {@code doFinally} are the outermost operators on purpose, so they see exactly what an
     * external subscriber - a browser's SSE connection - sees: one subscription, and one terminal
     * signal of whichever kind actually reaches it, cancellation included.
     */
    public Flux<EventMessage> updates() {
        return CatchUp.snapshotThenLive("the update stream of " + printer.getId(),
                        this::snapshot, updates.asFlux(), SUBSCRIBER_BACKLOG)
                .publishOn(executor)
                .doOnNext(rep -> log.info("<- {}", rep))
                .doOnSubscribe(subscription -> liveSubscribers.incrementAndGet())
                .doFinally(signal -> subscriberGone());
    }

    /**
     * What this machine is right now, as the events that would have announced it - see
     * {@link PrinterState#snapshot()}.
     *
     * <p>Read when subscribed, not when called, and read without a hop: it is built from volatile
     * references to immutable values, so asking does not queue behind this machine's replies.
     */
    public Flux<EventMessage> snapshot() {
        return Flux.defer(() -> Flux.fromIterable(printer.snapshot()))
                .map(fact -> new EventMessage(printer.getId(), fact));
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
     * One event from the aggregate: to the farm, to the roster, and then to this machine's own
     * subscribers.
     *
     * <p><strong>All three are now non-blocking offers that cannot fail, and none of them calls a
     * listener on this thread.</strong> The first two park an item on a {@link Handoff} and the
     * third parks one on the {@link Outbox}; whatever the roster's listeners cost - and they cost
     * a {@code @Transactional} JPA write - is charged to {@link #listeners} and not to the thread
     * that is reading this machine's replies off the wire. Before #54 the first two were inline
     * calls to {@code ApplicationEventPublisher}, on a scheduler shared by the whole farm.
     *
     * <p><strong>The durability difference is carried by the two queues' overflow policies now,
     * not by the order of these statements.</strong> {@link #toFarm} is bounded and drops;
     * {@link #toRoster} is unbounded and does not. <em>A browser missing an event is a refresh;
     * the roster missing one is a fact about the machine that is gone for good</em> - that is the
     * argument for the split, and the policies are where it is now written down rather than in
     * which line comes first.
     *
     * <p>The order is kept because it is still the order the facts were decided in, which is what
     * a reader of two queues sees. {@code printer-management} persists a connection state from
     * exactly these messages, and the lossless queue has a single consumer, so what reaches it
     * reaches it in the order this aggregate produced it.
     *
     * <p>The farm hop is the application event bus rather than a second sink handed in here, which
     * keeps plan item D2's farm view from having to be wired into every reactor as it is built -
     * and the bus is what this repository's skill calls the one dispatch mechanism. It carries
     * perhaps one or two messages a second per machine: a temperature auto-report and whatever the
     * aggregate decided was worth announcing, not every acknowledged block.
     */
    private void onEvent(Object report) {
        var message = new EventMessage(printer.getId(), report);
        toFarm.emit(message);
        report(report);
        emit(new Parked.Update(message));
    }

    /**
     * Parks one thing for the update sink, and publishes what is parked if nobody else is.
     *
     * <p><strong>Deliberately the last statement of {@link #onEvent}, because it is the volatile
     * one.</strong> It used to be the first, as {@code updates.emitNext(message, SERIALISE)} with a
     * {@code static final busyLooping} handler, and that was two bugs in one line. The spinning
     * happened on whichever thread emitted - as readily the netty inbound loop as the aggregate's
     * executor - which is this layer's cardinal rule broken outright. And when the spin ran out it
     * <em>threw</em>, so {@code roster.publishEvent} and {@link #report} never ran: a collision did
     * not merely delay a UI update, it lost the machine's firmware from the database and the throw
     * propagated out of {@link #gEvent}'s {@code doOnNext} and killed that session's reply stream.
     * The loss was permanent, not late - {@code PrinterState.on(FirmwareInfoRs)} sets its profile
     * before emitting, so the identical {@code M115} of a later redial is suppressed.
     *
     * <p>Worse than Reactor intends, too: {@code EmitFailureHandler.busyLooping}'s deadline is
     * computed <em>at construction</em> and its javadoc says "don't cache the returning handler for
     * later usage". Held in a static field it is expired one second after the class loads, so every
     * collision from then on threw immediately rather than retrying at all.
     *
     * <p>Parking in the {@link Outbox} throws nothing, blocks nothing and spins nothing, and
     * whichever thread is draining will publish what was parked. So the volatile half of
     * {@code onEvent} cannot skip the durable halves twice over: by construction, because this
     * cannot fail, and by order, because {@link #toFarm} and {@link #toRoster} have already been
     * offered their items by the time it runs. Since #54 those two are hand-offs as well, so
     * "already told" is now "already queued on a single-consumer queue that does not drop" - which
     * is the same guarantee for the roster and a weaker one, deliberately, for the farm stream.
     *
     * <p>The order matters for a second reason, which a test found rather than a reader: the drain
     * loop can hand a <em>later</em> event to a subscriber before the thread carrying the earlier
     * one has got as far as the roster. With the roster offered first, what it is told arrives in
     * the order the aggregate decided it - and {@code printer-management} persists a connection
     * state from exactly those messages, so a reordering there is a stale state written over a
     * current one.
     *
     * @see Outbox
     */
    private void emit(Parked parked) {
        outbox.emit(parked);
    }

    /**
     * One parked item onto the sink. {@link Outbox} only, so {@link #streamEnded} is plain.
     *
     * <p><strong>It is also where this machine's two threads are returned, and that is an ordering
     * requirement rather than tidiness.</strong> {@code tryEmitComplete()} delivers nothing
     * itself: it hands an {@code onComplete} to each subscriber's {@code publishOn} worker
     * <em>as a task on {@link #executor}</em>, and {@link #disposeWhenIdle} is built so the
     * disposal queues behind that task. That argument holds only while {@code tryEmitComplete()}
     * has actually run by the time the disposal is scheduled - and {@link #stop()} cannot promise
     * it.
     *
     * <p><strong>The race, which on a busy machine is the ordinary case.</strong> {@code stop()}
     * parks a {@code StreamEnd} and {@link Outbox#emit} drains only if no other thread is already
     * draining. A machine being deleted mid-conversation has its aggregate thread inside that
     * loop publishing a reply, so {@code emit} returns at once with the marker still queued and
     * nothing completed. Were {@code stop()} to dispose from there it would schedule
     * {@code shutdownNow()} on an executor whose queue is <em>empty</em> - there is no
     * {@code onComplete} in it yet - so it runs immediately. The other thread then publishes the
     * {@code StreamEnd}, {@code tryEmitComplete()} submits the {@code onComplete} to a scheduler
     * that is already gone, and it is discarded. <em>A browser holding an SSE connection to a
     * printer that has just been deleted waits forever</em> - which is the exact failure
     * {@code disposeWhenIdle} was written to prevent, arrived at one thread later.
     * {@code PrinterReactorManagerTest.aReleasedReactorCompletesItsUpdateStream} does not catch it
     * because nothing else is emitting there.
     *
     * <p>So the disposal is ordered through the same outbox that orders everything else on this
     * stream. Whichever thread drains the marker calls {@code tryEmitComplete()} and then, on that
     * same thread and strictly after it, schedules the two disposals - which is the happens-before
     * the design assumed and never had.
     *
     * <p><strong>It runs exactly once.</strong> {@code stop()}'s {@link #stopped} guard admits one
     * caller, so exactly one {@code StreamEnd} is ever parked and the {@code streamEnded} early
     * exit below is unreachable for it - it cannot swallow the disposal with it. {@link Outbox}
     * calls this from one thread at a time, so the pair is scheduled once, and {@code stop()}'s
     * own guard means there is no second {@code stop()} to reject a task onto a dead scheduler.
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
                // Strictly after tryEmitComplete, on this thread, for the reason above. The order
                // between the two is stop()'s and nothing depends on it: each disposal queues
                // behind its own scheduler's work and the two schedulers queue nothing on
                // each other.
                disposeWhenIdle(listeners);
                disposeWhenIdle(executor);
            }
            case Parked.Update(EventMessage message) -> {
                if (streamEnded) {
                    // Nothing follows a terminal signal, ever. A reply still in flight when the
                    // printer was released is the normal way to get here.
                    log.debug("the update stream has ended, so {} goes nowhere", message);
                    return;
                }
                Sinks.EmitResult result = updates.tryEmitNext(message);
                // Nobody watching is the normal case for a machine no page has open, and with
                // nothing replayed there is nothing to keep it for.
                if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                    log.warn("event dropped ({}): {}", result, message);
                }
            }
        }
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
     *
     * <p><strong>Each event is built here, on the aggregate's thread, and only then handed over -
     * including its {@code LocalDateTime.now()}.</strong> The timestamp is meant to say when the
     * machine said it. Building the record on the listener thread would make it say when the
     * database got round to it, and the gap is exactly as long as whatever is making the listener
     * slow - which is to say, longest in the case somebody would later be reading these timestamps
     * to understand.
     *
     * <p>These three are the lossless half of {@link #onEvent}: {@link #toRoster} is unbounded and
     * refuses nothing, because each one is a state change rather than a sample.
     */
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

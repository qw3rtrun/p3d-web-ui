package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.msg.MSpecDeclaredEvent;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The farm's running printers: {@code UUID -> PrinterReactor}, and the only owner allowed to build
 * or release one.
 *
 * <p>A reactor is a <strong>resource</strong>: a TCP connection, a send window, a command queue, a
 * replay sink referencing every SSE subscriber and, since #54, two threads of its own
 * ({@link PrinterThreads} is a factory, not a cache - every {@link #openReactor(UUID)} miss
 * allocates a pair that nothing but that reactor's own {@code stop()} will ever dispose).
 * {@link #release(UUID)} is what returns all of it; {@link #openReactor}, {@link #openReactorIfKnown}
 * and {@link #runtimeOf} differ only in what they do when there is none yet - throw, build one, or
 * answer null - and none of them dials a socket.
 *
 * <h3>Shutdown (#87)</h3>
 * {@link #releaseAll()} stops every reactor first and only then waits for each one's update stream
 * to actually finish, against <strong>one shared budget for the whole farm</strong>
 * ({@link #RELEASE_BUDGET}, five seconds - comfortably under Boot's default thirty-second
 * shutdown-phase timeout, since this runs inside that phase alongside other beans and spending the
 * whole thirty here would only move the hang issue #87 fixes rather than close it). Waiting on each
 * reactor in turn, instead of stopping all of them up front, could cost up to forty times that
 * budget on a farm of forty. {@link #shuttingDown} then refuses every new
 * {@link #openReactorIfKnown}, so a request arriving in the gap before the web server itself starts
 * refusing connections cannot dial a machine the farm is already saying goodbye to.
 * {@code shuttingDown} and {@link #running} are both read and written only inside the narrow
 * {@code synchronized} block at the top of {@link #releaseAll()} - never across the wait that
 * follows, which would otherwise block every concurrent {@link #openReactorIfKnown}/
 * {@link #runtimeOf} caller for the length of the budget. Blocking inside {@link #releaseAll()}
 * itself is correct here: its only caller is {@code SmartLifecycle.stop()} on the shutdown thread,
 * never a WebFlux event loop.
 *
 * <h3>Startup redial</h3>
 * {@link #onRegistered(MRegisteredEvent)} acts only when this module holds no runtime for the
 * machine yet; a machine already running is left alone. That gate matters because
 * {@code MRegisteredEvent} is re-published roughly seven times per connect (once per firmware
 * report, once per capability line, each from a {@code @Transactional} roster listener), so this
 * listener sees "it was linked" long after it stopped being news - one test window held thirty such
 * repeats and eleven redundant {@code -> CONNECTING} transitions before this existed. Gating on
 * "does a runtime exist" rather than on the announcement's own persisted state is deliberate: that
 * state lags the live aggregate by whatever is still ahead of it on this printer's single-consumer
 * listener queue, so the copies that read {@code IDLE} for a machine told to disconnect a moment ago
 * are exactly the ones that would do damage. The redial goes through
 * {@code PrinterReactor.handleConnectCmd}, the only door to a dial there is, so the connection
 * intent is set before the retry's own {@code filter(cause -> wantsToBeConnected())} ever consults
 * it - skipping that used to mean a machine redialled at startup gave up after its first refusal,
 * silently. It is applied on the machine's own thread rather than this listener's (#80) and
 * therefore returns before the dial starts, which is what this listener wants: it is holding
 * nothing open and has the rest of the announcement chain behind it.
 */
@Slf4j
public class PrinterReactorManager {

    private final PrinterDirectory directory;

    private final PrinterAggregateManager aggregateManager;

    private final ApplicationEventPublisher roster;

    private final ConnectionPolicy policy;

    private final PrinterThreads threads;

    private final Scheduler retries;

    private final Map<UUID, PrinterReactor> running = new HashMap<>();

    private boolean shuttingDown;

    private static final Duration RELEASE_BUDGET = Duration.ofSeconds(5);

    private final Duration releaseBudget;

    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster) {
        this(directory, aggregateManager, roster, ConnectionPolicy.standard());
    }

    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster, ConnectionPolicy policy) {
        this(directory, aggregateManager, roster, policy,
                PrinterThreads.virtualPerPrinter(), Schedulers.parallel());
    }

    /** One scheduler for everything, as the farm had before #54 - a test seam ({@link PrinterThreads#shared}). */
    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster, ConnectionPolicy policy,
                                 Scheduler executor, Scheduler retries) {
        this(directory, aggregateManager, roster, policy, PrinterThreads.shared(executor), retries);
    }

    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster, ConnectionPolicy policy,
                                 PrinterThreads threads, Scheduler retries) {
        this(directory, aggregateManager, roster, policy, threads, retries, RELEASE_BUDGET);
    }

    /** Takes {@link #releaseBudget} itself, so a test can prove {@link #releaseAll()} is bounded without waiting out a real five seconds. Package-private: nothing in production picks its own budget. */
    PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                          ApplicationEventPublisher roster, ConnectionPolicy policy,
                          PrinterThreads threads, Scheduler retries, Duration releaseBudget) {
        this.directory = directory;
        this.aggregateManager = aggregateManager;
        this.roster = roster;
        this.policy = policy;
        this.threads = threads;
        this.retries = retries;
        this.releaseBudget = releaseBudget;
    }

    /**
     * The printer's runtime, started if this is the first caller - throws for a machine this module
     * does not know, which is different from "the farm has no such machine" (it can also mean this
     * module has not been told yet). {@link #openReactorIfKnown(UUID)} is the non-throwing form this
     * is built on, and neither one dials a socket.
     */
    public synchronized PrinterReactor openReactor(UUID uuid) {
        return openReactorIfKnown(uuid)
                .orElseThrow(() -> new IllegalArgumentException("no machine known with id " + uuid));
    }

    /**
     * The printer's runtime, started if this is the first caller, or empty for an unknown machine -
     * which is ordinary (a page left open on a deleted printer) rather than exceptional. One
     * {@code synchronized} step rather than a check-then-act at the call site, so a concurrent
     * {@code MDeletedEvent} cannot land in the gap and leave a reactor nothing will ever release.
     * Answers empty unconditionally once {@link #releaseAll()} has run - see the class javadoc.
     */
    public synchronized Optional<PrinterReactor> openReactorIfKnown(UUID uuid) {
        if (shuttingDown) {
            return Optional.empty();
        }
        var already = running.get(uuid);
        if (already != null) {
            return Optional.of(already);
        }
        return directory.find(uuid).map(entry -> {
            var reactor = startReactor(entry);
            running.put(uuid, reactor);
            return reactor;
        });
    }

    /**
     * Whether this module has even been told about the machine - opens nothing. Answers from
     * {@link PrinterDirectory}, the only roster this module may consult (rule 2 of
     * {@code doc/sub_boundaries.txt}), so it can lag the database by a transaction and no more.
     * Kept separate from {@link #openReactorIfKnown} because a question like a cancel's "is this
     * even running" should not have to pay for a socket, a queue and two threads to be answered.
     */
    public boolean knows(UUID uuid) {
        return directory.find(uuid).isPresent();
    }

    /** Stops and forgets a printer's runtime. Idempotent, and a no-op for one never opened. */
    public synchronized void release(UUID uuid) {
        var reactor = running.remove(uuid);
        if (reactor == null) {
            return;
        }
        log.info("releasing the runtime of {}", uuid);
        reactor.stop();
    }

    /**
     * Lets go of every printer's runtime and closes the gate on opening another - see the class
     * javadoc's Shutdown section. Idempotent: a second call finds {@link #shuttingDown} already set
     * and touches nothing.
     */
    public void releaseAll() {
        List<Map.Entry<UUID, PrinterReactor>> released;
        synchronized (this) {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
            released = List.copyOf(running.entrySet());
            running.clear();
        }
        for (var entry : released) {
            log.info("releasing the runtime of {}", entry.getKey());
            entry.getValue().stop();
        }
        var deadline = System.nanoTime() + releaseBudget.toNanos();
        for (var entry : released) {
            var remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            if (!entry.getValue().awaitSubscribersGone(remaining)) {
                log.info("{} did not say goodbye to its own subscribers within {}; leaving it rather "
                        + "than holding up the rest of the farm's shutdown", entry.getKey(), releaseBudget);
            }
        }
    }

    /**
     * A machine was removed from the farm, so its runtime goes with it - an event rather than a call,
     * since {@code management} must not reach into this module. A plain {@link EventListener}, not a
     * transactional one: the transactional form silently does nothing outside a transaction, which
     * would reappear as a leaked socket; releasing slightly early on a rolled-back delete is the
     * cheaper mistake; a new runtime simply builds again later.
     */
    @EventListener
    public void onDeleted(MDeletedEvent event) {
        release(event.getMachineId());
    }

    /**
     * A re-announced machine: if the roster says it was linked and nothing is running yet, it is
     * dialled again - see the class javadoc's Startup redial section. Ordered after
     * {@link PrinterDirectory#onRegistered}, which the redial depends on having already run.
     *
     * <p>Deliberately does <strong>not</strong> apply the spec it carries; that is
     * {@link #onSpecDeclared} and {@code MSpecDeclaredEvent}'s whole reason for existing.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener
    public void onRegistered(MRegisteredEvent event) {
        if (!event.getState().isLinked()) {
            return;
        }
        redialAtStartup(event);
    }

    // synchronized with everything else touching running(), so the check and the open are one step
    // against a concurrent release(UUID). openReactorIfKnown rather than openReactor: the replica
    // answers empty for a machine the farm has deleted - which is exactly what a stale announcement
    // is about (#93) - and for a farm already shutting down, neither of which is a reason to throw
    // out of a listener and cost whatever is queued behind it.
    private synchronized void redialAtStartup(MRegisteredEvent event) {
        if (running.containsKey(event.getMachineId())) {
            return;
        }
        openReactorIfKnown(event.getMachineId()).ifPresent(reactor -> {
            log.info("{} was connected when the farm was last up, so dialling it again", event.getName());
            reactor.handleConnectCmd(new ConnectCmd(true));
        });
    }

    /** The runtime of a machine already running, or null - deliberately does not build one (unlike {@link #openReactor}). */
    public synchronized PrinterReactor runtimeOf(UUID uuid) {
        return running.get(uuid);
    }

    /**
     * Hands an edited spec to an already-running machine (plan item B4) - an aggregate is otherwise
     * only seeded at construction, so a declared change would reach the database and not the object
     * that refuses commands until a restart. Touches only an existing runtime, never builds one: a
     * machine that is not running will be seeded from {@link PrinterDirectory}'s entry when it is
     * stood up ({@code CoreConfiguration.aggregates}), and building one here would open a reactor
     * per row in the farm.
     *
     * <p>Called on the {@code boundedElastic} worker serving the {@code PUT}, inside the roster's
     * transaction, and it must not block there - so since #80 it <em>queues</em> the edit on the
     * machine's own thread rather than applying it here. That keeps plan item B4's promise without
     * a second writer of the aggregate: the executor is single-threaded and FIFO, so a command
     * issued after the {@code PUT} has answered is necessarily behind this edit, which is the only
     * ordering the endpoint's caller can observe. Nothing on the way out of the {@code PUT} reads
     * the aggregate - {@code describe()} answers from the roster.
     *
     * <p><strong>Deliberately not driven by {@code MRegisteredEvent}.</strong> That event is an
     * announcement re-published about seven times per connect, each copy carrying the spec as of its
     * own read, so one built before an edit silently put the old spec back 1.5 ms after it and the
     * next command was refused and never retried - issue #93's fourth root cause, captured in
     * {@code MSpecDeclaredEvent}'s KDoc.
     */
    @EventListener
    public synchronized void onSpecDeclared(MSpecDeclaredEvent event) {
        var reactor = running.get(event.getMachineId());
        if (reactor != null) {
            reactor.declared(event.getSpec());
        }
    }

    /**
     * Builds one machine's runtime on threads allocated for it alone - asked for here and nowhere
     * else, which is what makes this reactor the one thing able to dispose them. {@code retries} is
     * shared across the farm on purpose: it only ever holds a timer and does no work of this
     * printer's. Takes the directory entry rather than re-looking it up, since the caller has
     * already answered "is there one" and a second lookup on an event-fed replica is not guaranteed
     * to agree.
     */
    private PrinterReactor startReactor(PrinterDirectory.Entry entry) {
        var uuid = entry.id();
        var aggregate = (PrinterState) aggregateManager.loadState(uuid);
        return new PrinterReactor(new TerminalManager(entry.connection()), aggregate, roster,
                policy, threads.aggregateOf(uuid), threads.listenersOf(uuid), retries);
    }
}

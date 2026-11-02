package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The running printers, and the thing that owns them.
 *
 * <p>A reactor is a <strong>resource</strong>, not a value: it holds a TCP connection, a send
 * window, a command queue, a replay sink that keeps every SSE subscriber referenced, and - since
 * #54 - <strong>two threads of its own</strong>. This map used to only ever grow -
 * {@code computeIfAbsent} and nothing else - so deleting a printer through the management API left
 * all of that behind, connected to a machine the farm no longer knew about. Over a long-running
 * process that is a socket and a sink per printer ever registered.
 *
 * <p>{@link #release(UUID)} is what returns all of it, and the threads are the newest and least
 * obvious part of the list: {@link PrinterThreads} is a factory rather than a cache, so every
 * {@link #openReactor(UUID)} that misses allocates a scheduler pair that nothing else will ever
 * dispose. The reactor disposes them in {@code stop()}; this is what calls it.
 */
@Slf4j
public class PrinterReactorManager {

    private final PrinterDirectory directory;

    private final PrinterAggregateManager aggregateManager;

    private final ApplicationEventPublisher roster;

    private final ConnectionPolicy policy;

    /**
     * Where a machine's runtime is allowed to run - one thread for its aggregate, one for its
     * listeners, per machine.
     *
     * <p>Held instead of a single {@code Scheduler} since #54. The old field was the farm's one
     * shared {@code Schedulers.single()}, handed to every reactor this class built.
     */
    private final PrinterThreads threads;

    private final Scheduler retries;

    private final Map<UUID, PrinterReactor> running = new HashMap<>();

    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster) {
        this(directory, aggregateManager, roster, ConnectionPolicy.standard());
    }

    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster, ConnectionPolicy policy) {
        this(directory, aggregateManager, roster, policy,
                PrinterThreads.virtualPerPrinter(), Schedulers.parallel());
    }

    /**
     * The form that still takes one scheduler for everything, which is what a test wants.
     *
     * <p>Kept so that the seam a {@code VirtualTimeScheduler} or a {@code Schedulers.immediate()}
     * reaches the reactor through survives #54 unchanged. It is
     * {@link PrinterThreads#shared(Scheduler)}: every machine on the one handed in, and the
     * listeners on it too, which is exactly the arrangement the farm had before.
     *
     * <p>Unambiguous against the overload below because {@code Scheduler} and
     * {@link PrinterThreads} are unrelated types.
     */
    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster, ConnectionPolicy policy,
                                 Scheduler executor, Scheduler retries) {
        this(directory, aggregateManager, roster, policy, PrinterThreads.shared(executor), retries);
    }

    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster, ConnectionPolicy policy,
                                 PrinterThreads threads, Scheduler retries) {
        this.directory = directory;
        this.aggregateManager = aggregateManager;
        this.roster = roster;
        this.policy = policy;
        this.threads = threads;
        this.retries = retries;
    }

    /**
     * The printer's runtime, started if this is the first caller.
     *
     * <p><strong>Called {@code open} and not {@code get} because it is not a getter.</strong> On a
     * miss it builds a reactor for the machine, which is a resource. It pairs with
     * {@link #release(UUID)}: whatever this opens, that closes.
     *
     * <p>It does not open a socket. It used to, when the printer's row said {@code connected} -
     * which made a GET on a query path dial a machine, and which was dead code besides, since
     * nothing in production ever wrote the flag. Reconnecting after a restart is a real feature and
     * it is {@link #onRegistered(MRegisteredEvent)} now: a deliberate step at startup rather than a
     * side effect of a lookup.
     *
     * <p>Throws when the machine is unknown to this module. That is a different statement from
     * "the farm has no such machine" - it can also mean this module has not been told yet - and the
     * caller gets to see the difference rather than receiving a reactor pointed at nowhere.
     *
     * <p>A caller that would rather answer an unknown machine than be thrown at asks
     * {@link #openReactorIfKnown(UUID)}, which is what this is built on.
     */
    public synchronized PrinterReactor openReactor(UUID uuid) {
        return openReactorIfKnown(uuid)
                .orElseThrow(() -> new IllegalArgumentException("no machine known with id " + uuid));
    }

    /**
     * The printer's runtime, started if this is the first caller - or empty when there is no such
     * machine.
     *
     * <p>The same resource {@link #openReactor(UUID)} opens, with absence as a value instead of an
     * exception. That matters because the absence is ordinary: a page left open on a printer
     * somebody deleted posts to it, and the caller wants to answer "the farm has no machine X"
     * rather than catch an {@code IllegalArgumentException} and guess what it meant.
     *
     * <p><strong>One {@code synchronized} step, and that is the point of it being here rather than
     * composed at the call site.</strong> {@code knows(id)} followed by {@code openReactor(id)}
     * would be a check-then-act with a window for a concurrent {@code MDeletedEvent} to land in,
     * and the loser of that race opens a reactor nothing will ever release.
     *
     * <p>It still does not dial. Whatever this opens, {@link #release(UUID)} closes.
     */
    public synchronized Optional<PrinterReactor> openReactorIfKnown(UUID uuid) {
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
     * Whether this module has been told about the machine at all - and <strong>opens nothing</strong>.
     *
     * <p>Separate from {@link #openReactorIfKnown(UUID)} because some questions do not deserve a
     * runtime. A <em>cancel</em> has to be able to answer "there is no such machine" without
     * building one, and building one costs a socket's worth of resource - a terminal manager, a
     * command queue, a replay sink that keeps every subscriber referenced and, since #54, two
     * threads of this machine's own. Allocating all of that to tell a caller that a transfer which
     * is not running is not running would be the opposite of {@link #release(UUID)}'s whole point.
     *
     * <p><strong>It is {@code core}'s replica answering, and that is the only roster this module
     * is allowed to consult</strong> - rule 2 of {@code doc/sub_boundaries.txt}.
     * {@link PrinterDirectory} is fed by {@code MRegisteredEvent} and {@code MDeletedEvent}, so
     * "known" here means "this module has been told", which can lag the database by a transaction
     * and never by more. Asking {@code MachineManagementService} instead would be {@code core}
     * naming another submodel, and it would be a blocking JDBC call besides.
     */
    public boolean knows(UUID uuid) {
        return directory.find(uuid).isPresent();
    }

    /**
     * Lets go of a printer's runtime: stops it, and forgets it.
     *
     * <p>Idempotent, and a no-op for a printer that was never opened - a delete of a machine nobody
     * ever talked to has nothing to release, and should not have to know that.
     */
    public synchronized void release(UUID uuid) {
        var reactor = running.remove(uuid);
        if (reactor == null) {
            return;
        }
        log.info("releasing the runtime of {}", uuid);
        reactor.stop();
    }

    /**
     * A machine was removed from the farm, so its runtime goes with it.
     *
     * <p>An event and not a call, because {@code management} must not reach into this module - and
     * because the two facts are genuinely separate: the row is gone whether or not anything was
     * holding a socket to it.
     *
     * <p>A plain {@link EventListener} rather than a transactional one. The transactional variant
     * would only release after the delete commits, which is tidier, but it silently does nothing
     * when no transaction is active - a failure mode that would reappear as a leaked socket the
     * next time someone published this event from somewhere else. Releasing slightly early is the
     * cheaper mistake: a rolled-back delete leaves a printer whose runtime is closed, and the next
     * {@link #openReactor(UUID)} builds a new one.
     */
    @EventListener
    public void onDeleted(MDeletedEvent event) {
        release(event.getMachineId());
    }

    /**
     * A machine the roster says was connected gets dialled again.
     *
     * <p><strong>This is what gives the persisted connection state a reader.</strong> Before B3 the
     * {@code connected} column was written once at registration and never again, so a restart lost
     * every connection and nothing noticed; B3 makes {@code core} report each transition and the
     * roster keep it, and this is the other end of that - without it the column would merely be
     * write-only in a new place.
     *
     * <p>Ordered after {@link PrinterDirectory#onRegistered}, which consumes the same event: this
     * asks the replica where to dial, so the replica has to have been told first.
     *
     * <p><strong>An announcement is acted on once - when this module has no runtime for the
     * machine - and a machine that is already running is left entirely alone.</strong>
     * {@code MRegisteredEvent} is a statement of current fact re-published whenever the roster
     * learns something, which is roughly seven times per connect: once per firmware report and
     * once per capability line, each of them a {@code @Transactional} listener in
     * {@code MachineManagementImpl} that reads the row back and announces it again. So this
     * listener sees the same "it was linked" sentence over and over, long after it was true.
     *
     * <p>The paragraph this replaces claimed the repetition was harmless because
     * {@code openReactor} is idempotent and {@code connect()} "declines to start a second attempt
     * while one is in flight". That clause was load-bearing and false at the one moment it was
     * needed: {@link PrinterReactor#disconnect()} disposes the attempt and nulls it, so after a
     * disconnect there is no attempt in flight and a republication queued behind it redialled the
     * machine. See {@link #redialAtStartup(MRegisteredEvent)} for why the runtime, and not the
     * event's own state, is the thing that can tell the two apart.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener
    public void onRegistered(MRegisteredEvent event) {
        redeclare(event);
        if (!event.getState().isLinked()) {
            return;
        }
        redialAtStartup(event);
    }

    /**
     * Dials a machine the roster says was linked - the first time it says so, and never again.
     *
     * <p><strong>The redial is issued as a command, not as a call to {@code connect()}.</strong>
     * This repository's rule is that every operating command crosses the aggregate with a named
     * {@code PrinterState.handle(<Command>)}; {@code openReactor(id).connect()} was the write side
     * going round it, and that is how the connection intent came to be unset on a machine the farm
     * was actively dialling.
     *
     * <p><strong>Which fixed a second thing in the same line.</strong> Because the startup redial
     * never set the intent, {@code connect()}'s own
     * {@code Retry.backoff(...).filter(cause -> printer.wantsToBeConnected())} was consulting an
     * intent nobody had established - so a machine redialled at startup gave up after its first
     * refusal, with no retry and nothing in the log to say the series had been cut short. Nothing
     * covered that, which is why it was never seen.
     *
     * <p><strong>And it removes the republication storm.</strong> One test window of
     * {@code ConnectivityIntegrationTest} held thirty "dialling it again" lines for one machine and
     * eleven {@code -> CONNECTING} transitions. It is one line now.
     *
     * <p><strong>Why the runtime is the gate and {@code event.getState()} is not.</strong> The
     * state on the announcement is the <em>persisted</em> one, and it lags the live aggregate by
     * however much of this printer's listener queue is still ahead of the
     * {@code MConnectionChangedEvent} that would correct it - the republication triggers and the
     * connection fact travel the same single-consumer queue, and the triggers are in front. So the
     * copies that say {@code IDLE} for a machine told to disconnect a millisecond ago are exactly
     * the ones that do the damage, and gating on them would gate on nothing. Whether this module
     * already holds a runtime is not stale: at startup there is none, and afterwards there is one
     * that has already been told what it should be doing by someone closer to the truth than a
     * re-published statement of fact.
     *
     * <p>{@code synchronized} like everything else that touches {@link #running}, so that the
     * check and the open are one step against a concurrent {@link #release(UUID)}.
     */
    private synchronized void redialAtStartup(MRegisteredEvent event) {
        if (running.containsKey(event.getMachineId())) {
            return;
        }
        log.info("{} was connected when the farm was last up, so dialling it again", event.getName());
        openReactor(event.getMachineId()).handleConnectCmd(new ConnectCmd(true));
    }

    /**
     * Hands an edited spec to a machine that is already running (plan item B4).
     *
     * <p>An aggregate is seeded with the spec when it is built, which covers a restart and nothing
     * else. Without this, declaring a second extruder on a printer that is connected right now
     * would reach the database and the replica and not the object that refuses the command - so
     * the edit would appear to do nothing until the farm was restarted, which is the same shape of
     * defect as the write-only {@code connected} column plan item B3 removed.
     *
     * <p>Only an existing runtime is touched: this must not build one. {@code MRegisteredEvent} is
     * re-published for every machine at startup and on every firmware report, and starting a
     * reactor for each would undo plan item A3's "a reactor has an owner" by opening one for every
     * row in the farm.
     */
    /**
     * The runtime of a machine that is already running, or null.
     *
     * <p>Deliberately does not build one, which is why it is not {@code openReactor}. A feed into a
     * machine nothing has dialled is a print into a socket that does not exist, and answering it
     * with a fresh reactor would make that look like it had started.
     */
    public synchronized PrinterReactor runtimeOf(UUID uuid) {
        return running.get(uuid);
    }

    private synchronized void redeclare(MRegisteredEvent event) {
        var reactor = running.get(event.getMachineId());
        if (reactor != null) {
            reactor.declared(event.getSpec());
        }
    }

    /**
     * Builds one machine's runtime, on threads allocated for that machine alone.
     *
     * <p>The two schedulers are asked for here and nowhere else, so the reactor that gets them is
     * the one thing that could dispose them - which is what makes "a reactor owns its threads"
     * true rather than aspirational. {@code retries} is shared on purpose: it only ever holds a
     * timer, it does no work of this printer's, and giving every machine its own would be a thread
     * per printer for nothing.
     *
     * <p><strong>It takes the directory entry rather than finding it.</strong> The caller has just
     * looked the machine up in order to decide whether there is one at all, and looking it up
     * again here would be a second answer to a question already asked - which on a replica fed by
     * events is not guaranteed to be the same answer.
     */
    private PrinterReactor startReactor(PrinterDirectory.Entry entry) {
        var uuid = entry.id();
        var aggregate = (PrinterState) aggregateManager.loadState(uuid);
        return new PrinterReactor(new TerminalManager(entry.connection()), aggregate, roster,
                policy, threads.aggregateOf(uuid), threads.listenersOf(uuid), retries);
    }
}

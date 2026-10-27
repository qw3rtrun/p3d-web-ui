package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
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
import java.util.UUID;

/**
 * The running printers, and the thing that owns them.
 *
 * <p>A reactor is a <strong>resource</strong>, not a value: it holds a TCP connection, a send
 * window, a command queue and a replay sink that keeps every SSE subscriber referenced. This map
 * used to only ever grow - {@code computeIfAbsent} and nothing else - so deleting a printer through
 * the management API left all of that behind, connected to a machine the farm no longer knew about.
 * Over a long-running process that is a socket and a sink per printer ever registered.
 */
@Slf4j
public class PrinterReactorManager {

    private final PrinterDirectory directory;

    private final PrinterAggregateManager aggregateManager;

    private final ApplicationEventPublisher roster;

    private final ConnectionPolicy policy;

    private final Scheduler executor;

    private final Scheduler retries;

    private final Map<UUID, PrinterReactor> running = new HashMap<>();

    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster) {
        this(directory, aggregateManager, roster, ConnectionPolicy.standard());
    }

    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster, ConnectionPolicy policy) {
        this(directory, aggregateManager, roster, policy, Schedulers.single(), Schedulers.parallel());
    }

    public PrinterReactorManager(PrinterDirectory directory, PrinterAggregateManager aggregateManager,
                                 ApplicationEventPublisher roster, ConnectionPolicy policy,
                                 Scheduler executor, Scheduler retries) {
        this.directory = directory;
        this.aggregateManager = aggregateManager;
        this.roster = roster;
        this.policy = policy;
        this.executor = executor;
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
     */
    public synchronized PrinterReactor openReactor(UUID uuid) {
        return running.computeIfAbsent(uuid, this::startReactor);
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
     * <p>Safe to receive repeatedly, which matters because {@code MRegisteredEvent} is a statement
     * of current fact re-published whenever the roster learns something: {@code openReactor} is
     * idempotent and {@code connect()} declines to start a second attempt while one is in flight.
     * A machine already connected is therefore left alone rather than redialled.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener
    public void onRegistered(MRegisteredEvent event) {
        if (!event.getState().isLinked()) {
            return;
        }
        log.info("{} was connected when the farm was last up, so dialling it again", event.getName());
        openReactor(event.getMachineId()).connect();
    }

    private PrinterReactor startReactor(UUID uuid) {
        var entry = directory.find(uuid).orElseThrow(() ->
                new IllegalArgumentException("no machine known with id " + uuid));
        var aggregate = (PrinterState) aggregateManager.loadState(uuid);
        return new PrinterReactor(new TerminalManager(entry.connection()), aggregate, roster,
                policy, executor, retries);
    }
}

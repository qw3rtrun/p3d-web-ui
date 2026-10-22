package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.springframework.context.event.EventListener;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this module knows about the machines in the farm: enough to dial one, and nothing more.
 *
 * <p><strong>A replica, fed by events, and never a query.</strong> Rule 2 of
 * {@code doc/sub_boundaries.txt} says {@code core} communicates with other submodels through
 * events, and until now it did not: {@code PrinterReactorManager} called
 * {@code MachineManagementService.fetch} to find out where to dial. That read as compliant because
 * the interface lives in the shared {@code api} module, so the boundary check could not see it -
 * which is precisely why the rule needed to be made real rather than merely written down.
 *
 * <p>What it buys is not purity. It is that this module can be started, tested and reasoned about
 * without a database behind it: a printer is something it was *told* about, so a test tells it.
 * {@code PrinterReactorManagerTest} has no management module in it at all.
 *
 * <p><strong>Idempotent by construction.</strong> {@link MRegisteredEvent} is a statement of
 * current fact rather than of a change, and {@code printer-management} re-announces every row at
 * startup because a replica is empty when the process begins. So the same machine arrives more than
 * once by design, and the map simply takes the latest.
 *
 * <p><strong>A plain {@link EventListener}, and the reason is worth knowing.</strong> The obvious
 * choice is {@code @TransactionalEventListener}, so the replica only learns about a machine once
 * its row is committed. Using it here fails at runtime: {@code spring-modulith-starter-jpa} is on
 * the persistence module's classpath, and a transactional listener switches on Modulith's
 * <em>durable event publication registry</em>, which writes every such event to an
 * {@code EVENT_PUBLICATION} table. Liquibase owns this schema and has no changeset for that table,
 * so registering a printer dies on commit with {@code Table "EVENT_PUBLICATION" not found}.
 *
 * <p>That registry is a real feature and the plan wants it later - at-least-once delivery for job
 * events that must survive a restart (items C2 and C5), which needs a changeset for its table. It
 * is the wrong tool for <em>this</em>: a replica is derived state, rebuilt from the startup
 * announcement every time the process begins, so durably persisting the events that fill it buys
 * nothing and costs a database write per registration.
 *
 * <p>The cost of the plain listener is that it fires inside the registering transaction rather than
 * after it. A rolled-back {@code add} therefore leaves a machine in this map that has no row -
 * which is survivable, because a reactor opened for it can reach nothing and no caller is misled
 * about the farm's contents, since the farm's contents are answered from the database and not from
 * here.
 */
@Slf4j
public class PrinterDirectory {

    /** Everything needed to reach one machine. The farm's view of a printer is richer; this is not that. */
    public record Entry(UUID id, String name, ConnectionDetails connection) {
    }

    private final Map<UUID, Entry> known = new ConcurrentHashMap<>();

    @EventListener
    public void onRegistered(MRegisteredEvent event) {
        var entry = new Entry(event.getMachineId(), event.getName(),
                new ConnectionDetails(event.getHost(), event.getPort()));
        var previous = known.put(entry.id(), entry);
        if (previous == null) {
            log.info("the farm has {} at {}:{}", entry.name(), event.getHost(), event.getPort());
        }
    }

    @EventListener
    public void onDeleted(MDeletedEvent event) {
        var gone = known.remove(event.getMachineId());
        if (gone != null) {
            log.info("the farm no longer has {}", gone.name());
        }
    }

    /**
     * Where to dial a machine, if this module has been told about it.
     *
     * <p>Empty is a real answer and not an error: a machine registered a moment ago in another
     * transaction, or one deleted while a request was in flight. The caller decides what to do
     * about it, which is better than this guessing.
     */
    public Optional<Entry> find(UUID id) {
        return Optional.ofNullable(known.get(id));
    }

    /** How many machines this module knows about - a test's way of seeing the replica fill. */
    public int size() {
        return known.size();
    }
}

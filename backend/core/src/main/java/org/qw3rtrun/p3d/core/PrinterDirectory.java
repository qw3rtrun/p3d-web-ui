package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;

import java.util.List;
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

    /**
     * Everything this module needs about one machine: where to dial it, and what it turned out to
     * be.
     *
     * <p>The profile is here so that a restart does not have to ask. {@code PrinterState} checks a
     * tool index against the extruder count, and before anything learned it that check had nothing
     * to read - which is why a command to a machine the process had never connected to failed with
     * a {@code NullPointerException} rather than a refusal.
     *
     * <p>The spec travels beside it because that same check has to honour an override. A machine
     * flashed with a single-extruder configuration reports one tool whatever is bolted to it, and
     * the operator's declared count is the only thing that can say otherwise - so a replica that
     * carried the profile alone would refuse every command for tool 1 on a machine its owner had
     * already described correctly. It is also what plan item D1 will match a job against.
     *
     * <p>The state is the roster's last-known record of the machine's connection, and it is read
     * once: {@link PrinterReactorManager} redials, at startup, whatever was connected when the
     * process went down. It is not kept current here afterwards - {@code core} is where the live
     * answer lives, on the aggregate - so a reader that wants to know whether a printer is
     * connected asks {@code PrinterControl.connection}, not this.
     */
    public record Entry(UUID id, String name, ConnectionDetails connection, MachineProfile profile,
                        MachineSpec spec, MachineConnectionState state) {
    }

    private final Map<UUID, Entry> known = new ConcurrentHashMap<>();

    /**
     * Every machine this module has been told about.
     *
     * <p>A copy, so a caller iterating it cannot be surprised by a printer being registered
     * halfway through - which plan item D2's snapshot would otherwise be, since it is built on
     * whatever thread a browser attached on.
     */
    public List<Entry> all() {
        return List.copyOf(known.values());
    }

    /**
     * <p>{@code HIGHEST_PRECEDENCE} because {@link PrinterReactorManager} listens to the same event
     * in order to redial what was connected, and it asks this replica where to dial. Spring runs
     * same-event listeners in an undefined order unless they say otherwise, and "undefined" here
     * would mean a restart that reconnects only when the coin lands right.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    public void onRegistered(MRegisteredEvent event) {
        var entry = new Entry(event.getMachineId(), event.getName(),
                new ConnectionDetails(event.getHost(), event.getPort()), event.getProfile(),
                event.getSpec(), event.getState());
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

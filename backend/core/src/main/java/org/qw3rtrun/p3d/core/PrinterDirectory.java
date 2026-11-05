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
 * <p>A replica fed by events, never a query - rule 2 of {@code doc/sub_boundaries.txt}, made real
 * rather than merely written down: {@code PrinterReactorManager} used to call
 * {@code MachineManagementService.fetch} directly, which read as compliant only because the
 * interface lives in the shared {@code api} module and the boundary check could not see it. The
 * payoff is not purity but testability - this module starts and is reasoned about with no database
 * behind it, since a printer is something it was <em>told</em> about ({@code PrinterReactorManagerTest}
 * has no management module in it at all).
 *
 * <p>Idempotent by construction: {@link MRegisteredEvent} is a statement of current fact, not of a
 * change, and {@code printer-management} re-announces every row at startup since the replica starts
 * empty - so the map simply takes the latest arrival.
 *
 * <p>A plain {@link EventListener}, not {@code @TransactionalEventListener}: the transactional form
 * switches on Modulith's durable event publication registry the moment
 * {@code spring-modulith-starter-jpa} is on the classpath, writing every such event to an
 * {@code EVENT_PUBLICATION} table Liquibase has no changeset for - so registering a printer would
 * die on commit. That registry is the right tool for job events that must survive a restart (items
 * C2/C5); it is the wrong one for a replica that is rebuilt from scratch every boot anyway. The
 * trade-off: this listener fires inside the registering transaction, so a rolled-back {@code add}
 * can leave an entry with no row - survivable, since a reactor opened for it reaches nothing and the
 * farm's real contents are still answered from the database, not from here.
 */
@Slf4j
public class PrinterDirectory {

    /**
     * Everything this module needs about one machine: where to dial it, and what it turned out to
     * be. The profile is here so a restart does not have to ask - {@code PrinterState} checks a
     * tool index against the extruder count, and before this existed that check had nothing to
     * read, so a command to a never-connected machine NPE'd instead of being refused. The spec
     * travels beside it because the same check must honour an operator's override (a replica
     * carrying the profile alone would refuse tool 1 on a machine its owner had already described
     * correctly); it is also what plan item D1 will match a job against. The state is read once, at
     * startup, for {@link PrinterReactorManager} to redial what was connected when the process went
     * down - it is not kept current afterwards, since the live answer is {@code core}'s own
     * aggregate, reached through {@code PrinterControl.connection}.
     */
    public record Entry(UUID id, String name, ConnectionDetails connection, MachineProfile profile,
                        MachineSpec spec, MachineConnectionState state) {
    }

    private final Map<UUID, Entry> known = new ConcurrentHashMap<>();

    /** A copy, so a caller iterating it (plan item D2's snapshot, built on whatever thread a browser attached on) cannot be surprised by a registration arriving mid-iteration. */
    public List<Entry> all() {
        return List.copyOf(known.values());
    }

    /** {@code HIGHEST_PRECEDENCE}: {@link PrinterReactorManager} listens to the same event to redial what was connected, and asks this replica where - Spring's otherwise-undefined ordering would mean a restart that reconnects only when the coin lands right. */
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

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
 * empty - so the map takes the latest arrival, <strong>except for a machine that has been
 * deleted</strong>. For a deleted id a late arrival is not the latest fact, and issue #93 measured
 * what believing one costs: the roster re-publishes {@code MRegisteredEvent} about seven times per
 * connect, each from a {@code @Transactional} listener queued on that machine's own
 * {@code p3d-listener-<uuid>} thread, so one can land five milliseconds <em>after</em> the
 * {@code MDeletedEvent} that followed it in real time. It put the entry back; {@code PrinterReactorManager}
 * then found no runtime for a machine the roster still called {@code IDLE} and dialled a second
 * one, which nothing would ever release - a socket, a send window, a replay sink and two threads,
 * redialling the closed port for 45 seconds into the test classes that followed.
 *
 * <p>So {@link #onDeleted} writes a tombstone and {@link #onRegistered} refuses an id that carries
 * one. Three things about that, each load-bearing:
 * <ul>
 * <li><strong>It never expires.</strong> The straggler is bounded by that machine's listener queue,
 * not by a clock, so there is no interval that is safely long. The set holds one {@code UUID} per
 * machine ever deleted in this process, which on a farm of tens of machines is nothing.</li>
 * <li><strong>A deleted id can never legitimately return.</strong> {@code MachineManagementImpl}
 * generates a fresh {@code UUID} for every {@code add}, and an edit re-announces a machine that was
 * never deleted - which is why refusing by id is not over-strict.</li>
 * <li><strong>Not by timestamp.</strong> Both events carry a {@code LocalDateTime}, but they are
 * taken on different threads at millisecond resolution and the measured pair is 5 ms apart.</li>
 * </ul>
 *
 * <p>Both maps are concurrent and both listeners run on whichever thread published - a machine's
 * own listener thread, or a {@code boundedElastic} worker carrying a delete. The tombstone is
 * written <em>before</em> the entry is removed, and {@link #onRegistered} reads it from inside
 * {@code known.compute}, so the check and the write are one step under that key's lock: a delete
 * that lands in the middle either loses the race entirely (and its own {@code remove} runs after)
 * or is already visible (and the entry is never written). A {@code containsKey}-then-{@code put}
 * would have a gap between them, which is the whole of the bug being fixed.
 *
 * <p><strong>Both listeners are {@code HIGHEST_PRECEDENCE}, the delete side included</strong>, and
 * that half closes the last way round the tombstone. The two delete listeners run on one thread
 * inside one {@code publishEvent}, but a stale announcement arrives on another and can land
 * between them: with {@code PrinterReactorManager.onDeleted} first it would find the runtime
 * already released and the entry not yet gone, and dial a second one. Ordered this way the
 * announcement is either wholly before the delete (the machine is still running, so nothing dials)
 * or wholly after the tombstone (refused). The default for a listener is
 * {@code LOWEST_PRECEDENCE}, which makes the relative order bean-definition order and not a
 * contract.
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

    /** Every machine this process has been told was removed - see the class javadoc for why it is never pruned. */
    private final Set<UUID> deleted = ConcurrentHashMap.newKeySet();

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
        var replaced = new AtomicReference<Entry>();
        var accepted = known.compute(entry.id(), (id, previous) -> {
            replaced.set(previous);
            return deleted.contains(id) ? null : entry;
        });
        if (accepted == null) {
            log.info("ignoring an announcement of {} ({}): the farm has deleted it, so this one"
                    + " arrived behind its own deletion", entry.name(), entry.id());
        } else if (replaced.get() == null) {
            log.info("the farm has {} at {}:{}", entry.name(), event.getHost(), event.getPort());
        }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    public void onDeleted(MDeletedEvent event) {
        deleted.add(event.getMachineId());
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

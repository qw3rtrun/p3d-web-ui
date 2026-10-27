package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reactor is a resource, and this is the thing that owns it.
 *
 * <p>The map used to only ever grow. Deleting a printer through the management API left its reactor
 * running - a TCP connection to a machine the farm no longer knew about, a send window, and a
 * replay sink holding every SSE subscriber that had ever attached. Nothing noticed, because nothing
 * ever counted them.
 *
 * <p><strong>There is no management module anywhere in this test.</strong> That is the point of the
 * replica rather than a convenience: this module is told which machines exist, so a test tells it,
 * and nothing here needs a database, a repository or a service to stand a printer up. It used to
 * need a fake {@code MachineManagementService} - or, before that, a fake command gateway - purely
 * to answer one question about where to dial.
 *
 * <p>No connection is opened by any of this: {@code openReactor} no longer dials anything, and
 * {@link org.qw3rtrun.p3d.terminal.TerminalManager}'s constructor only builds a {@code TcpClient}.
 */
class PrinterReactorManagerTest {

    private final UUID id = UUID.randomUUID();
    private final PrinterDirectory directory = new PrinterDirectory();
    private final PrinterReactorManager manager =
            new PrinterReactorManager(directory, PrinterState::new, event -> {
            });

    private void theFarmHas(UUID machine) {
        directory.onRegistered(new MRegisteredEvent(machine, "a printer", "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now()));
    }

    @Test
    void aSecondCallerGetsTheRunningReactorRatherThanANewOne() {
        theFarmHas(id);

        assertSame(manager.openReactor(id), manager.openReactor(id));
    }

    @Test
    void releasingLetsGoSoTheNextCallerStartsAFreshOne() {
        theFarmHas(id);
        var first = manager.openReactor(id);

        manager.release(id);

        assertNotSame(first, manager.openReactor(id));
    }

    @Test
    void releasingAPrinterNobodyEverOpenedIsANoOp() {
        manager.release(id);
        manager.release(id);
    }

    @Test
    void releasingTwiceIsIdempotent() {
        theFarmHas(id);
        manager.openReactor(id);

        manager.release(id);
        manager.release(id);
    }

    /**
     * A machine this module has not been told about cannot be opened.
     *
     * <p>Distinct from "the farm has no such machine": it can also mean the registration has not
     * arrived here yet. Either way the caller gets an answer rather than a reactor pointed at
     * nowhere.
     */
    @Test
    void anUnknownMachineCannotBeOpened() {
        assertThrows(IllegalArgumentException.class, () -> manager.openReactor(id));
    }

    /**
     * Deleting the machine releases its runtime, through an event rather than a call.
     *
     * <p>{@code printer-management} must not reach into this module, and the two facts are separate
     * anyway: the row is gone whether or not anything was holding a socket to it.
     */
    @Test
    void aDeletedMachineReleasesItsRuntime() {
        theFarmHas(id);
        var first = manager.openReactor(id);

        var deleted = new MDeletedEvent(id, LocalDateTime.now());
        manager.onDeleted(deleted);
        directory.onDeleted(deleted);

        assertEquals(0, directory.size());
        assertThrows(IllegalArgumentException.class, () -> manager.openReactor(id));
        assertNotSame(first, null);
    }

    /**
     * The replica takes the same machine twice without complaint.
     *
     * <p>It has to: {@code printer-management} re-announces every row at startup, because a replica
     * built from events is empty when the process begins. {@code MRegisteredEvent} is therefore a
     * statement of current fact, and arriving twice is the normal case rather than a fault.
     */
    @Test
    void beingToldAboutTheSameMachineTwiceIsFine() {
        theFarmHas(id);
        theFarmHas(id);

        assertEquals(1, directory.size());
    }

    /**
     * An edit reaches a machine that is already running (plan item B4).
     *
     * <p>An aggregate is seeded with the spec when it is built, which covers a restart and nothing
     * else. Without this, declaring a second extruder on a printer that is connected right now
     * would reach the database and the replica but not the object that refuses the command - so
     * the edit would appear to do nothing until the farm was restarted, which is the same shape of
     * defect as the write-only {@code connected} column plan item B3 removed.
     */
    @Test
    void anEditReachesAMachineThatIsAlreadyRunning() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var owner = new PrinterReactorManager(directory,
                machine -> aggregates.computeIfAbsent(machine, PrinterState::new), event -> {
        });
        theFarmHas(id);
        owner.openReactor(id);
        assertEquals(1, aggregates.get(id).extruderCount(), "nothing declared, nothing reported");

        owner.onRegistered(announcing(new MachineSpec(null, 2, List.of(), Set.of(), Set.of())));

        assertEquals(2, aggregates.get(id).extruderCount());
    }

    /**
     * And it does not build one for a machine nobody opened.
     *
     * <p>{@code MRegisteredEvent} is re-published for every machine at startup and again on every
     * firmware report. Starting a reactor for each would open a socket, a send window and a sink
     * for every row in the farm, which is precisely the leak plan item A3 closed.
     */
    @Test
    void anAnnouncementDoesNotStartARuntimeForAMachineNobodyOpened() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var owner = new PrinterReactorManager(directory,
                machine -> aggregates.computeIfAbsent(machine, PrinterState::new), event -> {
        });
        theFarmHas(id);

        owner.onRegistered(announcing(new MachineSpec(null, 2, List.of(), Set.of(), Set.of())));

        assertTrue(aggregates.isEmpty(), "an announcement is not a reason to stand a printer up");
    }

    private MRegisteredEvent announcing(MachineSpec spec) {
        return new MRegisteredEvent(id, "a printer", "localhost", 8099,
                MachineProfile.unknown(), spec, MachineConnectionState.OFFLINE, LocalDateTime.now());
    }

    /**
     * A released reactor tells its subscribers it is over.
     *
     * <p>An SSE subscriber is a browser holding a connection. Abandoning the sink leaves it waiting
     * on a machine that no longer exists, and {@code Sinks.Many} keeps every one of them referenced
     * until it terminates - so this is both a correctness and a leak question.
     */
    @Test
    void aReleasedReactorCompletesItsUpdateStream() {
        theFarmHas(id);
        var updates = manager.openReactor(id).updates();

        StepVerifier.create(updates)
                .expectNextCount(1)
                .then(() -> manager.release(id))
                .verifyComplete();
    }
}

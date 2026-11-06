package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replica's own rules, with no reactor, no manager and no database anywhere near them - a class
 * of its own because what {@link PrinterDirectory} decides is which announcements it believes,
 * which is a question about a map and two events. {@code PrinterReactorManagerTest} keeps the case
 * where that decision is visible as damage (a socket dialled at a deleted machine), because that is
 * the manager's half.
 */
class PrinterDirectoryTest {

    private final UUID id = UUID.randomUUID();
    private final PrinterDirectory directory = new PrinterDirectory();

    private void theFarmHas(UUID machine, String name, int port, MachineConnectionState state) {
        directory.onRegistered(new MRegisteredEvent(machine, name, "localhost", port,
                MachineProfile.unknown(), MachineSpec.undeclared(), state, LocalDateTime.now()));
    }

    private void theFarmDeleted(UUID machine) {
        directory.onDeleted(new MDeletedEvent(machine, LocalDateTime.now()));
    }

    /**
     * Issue #93's second half. {@code MRegisteredEvent} is re-published about seven times per
     * connect, each time from a {@code @Transactional} roster listener queued on that machine's own
     * {@code p3d-listener-<uuid>} thread - so one can be delivered <em>after</em> the
     * {@code MDeletedEvent} that follows it in real time. Measured over five milliseconds: the
     * directory forgot the machine and the manager released its runtime, and five milliseconds
     * later a straggler from the same backlog put the entry back, the manager found no runtime for
     * a machine the roster still called {@code IDLE}, and dialled a brand-new one - which nothing
     * would ever release, and which went on redialling the closed port for another 45 seconds into
     * the test classes that followed.
     *
     * <p>Tombstone rather than timestamp: both events carry a {@code LocalDateTime} taken on
     * different threads at millisecond resolution, and in that capture the two are 5 ms apart.
     */
    @Test
    void aStaleAnnouncementDoesNotPutBackAMachineTheFarmHasDeleted() {
        theFarmHas(id, "deleted", 65506, MachineConnectionState.IDLE);
        theFarmDeleted(id);

        theFarmHas(id, "deleted", 65506, MachineConnectionState.IDLE);

        assertTrue(directory.find(id).isEmpty(),
                "a machine the farm has deleted is gone, however far behind the announcement that"
                        + " says otherwise");
        assertEquals(0, directory.size());
    }

    /** However many stragglers the backlog holds. */
    @Test
    void aDeletedMachineStaysGoneAcrossRepeatedAnnouncements() {
        theFarmHas(id, "deleted", 65506, MachineConnectionState.IDLE);
        theFarmDeleted(id);

        for (var i = 0; i < 7; i++) {
            theFarmHas(id, "deleted", 65506, MachineConnectionState.IDLE);
        }

        assertTrue(directory.find(id).isEmpty());
    }

    /**
     * The control, and the reason a tombstone is not over-strict: an edit re-publishes
     * {@code MRegisteredEvent} for a machine that was never deleted, and the replica is where a
     * changed host and port have to land before anything can dial them.
     */
    @Test
    void aReAnnouncedMachineThatWasNeverDeletedIsStillFoundAndUpdated() {
        theFarmHas(id, "benchy-maker", 8099, MachineConnectionState.OFFLINE);

        theFarmHas(id, "benchy-maker-mk2", 9000, MachineConnectionState.IDLE);

        var entry = directory.find(id).orElseThrow();
        assertEquals("benchy-maker-mk2", entry.name());
        assertEquals(9000, entry.connection().port());
        assertEquals(1, directory.size());
    }

    /** A tombstone is per machine: deleting one must not deafen the replica to the rest of the farm. */
    @Test
    void deletingOneMachineLeavesTheOthersAlone() {
        var other = UUID.randomUUID();
        theFarmHas(id, "gone", 8099, MachineConnectionState.IDLE);
        theFarmHas(other, "still here", 8100, MachineConnectionState.IDLE);

        theFarmDeleted(id);
        theFarmHas(other, "still here", 8100, MachineConnectionState.BUSY);

        assertTrue(directory.find(id).isEmpty());
        assertEquals(MachineConnectionState.BUSY, directory.find(other).orElseThrow().state());
        assertEquals(1, directory.size());
    }
}

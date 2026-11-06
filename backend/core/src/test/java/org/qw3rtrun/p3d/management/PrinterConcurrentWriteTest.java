package org.qw3rtrun.p3d.management;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.management.service.PrinterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two writers, one printer row - the audit half of issue #100, and the one entity of the three it
 * found a real concurrent writer on.
 *
 * <p>Three transactions write this row and none of them writes all of it: an operator's edit owns
 * the name, the connection details and the declared spec; {@code on(MFirmwareReported)} and
 * {@code on(MCapabilityReported)} own the profile; {@code on(MConnectionChanged)} owns one column.
 * The last three are serialised per machine by that machine's own listener thread, but an edit is
 * not - it arrives on {@code boundedElastic} from an HTTP request, and the moment it is most likely
 * to arrive is while somebody is watching a printer connect, which is exactly when the other three
 * are busiest (a connect produces three connection changes and about seven republications).
 *
 * <p>Both tests hold one transaction open where a real writer holds one - having read the row,
 * before saving it - and write the other half from another thread in between. The interleaving is
 * forced rather than hoped for, for the same reason {@code JobServiceTest} forces its own: the
 * window is a few milliseconds and a test that writes from two threads and hopes passes against
 * the bug on nearly every run.
 *
 * <p>The second one is the one with teeth. {@code connection_state} is read exactly once, at
 * startup, to decide which machines to dial again, so an edit that writes a stale copy of it back
 * does not look wrong anywhere until a restart quietly fails to reconnect a machine that was
 * printing.
 */
@SpringBootTest
class PrinterConcurrentWriteTest {

    @Autowired
    private PrinterRepository repo;

    @Autowired
    private MachineManagementService roster;

    @Autowired
    private TransactionTemplate transactions;

    private final List<UUID> registered = new ArrayList<>();

    @AfterEach
    void removeWhatWasRegistered() {
        registered.forEach(roster::delete);
        registered.clear();
    }

    private UUID machine(String name) {
        var added = roster.add(name, new ConnectionDetails("localhost", 8099),
                MachineSpec.undeclared());
        registered.add(added.uuid());
        return added.uuid();
    }

    private static MachineSpec declared() {
        return new MachineSpec(new BedVolume(220, 220, 250), 2,
                List.of(0.4, 0.6), Set.of("PLA"), Set.of("shelf-a"));
    }

    private CompletableFuture<Void> inAnOpenTransaction(Runnable body) {
        return CompletableFuture.runAsync(() ->
                transactions.executeWithoutResult(status -> body.run()));
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the other thread never got there");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** A machine coming online says so three times while somebody is looking at its edit page. */
    @Test
    void aConnectionChangeWrittenFromAStaleReadDoesNotRevertAnEditMadeSince() {
        var id = machine("before the edit");
        var read = new CountDownLatch(1);
        var edited = new CountDownLatch(1);

        var cameOnline = inAnOpenTransaction(() -> {
            var entity = repo.findById(id).orElseThrow();
            assertEquals("before the edit", entity.getName(), "core read the row as it was");
            read.countDown();
            waitFor(edited);
            entity.connectionChanged(MachineConnectionState.IDLE);
            repo.save(entity);
        });

        waitFor(read);
        roster.update(id, "after the edit", new ConnectionDetails("10.0.0.7", 9000), declared());
        edited.countDown();
        cameOnline.join();

        var after = repo.findById(id).orElseThrow();
        assertEquals("after the edit", after.getName(),
                "writing down where a connection got to must not put back the name, the address and"
                        + " the spec that the same transaction happened to read on its way in");
        assertEquals("10.0.0.7", after.getHost());
        assertEquals(9000, after.getPort());
        assertEquals(2, after.spec().extruderCount());
        assertEquals(MachineConnectionState.IDLE, after.connectionState(),
                "and the column the other writer actually came to write still lands");
    }

    /** The same race the other way round, and the expensive one. */
    @Test
    void anEditWrittenFromAStaleReadDoesNotRevertAConnectionChangeMadeSince() {
        var id = machine("editable");
        var read = new CountDownLatch(1);
        var connected = new CountDownLatch(1);

        var edit = inAnOpenTransaction(() -> {
            var entity = repo.findById(id).orElseThrow();
            assertEquals(MachineConnectionState.OFFLINE, entity.connectionState(),
                    "the edit read the row while the machine was still offline");
            read.countDown();
            waitFor(connected);
            entity.reachableAs("renamed", "10.0.0.8", 9100);
            entity.declare(declared());
            repo.save(entity);
        });

        waitFor(read);
        transactions.executeWithoutResult(status -> {
            var entity = repo.findById(id).orElseThrow();
            entity.connectionChanged(MachineConnectionState.IDLE);
            repo.save(entity);
        });
        connected.countDown();
        edit.join();

        var after = repo.findById(id).orElseThrow();
        assertEquals("renamed", after.getName(), "the edit lands");
        assertEquals(MachineConnectionState.IDLE, after.connectionState(),
                "and it must not carry the connection state it read on the way in: that column is"
                        + " read once, at startup, to decide which machines to dial again, so"
                        + " reverting it costs a reconnection and nothing says so until a restart");
    }
}

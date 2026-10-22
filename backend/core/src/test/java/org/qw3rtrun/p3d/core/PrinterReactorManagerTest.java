package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A reactor is a resource, and this is the thing that owns it.
 *
 * <p>The map used to only ever grow. Deleting a printer through the management API left its reactor
 * running - a TCP connection to a machine the farm no longer knew about, a send window, and a
 * replay sink holding every SSE subscriber that had ever attached. Nothing noticed, because nothing
 * ever counted them.
 *
 * <p>No connection is opened by any of this: the descriptors say {@code connected = false}, and
 * {@link org.qw3rtrun.p3d.terminal.TerminalManager}'s constructor only builds a {@code TcpClient}.
 */
class PrinterReactorManagerTest {

    private final UUID id = UUID.randomUUID();
    private final AtomicInteger lookups = new AtomicInteger();

    /**
     * Everything but {@code fetch}, which is all this test needs a management service for.
     *
     * <p>The reactor manager reads a printer's row and nothing else - it used to do it through a
     * command gateway that held one registration and ignored the response type it was handed, and
     * now it calls the interface that registration pointed at.
     */
    private abstract static class StubMachines implements MachineManagementService {
        @Override
        public Descriptor add(String name, ConnectionDetails connection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.stream.Stream<Descriptor> fetchAll() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public void delete(UUID uuid) {
        }

        @Override
        public void disconnect(UUID uuid) {
        }

        @Override
        public void connect(UUID uuid) {
        }
    }

    /** Counts how often the printer's row was read, which is how a fresh reactor gives itself away. */
    private PrinterReactorManager manager() {
        var machines = new StubMachines() {
            @Override
            public Descriptor fetch(UUID uuid) {
                lookups.incrementAndGet();
                return new Descriptor(uuid, "a printer", new ConnectionDetails("localhost", 1), false);
            }
        };
        return new PrinterReactorManager(machines, PrinterState::new);
    }

    @Test
    void aSecondCallerGetsTheRunningReactorRatherThanANewOne() {
        var manager = manager();

        assertSame(manager.openReactor(id), manager.openReactor(id));
        assertEquals(1, lookups.get());
    }

    @Test
    void releasingLetsGoSoTheNextCallerStartsAFreshOne() {
        var manager = manager();
        var first = manager.openReactor(id);

        manager.release(id);

        assertNotSame(first, manager.openReactor(id));
        assertEquals(2, lookups.get(), "the row was not read again, so the reactor was not released");
    }

    @Test
    void releasingAPrinterNobodyEverOpenedIsANoOp() {
        var manager = manager();

        manager.release(id);
        manager.release(id);

        assertEquals(0, lookups.get());
    }

    @Test
    void releasingTwiceIsIdempotent() {
        var manager = manager();
        manager.openReactor(id);

        manager.release(id);
        manager.release(id);
    }

    /**
     * Deleting the machine releases its runtime, through an event rather than a call.
     *
     * <p>{@code printer-management} must not reach into this module, and the two facts are separate
     * anyway: the row is gone whether or not anything was holding a socket to it.
     */
    @Test
    void aDeletedMachineReleasesItsRuntime() {
        var manager = manager();
        var first = manager.openReactor(id);

        manager.onDeleted(new MDeletedEvent(id, LocalDateTime.now()));

        assertNotSame(first, manager.openReactor(id));
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
        var manager = manager();
        var updates = manager.openReactor(id).updates();

        StepVerifier.create(updates)
                .expectNextCount(1)
                .then(() -> manager.release(id))
                .verifyComplete();
    }
}

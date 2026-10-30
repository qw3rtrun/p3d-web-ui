package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This module's side of {@link org.qw3rtrun.p3d.api.control.PrinterControl}, and the thread it
 * answers on.
 *
 * <p><strong>There is no translation left here to assert.</strong> The {@code toMarlin} switch this
 * class used to hold - the outbound half of a hand-written mirror of `:gcode`, with
 * {@code ReplyEvents} on the inbound side - is gone with the mirror. A command arrives as the
 * {@code GRq} it is, and the one place a browser's words become Marlin's is the controller that
 * received them; those assertions are {@code PrinterCommandTranslationTest} in
 * {@code :frontend:web-ui}.
 */
class PrinterControlServiceTest {

    /**
     * Opening a runtime happens off the caller's thread.
     *
     * <p>This assertion used to live in the frontend module, because the controllers did the
     * scheduling. It moved here with the lookup: which thread a resource is opened on is this
     * module's business, and the web layer no longer knows what a reactor is.
     *
     * <p>{@code openReactor} no longer reads a database - {@link PrinterDirectory} answers that -
     * but it is {@code synchronized} and builds a resource, and an event-loop thread should not be
     * queueing on a monitor when several requests arrive for one printer.
     */
    @Test
    void openingARuntimeRunsOffTheCallersThread() {
        var calledOn = new AtomicReference<String>();
        var directory = new PrinterDirectory() {
            @Override
            public Optional<Entry> find(UUID id) {
                calledOn.set(Thread.currentThread().getName());
                return super.find(id);
            }
        };
        var id = UUID.randomUUID();
        directory.onRegistered(new MRegisteredEvent(id, "a printer", "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now()));
        var manager = new PrinterReactorManager(directory, PrinterState::new, event -> {
        });
        // The uploader is reached only by the two card methods, and this test calls neither.
        var control = new PrinterControlService(manager, new SdUploader(manager, null));

        control.state(id).block();

        var thread = calledOn.get();
        assertNotNull(thread, "the directory was never consulted, so the test proved nothing");
        assertTrue(thread.startsWith("boundedElastic"),
                "the runtime was opened on '" + thread + "', which is the caller's thread");
    }
}

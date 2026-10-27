package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.MConnectionChangedEvent;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.core.PrinterDirectory;
import org.qw3rtrun.p3d.management.service.MachineManagementImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connection state crossing both boundaries, in the assembled application.
 *
 * <p>The twin of {@code PrinterProfileFlowTest}, and the same shape for the same reason: only
 * {@code core} can know whether a machine is connected, because knowing it means holding the
 * socket, and {@code printer-management} is where facts kept about a machine belong. So the fact
 * goes out as {@code MConnectionChangedEvent} and comes back inside the startup announcement, and
 * nothing below this level sees both ends.
 *
 * <p><strong>This is what stopped the column being write-only.</strong> Before plan item B3,
 * {@code MachineManagementService.connect} and {@code disconnect} were called by nothing but a test
 * - the connect endpoint has always gone to the runtime instead - so the persisted flag said
 * {@code false} from registration onwards whatever the machine was doing. Writing it from the
 * runtime is half the fix; reading it back at startup, which is what the directory below does, is
 * the other half.
 *
 * <p>The transition is published here rather than produced by connecting to something: a real one
 * needs a printer on the other end, and what is under test is what happens to the fact afterwards.
 * {@code PrinterReactorRetryTest} covers the reactor producing it.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConnectionStateFlowTest {

    @Autowired
    private MachineManagementService machines;

    @Autowired
    private MachineManagementImpl roster;

    @Autowired
    private PrinterDirectory directory;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Test
    void whatTheRuntimeReportsIsKeptAndHandedBackAtStartup() {
        var printer = machines.add("was printing", new ConnectionDetails("localhost", 8099));
        assertEquals(MachineConnectionState.OFFLINE, printer.state(), "nothing has dialled it yet");

        publisher.publishEvent(new MConnectionChangedEvent(
                printer.uuid(), MachineConnectionState.BUSY, null, LocalDateTime.now()));

        // printer-management kept it: the roster now describes the machine differently.
        assertEquals(MachineConnectionState.BUSY, machines.fetch(printer.uuid()).state());

        // and a restart would learn it: the replica is rebuilt from the startup announcement, and
        // that is where PrinterReactorManager reads which machines to dial again.
        roster.announceTheFarm();
        var entry = directory.find(printer.uuid()).orElseThrow();
        assertEquals(MachineConnectionState.BUSY, entry.state());
        assertTrue(entry.state().isLinked());
    }

    /**
     * A machine that was offline when the process went down is not dialled on the way back up.
     *
     * <p>The other half of the same decision, and the one that keeps a restart quiet: the farm
     * redials what was connected, and nothing else.
     */
    @Test
    void aMachineThatWasOfflineStaysOffline() {
        var printer = machines.add("idle on disk", new ConnectionDetails("localhost", 8099));

        roster.announceTheFarm();

        assertEquals(MachineConnectionState.OFFLINE,
                directory.find(printer.uuid()).orElseThrow().state());
    }
}

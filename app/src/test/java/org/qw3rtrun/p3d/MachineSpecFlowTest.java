package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MFirmwareReportedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.core.PrinterDirectory;
import org.qw3rtrun.p3d.management.service.MachineManagementImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declared half of plan item B4, across the assembled application.
 *
 * <p>The seam is the same one the profile crosses and it runs the other way. A profile is a fact
 * {@code core} learned and the roster keeps; a spec is a fact a person declared to the roster and
 * {@code core} has to be told, because a declared extruder count is what decides whether a command
 * for tool 1 is refused. Nothing below this level sees both ends of that.
 */
@SpringBootTest
@ActiveProfiles("test")
class MachineSpecFlowTest {

    @Autowired
    private MachineManagementService machines;

    @Autowired
    private MachineManagementImpl roster;

    @Autowired
    private PrinterDirectory directory;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private Announcements announcements;

    @TestConfiguration
    static class Config {
        @Bean
        Announcements specAnnouncements() {
            return new Announcements();
        }
    }

    static class Announcements {
        final List<MRegisteredEvent> heard = new ArrayList<>();

        @EventListener
        void on(MRegisteredEvent event) {
            heard.add(event);
        }
    }

    private static MachineSpec declared() {
        return new MachineSpec(new BedVolume(220, 220, 250), 2,
                List.of(0.4, 0.6), Set.of("PETG"), Set.of("prusa-shelf"));
    }

    @Test
    void whatSomebodyDeclaresIsKeptAndAnnouncedToCore() {
        var printer = machines.add("needs describing", new ConnectionDetails("localhost", 8099));
        assertFalse(printer.spec().isDeclared(), "nobody has said anything about it yet");

        machines.update(printer.uuid(), "needs describing",
                new ConnectionDetails("localhost", 8099), declared());

        var described = machines.fetch(printer.uuid());
        assertEquals(new BedVolume(220, 220, 250), described.spec().bedVolume());
        assertEquals(Set.of("prusa-shelf"), described.spec().tags());

        // and core's replica was told, without core ever asking.
        var entry = directory.find(printer.uuid()).orElseThrow();
        assertEquals(2, entry.spec().extruderCount());
        assertEquals(List.of(0.4, 0.6), entry.spec().nozzleDiameters());
    }

    /**
     * The override that makes the declared half worth storing.
     *
     * <p>A dual-extruder machine flashed with a single-extruder configuration reports one tool
     * whatever is bolted to it. Without this, every command for tool 1 is refused and the operator
     * has no way to say otherwise; with it, what a person typed wins and what the firmware said is
     * still there to be looked at.
     */
    @Test
    void aDeclaredExtruderCountOverridesTheFirmwareEndToEnd() {
        var printer = machines.add("under-reports", new ConnectionDetails("localhost", 8099));
        publisher.publishEvent(new MFirmwareReportedEvent(printer.uuid(),
                Map.of("EXTRUDER_COUNT", "1"), LocalDateTime.now()));

        assertEquals(1, machines.fetch(printer.uuid()).extruderCount(), "before anybody says otherwise");

        machines.update(printer.uuid(), "under-reports",
                new ConnectionDetails("localhost", 8099),
                new MachineSpec(null, 2, List.of(), Set.of(), Set.of()));

        var described = machines.fetch(printer.uuid());
        assertEquals(2, described.extruderCount(), "the operator's count wins");
        assertEquals(1, described.profile().extruderCount(), "and the firmware's is still readable");
    }

    /** An edit is not a firmware report, and must not be able to look like one. */
    @Test
    void anEditCannotTouchWhatTheMachineReported() {
        var printer = machines.add("reports things", new ConnectionDetails("localhost", 8099));
        publisher.publishEvent(new MFirmwareReportedEvent(printer.uuid(),
                Map.of("FIRMWARE_NAME", "Marlin 2.1.2"), LocalDateTime.now()));

        machines.update(printer.uuid(), "renamed", new ConnectionDetails("elsewhere", 9000), declared());

        var described = machines.fetch(printer.uuid());
        assertEquals("Marlin 2.1.2", described.profile().firmwareName());
        assertTrue(described.profile().isKnown());
        assertEquals("renamed", described.name());
        assertEquals("elsewhere", described.connection().host());
    }

    /** And a firmware report is not an edit: reconnecting must not undo an override. */
    @Test
    void aFirmwareReportDoesNotUndoAnOverride() {
        var printer = machines.add("reconnects", new ConnectionDetails("localhost", 8099));
        machines.update(printer.uuid(), "reconnects",
                new ConnectionDetails("localhost", 8099), declared());

        publisher.publishEvent(new MFirmwareReportedEvent(printer.uuid(),
                Map.of("EXTRUDER_COUNT", "1"), LocalDateTime.now()));

        assertEquals(2, machines.fetch(printer.uuid()).extruderCount());
        assertEquals(2, directory.find(printer.uuid()).orElseThrow().spec().extruderCount(),
                "and the replica still has it too");
    }

    /**
     * The startup announcement carries the spec, which is what a restart depends on.
     *
     * <p>Same argument as the profile's: a replica is empty when the process begins, and an
     * announcement that carried only what the machine had reported would lose every override at
     * every restart.
     */
    @Test
    void theStartupAnnouncementCarriesWhatWasDeclared() {
        var printer = machines.add("remembered", new ConnectionDetails("localhost", 8099));
        machines.update(printer.uuid(), "remembered",
                new ConnectionDetails("localhost", 8099), declared());
        announcements.heard.clear();

        roster.announceTheFarm();

        var announced = announcements.heard.stream()
                .filter(event -> event.getMachineId().equals(printer.uuid()))
                .findFirst().orElseThrow(() -> new AssertionError("the machine was not announced"));
        assertEquals(2, announced.getSpec().extruderCount());
        assertEquals(Set.of("prusa-shelf"), announced.getSpec().tags());
    }

    /** Clearing the form clears the declaration, and leaves the firmware answering again. */
    @Test
    void clearingTheDeclarationFallsBackToTheFirmware() {
        var printer = machines.add("cleared", new ConnectionDetails("localhost", 8099));
        publisher.publishEvent(new MFirmwareReportedEvent(printer.uuid(),
                Map.of("EXTRUDER_COUNT", "1"), LocalDateTime.now()));
        machines.update(printer.uuid(), "cleared",
                new ConnectionDetails("localhost", 8099), declared());
        assertEquals(2, machines.fetch(printer.uuid()).extruderCount());

        machines.update(printer.uuid(), "cleared",
                new ConnectionDetails("localhost", 8099), MachineSpec.undeclared());

        var described = machines.fetch(printer.uuid());
        assertNull(described.spec().extruderCount());
        assertEquals(1, described.extruderCount(), "back to what the machine says");
    }
}

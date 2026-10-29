package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.MCapabilityReportedEvent;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole loop, in the assembled application: {@code core} learns, {@code management} keeps,
 * and the roster announces it back.
 *
 * <p>Worth an integration test rather than three unit tests, because every interesting part of it
 * is a <em>seam</em>. {@code core} is the only module that can ask a printer what it is, and
 * {@code printer-management} is where static facts about a machine belong - so the fact has to
 * cross the boundary twice, as an event each way, and nothing below this level sees both ends.
 *
 * <p>The firmware report is published here rather than being learned from a machine: a real
 * {@code M115} needs a printer on the other end, and what is under test is what happens to the fact
 * afterwards. {@code PrinterReactor} publishing it is covered where that code lives.
 */
@SpringBootTest
@ActiveProfiles("test")
class PrinterProfileFlowTest {

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

    /** Catches what the roster announces, which is what a replica is rebuilt from. */
    @TestConfiguration
    static class Config {
        @Bean
        Announcements announcements() {
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

    @Test
    void whatAMachineReportsIsKeptAndAnnouncedBack() {
        var printer = machines.add("learns things", new ConnectionDetails("localhost", 8099));
        assertTrue(printer.profile().firmware().isEmpty(), "nothing has asked it yet");

        publisher.publishEvent(new MFirmwareReportedEvent(printer.uuid(),
                Map.of("FIRMWARE_NAME", "Marlin 2.1.2", "EXTRUDER_COUNT", "2"), LocalDateTime.now()));
        publisher.publishEvent(new MCapabilityReportedEvent(printer.uuid(),
                "AUTOREPORT_TEMP", true, LocalDateTime.now()));

        // printer-management kept it: the roster now describes the machine differently.
        var described = machines.fetch(printer.uuid());
        assertEquals("Marlin 2.1.2", described.profile().firmwareName());
        assertEquals(2, described.profile().extruderCount());
        assertEquals(Map.of("AUTOREPORT_TEMP", true), described.profile().capabilities());
        assertTrue(described.profile().isKnown());

        // and announced it back, so core's replica knows without ever asking again.
        var entry = directory.find(printer.uuid()).orElseThrow();
        assertEquals(2, entry.profile().extruderCount());
    }

    /**
     * The startup announcement carries the profile, which is what a restart depends on.
     *
     * <p>A replica is empty when the process begins, so {@code announceTheFarm} is what refills it.
     * If that announcement carried only a host and a port, {@code core} would know where to dial
     * every machine and what none of them were - and the extruder count it checks a tool index
     * against would be back to a default until something connected.
     */
    @Test
    void theStartupAnnouncementCarriesWhatWasLearned() {
        var printer = machines.add("remembered", new ConnectionDetails("localhost", 8099));
        publisher.publishEvent(new MFirmwareReportedEvent(printer.uuid(),
                Map.of("EXTRUDER_COUNT", "3"), LocalDateTime.now()));
        announcements.heard.clear();

        roster.announceTheFarm();

        var announced = announcements.heard.stream()
                .filter(event -> event.getMachineId().equals(printer.uuid()))
                .findFirst().orElseThrow(() -> new AssertionError("the machine was not announced"));
        assertEquals(3, announced.getProfile().extruderCount());
        assertTrue(announced.getProfile().isKnown());
    }
}

package org.qw3rtrun.p3d;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MConnectionChangedEvent;
import org.qw3rtrun.p3d.api.msg.MFirmwareReportedEvent;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.core.PrinterDirectory;
import org.qw3rtrun.p3d.management.service.MachineManagementImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a restart remembers - <strong>the one seam in this module with no HTTP surface</strong>,
 * the deliberate exception #43 names in {@code doc/specs/INTEGRATION_TESTS_spec.md}. The fact under
 * test: {@code announceTheFarm()}, run once on {@code ApplicationReadyEvent}, carries enough for
 * {@code core} to rebuild its replica from nothing - a replica that is empty when the process
 * begins, so an announcement carrying only a host and a port would leave the farm knowing where to
 * dial every machine and what none of them were.
 *
 * <p>No request triggers it and no response reveals it, and a test here cannot restart a context,
 * so it is driven by calling the announcement and reading the replica - the shape the three
 * {@code *FlowTest}s this consolidates had. Everything else they covered is now in
 * {@link PrinterManagementIntegrationTest} and {@link ConnectivityIntegrationTest}, driven through
 * endpoints against a mocked printer, and better for it: the firmware report there is a real
 * {@code M115}, not an event the test publishes.
 *
 * <p>{@code PrinterDirectory} is built from {@code MRegisteredEvent} and nothing else, so a fact
 * being in the replica is the same statement as the announcement having carried it - without a
 * listener bean of its own, which would fork the context and cost the suite a second boot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StartupAnnouncementFlowTest {

    @Autowired
    private MachineManagementService machines;

    @Autowired
    private MachineManagementImpl roster;

    @Autowired
    private PrinterDirectory directory;

    @Autowired
    private ApplicationEventPublisher publisher;

    private final List<UUID> registered = new ArrayList<>();

    /**
     * {@code announceTheFarm} dials whatever was connected, so a machine left behind linked would
     * be redialled by every later call - including the ones other test classes make through their
     * own fixtures.
     */
    @AfterEach
    void removeTheMachines() {
        registered.forEach(id -> machines.delete(id));
        registered.clear();
    }

    /** Pointed at a port nothing answers: these tests are about the announcement, not the socket. */
    private UUID add(String name) {
        var printer = machines.add(name, new ConnectionDetails("localhost", MockPrinter.deadPort()));
        registered.add(printer.uuid());
        return printer.uuid();
    }

    /**
     * Without it, the extruder count the aggregate checks a tool index against would default until
     * something connected, refusing every command for a second tool in the window between starting
     * and dialling.
     */
    @Test
    void theAnnouncementCarriesWhatTheMachineReported() {
        var id = add("learns things");
        publisher.publishEvent(new MFirmwareReportedEvent(id,
                Map.of("FIRMWARE_NAME", "Marlin 2.1.2", "EXTRUDER_COUNT", "3"), LocalDateTime.now()));

        roster.announceTheFarm();

        var entry = directory.find(id).orElseThrow(() -> new AssertionError("not announced"));
        assertEquals(3, entry.profile().extruderCount());
        assertTrue(entry.profile().isKnown());
    }

    /**
     * Without this half, every override would be discarded at every restart - and an override
     * exists precisely for a machine whose firmware is wrong, so it would come back wrong.
     */
    @Test
    void theAnnouncementCarriesWhatSomebodyDeclared() {
        var id = add("described");
        // orElseThrow because this one was just added: fetch answers with an Optional since #65,
        // where "the farm has no such machine" became a value instead of an exception.
        var port = machines.fetch(id).orElseThrow().connection().port();
        machines.update(id, "described", new ConnectionDetails("localhost", port),
                new MachineSpec(null, 2, List.of(0.4), Set.of("PETG"), Set.of("prusa-shelf")));

        roster.announceTheFarm();

        var entry = directory.find(id).orElseThrow(() -> new AssertionError("not announced"));
        assertEquals(2, entry.spec().extruderCount());
        assertEquals(Set.of("prusa-shelf"), entry.spec().tags());
    }

    /**
     * {@code printer_entity.connection_state} is written from {@code core} (knowing a machine is
     * connected means holding the socket) and read exactly here, to decide what to dial again - both
     * halves required, unlike the write-only {@code connected} boolean this replaced.
     */
    @Test
    void theAnnouncementCarriesTheConnectionStateSoAMachineIsDialledAgain() {
        var id = add("was printing");
        publisher.publishEvent(new MConnectionChangedEvent(
                id, MachineConnectionState.BUSY, null, LocalDateTime.now()));

        roster.announceTheFarm();

        var entry = directory.find(id).orElseThrow(() -> new AssertionError("not announced"));
        assertEquals(MachineConnectionState.BUSY, entry.state());
        assertTrue(entry.state().isLinked(), "which is what makes the farm redial it");
    }

    /** A machine that was offline when the process went down is left alone, which keeps a start quiet. */
    @Test
    void aMachineThatWasOfflineIsNotDialledAgain() {
        var id = add("idle on disk");

        roster.announceTheFarm();

        assertEquals(MachineConnectionState.OFFLINE,
                directory.find(id).orElseThrow().state());
    }
}

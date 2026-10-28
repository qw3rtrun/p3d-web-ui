package org.qw3rtrun.p3d.management;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.management.model.PrinterEntity;
import org.qw3rtrun.p3d.management.service.PrinterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a machine turned out to be, written down and read back.
 *
 * <p>Against the <strong>real Liquibase schema</strong> rather than one Hibernate generated from
 * the entity, which is the only version of this test worth having: the two disagreeing is exactly
 * the failure it exists to catch, and it already caught one. The firmware value column is
 * {@code key_value} and not {@code value} because {@code value} is a reserved word - Hibernate
 * emitted it happily and the database rejected the query, with no hint that the entity was the
 * problem.
 *
 * <p><strong>Not {@code @DataJpaTest}</strong>, which the plan item asked for: Spring Boot 4 moved
 * the test slices out of {@code spring-boot-test-autoconfigure} and the annotation is not on this
 * module's classpath at all. {@code @SpringBootTest} with {@code @Transactional} gives what the
 * item actually wanted - the repository exercised against the real schema, rolled back after each
 * test - at the cost of booting the whole module rather than a slice of it.
 */
@SpringBootTest
@Transactional
class PrinterProfilePersistenceTest {

    @Autowired
    private PrinterRepository repo;

    private PrinterEntity registered() {
        return repo.save(new PrinterEntity(UUID.randomUUID(), "a printer", "localhost", 8099));
    }

    /**
     * A machine nothing has ever connected to has a profile, and it says so.
     *
     * <p>Never null, so that a caller - the aggregate seeding itself, a page rendering a row - does
     * not have to check. An unknown machine is a fact rather than a gap.
     */
    @Test
    void aFreshlyRegisteredMachineHasAnUnknownProfile() {
        var profile = registered().profile();

        assertFalse(profile.isKnown());
        assertEquals(Map.of(), profile.firmware());
        assertEquals(Map.of(), profile.capabilities());
    }

    /**
     * An unknown machine still claims one extruder.
     *
     * <p>One and not zero, because the aggregate checks a tool index against this before sending
     * {@code M104}, and zero would refuse every command including the one for tool 0 - on a machine
     * nobody has had the chance to ask yet.
     */
    @Test
    void anUnknownMachineStillClaimsOneExtruder() {
        assertEquals(1, registered().profile().extruderCount());
    }

    @Test
    void firmwareSurvivesARoundTrip() {
        var when = LocalDateTime.now().withNano(0);
        var id = registered().getId();

        repo.findById(id).orElseThrow().learnedFirmware(Map.of(
                "FIRMWARE_NAME", "Marlin 2.1.2",
                "MACHINE_TYPE", "Ender-3",
                "EXTRUDER_COUNT", "2",
                "UUID", "cede2a2f-41a2-4748-9b12-c55c62f367ff"), when);
        repo.flush();

        var profile = repo.findById(id).orElseThrow().profile();
        assertTrue(profile.isKnown());
        assertEquals("Marlin 2.1.2", profile.firmwareName());
        assertEquals("Ender-3", profile.machineType());
        assertEquals(2, profile.extruderCount());
        assertEquals(when, profile.learnedAt());
    }

    /**
     * A key the host has never heard of comes back anyway.
     *
     * <p>The reason the firmware is a table of key-values rather than a column per field: what
     * {@code M115} reports differs between builds, and a schema that named only today's keys would
     * silently drop whatever a later Marlin adds.
     */
    @Test
    void aKeyNobodyModelledSurvivesToo() {
        var id = registered().getId();

        repo.findById(id).orElseThrow()
                .learnedFirmware(Map.of("SOMETHING_NEW", "42"), LocalDateTime.now());
        repo.flush();

        assertEquals("42", repo.findById(id).orElseThrow().profile().firmware().get("SOMETHING_NEW"));
    }

    /**
     * A second firmware report replaces the first rather than merging into it.
     *
     * <p>An {@code M115} is a complete statement about one build, so a key that has stopped being
     * reported has stopped being true - a reflashed printer should not keep claiming the extruder
     * count of the firmware it used to run.
     */
    @Test
    void aFreshFirmwareReportReplacesTheOldOne() {
        var id = registered().getId();
        repo.findById(id).orElseThrow()
                .learnedFirmware(Map.of("EXTRUDER_COUNT", "2", "GONE_IN_THE_NEXT_BUILD", "yes"),
                        LocalDateTime.now());
        repo.flush();

        repo.findById(id).orElseThrow()
                .learnedFirmware(Map.of("EXTRUDER_COUNT", "1"), LocalDateTime.now());
        repo.flush();

        var firmware = repo.findById(id).orElseThrow().profile().firmware();
        assertEquals(Map.of("EXTRUDER_COUNT", "1"), firmware);
    }

    /**
     * Capabilities accumulate, because the firmware sends them one line at a time.
     *
     * <p>Nothing marks the last {@code Cap:} line, so there is no moment at which the set is known
     * to be complete - which is why these are upserted while a firmware report is replaced.
     */
    @Test
    void capabilitiesAccumulateOneLineAtATime() {
        var id = registered().getId();
        var entity = repo.findById(id).orElseThrow();

        entity.learnedCapability("EEPROM", true, LocalDateTime.now());
        entity.learnedCapability("AUTOREPORT_TEMP", true, LocalDateTime.now());
        entity.learnedCapability("SDCARD", false, LocalDateTime.now());
        repo.flush();

        var capabilities = repo.findById(id).orElseThrow().profile().capabilities();
        assertEquals(Map.of("EEPROM", true, "AUTOREPORT_TEMP", true, "SDCARD", false), capabilities);
    }

    @Test
    void aCapabilityThatChangesIsOverwrittenAndNotDuplicated() {
        var id = registered().getId();
        repo.findById(id).orElseThrow().learnedCapability("EEPROM", true, LocalDateTime.now());
        repo.flush();

        repo.findById(id).orElseThrow().learnedCapability("EEPROM", false, LocalDateTime.now());
        repo.flush();

        assertEquals(Map.of("EEPROM", false), repo.findById(id).orElseThrow().profile().capabilities());
    }

    /** Deleting a machine takes its profile with it - the tables cascade. */
    @Test
    void deletingAMachineRemovesWhatWasLearnedAboutIt() {
        var id = registered().getId();
        repo.findById(id).orElseThrow().learnedFirmware(Map.of("EXTRUDER_COUNT", "2"), LocalDateTime.now());
        repo.flush();

        repo.deleteById(id);
        repo.flush();

        assertTrue(repo.findById(id).isEmpty());
    }
}

package org.qw3rtrun.p3d.management;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.management.model.PrinterEntity;
import org.qw3rtrun.p3d.management.service.PrinterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a human declared, written down and read back - plan item B4's changeset, exercised.
 *
 * <p>Against the <strong>real Liquibase schema</strong> for the reason the profile's version of
 * this test exists: the entity and the changeset disagreeing is the failure worth catching, and
 * that has already happened once here over a reserved word.
 *
 * <p>The tests that matter most are the last two. B4's "what was discovered is distinguishable
 * from what was typed" is only true if neither half can write the other, and that is a property of
 * this class - {@code learnedFirmware} and {@code declare} touch disjoint columns - rather than
 * something a reader can see by looking at a {@code Descriptor}.
 */
@SpringBootTest
@Transactional
class MachineSpecPersistenceTest {

    @Autowired
    private PrinterRepository repo;

    private PrinterEntity registered() {
        return repo.save(new PrinterEntity(UUID.randomUUID(), "a printer", "localhost", 8099));
    }

    private static MachineSpec fullSpec() {
        return new MachineSpec(new BedVolume(220, 220, 250), 2,
                List.of(0.4, 0.6), Set.of("PLA", "PETG"), Set.of("prusa-shelf"));
    }

    @Test
    void aFreshlyRegisteredMachineHasNothingDeclared() {
        var spec = registered().spec();

        assertFalse(spec.isDeclared());
        assertNull(spec.bedVolume());
        assertNull(spec.extruderCount());
        assertEquals(List.of(), spec.nozzleDiameters());
    }

    @Test
    void everythingDeclaredSurvivesARoundTrip() {
        var entity = registered();
        entity.declare(fullSpec());
        repo.save(entity);
        repo.flush();

        var read = repo.findById(entity.getId()).orElseThrow().spec();

        assertEquals(new BedVolume(220, 220, 250), read.bedVolume());
        assertEquals(2, read.extruderCount());
        assertEquals(List.of(0.4, 0.6), read.nozzleDiameters(), "nozzles keep their tool order");
        assertEquals(Set.of("PLA", "PETG"), read.materials());
        assertEquals(Set.of("prusa-shelf"), read.tags());
    }

    /**
     * Declaring again replaces, where a capability report upserts.
     *
     * <p>Different because the sources are different. A firmware sends capabilities one line at a
     * time with nothing marking the last, so there is no moment at which the set is known to be
     * complete; a person submits a whole form, and a material they took out of it is a material
     * they meant to remove.
     */
    @Test
    void declaringAgainReplacesRatherThanMerging() {
        var entity = registered();
        entity.declare(fullSpec());
        repo.save(entity);
        repo.flush();

        entity.declare(new MachineSpec(null, null, List.of(0.4), Set.of("PLA"), Set.of()));
        repo.save(entity);
        repo.flush();

        var read = repo.findById(entity.getId()).orElseThrow().spec();
        assertNull(read.bedVolume(), "a bed cleared on the form is a bed cleared");
        assertEquals(List.of(0.4), read.nozzleDiameters());
        assertEquals(Set.of("PLA"), read.materials());
        assertEquals(Set.of(), read.tags());
    }

    /** A bed needs all three dimensions; two of them is a half-filled form and declares nothing. */
    @Test
    void aPartlyFilledBedIsNoBedAtAll() {
        var entity = registered();
        entity.declare(new MachineSpec(new BedVolume(220, 220, 250), null, List.of(), Set.of(), Set.of()));
        repo.save(entity);

        entity.declare(MachineSpec.undeclared());
        repo.save(entity);
        repo.flush();

        assertNull(repo.findById(entity.getId()).orElseThrow().spec().bedVolume());
    }

    /**
     * A firmware report cannot overwrite what a person declared.
     *
     * <p>This is the half of "distinguishable" that a machine could break. A profile is replaced
     * wholesale every time a printer reports - so if the two shared storage, every reconnection
     * would silently undo an operator's override, and the only symptom would be a command for tool
     * 1 starting to be refused again some time after it had been fixed.
     */
    @Test
    void aFirmwareReportLeavesTheDeclaredSpecAlone() {
        var entity = registered();
        entity.declare(fullSpec());
        repo.save(entity);
        repo.flush();

        entity.learnedFirmware(Map.of("FIRMWARE_NAME", "Marlin 2.1.2", "EXTRUDER_COUNT", "1"),
                LocalDateTime.now());
        entity.learnedCapability("EEPROM", true, LocalDateTime.now());
        repo.save(entity);
        repo.flush();

        var read = repo.findById(entity.getId()).orElseThrow();
        assertEquals(2, read.spec().extruderCount(), "the firmware says 1; the operator said 2");
        assertEquals(Set.of("PLA", "PETG"), read.spec().materials());
        assertTrue(read.profile().isKnown(), "and the report still landed");
    }

    /** And the other way: an edit cannot pass itself off as something the machine said. */
    @Test
    void declaringLeavesTheReportedProfileAlone() {
        var entity = registered();
        entity.learnedFirmware(Map.of("FIRMWARE_NAME", "Marlin 2.1.2"), LocalDateTime.now());
        repo.save(entity);
        repo.flush();

        entity.declare(fullSpec());
        repo.save(entity);
        repo.flush();

        var read = repo.findById(entity.getId()).orElseThrow();
        assertEquals(Map.of("FIRMWARE_NAME", "Marlin 2.1.2"), read.profile().firmware());
        assertTrue(read.profile().isKnown());
    }
}

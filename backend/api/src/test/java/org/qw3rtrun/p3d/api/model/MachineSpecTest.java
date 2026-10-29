package org.qw3rtrun.p3d.api.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declared half of a machine's description, and the rule that resolves it against the
 * discovered half.
 *
 * <p>Plan item B4's "what was discovered is distinguishable from what was typed" is structural
 * rather than a flag per field: {@link MachineProfile} is only ever written from what a machine
 * reported, {@link MachineSpec} is only ever written by a human, and neither can overwrite the
 * other. A reader that wants one answer asks {@link Descriptor}, which is the only place the two
 * meet.
 */
class MachineSpecTest {

    private static Descriptor descriptor(MachineProfile profile, MachineSpec spec) {
        return new Descriptor(UUID.randomUUID(), "a printer",
                new ConnectionDetails("localhost", 8099), MachineConnectionState.OFFLINE,
                profile, spec);
    }

    private static MachineProfile reporting(String extruders) {
        return new MachineProfile(Map.of("EXTRUDER_COUNT", extruders), Map.of(), LocalDateTime.now());
    }

    @Test
    void anUndeclaredSpecIsEmptyRatherThanNull() {
        var spec = MachineSpec.undeclared();

        assertFalse(spec.isDeclared());
        assertEquals(List.of(), spec.nozzleDiameters());
        assertEquals(Set.of(), spec.materials());
        assertEquals(Set.of(), spec.tags());
    }

    /**
     * Nulls are normalised on the way in, because this record is built from a web form.
     *
     * <p>An unticked checkbox and an absent JSON field both arrive as null, and a caller that has
     * to null-check five accessors will miss one. Only {@code bedVolume} and {@code extruderCount}
     * stay nullable, and they do so because for them "not declared" is a real third state.
     */
    @Test
    void nullCollectionsBecomeEmptyOnes() {
        var spec = new MachineSpec(null, null, null, null, null);

        assertEquals(MachineSpec.undeclared(), spec);
    }

    @Test
    void aDeclaredSpecSaysSo() {
        assertTrue(new MachineSpec(new BedVolume(220, 220, 250), null, List.of(), Set.of(), Set.of())
                .isDeclared());
        assertTrue(new MachineSpec(null, 2, List.of(), Set.of(), Set.of()).isDeclared());
        assertTrue(new MachineSpec(null, null, List.of(0.4), Set.of(), Set.of()).isDeclared());
        assertTrue(new MachineSpec(null, null, List.of(), Set.of("PETG"), Set.of()).isDeclared());
        assertTrue(new MachineSpec(null, null, List.of(), Set.of(), Set.of("prusa-farm")).isDeclared());
    }

    /**
     * What a human typed wins over what the firmware said, and that is the whole point of having
     * both.
     *
     * <p>A machine that under-reports its tools - a dual-extruder build flashed with a
     * single-extruder configuration is the usual way - would otherwise have every command for tool
     * 1 refused by {@code PrinterState}, with no way for the operator to say otherwise.
     */
    @Test
    void aDeclaredExtruderCountOverridesWhatTheFirmwareReported() {
        var declared = descriptor(reporting("1"), new MachineSpec(null, 2, List.of(), Set.of(), Set.of()));

        assertEquals(2, declared.extruderCount());
    }

    @Test
    void anUndeclaredExtruderCountFallsBackToTheFirmware() {
        assertEquals(2, descriptor(reporting("2"), MachineSpec.undeclared()).extruderCount());
    }

    /**
     * And with neither, the profile's own default of one - not zero.
     *
     * <p>The caller is about to check a tool index against this, where zero refuses every command
     * including the one for tool 0.
     */
    @Test
    void withNothingDeclaredAndNothingReportedItIsOne() {
        assertEquals(1, descriptor(MachineProfile.unknown(), MachineSpec.undeclared()).extruderCount());
    }

    /** A bed is three positive dimensions or it is not a bed. */
    @Test
    void aBedVolumeMustBePositiveInEveryDimension() {
        assertThrows(IllegalArgumentException.class, () -> new BedVolume(0, 220, 250));
        assertThrows(IllegalArgumentException.class, () -> new BedVolume(220, -1, 250));
        assertThrows(IllegalArgumentException.class, () -> new BedVolume(220, 220, 0));
    }
}

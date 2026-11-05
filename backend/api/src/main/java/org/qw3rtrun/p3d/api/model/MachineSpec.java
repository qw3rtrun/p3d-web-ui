package org.qw3rtrun.p3d.api.model;

import java.util.List;
import java.util.Set;

/**
 * What a human declared about a machine, as opposed to what the machine said about itself.
 *
 * <p>The other half of {@link MachineProfile} - B4's "what was discovered is distinguishable
 * from what was typed". Structural, not a flag per field: a profile is only ever written from a
 * firmware report, a spec only from the management API, neither can overwrite the other, and
 * {@link Descriptor} is where the two meet. A flag per field would lose the discovered value the
 * moment someone overrides it and could not show both, which an edit page wants to.
 *
 * <p>Most fields are declared-only because nothing asks the firmware yet - only
 * {@link #extruderCount()} has a discovered counterpart, from {@code M115}'s
 * {@code EXTRUDER_COUNT}; bed volume and nozzle diameters sit in an unread {@code M503} dump and
 * become overrides the same way once something asks.
 *
 * <p>Groups are tags: cheaper than a group id and lets a machine belong to two.
 *
 * @param bedVolume       null when nobody said
 * @param extruderCount   null to believe the firmware
 * @param nozzleDiameters one per extruder in tool order, in millimetres; empty when nobody said
 * @param materials       what it is set up to print - "PLA", "PETG"; empty when nobody said
 * @param tags            free-form labels, also how a farm expresses groups
 */
public record MachineSpec(
        BedVolume bedVolume,
        Integer extruderCount,
        List<Double> nozzleDiameters,
        Set<String> materials,
        Set<String> tags
) {

    /**
     * Normalises the collections, since this record is built from a web form: an absent JSON
     * field and an empty form input both arrive as null, and a caller null-checking five accessors
     * would eventually miss one. {@code bedVolume}/{@code extruderCount} stay nullable because
     * "not declared" is a real third state an empty value cannot express.
     */
    public MachineSpec {
        nozzleDiameters = nozzleDiameters == null ? List.of() : List.copyOf(nozzleDiameters);
        materials = materials == null ? Set.of() : Set.copyOf(materials);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (extruderCount != null && extruderCount <= 0) {
            throw new IllegalArgumentException("a declared extruder count is positive, not " + extruderCount);
        }
    }

    /** A machine nobody has described. Not null, so a caller never has to check. */
    public static MachineSpec undeclared() {
        return new MachineSpec(null, null, List.of(), Set.of(), Set.of());
    }

    /** Whether a human has said anything at all about this machine. */
    public boolean isDeclared() {
        return bedVolume != null
                || extruderCount != null
                || !nozzleDiameters.isEmpty()
                || !materials.isEmpty()
                || !tags.isEmpty();
    }

    /**
     * The tool count to believe: what was declared, or failing that what {@code reported} said.
     *
     * <p>The rule lives here once, shared by {@link Descriptor} and {@code PrinterState}'s
     * tool-index check rather than copied; taking the profile as a parameter keeps the two
     * records independent. Declared wins because the operator is looking at the machine and the
     * firmware is not.
     */
    public int extruderCountOr(MachineProfile reported) {
        if (extruderCount != null) {
            return extruderCount;
        }
        return reported == null ? MachineProfile.unknown().extruderCount() : reported.extruderCount();
    }

    /** Whether this machine carries {@code tag}, which is how plan item D1 will read a group. */
    public boolean taggedWith(String tag) {
        return tags.contains(tag);
    }
}

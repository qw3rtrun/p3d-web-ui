package org.qw3rtrun.p3d.api.model;

import java.util.List;
import java.util.Set;

/**
 * What a human declared about a machine, as opposed to what the machine said about itself.
 *
 * <p><strong>This is the other half of {@link MachineProfile}, and keeping them apart is plan item
 * B4's "what was discovered is distinguishable from what was typed".</strong> The distinction is
 * structural rather than a flag beside each field: a profile is only ever written from a firmware
 * report, a spec is only ever written from the management API, and neither can overwrite the other.
 * A reader that wants a single answer asks {@link Descriptor}, which is the one place the two meet.
 *
 * <p>A flag per field was the alternative and it is worse. It makes every writer responsible for
 * setting it correctly, it loses the discovered value the moment someone overrides it - so a
 * reflashed machine cannot be told that its typed-in value is now wrong - and it gives a reader no
 * way to show both, which is exactly what an edit page wants to show.
 *
 * <p><strong>Most of this is declared because nothing asks for it yet.</strong> Only
 * {@link #extruderCount()} has a discovered counterpart today, from {@code M115}'s
 * {@code EXTRUDER_COUNT}. Bed volume and nozzle diameters are both in an {@code M503} dump that B2
 * did not ask for; when something does, they become overrides like the extruder count rather than
 * the only source, and nothing here has to change shape.
 *
 * <p><strong>Groups are tags.</strong> B4 offered "free-form tags or a group id" and this takes
 * tags, because a group is a tag that a reader filters on and the two cost the same to store. A
 * farm that wants "the Prusa shelf" declares a {@code prusa-shelf} tag on each machine; a farm that
 * wants a machine in two groups can have it, which a single group id cannot express.
 *
 * @param bedVolume      how much room it has, or null when nobody said
 * @param extruderCount  how many tools it has, or null to believe the firmware
 * @param nozzleDiameters one per extruder in tool order, in millimetres. Empty when nobody said
 * @param materials      what it is set up to print - "PLA", "PETG". Empty when nobody said
 * @param tags           free-form labels, which is also how a farm expresses groups
 */
public record MachineSpec(
        BedVolume bedVolume,
        Integer extruderCount,
        List<Double> nozzleDiameters,
        Set<String> materials,
        Set<String> tags
) {

    /**
     * Normalises the collections, because this record is built from a web form.
     *
     * <p>An absent JSON field and an empty form input both arrive as null, and a caller that has to
     * null-check five accessors will eventually miss one. The two that stay nullable do so because
     * for them "not declared" is a real third state that an empty value cannot express: a bed of
     * zero is not a bed, and an extruder count of zero is not "ask the firmware".
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
     * <p><strong>The rule lives here, once.</strong> Two holders need it - {@link Descriptor} for
     * anything reading the roster, and {@code PrinterState} for the tool-index check that decides
     * whether a command is refused - and a one-line rule copied into both is a one-line rule that
     * drifts. Taking the profile as a parameter rather than holding one keeps the two records
     * independent: a spec still knows nothing about any particular machine's firmware.
     *
     * <p>Declared wins because the operator is looking at the machine and the firmware is not.
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

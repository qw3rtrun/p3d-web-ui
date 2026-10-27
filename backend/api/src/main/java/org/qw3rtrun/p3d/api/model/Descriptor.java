package org.qw3rtrun.p3d.api.model;

import java.util.UUID;

/**
 * One machine as the farm describes it: who it is, where it is, and what it turned out to be.
 *
 * @param uuid       the machine's identity
 * @param name       what a human calls it
 * @param connection where to dial it
 * @param state      the roster's record of where the machine's connection is. It was a
 *                   {@code connected} boolean that nothing in production ever set - see
 *                   {@code MConnectionChangedEvent} for why it is now derived from the runtime
 * @param profile    what it reported about itself the last time anything asked, never null
 * @param spec       what a human declared about it, never null - see {@link MachineSpec} for why
 *                   this is a second record rather than a flag beside each field
 */
public record Descriptor(
        UUID uuid,
        String name,
        ConnectionDetails connection,
        MachineConnectionState state,
        MachineProfile profile,
        MachineSpec spec
) {

    public Descriptor {
        profile = profile == null ? MachineProfile.unknown() : profile;
        spec = spec == null ? MachineSpec.undeclared() : spec;
    }

    /**
     * How many tools this machine has: what a human declared, or failing that what it reported.
     *
     * <p>The rule itself is {@link MachineSpec#extruderCountOr}, because {@code PrinterState}
     * needs the same answer for its tool-index check and a one-line rule copied twice is a
     * one-line rule that drifts. What this adds is that a reader holding a descriptor does not
     * have to know there were two halves to resolve.
     *
     * <p>Declared wins because the operator is looking at the machine and the firmware is not. A
     * dual-extruder build flashed with a single-extruder configuration reports one tool, and
     * without an override every command for tool 1 is refused with no way to say otherwise.
     */
    public int extruderCount() {
        return spec.extruderCountOr(profile);
    }
}

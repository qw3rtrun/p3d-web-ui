package org.qw3rtrun.p3d.api.model;

import java.util.UUID;

/**
 * One machine as the farm describes it: who it is, where it is, and what it turned out to be.
 *
 * @param state   the roster's record of the machine's connection, derived from the runtime (see
 *                {@code MConnectionChangedEvent}) rather than a {@code connected} boolean nothing
 *                in production ever set
 * @param profile what it reported about itself the last time anything asked, never null
 * @param spec    what a human declared about it, never null - see {@link MachineSpec}
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
     * <p>The rule is {@link MachineSpec#extruderCountOr}, shared with {@code PrinterState}'s own
     * tool-index check rather than copied. Declared wins because the operator is looking at the
     * machine and the firmware is not - a dual-extruder build flashed with a single-extruder
     * configuration reports one tool, and without an override every command for tool 1 is refused.
     */
    public int extruderCount() {
        return spec.extruderCountOr(profile);
    }
}

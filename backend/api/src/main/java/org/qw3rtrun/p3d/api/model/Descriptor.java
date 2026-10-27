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
 */
public record Descriptor(
        UUID uuid,
        String name,
        ConnectionDetails connection,
        MachineConnectionState state,
        MachineProfile profile
) {
}

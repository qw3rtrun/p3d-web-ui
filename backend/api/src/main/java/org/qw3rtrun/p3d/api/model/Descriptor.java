package org.qw3rtrun.p3d.api.model;

import java.util.UUID;

/**
 * One machine as the farm describes it: who it is, where it is, and what it turned out to be.
 *
 * @param uuid       the machine's identity
 * @param name       what a human calls it
 * @param connection where to dial it
 * @param connected  whether the roster believes it is connected. See the note on
 *                   {@code MachineManagementService} - nothing writes this yet
 * @param profile    what it reported about itself the last time anything asked, never null
 */
public record Descriptor(
        UUID uuid,
        String name,
        ConnectionDetails connection,
        boolean connected,
        MachineProfile profile
) {
}

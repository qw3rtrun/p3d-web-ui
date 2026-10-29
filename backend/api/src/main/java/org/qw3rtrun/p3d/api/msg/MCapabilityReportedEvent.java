package org.qw3rtrun.p3d.api.msg;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A machine declared one capability - a {@code Cap:} line.
 *
 * <p>One event per line rather than one per set, because the firmware sends them that way and
 * nothing marks the last one. {@code printer-management} upserts, so a machine that gains a
 * capability between firmware versions simply reports it next time.
 */
public record MCapabilityReportedEvent(UUID machineId,
                                       String capability,
                                       boolean enabled,
                                       LocalDateTime timestamp) implements ManagementEvent {

    @Override
    public UUID getMachineId() {
        return machineId;
    }

    @Override
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}

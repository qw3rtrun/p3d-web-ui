package org.qw3rtrun.p3d.api.msg;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A machine said what firmware it runs - the host's answer to {@code M115}.
 *
 * <p>Travels from {@code core}, which is the only module that can ask, to
 * {@code printer-management}, which is where static facts about a machine are kept. The
 * {@code Management} prefix marks it as roster news rather than something a UI watches.
 */
public record MFirmwareReportedEvent(UUID machineId,
                                     Map<String, String> firmware,
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

package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.RestoreSettings;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Throw away what is in live memory and load the EEPROM's copy back - {@code M501}.
 *
 * <p>The undo for this session's edits, and the recoverable one of the two discarding commands:
 * whatever was stored is still stored, so this costs only the unsaved edits.
 *
 * <p>Measured: the firmware announces nothing, a bare {@code ok} with no word on what changed. So
 * the aggregate sends its own {@code M503} behind this one and the browser is told the reverted
 * values - otherwise every open Settings tab would keep showing the discarded edits.
 */
public record RevertSettings() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new RestoreSettings());
    }
}

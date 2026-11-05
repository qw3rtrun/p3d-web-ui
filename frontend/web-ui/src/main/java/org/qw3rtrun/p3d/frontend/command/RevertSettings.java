package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.RestoreSettings;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Throw away what is in live memory and load the EEPROM's copy back - {@code M501}.
 *
 * <p>The undo for every setting changed in this session, and the recoverable one of the two
 * discarding commands: whatever was stored is still stored, so this costs an operator only the
 * edits they have not saved.
 *
 * <p><strong>The firmware announces nothing.</strong> Measured: {@code M501} answers a bare
 * {@code ok} and says not one word about which of a dozen values it just changed. So the aggregate
 * sends its own {@code M503} behind this one and the browser is told the reverted values - without
 * that, every open Settings tab would go on showing the edits that have just been discarded, and
 * the only way out would be an operator thinking to press Read.
 */
public record RevertSettings() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new RestoreSettings());
    }
}

package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.FactoryReset;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Load the values the firmware was compiled with - {@code M502}.
 *
 * <p>More destructive than {@link RevertSettings}: {@code M501} loads the EEPROM, undoing this
 * session's edits, while {@code M502} loads the build's {@code Configuration.h}, undoing every
 * calibration the machine has ever been given, including what is only in the EEPROM. Measured on
 * MarlinSim: the stored settings and the defaults really do differ, so this is not the no-op it can
 * look like on a fresh board.
 *
 * <p>Does not by itself touch the EEPROM - the defaults land in live memory, and the stored copy
 * survives until {@code M500}. So the sequence that actually loses a calibration is {@code M502}
 * then {@code M500}, and {@code M501} recovers from a mistaken reset; the page's confirmation says
 * that rather than a generic "are you sure".
 *
 * <p>Like {@code M501} it announces nothing - a bare {@code ok} - so the aggregate re-reads and the
 * browser is told the defaulted values.
 */
public record ResetSettingsToDefaults() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new FactoryReset());
    }
}

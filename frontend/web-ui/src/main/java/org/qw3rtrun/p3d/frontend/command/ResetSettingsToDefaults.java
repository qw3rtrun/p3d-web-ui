package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.FactoryReset;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Load the values the firmware was compiled with - {@code M502}.
 *
 * <p><strong>Not the same thing as {@link RevertSettings}, and the more destructive of the
 * two.</strong> {@code M501} loads what is in the EEPROM, so it undoes this session's edits;
 * {@code M502} loads what the build's {@code Configuration.h} said, so it undoes <em>every</em>
 * calibration the machine has ever been given - including the ones that are in the EEPROM and are
 * the only record of them. Measured on MarlinSim, the stored settings and the defaults really do
 * differ, so this is not the no-op it can look like on a fresh board.
 *
 * <p>It does not by itself touch the EEPROM: the defaults land in live memory, and the stored copy
 * survives until an {@code M500}. That makes the sequence that actually loses a calibration
 * {@code M502} then {@code M500}, and the one that recovers from a mistaken reset
 * {@code M501} - which is why the page's confirmation says so rather than saying "are you sure".
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

package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SaveSettings;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Write the machine's current settings to its EEPROM - {@code M500}.
 *
 * <p><strong>It persists whatever is in the firmware's memory right now</strong>, including
 * anything an earlier command changed in this session. That makes it the one command here with a
 * consequence that outlives the connection: a wrong value stored is a wrong value the machine
 * keeps after a power cycle, and recovering means {@code M502} and a reflash of the operator's
 * intentions.
 *
 * <p>It carries no fields because the firmware decides what is worth storing. A farm that wanted
 * to change a setting would send that setting's own command first; this only makes it stick.
 */
public record StoreSettings() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SaveSettings());
    }
}

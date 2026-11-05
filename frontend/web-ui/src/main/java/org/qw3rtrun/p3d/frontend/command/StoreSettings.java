package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SaveSettings;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Write the machine's current settings to its EEPROM - {@code M500}.
 *
 * <p>Persists whatever is in the firmware's memory right now, including anything an earlier command
 * changed this session - the one command here whose consequence outlives the connection, since a
 * wrong value stored survives a power cycle and recovering means {@code M502} and reflashing intent.
 *
 * <p>Carries no fields: the firmware decides what is worth storing, so changing a setting means
 * sending that setting's own command first - this only makes it stick.
 */
public record StoreSettings() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SaveSettings());
    }
}

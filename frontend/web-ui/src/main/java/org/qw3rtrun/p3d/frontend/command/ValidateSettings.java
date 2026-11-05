package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.ValidateEEPROMContents;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Ask the firmware whether what is in its EEPROM is readable - {@code M504}.
 *
 * <p>The verdict arrives on the event stream as an {@code EepromReport}, carrying the firmware's
 * message verbatim: {@code echo:EEPROM OK} is what MarlinSim writes, and <strong>the failure text
 * has never been captured</strong> - the simulator has no way to be asked for a corrupt one. That
 * is why the reply is a string rather than a boolean, and why the page renders the message instead
 * of a tick or a cross it would have had to guess the rule for.
 *
 * <p>It changes nothing. A validation is the one operation here an operator can run on a machine
 * mid-print without consequence.
 */
public record ValidateSettings() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new ValidateEEPROMContents());
    }
}

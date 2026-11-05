package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.ReportSettings;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Ask the machine what its settings are - {@code M503}.
 *
 * <p><strong>The answer is G-code.</strong> Marlin reports the settings as the commands that would
 * restore them - {@code echo:  M92 X80.00 Y80.00 Z400.00 E500.00} under a section heading - and it
 * reaches the browser as a stream of {@code SettingsSection} and {@code SettingsValue} events
 * rather than as a parsed model. There is no parsed model and deliberately will not be one: the
 * report already <em>is</em> the command set, so a second number model would only be a second place
 * for the scale rules to drift.
 *
 * <p>The bare form is sent. {@code M503 S0} answers in a second shape with no section headings at
 * all, which leaves a page nothing to group the report by, and {@code C} compares against the
 * compiled defaults rather than reporting what is live.
 *
 * <p><strong>A firmware built with {@code DISABLE_M503} refuses this, and the refusal is the only
 * way to find out.</strong> {@code M115} reports nothing about {@code M503} under any spelling, so
 * unlike the EEPROM capability this cannot be checked before asking: the answer arrives as an
 * {@code UnknownCommand} naming {@code M503}, and the Settings tab reads that as "this firmware has
 * no settings report" rather than as an empty one.
 */
public record ReadSettings() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new ReportSettings(false, false));
    }
}

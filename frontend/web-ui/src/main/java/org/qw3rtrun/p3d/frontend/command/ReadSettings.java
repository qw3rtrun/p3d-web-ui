package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.ReportSettings;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Ask the machine what its settings are - {@code M503}.
 *
 * <p>The answer is G-code: Marlin reports settings as the commands that would restore them -
 * {@code echo:  M92 X80.00 Y80.00 Z400.00 E500.00} under a section heading - reaching the browser as
 * a stream of {@code SettingsSection}/{@code SettingsValue} events. Deliberately no parsed model:
 * the report already <em>is</em> the command set, so a second number model would only be a second
 * place for the scale rules to drift.
 *
 * <p>The bare form is sent: {@code M503 S0} drops the section headings a page needs to group by, and
 * {@code C} compares against compiled defaults rather than reporting what is live.
 *
 * <p>A firmware built with {@code DISABLE_M503} refuses this, and the refusal is the only way to
 * find out - {@code M115} says nothing about {@code M503} under any spelling, so unlike the EEPROM
 * capability this cannot be checked beforehand. The answer arrives as {@code UnknownCommand} naming
 * {@code M503}, read by the Settings tab as "no settings report" rather than an empty one.
 */
public record ReadSettings() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new ReportSettings(false, false));
    }
}

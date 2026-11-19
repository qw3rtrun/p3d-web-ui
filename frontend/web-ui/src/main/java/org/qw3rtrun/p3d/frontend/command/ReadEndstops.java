package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.EndstopStates;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Ask the machine which of its endstops are triggered - {@code M119}.
 *
 * <p>The answer is several lines: a {@code Reporting endstop status} heading and then one line per
 * endstop, reaching the browser as {@code EndstopReportHeader} and {@code EndstopStateRs} events
 * rather than one report object. There is nothing left to parse - {@code EndstopStateRs} already
 * carries {@code name} and {@code triggered} as fields, unlike {@code SettingsValue}, which keeps
 * the firmware's raw G-code line - so what a page assembles is the <em>set</em> of lines, not their
 * contents.
 *
 * <p>Which endstops a machine has, how many, and in what order they are reported is the machine's
 * business ({@code Endstops::report_states}): the measured build answers three
 * ({@code x_min}, {@code y_min}, {@code z_min}), a delta or a machine with a filament sensor answers
 * a different set. Nothing in this farm assumes one.
 *
 * <p>Nothing gates this: {@code M115} reports no capability for it and no refusal of it has been
 * measured, so unlike {@link ReadSettings} there is no "this firmware has no such command" state to
 * render. A page opened after somebody else pressed the button gets the last-known states from the
 * aggregate's snapshot rather than an empty table.
 */
public record ReadEndstops() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new EndstopStates());
    }
}

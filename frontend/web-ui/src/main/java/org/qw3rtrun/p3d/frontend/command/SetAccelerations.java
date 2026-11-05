package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetStartingAcceleration;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a move is asked to accelerate at - {@code M204 P… R… T…}, in mm/s².
 *
 * <p>Three kinds of move, the names Marlin's own: {@code print} extrudes, {@code retract} is
 * filament-only, {@code travel} has the nozzle off the work - matching the report's own heading,
 * {@code (P<print-accel> R<retract-accel> T<travel-accel>)}.
 *
 * <p>Clamped by {@link SetMaxAccelerations}, so a generous {@code print} on a machine with a
 * conservative {@code M201} changes nothing visible.
 *
 * <p>A null value is left alone, and the change is live memory only - see {@link SetStepsPerUnit}.
 */
public record SetAccelerations(BigDecimal print, BigDecimal retract, BigDecimal travel)
        implements PrinterCommand {

    // M204's generated parameters: accel writes P, r writes R, t writes T. Its fourth, s, is a
    // legacy spelling of P that mainline Marlin still reads, so it stays null - two words for one
    // setting in one command is two answers to one question.
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetStartingAcceleration(print, retract, travel, null));
    }
}

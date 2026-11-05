package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetStartingAcceleration;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a move is asked to accelerate at - {@code M204 P… R… T…}, in mm/s².
 *
 * <p>Three kinds of move, three numbers, and the names are the firmware's own: {@code print} is a
 * move that extrudes, {@code retract} is a filament-only move, {@code travel} is a move with the
 * nozzle off the work. Marlin's report labels them in exactly those words -
 * {@code (P<print-accel> R<retract-accel> T<travel-accel>)} - which is the section heading the page
 * shows above these fields.
 *
 * <p>Clamped by {@link SetMaxAccelerations}, so a generous {@code print} on a machine with a
 * conservative {@code M201} changes nothing visible.
 *
 * <p>A null value is left alone, and the change is live memory only - see {@link SetStepsPerUnit}.
 */
public record SetAccelerations(BigDecimal print, BigDecimal retract, BigDecimal travel)
        implements PrinterCommand {

    /**
     * {@code M204}, positionally.
     *
     * <p>The generated class's first parameter is called {@code accel} and writes {@code P}; then
     * {@code r} writes {@code R} and {@code t} writes {@code T}. Its fourth, {@code s}, writes
     * {@code S} - a legacy spelling of the print acceleration that mainline Marlin still reads, so
     * it is left null rather than sent alongside {@code P}: two words for one setting in one
     * command is two answers to one question.
     */
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetStartingAcceleration(print, retract, travel, null));
    }
}

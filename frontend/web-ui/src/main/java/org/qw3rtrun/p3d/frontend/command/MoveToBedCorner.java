package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.LinearMoveG1;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Send the nozzle to one bed corner for manual levelling - hop, travel, descend, as three
 * {@code G1}s in that order.
 *
 * <p>Marlin's own {@code LEVEL_BED_CORNERS} is an LCD-menu feature with <strong>no G-code entry
 * point</strong>, so a host that wants the wizard has to run the sequence out of ordinary moves. The
 * browser computes each corner's coordinates from the declared bed volume and posts one of these per
 * corner.
 *
 * <p><strong>IMPORTANT - the three moves are one record because the order is a safety property, not
 * a convenience.</strong> {@link MoveAxes} already notes that "the runtime applies the whole list as
 * one task, so no other command lands while the mode is wrong"; this has the same hazard for a
 * different reason. Posted as three separate {@code /move} calls, another operator's jog or a second
 * browser tab can interleave, and a descend to the check height landing <em>between</em> the hop and
 * the XY travel leaves the nozzle crossing the bed at 0.2&nbsp;mm - a bed crash, and a real race.
 * {@code PrinterControl.send} takes a {@code List<GRq<?>>} precisely so a group is atomic: one
 * record, one list, one task.
 *
 * <p>All three moves are absolute and <strong>no mode switch is emitted</strong>, because the
 * machine's resting mode is absolute - the same reason {@code MoveAxes} returns a bare {@code G1}
 * when it is not relative.
 *
 * <p>A null component is a move this request does not make, never a zero: {@code Z0} is the bed and
 * {@code Y0} is its front edge. So a null {@code travelZ} drops the hop (the nozzle is already
 * clear), a null {@code checkZ} drops the descend (park over the corner without lowering), and
 * {@code x} and {@code y} both null leaves only the two height moves. Feed rates are Marlin's
 * {@code F}, in <strong>millimetres per minute</strong>, and a null one leaves the machine's own.
 *
 * <p>Nothing here throws: the record is bound straight from an HTTP body, so a bad value must be a
 * 400 from the binder or a harmless omission, never a 500 out of a handler.
 */
public record MoveToBedCorner(BigDecimal x, BigDecimal y,
                              BigDecimal travelZ, BigDecimal checkZ,
                              BigDecimal xyFeedRate, BigDecimal zFeedRate)
        implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        var moves = new ArrayList<GRq<?>>(3);
        if (travelZ != null) {
            moves.add(zMove(travelZ));
        }
        if (x != null || y != null) {
            moves.add(new LinearMoveG1(
                    x, y, null, null, null, null, null, null, null,
                    null, xyFeedRate, null));
        }
        if (checkZ != null) {
            moves.add(zMove(checkZ));
        }
        return List.copyOf(moves);
    }

    private LinearMoveG1 zMove(BigDecimal z) {
        return new LinearMoveG1(
                null, null, z, null, null, null, null, null, null,
                null, zFeedRate, null);
    }
}

package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.AbsolutePositioning;
import org.qw3rtrun.p3d.g.marlin.command.LinearMoveG1;
import org.qw3rtrun.p3d.g.marlin.command.RelativePositioning;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * Move the toolhead - {@code G1}, with {@code G91}/{@code G90} around it when relative.
 *
 * <p><strong>{@code relative} is what a jog button means.</strong> An operator pressing "X +10"
 * knows nothing about where the machine is, and a host that had to compute an absolute target
 * would be wrong whenever its idea of the position was stale - after a manual move, after a reset,
 * before the first home.
 *
 * <p>A null axis is one the move does not mention, which is different from zero: zero in relative
 * mode means "do not move this axis" and in absolute mode means "go to the origin".
 *
 * <p>{@code feedRate} is Marlin's {@code F}, in <strong>millimetres per minute</strong>. Null
 * leaves the machine's current feedrate alone - which is the right default for a jog and the wrong
 * one for a first move after power-on, where it may be anything the last job left.
 */
public record MoveAxes(BigDecimal x, BigDecimal y, BigDecimal z, BigDecimal e,
                       BigDecimal feedRate, Boolean relative) implements PrinterCommand {

    public boolean isRelative() {
        return Boolean.TRUE.equals(relative);
    }

    /** Whether the command would move anything at all. */
    public boolean movesNothing() {
        return x == null && y == null && z == null && e == null;
    }

    /**
     * The move, and the mode switch a relative one needs around it.
     *
     * <p>Marlin has no per-command relative flag: {@code G91} puts the machine in relative mode and
     * it <strong>stays there</strong> until {@code G90} puts it back. So a relative jog is three
     * commands, and leaving the machine in relative mode by forgetting the third would make every
     * later absolute move - including a print's - land somewhere else entirely.
     *
     * <p>That is also why they are returned together rather than sent as three requests: the
     * runtime applies the whole list as one task on the aggregate's thread, so no other command can
     * be interpreted while the machine is in the wrong mode.
     */
    @Override
    public List<GRq<?>> toMarlin() {
        var g1 = new LinearMoveG1(
                x, y, z, null, null, null, null, null, null,
                e, feedRate, null);
        if (!isRelative()) {
            return List.of(g1);
        }
        return List.of(
                new RelativePositioning(),
                g1,
                new AbsolutePositioning());
    }
}

package org.qw3rtrun.p3d.api.msg;

import java.math.BigDecimal;

/**
 * Move the toolhead - {@code G1}, with {@code G91}/{@code G90} around it when relative.
 *
 * <p><strong>{@code relative} is what a jog button means.</strong> An operator pressing "X +10"
 * knows nothing about where the machine is, and a host that had to compute an absolute target
 * would be wrong whenever its idea of the position was stale - after a manual move, after a reset,
 * before the first home. Marlin has no per-command relative flag, so a relative move is three wire
 * commands; {@code core} sends them as one unit so nothing can land between them and be
 * interpreted in the wrong mode.
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
}

package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Something a browser asked a machine to do, in the shape the browser sends it.
 *
 * <p><strong>A request body, not a model.</strong> These records exist because an HTTP contract is
 * not a command set: the page posts {@code {"x":10,"relative":true}} to {@code /move} and should go
 * on being able to, whatever {@code G1}'s parameter list looks like this year. They are
 * <em>not</em> a second vocabulary for what a printer can do - that is {@code :gcode}'s, and
 * {@link #toMarlin()} is the one step between the two.
 *
 * <p>They live in this module for the same reason: the shape of a request belongs to the surface
 * that receives it. {@code api} used to carry them as a sealed {@code PrinterCommand} with a
 * translating switch in {@code core}, which made the application's vocabulary a mirror of Marlin's
 * - two names and two field lists for every single thing a printer can be told.
 *
 * <p><strong>Each record translates itself</strong>, and a translation may be more than one
 * command: see {@link MoveAxes}, where a relative jog is a mode switch, the move and the mode
 * switch back. The list is sent as one unit, so nothing can land between them.
 */
public interface PrinterCommand {

    /** This request as the Marlin command (or commands) that carry it. */
    List<GRq<?>> toMarlin();
}

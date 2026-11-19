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
 *
 * <p><strong>There is one raw-G-code route, and this note used to say there was none.</strong> Issue
 * #135 added {@link SendRawLine} and {@code POST /api-printer/&#123;id&#125;/terminal} - a console an
 * operator types into, which is a feature whose subject <em>is</em> the protocol rather than a
 * control working around a missing command. The rule the prohibition existed for is narrowed and not
 * abandoned, and the narrowed form is the contract: <strong>a control may not reach for the terminal
 * to do what a named command should do.</strong> {@code /fan} exists, so a fan slider posting
 * {@code M106 S200} to {@code /terminal} would be a defect - it would have no server-side meaning,
 * no refusal and no test, and it would decay the moment the firmware changed. Anything a browser can
 * describe as an intent is a record in this package implementing this interface;
 * {@link SendRawLine} is the one deliberate exception and a second one is a design discussion rather
 * than a precedent. The full argument is §6.3 of {@code doc/specs/INTEGRATION_TESTS_spec.md}.
 */
public interface PrinterCommand {

    /** This request as the Marlin command (or commands) that carry it. */
    List<GRq<?>> toMarlin();
}

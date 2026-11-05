package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetMaxFeedrate;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * The fastest each axis may ever be driven - {@code M203 X… Y… Z… E…}, in millimetres per second.
 *
 * <p><strong>Seconds here, minutes in a move.</strong> {@code M203}'s units are mm/s and
 * {@code G1 F}'s are mm/min, which is a factor of sixty between two numbers that look alike and
 * sit on the same page. Marlin's own report says which - {@code ; Max feedrates (units/s):} - and
 * that heading is worth keeping in front of whoever is typing.
 *
 * <p>This is a ceiling, not a speed: it clamps what a job may ask for rather than setting what the
 * machine does. Lowering {@code Z} is how a machine with a leadscrew stops a print's optimistic
 * {@code F} from asking for steps the motor cannot take - which it answers by losing them in
 * silence, so the position it reports afterwards is a fiction.
 *
 * <p>A null axis is left alone. See {@link SetStepsPerUnit}, and note that the value takes effect
 * in live memory only until {@link StoreSettings} is sent.
 */
public record SetMaxFeedrates(BigDecimal x, BigDecimal y, BigDecimal z, BigDecimal e)
        implements PrinterCommand {

    /**
     * {@code M203} with only the axes that were named.
     *
     * <p>Positional like the rest of this family. This is the one generated class whose parameters
     * happen to be named after the letters they write, which is no reason to rely on it.
     */
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetMaxFeedrate(x, y, z, e, null));
    }
}

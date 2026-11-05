package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetMaxFeedrate;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * The fastest each axis may ever be driven - {@code M203 X… Y… Z… E…}, in millimetres per second.
 *
 * <p>Seconds here, minutes in a move: {@code M203} is mm/s and {@code G1 F} is mm/min, a factor of
 * sixty between two numbers that look alike on the same page. Marlin's own report heading says
 * which - {@code ; Max feedrates (units/s):}.
 *
 * <p>A ceiling, not a speed - it clamps what a job may ask for. Lowering {@code Z} is how a
 * leadscrew machine stops a print's optimistic {@code F} from asking for steps the motor cannot
 * take, which it otherwise answers by losing them in silence, making the reported position fiction.
 *
 * <p>A null axis is left alone. See {@link SetStepsPerUnit}; live memory only until
 * {@link StoreSettings} is sent.
 */
public record SetMaxFeedrates(BigDecimal x, BigDecimal y, BigDecimal z, BigDecimal e)
        implements PrinterCommand {

    // Positional like the rest of this family, even though this generated class's parameters
    // happen to be named after the letters they write - no reason to rely on that holding.
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetMaxFeedrate(x, y, z, e, null));
    }
}

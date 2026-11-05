package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.PrintTravelMoveLimits;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * The hardest each axis may ever be accelerated - {@code M201 X… Y… Z… E…}, in mm/s².
 *
 * <p>A per-axis ceiling, and a different thing from {@link SetAccelerations}: this is what the
 * motor and the belt can take, while {@code M204} is what a move is <em>asked</em> for. Marlin
 * applies the lower of the two, so raising {@code M204} past these does nothing at all and
 * lowering these throttles every job on the machine. That asymmetry is why both are on the page
 * rather than one standing in for the other.
 *
 * <p>A null axis is left alone, and the change is live memory only - see {@link SetStepsPerUnit}.
 */
public record SetMaxAccelerations(BigDecimal x, BigDecimal y, BigDecimal z, BigDecimal e)
        implements PrinterCommand {

    /**
     * {@code M201} with only the axes that were named.
     *
     * <p><strong>Positional, because the first parameter is called {@code accel} and writes
     * {@code X}.</strong> The last two - {@code f} and {@code percent}, which write {@code F} and
     * {@code S} - are a travel-limit pair this farm does not use.
     */
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new PrintTravelMoveLimits(x, y, z, e, null, null, null));
    }
}

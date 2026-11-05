package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetAxisStepsPerUnit;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * How many steps move an axis one millimetre - {@code M92 X… Y… Z… E…}.
 *
 * <p>Live memory only: the machine keeps the new value until power-cycled or until
 * {@link StoreSettings} is sent - deliberate, so an operator calibrating an extruder can try a
 * value and print before a host writes a wrong trial into flash.
 *
 * <p>A null axis leaves the firmware's value alone; zero would be legal and ruinous, since an axis
 * with no steps per millimetre cannot move - "leave it" and "set it to nothing" must not collapse.
 *
 * <p>The extruder calibration everybody does changes this value: measure 100 mm of filament, see
 * how much actually went in, scale {@code E} by the ratio.
 */
public record SetStepsPerUnit(BigDecimal x, BigDecimal y, BigDecimal z, BigDecimal e)
        implements PrinterCommand {

    // Positional: the generated class's parameter names are misleading - SetAxisStepsPerUnit's
    // first is called "steps" and writes X, its last "index" and writes T. The six in between are
    // the extra axes a delta or IDEX machine has, which this farm has none of.
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetAxisStepsPerUnit(
                x, y, z,
                null, null, null, null, null, null,
                e, null));
    }
}

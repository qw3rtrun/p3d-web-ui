package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.SetAxisStepsPerUnit;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.math.BigDecimal;
import java.util.List;

/**
 * How many steps move an axis one millimetre - {@code M92 X… Y… Z… E…}.
 *
 * <p><strong>In live memory only.</strong> Nothing here reaches the EEPROM; the machine keeps the
 * new value until it is power-cycled or until {@link StoreSettings} is sent. That split is the
 * whole shape of this feature and it is deliberately not hidden behind a convenience: an operator
 * calibrating an extruder wants to try a value, print, and decide - and a host that stored every
 * trial would write a wrong one into flash before the print finished.
 *
 * <p>A null axis is an axis the command does not mention, which leaves the firmware's value alone.
 * Zero would be a legal and ruinous thing to send - an axis that takes no steps per millimetre
 * cannot move - so "leave it" and "set it to nothing" must not collapse into the same request.
 *
 * <p>These are the values the extruder calibration everybody does changes: measure 100 mm of
 * filament, see how much actually went in, scale {@code E} by the ratio.
 */
public record SetStepsPerUnit(BigDecimal x, BigDecimal y, BigDecimal z, BigDecimal e)
        implements PrinterCommand {

    /**
     * {@code M92} with only the axes that were named.
     *
     * <p><strong>Constructed positionally, because the generated class's parameter names are
     * misleading.</strong> {@code SetAxisStepsPerUnit}'s first parameter is called {@code steps}
     * and writes {@code X}; its last is called {@code index} and writes {@code T}. The six in
     * between are the extra axes a delta or an IDEX machine has, which this farm has none of.
     */
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new SetAxisStepsPerUnit(
                x, y, z,
                null, null, null, null, null, null,
                e, null));
    }
}

package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.AutoHome;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Send the named axes to their endstops - {@code G28}.
 *
 * <p><strong>Nothing selected means all of them</strong>, which is Marlin's own rule for a bare
 * {@code G28} and the thing an operator means by "home". It is expressed as three nullable flags
 * rather than a set of axis names so that the HTTP contract cannot carry an axis this farm does
 * not have.
 *
 * <p>Homing is a <em>move</em>, and a slow one: the machine is unresponsive for several seconds
 * and will drag the nozzle across whatever is on the bed. That is the operator's call to make, not
 * something to issue automatically.
 */
public record HomeAxes(Boolean x, Boolean y, Boolean z) implements PrinterCommand {

    /** True when the request named no axis, which Marlin reads as "home everything". */
    public boolean all() {
        return !Boolean.TRUE.equals(x) && !Boolean.TRUE.equals(y) && !Boolean.TRUE.equals(z);
    }

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(all()
                ? new AutoHome()
                : new AutoHome(
                        false, null, false, null,
                        Boolean.TRUE.equals(x),
                        Boolean.TRUE.equals(y),
                        Boolean.TRUE.equals(z),
                        false, false, false, false, false, false));
    }
}

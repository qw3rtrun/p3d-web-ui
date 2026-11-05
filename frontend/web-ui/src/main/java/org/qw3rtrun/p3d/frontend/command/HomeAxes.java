package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.AutoHome;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Send the named axes to their endstops - {@code G28}.
 *
 * <p>Nothing selected means all of them, Marlin's own rule for a bare {@code G28}. Three nullable
 * flags rather than a set of axis names, so the HTTP contract cannot carry an axis this farm does
 * not have.
 *
 * <p>Homing is a slow move that drags the nozzle across whatever is on the bed, so it is the
 * operator's call to make, not something issued automatically.
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

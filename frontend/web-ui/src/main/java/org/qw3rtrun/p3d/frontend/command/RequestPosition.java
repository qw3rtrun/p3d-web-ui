package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.GetCurrentPosition;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Ask where the toolhead is - {@code M114}.
 *
 * <p>The answer arrives on the event stream as a {@code PositionRs}, not as this call's return,
 * since a reply can also arrive unasked.
 *
 * <p>There is no auto-report for position the way {@code M155} auto-reports temperature, so a page
 * must ask - on demand and after a move, not on a timer, since the planner is busy during a print
 * and every {@code M114} takes a queue slot.
 */
public record RequestPosition() implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(new GetCurrentPosition(false, false, false));
    }
}

package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Ask for one temperature reading now, rather than waiting for the next auto-report - {@code M105}.
 *
 * @param index which hotend to report on; null means all of them
 */
public record RequestTemperatureReport(Integer index) implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(MarlinG.INSTANCE.m105(index));
    }
}

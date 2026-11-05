package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Have the machine report its temperatures every {@code period} seconds, or stop with zero -
 * {@code M155}.
 *
 * <p>Named {@code period} because that is what the browser sends, while the generated command's
 * property is {@code seconds} - binding the generated class directly once made this endpoint
 * silently ask for the firmware's default instead, which is why these records exist at all.
 *
 * @param period seconds between reports; 0 stops them, null leaves it to the firmware
 */
public record SetTemperatureAutoReport(Integer period) implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(MarlinG.INSTANCE.m155(period));
    }
}

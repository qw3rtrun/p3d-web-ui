package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Have the machine report its temperatures every {@code period} seconds, or stop with zero -
 * {@code M155}.
 *
 * <p>The field is {@code period} because that is what the browser has always sent, while the
 * generated command's property is {@code seconds}. Binding the generated class directly is what
 * made this endpoint silently ask for the firmware's default instead of the period the user chose,
 * and it is the reason these records exist.
 *
 * @param period seconds between reports; 0 stops them, null leaves it to the firmware
 */
public record SetTemperatureAutoReport(Integer period) implements PrinterCommand {

    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(MarlinG.INSTANCE.m155(period));
    }
}

package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.FanOff;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Set a part-cooling fan - {@code M106}, or {@code M107} at zero.
 *
 * <p>{@code speed} is Marlin's 0..255 PWM scale, not a percentage - the UI converts, since a
 * percentage would be a second unit to get wrong on the way to the machine.
 *
 * <p>Zero is sent as {@code M107} rather than {@code M106 S0}: Marlin treats them the same on a
 * plain part fan, but {@code M107} is the documented "off" and reads correctly in a log.
 *
 * <p>A null {@code index} is the default fan, which is fan 0 on every machine this farm has seen.
 */
public record SetFanSpeed(Integer index, Integer speed) implements PrinterCommand {

    public boolean off() {
        return speed == null || speed <= 0;
    }

    // The fan index goes in M106's P, not I: Marlin's docs list both as "index" and the generated
    // class carries both fields, but P is what mainline firmware reads, and what M107 uses too.
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(off()
                ? new FanOff(index)
                : new org.qw3rtrun.p3d.g.marlin.command.SetFanSpeed(null, speed, index, null));
    }
}

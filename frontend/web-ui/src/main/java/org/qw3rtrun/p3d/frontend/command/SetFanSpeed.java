package org.qw3rtrun.p3d.frontend.command;

import org.qw3rtrun.p3d.g.marlin.command.FanOff;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.util.List;

/**
 * Set a part-cooling fan - {@code M106}, or {@code M107} at zero.
 *
 * <p><strong>{@code speed} is Marlin's 0..255 PWM scale</strong>, not a percentage. The UI shows a
 * percentage because that is what a person reads; the conversion belongs there, because a
 * percentage would be a second unit to get wrong on the way to a machine that only understands the
 * first.
 *
 * <p>Zero is sent as {@code M107} rather than {@code M106 S0}. Marlin treats them the same on a
 * plain part fan, but {@code M107} is the documented "off" and reads correctly in a log.
 *
 * <p>A null {@code index} is the default fan, which is fan 0 on every machine this farm has seen.
 */
public record SetFanSpeed(Integer index, Integer speed) implements PrinterCommand {

    public boolean off() {
        return speed == null || speed <= 0;
    }

    /**
     * {@code M106}, with the fan number in {@code P}.
     *
     * <p>The fan index goes in P and not in I. Marlin's own documentation lists both as "index" and
     * the generated class carries both fields, but P is the one mainline firmware reads as the fan
     * number - and it is what {@code M107} uses, so the pair stays consistent. A null index sends
     * no index word at all, which means fan 0.
     */
    @Override
    public List<GRq<?>> toMarlin() {
        return List.of(off()
                ? new FanOff(index)
                : new org.qw3rtrun.p3d.g.marlin.command.SetFanSpeed(null, speed, index, null));
    }
}

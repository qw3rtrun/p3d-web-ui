package org.qw3rtrun.p3d.api.msg;

/**
 * Set a part-cooling fan - {@code M106}, or {@code M107} at zero.
 *
 * <p><strong>{@code speed} is Marlin's 0..255 PWM scale</strong>, not a percentage. The UI shows a
 * percentage because that is what a person reads; the conversion belongs there, because this is
 * the application's command vocabulary and a percentage would be a second unit to get wrong on
 * the way to a machine that only understands the first.
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
}

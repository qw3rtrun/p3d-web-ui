package org.qw3rtrun.p3d.g.code;

import static java.lang.String.format;

/**
 * M140 S<temp>
 *
 * @link
 * @see <a href="https://marlinfw.org/docs/gcode/M140.html">MarlinFirmare M140 Set Bed Temperature doc</a>
 */
@GCommand("M140")
public record SetBedTemperature(
        @GParameter("S") double temp
) implements GCode {

    public static SetBedTemperature m140(double temp) {
        return new SetBedTemperature(temp);
    }

    public static SetBedTemperature m104(double temp) {
        return new SetBedTemperature(temp);
    }

    @Override
    public String encode() {
        return format("M140 S%.2f", temp());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + encode() + ')';
    }
}

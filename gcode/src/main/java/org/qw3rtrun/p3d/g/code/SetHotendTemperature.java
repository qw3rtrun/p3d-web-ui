package org.qw3rtrun.p3d.g.code;

import static java.lang.String.format;

/**
 * M104 T<index> S<temp>
 *
 * @link
 * @see <a href="https://marlinfw.org/docs/gcode/M104.html">MarlinFirmare M104 doc</a>
 */
@GCode("M104")
public record SetHotendTemperature(
        @GParam("T") int index,
        @GParam("S") double temp
) implements GEncodable {

    public static SetHotendTemperature m104(int index, double temp) {
        return new SetHotendTemperature(index, temp);
    }

    public static SetHotendTemperature m104(double temp) {
        return new SetHotendTemperature(0, temp);
    }

    public SetHotendTemperature(double temp) {
        this(0, temp);
    }

    @Override
    public String encode() {
        return format("M104 T%d S%.2f", index(), temp());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + encode() + ')';
    }
}

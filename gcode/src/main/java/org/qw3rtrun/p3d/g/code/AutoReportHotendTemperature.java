package org.qw3rtrun.p3d.g.code;

import static java.lang.String.format;

/**
 * M155
 *
 * @link
 * @see <a href="https://marlinfw.org/docs/gcode/M155.html">MarlinFirmare M155 doc</a>
 */
public record AutoReportHotendTemperature(int period) implements GCode {

    public static AutoReportHotendTemperature m155(int period) {
        return new AutoReportHotendTemperature(period);
    }

    public static AutoReportHotendTemperature m155() {
        return new AutoReportHotendTemperature(0);
    }

    public AutoReportHotendTemperature() {
        this(0);
    }

    @Override
    public String encode() {
        return format("M155 S%d", period());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + encode() + ')';
    }
}

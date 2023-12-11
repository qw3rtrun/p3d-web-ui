package org.qw3rtrun.p3d.g.code;

import static java.lang.String.format;

/**
 * M105 T<index>
 *
 * @link
 * @see <a href="https://marlinfw.org/docs/gcode/M105.html">MarlinFirmare M105 doc</a>
 */
@GCode("M105")
public record ReportHotendTemperature(@GParam("I") int index) implements GEncodable {

    public static ReportHotendTemperature m105(int index) {
        return new ReportHotendTemperature(index);
    }

    public static ReportHotendTemperature m105() {
        return new ReportHotendTemperature(0);
    }

    public ReportHotendTemperature() {
        this(0);
    }

    @Override
    public String encode() {
        return format("M105 T%d", index());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()+"("+encode()+')';
    }
}

package org.qw3rtrun.p3d.g.code;

import static java.lang.String.format;

/**
 * M115
 *
 * @link
 * @see <a href="https://marlinfw.org/docs/gcode/M115.html">MarlinFirmare M115 FirmwareInfo</a>
 */
@GCode("M115")
public record FirmwareInfo() implements GEncodable {

    public static FirmwareInfo m115() {
        return new FirmwareInfo();
    }

    @Override
    public String encode() {
        return "M115";
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()+"("+encode()+')';
    }
}

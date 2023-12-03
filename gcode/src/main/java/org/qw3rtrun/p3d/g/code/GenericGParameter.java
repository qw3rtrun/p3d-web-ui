package org.qw3rtrun.p3d.g.code;

import org.apache.commons.lang3.math.NumberUtils;

public record GenericGParameter(char name, String raw, Boolean bool, Integer integer, Double number) {

    public static GenericGParameter parse(String line) {
        var name = line.charAt(0);
        if (line.length() == 1) {
            return new GenericGParameter(name, "", true, null, null);
        } else {
            var valStr = line.substring(1);
            if (NumberUtils.isCreatable(valStr)) {
                var number = NumberUtils.createBigDecimal(valStr);
                return new GenericGParameter(name, valStr, false, (number.precision() == 0) ? number.intValue() : null, number.doubleValue());
            } else {
                return new GenericGParameter(name, valStr, null, null, null);
            }
        }
    }

    public String encode() {
        return name + raw;
    }
}

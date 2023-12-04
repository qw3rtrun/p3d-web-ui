package org.qw3rtrun.p3d.g.code;

import org.apache.commons.lang3.math.NumberUtils;

import java.math.BigDecimal;

public record GenericGParameter(String raw, String name, String str, Boolean bool, Integer integer, Double number) {

    public static GenericGParameter parse(String line) {
        var name = String.valueOf(line.charAt(0));
        if (line.length() == 1) {
            return new GenericGParameter(line, name, "", true, null, null);
        } else {
            var valStr = line.substring(1);
            if (NumberUtils.isCreatable(valStr)) {
                var number = NumberUtils.createBigDecimal(valStr);
                return new GenericGParameter(line, name, valStr, number.compareTo(BigDecimal.ONE) >=0, isIntegerValue(number) ? number.intValue() : null, number.doubleValue());
            } else {
                return new GenericGParameter(line, name, valStr, null, null, null);
            }
        }
    }

    private static boolean isIntegerValue(BigDecimal bd) {
        return bd.signum() == 0 || bd.scale() <= 0 || bd.stripTrailingZeros().scale() <= 0;
    }

    public String encode() {
        return name + raw;
    }
}

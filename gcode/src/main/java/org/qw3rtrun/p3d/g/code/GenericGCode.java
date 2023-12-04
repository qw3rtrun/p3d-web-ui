package org.qw3rtrun.p3d.g.code;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

public record GenericGCode(String prefix, String letter, int number, String rawParams,
                           GenericGParameter... parameters) implements GCode {

    public static Optional<GenericGCode> parse(String line) {
        if (line.length() < 2) {
            return Optional.empty();
        }
        int firstSeparator = line.indexOf(' ');
        if (firstSeparator < 0) {
            var code = GenericGParameter.parse(line);
            if (code.integer() != null && (code.name().equals("M") || code.name().equals("G") || code.name().equals("m") || code.name().equals("g"))) {
                return Optional.of(new GenericGCode(code.raw(), code.name(), code.integer(), ""));
            } else {
                return Optional.empty();
            }
        } else {
            var code = GenericGParameter.parse(line.substring(0, firstSeparator));
            if (code.integer() != null && (code.name().equals("M") || code.name().equals("G") || code.name().equals("m") || code.name().equals("g"))) {
                var rawValue = line.substring(firstSeparator).trim();
                String[] split = rawValue.split(" +");
                GenericGParameter[] params = new GenericGParameter[split.length];
                for (int i = 0; i < split.length; i++) {
                    params[i] = GenericGParameter.parse(split[i]);
                }
                return Optional.of(new GenericGCode(code.raw(), code.name(), code.integer(), rawValue, params));
            } else {
                return Optional.empty();
            }
        }
    }

    @Override
    public String encode() {
        return STR. "\{ letter }\{ number } \{ Arrays.stream(parameters).map(GenericGParameter::encode).collect(Collectors.joining(" ")) }" ;
    }
}

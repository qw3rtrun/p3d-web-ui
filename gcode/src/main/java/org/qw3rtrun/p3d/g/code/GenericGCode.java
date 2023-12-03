package org.qw3rtrun.p3d.g.code;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

public record GenericGCode(char prefix, int number, GenericGParameter... parameters) implements GCode {

    public static Optional<GenericGCode> parse(String line) {
        if (line.length() < 2) {
            return Optional.empty();
        }
        int firstSeparator = line.indexOf('_');
        if (firstSeparator < 0) {
            var code = GenericGParameter.parse(line);
            if (code.integer() != null && (code.name() == 'M' || code.name() == 'G' || code.name() == 'm' || code.name() == 'g')) {
                return Optional.of(new GenericGCode(code.name(), code.integer()));
            } else {
                return Optional.empty();
            }
        } else {
            var code = GenericGParameter.parse(line.substring(0, firstSeparator));
            if (code.integer() != null && (code.name() == 'M' || code.name() == 'G' || code.name() == 'm' || code.name() == 'g')) {
                String[] split = line.substring(firstSeparator).split(" *");
                GenericGParameter[] params = new GenericGParameter[split.length];
                for (int i = 0; i < split.length; i++) {
                    params[i] = GenericGParameter.parse(split[i]);
                }
                return Optional.of(new GenericGCode(code.name(), code.integer(), params));
            } else {
                return Optional.empty();
            }
        }
    }

    @Override
    public String encode() {
        return STR. "\{ prefix }\{ number } \{ Arrays.stream(parameters).map(GenericGParameter::encode).collect(Collectors.joining(" ")) }" ;
    }
}

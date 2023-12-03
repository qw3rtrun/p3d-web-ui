package org.qw3rtrun.p3d.g.code.decoder;

import org.apache.commons.lang3.StringUtils;
import org.qw3rtrun.p3d.g.code.GCode;
import org.qw3rtrun.p3d.g.code.GCommand;

import java.util.function.Predicate;

public class CommandDecoder<T extends GCode> implements GCodeDecoder<T> {

//    private final Predicate<String> checkCommandPrefix;
//
//    private final int commandPrefixLength;

    public static <T extends GCode> CommandDecoder<T> build(Class<T> type, GCommand annotation) {
        var cmdPrefix = annotation.value().length();
        Predicate<String> isApplicable = buildCommandPrefixCheck(type, annotation);
        return null;
    }

    private static <T extends GCode> Predicate<String> buildCommandPrefixCheck(Class<T> type, GCommand annotation) {
        return line -> StringUtils.startsWithIgnoreCase(line, annotation.value());
    }

    @Override
    public boolean isApplicable(String line) {
        return applicablePredicate.test(line);
    }

    @Override
    public T decode(String line) {
        return null;
    }
}

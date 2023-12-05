package org.qw3rtrun.p3d.g.code.core;

import org.apache.commons.lang3.math.NumberUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.min;

public class GCoreCodec {

    public String encode(GField field) {
        return field.letter() + field.rawValue();
    }

    public String encode(GField... fields) {
        return Arrays.stream(fields).map(this::encode).collect(Collectors.joining(" "));
    }

    public String encode(Collection<? extends GField> fields) {
        return fields.stream().map(this::encode).collect(Collectors.joining(" "));
    }

    public Stream<String> splitFields(String line) {
        return Stream.generate(new Supplier<String>() {
            private char[] chars = line.toCharArray();
            private int pointer = 0;

            @Override
            public String get() {
                if (pointer >= chars.length) {
                    return null;
                }
                for (; pointer < chars.length && chars[pointer] == ' '; pointer++) ;
                if (pointer >= chars.length) {
                    return null;
                }
                int start = pointer;
                for (; pointer < chars.length && chars[pointer] != '"' && chars[pointer] != ' '; pointer++) ;
                if (pointer >= chars.length) {
                    return new String(chars, start, chars.length - start);
                }
                if (chars[pointer] == ' ') {
                    return new String(chars, start, pointer - start);
                }
                pointer++;
                for (; pointer < chars.length &&
                        (!(chars[pointer] == '"' && (pointer == chars.length - 1 || chars[pointer + 1] != '"')) ||
                        !(chars[pointer] == '"' && chars[pointer - 1] != '"')); pointer++) ;
                pointer++;
                return new String(chars, start, min(pointer, chars.length) - start);
            }
        }).takeWhile(Objects::nonNull);
    }

    public List<GField> decodeLine(String line) {
        return splitFields(line).map(this::decodeField).collect(Collectors.toList());
    }

    public GField decodeField(String raw) {
        return switch (raw.substring(1)) {
            case String s when s.isEmpty() -> new GFlagField(raw.charAt(0));
            case String s when !NumberUtils.isCreatable(s) -> new GStringField(raw.charAt(0), s);
            case String s -> switch (NumberUtils.createNumber(s)) {
                case Integer i -> new GIntField(raw.charAt(0), i);
                case BigDecimal bd -> new GDoubleField(raw.charAt(0), bd);
                case Number n -> new GDoubleField(raw.charAt(0), BigDecimal.valueOf(n.doubleValue()));
            };
        };
    }
}

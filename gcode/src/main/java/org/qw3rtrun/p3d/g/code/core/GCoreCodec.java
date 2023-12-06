package org.qw3rtrun.p3d.g.code.core;

import org.apache.commons.lang3.StringUtils;
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

    public static final String SPECIALCHARS = ":;\"";

    public String encode(GElement... fields) {
        return Arrays.stream(fields).map(this::encodeElement).collect(Collectors.joining(" "));
    }

    public String encode(Collection<? extends GElement> fields) {
        return fields.stream().map(this::encodeElement).collect(Collectors.joining(" "));
    }

    public String encodeElement(GElement field) {
        return switch (field) {
            case GLiteral str -> encodeString(str.string());
            case GStrField sf -> encodeString(sf.asString());
            default -> field.asString();
        };
    }

    public List<GElement> decode(String line) {
        return splitFields(line).map(this::decodeElement).collect(Collectors.toList());
    }

    private GElement decodeElement(String raw) {
        return switch (raw.substring(1)) {
            case String val when raw.startsWith(";") -> new GComment(val);
            case String val when raw.startsWith("\"") -> new GLiteral(decodeString(raw));
            case String val when val.isEmpty() -> new GFlagField(raw.charAt(0));
            case String val when !NumberUtils.isCreatable(val) -> new GStrField(raw.charAt(0), decodeString(val));
            case String val -> switch (NumberUtils.createNumber(val)) {
                case Integer i -> new GIntField(raw.charAt(0), i);
                case BigDecimal bd -> new GDoubleField(raw.charAt(0), bd);
                case Number n -> new GDoubleField(raw.charAt(0), BigDecimal.valueOf(n.doubleValue()));
            };
        };
    }

    private String decodeString(String str) {
        if (str.startsWith("\"")) {
            if (str.length() < 2 || str.charAt(str.length() - 1) != '"') {
                throw new GCodeSyntaxException(str, "Quoted String's end-quote not found");
            }
        }
        if (str.startsWith("\"") && str.length() > 1 && str.charAt(str.length() - 1) == '"') {
            return StringUtils.replace(str.substring(1, str.length() - 1), "\"\"", "\"");
        }
        return str;
    }

    private String encodeString(String str) {
        if (StringUtils.containsAny(str, SPECIALCHARS)) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
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
                if (chars[pointer] == ';') {
                    pointer = chars.length;
                    return new String(chars, start, pointer - start);
                }
                for (; pointer < chars.length && chars[pointer] != '"' && chars[pointer] != ' ' && chars[pointer] != ';'; pointer++)
                    ;
                if (pointer >= chars.length) {
                    return new String(chars, start, chars.length - start);
                }
                if (chars[pointer] == ' ' || chars[pointer] == ';') {
                    return new String(chars, start, pointer - start);
                }
                pointer++;
                for (; pointer < chars.length &&
                        (!(chars[pointer] == '"' && (pointer == chars.length - 1 || chars[pointer + 1] != '"')) ||
                                !(chars[pointer] == '"' && chars[pointer - 1] != '"')); pointer++)
                    ;
                pointer++;
                return new String(chars, start, min(pointer, chars.length) - start);
            }
        }).takeWhile(Objects::nonNull);
    }


}

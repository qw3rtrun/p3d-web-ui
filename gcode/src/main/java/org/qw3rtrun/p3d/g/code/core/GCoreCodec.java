package org.qw3rtrun.p3d.g.code.core;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class GCoreCodec implements GCodec {

    public static final String SPECIALCHARS = ":;\"";

    @Override
    public String encode(GElement... fields) {
        return Arrays.stream(fields).map(this::encodeElement).collect(Collectors.joining(" "));
    }

    @Override
    public String encode(List<? extends GElement> fields) {
        return fields.stream().map(this::encodeElement).collect(Collectors.joining(" "));
    }

    @Override
    public List<GElement> decode(String line) {
        return new GCoreParser(line).parse();
    }

    private String encodeElement(GElement field) {
        return switch (field) {
            case GLiteral str -> encodeString(str.string());
            case GStrField sf -> encodeString(sf.asString());
            default -> field.asString();
        };
    }

    private String encodeString(String str) {
        if (StringUtils.containsAny(str, SPECIALCHARS)) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }
}

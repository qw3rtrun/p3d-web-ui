package org.qw3rtrun.p3d.g.code.core;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GCoreEncoder {

    public static final String SPECIALCHARS = ":;\"";

    private final CheckSum checksum;

    public String encode(@NonNull List<? extends GElement> fields) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("GCode fields in empty");
        }
        if (fields.get(0) instanceof GIntField i && i.letter() == 'N') {

        }
        return fields.stream().map(this::encodeElement).collect(Collectors.joining(" "));
    }

    private String encodeElement(GElement field) {
        return switch (field) {
            case GLiteral str -> encodeString(str.string());
            case GStrField sf -> sf.letter() + encodeString(sf.rawValue());
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

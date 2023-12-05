package org.qw3rtrun.p3d.g.code.core;

import org.apache.commons.lang3.StringUtils;

public record GStringField(char letter, String str) implements GField {

    public static final String SPECIALCHARS = ":;\"";

    public GStringField(char letter, String str) {
        if (str.contains("\n") || str.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
        this.str = decode(str);
        this.letter = letter;
    }

    private static String decode(String str) {
        if (str.startsWith("\"") && str.length() > 1 && str.charAt(str.length() - 1) == '"') {
            return StringUtils.replace(str.substring(1, str.length() - 1), "\"\"", "\"");
        }
        return str;
    }

    @Override
    public String rawValue() {
        return encode();
    }

    private String encode() {
        if (StringUtils.containsAny(str, SPECIALCHARS)) {
            return "\"" + str.replace("\"", "\"\"");
        }
        return str;
    }

    public String value() {
        return str;
    }
}

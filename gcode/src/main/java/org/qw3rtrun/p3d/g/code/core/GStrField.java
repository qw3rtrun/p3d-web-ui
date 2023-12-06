package org.qw3rtrun.p3d.g.code.core;

import org.apache.commons.lang3.StringUtils;

public record GStrField(char letter, String value) implements GField {

    public GStrField(char letter, String value) {
        if (value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
        this.value = value;
        this.letter = letter;
    }

    @Override
    public String rawValue() {
        return value;
    }

    @Override
    public String toString() {
        return "GStr[" + asString() + ']';
    }
}

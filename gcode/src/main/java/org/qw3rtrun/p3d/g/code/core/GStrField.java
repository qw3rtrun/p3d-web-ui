package org.qw3rtrun.p3d.g.code.core;

public record GStrField(char letter, String value) implements GNamedField {

    public GStrField {
        if (value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
    }

    public GStrField(String raw) {
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("GString can't be empty");
        }
        if (raw.contains("\n") || raw.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }

        this(raw.charAt(0), raw.substring(1));
    }

    @Override
    public String rawValue() {
        return value;
    }

    @Override
    public String toString() {
        return STR."\{letter}(\"\{rawValue()}\")";
    }
}

package org.qw3rtrun.p3d.g.code.core;

public record GStrField(char letter, String value) implements GField {

    public GStrField {
        if (value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
    }

    @Override
    public String rawValue() {
        return value;
    }

    @Override
    public String toString() {
        return STR. "\{letter}(\"\{rawValue()}\")";
    }
}

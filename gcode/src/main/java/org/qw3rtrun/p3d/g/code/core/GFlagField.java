package org.qw3rtrun.p3d.g.code.core;

public record GFlagField(char letter) implements GNamedField {

    @Override
    public String rawValue() {
        return "";
    }

    @Override
    public String toString() {
        return letter + "()";
    }
}

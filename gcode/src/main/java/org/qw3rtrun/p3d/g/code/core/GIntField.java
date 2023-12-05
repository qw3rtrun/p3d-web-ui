package org.qw3rtrun.p3d.g.code.core;

public record GIntField(char letter, int value) implements GField {

    @Override
    public String rawValue() {
        return String.valueOf(value);
    }
}

package org.qw3rtrun.p3d.g.code.core;

public record GIntField(char letter, int value) implements GField {

    @Override
    public String rawValue() {
        return String.valueOf(value);
    }

    public boolean isN() {
        return is('N');
    }

    public boolean isG() {
        return is('G');
    }

    public boolean isM() {
        return is('M');
    }

    @Override
    public String toString() {
        return STR. "\{letter}(\{rawValue()})";
    }

}

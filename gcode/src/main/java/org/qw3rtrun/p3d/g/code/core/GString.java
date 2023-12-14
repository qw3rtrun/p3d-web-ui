package org.qw3rtrun.p3d.g.code.core;

public record GString(String string) implements GLiteral {
    public GString(String string) {
        this.string = string;
        if (this.string().isEmpty()) {
            throw new IllegalArgumentException("GString can't be empty");
        }
        if (this.string().contains("\n") || string.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
    }

    @Override
    public String asString() {
        return string;
    }

    @Override
    public String toString() {
        return "GLiteral[" + string + ']';
    }
}

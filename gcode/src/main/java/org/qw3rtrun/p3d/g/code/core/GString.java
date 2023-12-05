package org.qw3rtrun.p3d.g.code.core;

public record GString(String string) implements GField {
    public GString {
        if (this.string().isEmpty()) {
            throw new IllegalArgumentException("GString can't be empty");
        }
        if (this.string().contains("\n") || string.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
    }

    @Override
    public char letter() {
        return string.charAt(1);
    }

    @Override
    public String rawValue() {
        return string.substring(1);
    }

    @Override
    public GString asGString() {
        return this;
    }
}

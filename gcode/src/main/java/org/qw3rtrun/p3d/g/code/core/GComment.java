package org.qw3rtrun.p3d.g.code.core;

public record GComment(String text) implements GElement {
    @Override
    public String asString() {
        return STR.";\{text}";
    }

    @Override
    public String toString() {
        return "GComment[" + asString() + ']';
    }

}

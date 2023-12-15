package org.qw3rtrun.p3d.g.code.core;

public record GComment(String text) implements GElement {

    public static GComment from(String raw) {
        return new GComment(raw.substring(1));
    }

    @Override
    public String asString() {
        return STR. ";\{ text }" ;
    }

    @Override
    public String toString() {
        return STR."COM(\"\{text}\")";
    }

}

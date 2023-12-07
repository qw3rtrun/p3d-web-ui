package org.qw3rtrun.p3d.g.code.core;

public record GChecksum(int sum) implements GElement {
    @Override
    public String asString() {
        return STR."*\{sum}";
    }

    @Override
    public String toString() {
        return "GComment[" + asString() + ']';
    }

}

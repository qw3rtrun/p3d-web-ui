package org.qw3rtrun.p3d.g.code.core;

public sealed interface GField extends GElement permits GIntField, GDoubleField, GStrField, GFlagField {

    char letter();

    String rawValue();

    default String asString() {
        return letter() + rawValue();
    }
}


package org.qw3rtrun.p3d.g.code.core;

public sealed interface GField extends GElement permits GNamedField, GUnnamedField {

    String asString();

}


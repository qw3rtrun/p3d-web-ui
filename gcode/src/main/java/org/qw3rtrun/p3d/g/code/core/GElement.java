package org.qw3rtrun.p3d.g.code.core;

public sealed interface GElement permits GLiteral, GComment, GField {

    String asString();
}

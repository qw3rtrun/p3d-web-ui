package org.qw3rtrun.p3d.g.code.core;

import java.math.BigDecimal;

public record GDoubleField(char letter, BigDecimal value) implements GField {

    @Override
    public String rawValue() {
        return value.toString();
    }

    @Override
    public String toString() {
        return "GDouble[" + asString() + ']';
    }
}

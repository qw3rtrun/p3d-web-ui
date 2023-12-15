package org.qw3rtrun.p3d.g.code.core;

import org.apache.commons.lang3.math.NumberUtils;

import java.math.BigDecimal;

public sealed interface GField extends GElement permits GDoubleField, GFlagField, GIntField, GQuoteField, GStrField {

    char letter();

    default boolean is(char ch) {
        return ch == letter() || Character.toUpperCase(ch) == letter();
    }

    String rawValue();

    default String asString() {
        return letter() + rawValue();
    }

    static GField from(String raw) {
        return switch (raw.substring(1)) {
            case String val when val.isEmpty() -> new GFlagField(raw.charAt(0));
            case String val when !NumberUtils.isCreatable(val) -> new GStrField(raw.charAt(0), val);
            case String val -> switch (NumberUtils.createNumber(val)) {
                case Integer i -> new GIntField(raw.charAt(0), i);
                default -> new GDoubleField(raw.charAt(0), new BigDecimal(val));
            };
        };
    }
}


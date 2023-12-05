package org.qw3rtrun.p3d.g.code.core;

import org.apache.commons.lang3.math.NumberUtils;

import java.math.BigDecimal;
public interface GField {

    char letter();
    String rawValue();

    default GString asGString() {
        return new GString(letter()+rawValue());
    }
}


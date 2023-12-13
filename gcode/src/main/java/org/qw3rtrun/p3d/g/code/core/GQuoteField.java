package org.qw3rtrun.p3d.g.code.core;

import lombok.NonNull;

public record GQuoteField(char letter, @NonNull String value) implements GField {

    public GQuoteField(char letter, @NonNull String value) {
        if (value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
        this.value = value;
        this.letter = letter;
    }

    public static GQuoteField from(@NonNull String raw) {
        return new GQuoteField(raw.charAt(0), QuoteUtils.unquote(raw.substring(1)));
    }

    @Override
    public String rawValue() {
        return QuoteUtils.quote(value);
    }

    @Override
    public String toString() {
        return "GQuoteField[" + asString() + ']';
    }
}

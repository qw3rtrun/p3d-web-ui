package org.qw3rtrun.p3d.g.code.core;

public record GQuote(String string) implements GElement {

    public GQuote(String string) {
        this.string = string;
        if (this.string().isEmpty()) {
            throw new IllegalArgumentException(getClass().getSimpleName() + " can't be empty");
        }
        if (this.string().contains("\n") || string.contains("\r")) {
            throw new IllegalArgumentException(getClass().getSimpleName() + " can't have more then a line");
        }
    }

    public static GQuote from(String raw) {
        return new GQuote(QuoteUtils.unquote(raw));
    }

    @Override
    public String asString() {
        return QuoteUtils.quote(string);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "[" + string + ']';
    }


}

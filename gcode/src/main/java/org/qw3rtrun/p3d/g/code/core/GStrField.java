package org.qw3rtrun.p3d.g.code.core;

public record GStrField(char letter, String value) implements GNamedField {

    public GStrField {
        if (value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
    }

    public GStrField(String raw) {
        this(extractLetter(raw), extractValue(raw));
    }

    @Override
    public String rawValue() {
        return value;
    }

    @Override
    public String toString() {
        return letter + "(\"" + rawValue() + "\")";
    }

    private static char extractLetter(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("GString can't be empty");
        }
        if (raw.contains("\n") || raw.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
        return raw.charAt(0);
    }

    private static String extractValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("GString can't be empty");
        }
        if (raw.contains("\n") || raw.contains("\r")) {
            throw new IllegalArgumentException("GString can't have more then a line");
        }
        return raw.substring(1);
    }
}

package org.qw3rtrun.p3d.g.code.core;

import lombok.NonNull;

public interface CheckSum {
    default void add(@NonNull CharSequence chars) {
        for (int i = 0; i < chars.length(); i++) {
            add(chars.charAt(i));
        }
    }

    void add(char ch);

    int get();

    default String getString() {
        return String.valueOf(get());
    }
}

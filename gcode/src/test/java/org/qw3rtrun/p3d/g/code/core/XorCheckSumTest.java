package org.qw3rtrun.p3d.g.code.core;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XorCheckSumTest {

    private final XorCheckSum checkSum = new XorCheckSum();

    @ParameterizedTest
    @ValueSource(strings = {
            "N0 M115*38",
            "N1 M155 S1*97",
            "N2 M117 Hello World!*7"
    })

    void checksum(String gcode) {
        var data = split(gcode);
        checkSum.add(data.getKey());
        assertEquals(data.getValue(), checkSum.getString());
    }

    private Pair<String, String> split(String str) {
        var split = str.split("[*]");
        return Pair.of(split[0], split[1]);
    }
}

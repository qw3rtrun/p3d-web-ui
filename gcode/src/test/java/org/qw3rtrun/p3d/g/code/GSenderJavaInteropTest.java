package org.qw3rtrun.p3d.g.code;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Written in Java on purpose: it is the shape {@code G.java}'s callers use, and the point of this
 * test is that they can move to {@link GSender} without changing it.
 *
 * {@code GFlux} does {@code new G(this::onG)}, which does not compile against a Kotlin
 * {@code (String) -> Unit} because {@code void} is not {@code Unit}. The {@code Consumer} overload
 * is what makes the swap mechanical.
 */
class GSenderJavaInteropTest {

    private final List<String> sent = new ArrayList<>();

    private void onG(String gcode) {
        sent.add(gcode);
    }

    @Test
    void aJavaMethodReferenceIsAValidSink() {
        GSender g = new GSender(this::onG);

        g.firmwareInfo();
        g.autoReportTemp(1);
        g.setBedTemperature(new BigDecimal("60"));

        assertEquals(List.of("M115", "M155 S1", "M140 S60"), sent);
    }
}

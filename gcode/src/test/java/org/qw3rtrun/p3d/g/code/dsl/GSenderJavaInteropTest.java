package org.qw3rtrun.p3d.g.code.dsl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Written in Java on purpose: this is the shape the {@code G.java} facade's callers used, and this
 * test is what let them move to {@link GSender} unchanged. They have now all moved and {@code G}
 * is deleted, so this is the only place left that holds the Java-facing contract.
 *
 * <p>{@code new GSender(this::onG)} does not compile against a Kotlin {@code (String) -> Unit},
 * because {@code void} is not {@code Unit}; the {@code Consumer} overload is what makes it work.
 *
 * <p>It has to be a <b>method reference</b>, and that is the second half of the contract. With two
 * constructors — {@code Consumer<String>} and {@code Function1<String, Unit>} — an implicitly typed
 * lambda whose body is an expression is potentially compatible with both, and
 * {@code new GSender(str -> queue.add(str))} fails with "reference to GSender is ambiguous". An
 * exact {@code void} method reference resolves it, which is why {@code GFlux} and
 * {@code PrinterReactor} both use one.
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

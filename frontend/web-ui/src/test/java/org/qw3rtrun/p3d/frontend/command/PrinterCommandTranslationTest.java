package org.qw3rtrun.p3d.frontend.command;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.code.core.GEncoder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What a request body becomes on the wire.
 *
 * <p>This is the one translation left in the application, and it lives here because a request body
 * is this module's business: {@code api} used to carry a command record for every one of these and
 * {@code core} a switch that turned each into Marlin's, which is a mirror of the protocol
 * maintained by hand. The runtime now takes {@code GRq} directly, so what is asserted here is the
 * whole of the mapping rather than its first half.
 *
 * <p>The mapping is asserted as <strong>the G-code that comes out</strong> rather than as the
 * command object, because the bytes are what a printer sees and the object is an implementation
 * detail of getting there. The other half - that the aggregate actually sends what it is handed -
 * is {@code PrinterStateMotionTest} in {@code :backend:core}, on the far side of
 * {@code PrinterControl}.
 */
class PrinterCommandTranslationTest {

    private static List<String> wire(PrinterCommand command) {
        return command.toMarlin().stream()
                .map(c -> GEncoder.INSTANCE.encode(c.encode()))
                .toList();
    }

    /** One command, for the requests that are only ever one. */
    private static String one(PrinterCommand command) {
        var lines = wire(command);
        assertEquals(1, lines.size(), () -> command + " became " + lines);
        return lines.getFirst();
    }

    // ---------------------------------------------------------- temperatures

    @Test
    void aHotendTargetBecomesM104WithItsTool() {
        assertEquals("M104 S60.0 T0", one(new SetHotendTarget(0, new BigDecimal("60.0"))));
        assertEquals("M104 S210.5 T1", one(new SetHotendTarget(1, new BigDecimal("210.5"))));
    }

    /**
     * No tool means the first one.
     *
     * <p>The browser sends {@code {index, temp}} and has always been able to leave {@code index}
     * out. Defaulting here rather than leaving a null to travel keeps the aggregate's
     * extruder-count check meaningful.
     */
    @Test
    void aHotendTargetWithNoToolMeansTheFirst() {
        assertEquals("M104 S60.0 T0", one(new SetHotendTarget(null, new BigDecimal("60.0"))));
    }

    @Test
    void aBedTargetBecomesM140() {
        assertEquals("M140 S60.0", one(new SetBedTarget(new BigDecimal("60.0"))));
    }

    @Test
    void aTemperatureRequestBecomesM105() {
        assertEquals("M105", one(new RequestTemperatureReport(null)));
        assertEquals("M105 T2", one(new RequestTemperatureReport(2)));
    }

    /**
     * Auto-reporting becomes {@code M155}, carrying the period the user actually chose.
     *
     * <p>It did not, before these records existed. The browser posts {@code {period}}, the endpoint
     * bound `:gcode`'s generated class whose property is {@code seconds}, so the field matched
     * nothing and every request silently asked for the firmware's default. A request body that is
     * the browser's shape rather than Marlin's is what fixes it - and is the reason this package
     * survived the rest of the mirror being deleted.
     */
    @Test
    void autoReportingCarriesThePeriod() {
        assertEquals("M155 S1", one(new SetTemperatureAutoReport(1)));
        assertEquals("M155 S0", one(new SetTemperatureAutoReport(0)));
        assertEquals("M155", one(new SetTemperatureAutoReport(null)));
    }

    // ---------------------------------------------------------------- homing

    /** A bare {@code G28} is Marlin's "home everything", and it is what naming no axis means. */
    @Test
    void homingNothingInParticularHomesEverything() {
        assertEquals("G28", one(new HomeAxes(null, null, null)));
    }

    @Test
    void homingNamedAxesSendsOnlyThose() {
        assertEquals("G28 X Z", one(new HomeAxes(true, false, true)));
    }

    /**
     * An axis is a flag, not a value.
     *
     * <p>{@code G28 X0} is a legal line that means something subtly different in some firmwares,
     * and the encoder emitting a value here would be the kind of difference nothing notices until
     * a machine homes the wrong way.
     */
    @Test
    void anAxisIsAFlagWithNoValue() {
        var line = one(new HomeAxes(true, true, true));
        assertEquals("G28 X Y Z", line);
        assertFalse(line.contains("X0"), line);
    }

    // ---------------------------------------------------------------- moving

    @Test
    void anAbsoluteMoveIsOneCommand() {
        assertEquals("G1 X10 Y20 F3000",
                one(new MoveAxes(new BigDecimal("10"), new BigDecimal("20"), null, null,
                        new BigDecimal("3000"), false)));
    }

    /**
     * A relative move is three commands, and the third is the one that matters.
     *
     * <p>{@code G91} is <strong>modal</strong>: the machine stays in relative mode until something
     * says otherwise. Forgetting the {@code G90} would leave every later absolute move - including
     * a print's - interpreted as an offset from wherever the toolhead happened to be.
     */
    @Test
    void aRelativeMoveRestoresAbsoluteModeAfterwards() {
        assertEquals(
                List.of("G91", "G1 X10", "G90"),
                wire(new MoveAxes(new BigDecimal("10"), null, null, null, null, true)));
    }

    @Test
    void aMoveCarriesTheExtruderAndTheFeedrate() {
        assertEquals("G1 Z0.2 E5 F1200",
                one(new MoveAxes(null, null, new BigDecimal("0.2"), new BigDecimal("5"),
                        new BigDecimal("1200"), false)));
    }

    // --------------------------------------------------------------- the fan

    @Test
    void aFanSpeedIsSentOnTheMarlinPwmScale() {
        assertEquals("M106 S128", one(new SetFanSpeed(null, 128)));
    }

    /**
     * The fan index travels in {@code P}.
     *
     * <p>Marlin's documentation lists both {@code I} and {@code P} as "index" and the generated
     * command class carries both, but {@code P} is the one mainline firmware reads as the fan
     * number - and it is what {@code M107} uses, so the pair stays consistent.
     */
    @Test
    void aNamedFanTravelsInP() {
        assertEquals("M106 S200 P1", one(new SetFanSpeed(1, 200)));
    }

    /** Zero is {@code M107}, the documented "off", rather than {@code M106 S0}. */
    @Test
    void zeroSpeedTurnsTheFanOff() {
        assertEquals("M107", one(new SetFanSpeed(null, 0)));
        assertEquals("M107", one(new SetFanSpeed(null, null)));
    }

    // ------------------------------------------------- settings and position

    @Test
    void storingSettingsIsM500() {
        assertEquals("M500", one(new StoreSettings()));
    }

    @Test
    void askingWhereTheToolheadIsIsM114() {
        assertEquals("M114", one(new RequestPosition()));
    }
}

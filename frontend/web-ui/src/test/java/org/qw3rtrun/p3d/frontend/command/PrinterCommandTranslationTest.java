package org.qw3rtrun.p3d.frontend.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qw3rtrun.p3d.g.code.core.GEncoder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    // ------------------------------------------------- the card

    @Test
    void mountingAndReleasingTheCardAreM21AndM22() {
        assertEquals("M21", one(new MountCard()));
        assertEquals("M22", one(new ReleaseCard()));
    }

    /**
     * The bare {@code M20}, without the flags for long names or timestamps.
     *
     * <p>{@code Cap:LONG_FILENAME:0} on this firmware, so {@code L} asks for something it cannot
     * give - and a flag one machine ignores and another honours is two different answers to one
     * request.
     */
    @Test
    void listingTheCardIsABareM20() {
        assertEquals("M20", one(new ListFiles()));
    }

    @Test
    void deletingAFileIsM30WithItsName() {
        assertEquals("M30 BENCHY.GCO", one(new DeleteFile("BENCHY.GCO")));
    }

    /**
     * Printing is two commands, and that is a gap rather than a preference.
     *
     * <p>{@code M32} would do it in one, but {@code :gcode}'s {@code SelectAndStart} is generated
     * from a documented signature that omits the filename, so it has nowhere to put one. The pair
     * is sent as a unit by {@code PrinterControl.send}, so nothing lands between them.
     */
    @Test
    void printingAFileIsSelectThenStart() {
        assertEquals(List.of("M23 BENCHY.GCO", "M24"), wire(new PrintFile("BENCHY.GCO")));
    }

    /**
     * A name is trimmed, because a trailing blank would reach the card as part of the name.
     *
     * <p>The firmware takes everything to the end of the line, so {@code "M30 a.gco "} and
     * {@code "M30 a.gco"} are not the same request - and a browser form hands over whatever was
     * typed.
     */
    @Test
    void aNameIsTrimmedBeforeItTravels() {
        assertEquals("M30 A.GCO", one(new DeleteFile("  A.GCO  ")));
    }

    /**
     * A filename that would change what the block means is refused.
     *
     * <p><strong>The newline is the one that matters.</strong> A filename is a bare rest-of-line
     * string, the transport frames what it is handed without parsing it, so {@code "a.gco\nM112"}
     * is not a file to delete - it is a delete <em>and</em> an emergency stop, with the checksum on
     * the wrong side of the break. The others are quieter: {@code ;} opens a comment that swallows
     * the checksum, and {@code *} is the checksum delimiter itself.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "a.gco\nM112",
            "a.gco\r\nM112",
            "a.gco;M112",
            "a.gco*42",
            "café.gco",
            "",
            "   ",
    })
    void aFilenameThatWouldChangeTheBlockIsRefused(String name) {
        assertThrows(IllegalArgumentException.class, () -> new DeleteFile(name));
        assertThrows(IllegalArgumentException.class, () -> new PrintFile(name));
    }

    @Test
    void aMissingFilenameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new DeleteFile(null));
    }

    /**
     * A name too long for a G-code block is refused rather than silently truncated.
     *
     * <p>Spec 1.3: Marlin's line buffer is 96 bytes including the {@code N} word and the checksum,
     * and a line past it is <em>truncated</em> and executed - so an over-long name would delete or
     * print a different file than the one asked for. The budget is taken at a six-digit line
     * number, which an upload reaches on its own, so a name is not accepted early in a session and
     * refused later in it.
     */
    @Test
    void aNameTooLongForABlockIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new DeleteFile("x".repeat(200)));
        // and one that fits is not
        assertEquals("M30 " + "x".repeat(60), one(new DeleteFile("x".repeat(60))));
    }
}

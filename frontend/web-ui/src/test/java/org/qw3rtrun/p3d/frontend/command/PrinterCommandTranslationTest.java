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
 * <p>The one translation left in the application: {@code api} used to carry a command record for
 * each of these with a translating switch in {@code core}, a hand-maintained mirror of the protocol.
 * The runtime now takes {@code GRq} directly, so this is the whole of the mapping, asserted as the
 * G-code that comes out rather than the command object - the bytes are what a printer sees. The
 * other half, that the aggregate sends what it is handed, is {@code PrinterStateMotionTest} in
 * {@code :backend:core}.
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

    // The browser can leave index out; defaulting here rather than letting a null travel keeps the
    // aggregate's extruder-count check meaningful.
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

    // It did not, before these records existed: the endpoint bound `:gcode`'s generated class
    // whose property is `seconds`, so `period` matched nothing and every request silently asked
    // for the firmware's default - the reason this package survived the rest of the mirror deleted.
    @Test
    void autoReportingCarriesThePeriod() {
        assertEquals("M155 S1", one(new SetTemperatureAutoReport(1)));
        assertEquals("M155 S0", one(new SetTemperatureAutoReport(0)));
        assertEquals("M155", one(new SetTemperatureAutoReport(null)));
    }

    // ---------------------------------------------------------------- homing

    // A bare G28 is Marlin's "home everything", what naming no axis means.
    @Test
    void homingNothingInParticularHomesEverything() {
        assertEquals("G28", one(new HomeAxes(null, null, null)));
    }

    @Test
    void homingNamedAxesSendsOnlyThose() {
        assertEquals("G28 X Z", one(new HomeAxes(true, false, true)));
    }

    // G28 X0 is a legal line meaning something subtly different in some firmwares - an axis is a
    // flag here, not a value, and emitting one would go unnoticed until a machine homed the wrong way.
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

    // G91 is modal - the machine stays in relative mode until told otherwise - so forgetting the
    // trailing G90 would leave every later absolute move, including a print's, offset from wherever
    // the toolhead happened to be.
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

    // Marlin's docs list both I and P as "index" and the generated class carries both, but P is
    // what mainline firmware reads as the fan number, matching M107.
    @Test
    void aNamedFanTravelsInP() {
        assertEquals("M106 S200 P1", one(new SetFanSpeed(1, 200)));
    }

    // Zero is M107, the documented "off", rather than M106 S0.
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

    // M503 is asserted bare, not with S or C: S0 drops the section headings the Settings tab groups
    // by, and C compares against compiled defaults instead of what is live - a flag slipped in here
    // would be invisible on this side and show up as an ungrouped tab.
    @Test
    void theFourSettingsOperationsAreBareMCodes() {
        assertEquals("M503", one(new ReadSettings()));
        assertEquals("M501", one(new RevertSettings()));
        assertEquals("M502", one(new ResetSettingsToDefaults()));
        assertEquals("M504", one(new ValidateSettings()));
    }

    // ------------------------------------------------- changing a setting

    // Every assertion below is the exact wire text, because the generated classes' parameter names
    // name a different letter than they write: SetAxisStepsPerUnit's first is `steps` and writes X,
    // SetStartingAcceleration's is `accel` and writes P, SetHomeOffsets' is `offset` and writes P
    // rather than X - only the string would catch M206 P-0.1 (select a tool) replacing M206 Z-0.1
    // (lower a nozzle).

    @Test
    void stepsPerUnitIsM92WithTheAxesItWasGiven() {
        assertEquals("M92 X80 Y80 Z400 E500",
                one(new SetStepsPerUnit(new BigDecimal("80"), new BigDecimal("80"),
                        new BigDecimal("400"), new BigDecimal("500"))));
    }

    // Not zero: an axis with no steps per millimetre cannot move, so "leave it alone" and "set it
    // to nothing" must not be the same request - the same rule holds for every setter in this group.
    @Test
    void anUnnamedAxisIsLeftOutRatherThanZeroed() {
        var line = one(new SetStepsPerUnit(new BigDecimal("81"), null, null, null));
        assertEquals("M92 X81", line);
        assertFalse(line.contains("Y"), line);
        assertFalse(line.contains("E0"), line);
    }

    @Test
    void maxFeedratesAreM203() {
        assertEquals("M203 X300 Y300 Z5 E25",
                one(new SetMaxFeedrates(new BigDecimal("300"), new BigDecimal("300"),
                        new BigDecimal("5"), new BigDecimal("25"))));
        assertEquals("M203 X250", one(new SetMaxFeedrates(new BigDecimal("250"), null, null, null)));
    }

    // M201, whose first generated parameter is called `accel` and writes X.
    @Test
    void maxAccelerationsAreM201AndTheFirstWordIsX() {
        assertEquals("M201 X2500 Y2500 Z100 E5000",
                one(new SetMaxAccelerations(new BigDecimal("2500"), new BigDecimal("2500"),
                        new BigDecimal("100"), new BigDecimal("5000"))));
        assertEquals("M201 X2500",
                one(new SetMaxAccelerations(new BigDecimal("2500"), null, null, null)));
    }

    // The generated class also carries S, a legacy spelling of print acceleration that mainline
    // Marlin still reads; it stays out, since two words for one setting is two answers to one
    // question, and the assertion that S is absent is what keeps it out.
    @Test
    void accelerationsAreM204InPrintRetractTravelOrder() {
        var line = one(new SetAccelerations(new BigDecimal("2000"), new BigDecimal("3000"),
                new BigDecimal("4000")));
        assertEquals("M204 P2000 R3000 T4000", line);
        assertFalse(line.contains("S"), "the legacy S spelling must not be sent beside P: " + line);

        assertEquals("M204 P2000",
                one(new SetAccelerations(new BigDecimal("2000"), null, null)));
    }

    // B is an integer, the rest decimals (the firmware's own split). J (junction deviation) writes
    // from a generated parameter called `deviation`; the four jerk parameters ahead of it (first
    // called `jerk`, writing X) are left null, since a junction-deviation build ignores a jerk word
    // while appearing to take it.
    @Test
    void advancedMotionIsM205WithNoJerkWords() {
        var line = one(new SetAdvancedMotion(20000, new BigDecimal("0"), new BigDecimal("0"),
                new BigDecimal("0.013")));
        assertEquals("M205 B20000 S0 T0 J0.013", line);
        assertFalse(line.contains("X"), "a jerk word would be ignored and look applied: " + line);

        assertEquals("M205 J0.02",
                one(new SetAdvancedMotion(null, null, null, new BigDecimal("0.02"))));
    }

    // Real damage would follow from the generated name here: SetHomeOffsets' first parameter is
    // called `offset` and writes P, the tool index on a per-tool-offset build - binding a Z offset
    // to it would send M206 P-0.1, selecting a tool rather than lowering a nozzle.
    @Test
    void aHomeOffsetIsM206AndNeverCarriesP() {
        var line = one(new SetHomeOffset(null, null, new BigDecimal("-0.1")));
        assertEquals("M206 Z-0.1", line);
        assertFalse(line.contains("P"), "P is the tool index, not an offset: " + line);

        assertEquals("M206 X0 Y0 Z-0.1",
                one(new SetHomeOffset(new BigDecimal("0"), new BigDecimal("0"),
                        new BigDecimal("-0.1"))));
    }

    // BigDecimal keeps 80.00 and 80 apart and the encoder writes what it is handed, so nothing in
    // this chain invents a precision - which matters since the firmware's own report comes back at
    // two decimal places and normalising to that would be making up digits.
    @Test
    void nothingInTheChainInventsAScale() {
        assertEquals("M92 X81", one(new SetStepsPerUnit(new BigDecimal("81"), null, null, null)));
        assertEquals("M92 X81.00",
                one(new SetStepsPerUnit(new BigDecimal("81.00"), null, null, null)));
    }

    // ------------------------------------------------- the card

    @Test
    void mountingAndReleasingTheCardAreM21AndM22() {
        assertEquals("M21", one(new MountCard()));
        assertEquals("M22", one(new ReleaseCard()));
    }

    // Cap:LONG_FILENAME:0 on this firmware, so L would ask for something it cannot give - a flag
    // one machine ignores and another honours is two different answers to one request.
    @Test
    void listingTheCardIsABareM20() {
        assertEquals("M20", one(new ListFiles()));
    }

    @Test
    void deletingAFileIsM30WithItsName() {
        assertEquals("M30 BENCHY.GCO", one(new DeleteFile("BENCHY.GCO")));
    }

    // Sent as a unit by PrinterControl.send. One M32 is expressible (#37) and is not what a print
    // takes: M32 parses its line for parameters, so a `p` in the filename means "call as a
    // subroutine" and a second file prints after this one. #109, and PrintFile carries the capture.
    @Test
    void printingAFileIsSelectThenStart() {
        assertEquals(List.of("M23 BENCHY.GCO", "M24"), wire(new PrintFile("BENCHY.GCO")));
    }

    // Trimmed because a trailing blank would reach the card as part of the name: the firmware takes
    // everything to the end of the line, so "M30 a.gco " and "M30 a.gco" are not the same request.
    @Test
    void aNameIsTrimmedBeforeItTravels() {
        assertEquals("M30 A.GCO", one(new DeleteFile("  A.GCO  ")));
    }

    // The newline case is the one that matters: a filename is a bare rest-of-line string the
    // transport frames without parsing, so "a.gco\nM112" is a delete *and* an emergency stop. The
    // others are quieter - `;` opens a comment that swallows the checksum, `*` is the checksum
    // delimiter itself.
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

    // Spec 1.3: Marlin's line buffer is 96 bytes including the N word and checksum, and a line past
    // it is truncated and executed - so an over-long name would delete or print the wrong file. The
    // budget is taken at a six-digit line number, which an upload reaches on its own, so a name is
    // not accepted early in a session and refused later in it.
    @Test
    void aNameTooLongForABlockIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new DeleteFile("x".repeat(200)));
        // and one that fits is not
        assertEquals("M30 " + "x".repeat(60), one(new DeleteFile("x".repeat(60))));
    }
}

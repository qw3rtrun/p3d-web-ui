package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driving the machine by hand - issue #43's {@code MotionControl} feature, deferred as #49:
 * {@code /home}, {@code /move}, {@code /fan} and {@code /position}, the things a Marlin screen's
 * buttons do, asked for over HTTP. {@code /store-settings} moved out to
 * {@code EepromSettingsIntegrationTest} under #88, since {@code M500} is one of five EEPROM
 * operations and the other four have no motion in them.
 *
 * <p>This is the feature that shipped dead first (§3.1 names three): every translation was tested,
 * every request answered 200, and {@code PrinterState.onCommand}'s {@code default} arm dropped the
 * lot before a byte left the host. So every test here asserts an exact list on
 * {@link MockPrinter#commands()} rather than a prefix, since the bugs this feature is exposed to
 * are about what is missing or extra, and a prefix check sees neither.
 *
 * <p>Two rules have teeth: {@code G91} is modal and stays in relative mode until a {@code G90}
 * restores it, so a lost restoration corrupts every later absolute move rather than failing
 * visibly at the jog itself; and a null axis (not named) must never collapse into a zero axis
 * (named and sent), since on an absolute move that difference is a toolhead driven into whatever
 * is on the bed.
 *
 * <p>The baseline configuration is {@code marlin()}; the one other row of §5's table that changes
 * anything here is {@code legacyOk()}, whose bare {@code ok} holds the host to one outstanding
 * block, so a jog's three commands cross one round trip at a time instead of together - same
 * outcome, different mechanism. Extruder count, a card reader and a mute machine change nothing
 * about a {@code G28}, so §8 declines the ceremony of varying them too.
 *
 * <h2>What was measured</h2>
 *
 * Every command string asserted below was put to a running MarlinSim on {@code localhost:8099}
 * before it was written here, and all of them were acknowledged - {@code G28 X Z},
 * {@code M106 S200 P1}, {@code M107 P1}, {@code M500}, {@code G1 X10 Y0}. {@code M114} answered
 * {@code X:0.00 Y:0.00 Z:5.00 E:5.00 Count X:0 Y:0 Z:2000}, which is the census line
 * {@link MockPrinter} replies with.
 *
 * <p>The whole farm was then driven against that simulator, which is the only thing that has ever
 * caught this feature being dead. The nine commands below left the host, in this order:
 *
 * <pre>
 * AutoHome(G28 X Z)            LinearMoveG1(G1 Z0.2)        GetCurrentPosition(M114)
 * LinearMoveG1(G1 X10 Y20 F3000)  AbsolutePositioning(G90)  SaveSettings(M500)
 * RelativePositioning(G91)     SetFanSpeed(M106 S200 P1)    FanOff(M107)
 * </pre>
 *
 * and the {@code M114} that followed them answered
 * {@code "axes":{"X":10.00,"Y":20.00,"Z":5.20,"E":5.00}} on the SSE stream: Z moved by 0.2 rather
 * than to 0.2 ({@code G91}/{@code G90} obeyed and restored), and X/Y are still where the absolute
 * move left them (axes the jog did not name were not sent to the origin).
 */
class MotionControlIntegrationTest extends FarmIntegrationTest {

    // ------------------------------------------------------------------------------- homing

    /**
     * The exact list, not {@code sent("G28")}: the bug this guards is {@code HomeAxes.all()} read
     * the other way round, sending {@code G28 X Y Z} - also a home of everything on three axes, but
     * the wrong command on a fourth.
     */
    @Test
    void homingWithNoAxisNamedHomesEverything() {
        var printer = machine(MockPrinter.marlin());
        var id = register("homing all", printer);
        connectAndSettle(id, printer);

        command(id, "/home", "{}").expectStatus().isOk();

        awaitCommands(printer, List.of("G28"));
        assertEquals(List.of("G28"), printer.commands(),
                "nothing selected means all of them, and Marlin spells that as a bare G28");
    }

    /**
     * {@code G28 X Z}, not {@code G28 X1 Z1}: a value after the letter overrides that axis's
     * endstop, which is a different command with the same spelling.
     */
    @Test
    void homingNamedAxesSendsOnlyThose() {
        var printer = machine(MockPrinter.marlin());
        var id = register("homing xz", printer);
        connectAndSettle(id, printer);

        command(id, "/home", "{\"x\":true,\"z\":true}").expectStatus().isOk();

        awaitCommands(printer, List.of("G28 X Z"));
        assertEquals(List.of("G28 X Z"), printer.commands(),
                "the axes named and no others - Y was not asked for and must not be homed");
    }

    // -------------------------------------------------------------------------------- moving

    /**
     * {@code G91} is modal: the three commands must arrive whole and in order, and the last matters
     * most - without it every later absolute move (the next jog, a home-and-go, a print from the
     * card) reads as an offset from wherever the nozzle is, with nothing failing at the moment it is
     * lost. The wait is on {@code G90} arriving, not on list equality, so a missing restoration
     * reports as what actually arrived rather than a diff nobody can read.
     */
    @Test
    void aRelativeJogPutsTheMachineBackIntoAbsoluteMode() {
        var printer = machine(MockPrinter.marlin());
        var id = register("jogging", printer);
        connectAndSettle(id, printer);

        command(id, "/move", "{\"x\":10,\"relative\":true}").expectStatus().isOk();

        awaitCommands(printer, List.of("G91", "G1 X10", "G90"));
        assertEquals(List.of("G91", "G1 X10", "G90"), printer.commands(),
                "a jog is the mode switch, the move and the mode switch back, as one unit");
    }

    /**
     * The mirror of the test above: a stray {@code G90} is harmless, a stray {@code G91} is not, so
     * "three commands always" would be as wrong as "one command always".
     */
    @Test
    void anAbsoluteMoveIsOneCommandAndSwitchesNoModes() {
        var printer = machine(MockPrinter.marlin());
        var id = register("positioning", printer);
        connectAndSettle(id, printer);

        command(id, "/move", "{\"x\":10,\"y\":20,\"feedRate\":3000}").expectStatus().isOk();

        awaitCommands(printer, List.of("G1 X10 Y20 F3000"));
        assertEquals(List.of("G1 X10 Y20 F3000"), printer.commands(),
                "no mode switch belongs around a move that was already absolute");
    }

    /**
     * Null means "not named"; if it became zero the move would also carry {@code X0 Y0 E0}, a
     * wordy no-op in relative mode and a toolhead driven to the origin in absolute mode. The exact
     * list asserts that absence as much as {@code Z}'s presence - the reason it is a list, not
     * {@code sent("G1 Z")}.
     */
    @Test
    void aSingleAxisJogMovesOnlyThatAxis() {
        var printer = machine(MockPrinter.marlin());
        var id = register("one axis", printer);
        connectAndSettle(id, printer);

        command(id, "/move", "{\"z\":0.2,\"relative\":true}").expectStatus().isOk();

        awaitCommands(printer, List.of("G91", "G1 Z0.2", "G90"));
        assertEquals(List.of("G91", "G1 Z0.2", "G90"), printer.commands(),
                "one axis was named, so one axis may appear in the move -"
                        + " a null that became a zero reads as G1 X0 Y0 Z0.2 E0");
    }

    /**
     * The other half: an implementation that dropped every zero with every null would still pass
     * {@link #aSingleAxisJogMovesOnlyThatAxis()} while silently discarding {@code Y0} - an operator
     * asking for exactly one thing, do not move Y.
     */
    @Test
    void aZeroTravelsWhereANullDoesNot() {
        var printer = machine(MockPrinter.marlin());
        var id = register("zero and null", printer);
        connectAndSettle(id, printer);

        command(id, "/move", "{\"x\":10,\"y\":0,\"relative\":true}").expectStatus().isOk();

        awaitCommands(printer, List.of("G91", "G1 X10 Y0", "G90"));
        assertEquals(List.of("G91", "G1 X10 Y0", "G90"), printer.commands(),
                "Y was named as zero and must be on the wire; Z and E were not named and must not");
    }

    // ---------------------------------------------------------------------------------- the fan

    /**
     * {@code I} is the trap: Marlin's M106 docs list both {@code I} and {@code P} as "index", and
     * {@code :gcode}'s generated class carries both with {@code I} first, so the mistake writes
     * {@code M106 I1 S200} - read by mainline firmware as fan 0 with a stray word, and accepted
     * without complaint (confirmed on the simulator), so only the exact string tells the two apart.
     * And 200 is 200, not 78% - the percentage is the page's business, not a second unit to get
     * wrong here.
     */
    @Test
    void aFanIndexTravelsInPAndTheSpeedIsPwm() {
        var printer = machine(MockPrinter.marlin());
        var id = register("fan 1", printer);
        connectAndSettle(id, printer);

        command(id, "/fan", "{\"index\":1,\"speed\":200}").expectStatus().isOk();

        awaitCommands(printer, List.of("M106 S200 P1"));
        assertEquals(List.of("M106 S200 P1"), printer.commands(),
                "the fan number goes in P; in I it would be a stray word and fan 0 would spin");
    }

    /** No index is the default fan, which is fan 0 - and the word is simply absent. */
    @Test
    void theDefaultFanIsSentWithNoIndexWordAtAll() {
        var printer = machine(MockPrinter.marlin());
        var id = register("default fan", printer);
        connectAndSettle(id, printer);

        command(id, "/fan", "{\"speed\":128}").expectStatus().isOk();

        awaitCommands(printer, List.of("M106 S128"));
        assertEquals(List.of("M106 S128"), printer.commands(),
                "a null index is no index word, not P0");
    }

    /**
     * Marlin treats {@code M106 S0} identically on a plain part fan, so this is readability rather
     * than function - and worth pinning, since a refactor "simplifying" this into {@code M106 S0}
     * goes unnoticed until someone reads a failed print's log.
     */
    @Test
    void turningTheFanOffIsM107() {
        var printer = machine(MockPrinter.marlin());
        var id = register("fan off", printer);
        connectAndSettle(id, printer);

        command(id, "/fan", "{\"index\":1,\"speed\":0}").expectStatus().isOk();

        awaitCommands(printer, List.of("M107 P1"));
        assertEquals(List.of("M107 P1"), printer.commands(),
                "zero is off, the documented spelling of off is M107, and the index still travels");
    }

    // --------------------------------------------------------------- position, and settings

    /**
     * Every reply here is an event, since a reply can also arrive unasked and a caller waiting for
     * its own would miss those - so the {@code /position} response is empty on purpose, and
     * asserting that is half the test. They do arrive unasked: driving the farm against the
     * simulator produced two {@code PositionRs} for one {@code M114} (MarlinSim reports position
     * when a {@code G28} finishes, unrequested); {@link MockPrinter} is deterministic by design
     * (§4.3) and does not imitate that, so this test asks for the one it asserts on. {@code axes}
     * stays a map rather than four fields because the axis set is the machine's business - a delta
     * reports A/B/C, an IDEX two carriages.
     */
    @Test
    void askingWhereTheToolheadIsAnswersOnTheStreamAndNotInTheResponse() {
        var printer = machine(MockPrinter.marlin());
        var id = register("where am i", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/position").expectStatus().isOk().expectBody().isEmpty();

        awaitCommands(printer, List.of("M114"));
        assertEquals(List.of("M114"), printer.commands());

        var position = events.await("PositionRs");
        assertEquals(10.0, coordinate(axes(position), "X"));
        assertEquals(10.0, coordinate(axes(position), "Y"));
        assertEquals(5.0, coordinate(axes(position), "Z"));
        assertEquals(10.0, coordinate(axes(position), "E"));
        assertEquals(800.0, coordinate(counts(position), "X"),
                "the stepper counts are the other half of the line and mean something different");
    }

    // ------------------------------------------------------------------ over another configuration

    /**
     * {@code legacyOk()}'s bare {@code ok} never widens the send window past
     * {@code AdvancedOkRs(1, 1, null)}'s one block, so the three commands cross one at a time
     * instead of together - same outcome, different mechanism, which is the difference §5 asks a
     * second configuration to be chosen for. It is also the configuration where a dropped
     * {@code G90} is most plausible, since the restoration queues longest behind something else.
     */
    @Test
    void aRelativeJogArrivesWholeOnAFirmwareWithNoBufferCounts() {
        var printer = machine(MockPrinter.legacyOk());
        var id = register("legacy jog", printer);
        connectAndSettle(id, printer);

        command(id, "/move", "{\"y\":-5,\"feedRate\":1500,\"relative\":true}")
                .expectStatus().isOk();

        awaitCommands(printer, List.of("G91", "G1 Y-5 F1500", "G90"));
        assertEquals(List.of("G91", "G1 Y-5 F1500", "G90"), printer.commands(),
                "one outstanding block at a time, and the mode switch back is still the last of them");
    }

    /**
     * Worth stating for motion specifically: a leaked temperature target is a wrong number, a
     * leaked {@code G28} is a toolhead crossing the bed. The refusal is
     * {@code PrinterState.onCommand}'s connection gate; what is observable here is that no socket
     * was opened at all.
     */
    @Test
    void anUnconnectedMachineIsNeitherHomedNorDialled() {
        var printer = machine(MockPrinter.marlin());
        var id = register("not dialled", printer);

        command(id, "/home", "{}").expectStatus().isOk();

        // Bounded, because the claim is a negative: a dial and handshake on loopback are two orders
        // of magnitude inside this.
        quiet();
        assertEquals(0, printer.sessions(),
                "asking a machine to home must not be what connects it");
        assertTrue(printer.commands().isEmpty(),
                "nothing may reach a machine the farm has no link to: " + printer.commands());
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * Separate from the {@code assertEquals} that follows, deliberately: waiting on list equality
     * reports a missing {@code G90} as a timeout with no diff, and asserting without waiting races
     * the write. Waiting on the final element then comparing gives a readable message either way,
     * with evidence sampled after the wait - the only thing a CI-only failure can be diagnosed from.
     */
    private static void awaitCommands(MockPrinter printer, List<String> expected) {
        var last = expected.getLast();
        await(() -> "the machine to receive " + expected + ". It received " + printer.commands(),
                () -> printer.sent(last));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> axes(Map<String, Object> position) {
        assertNotNull(position, "expected a position report on the stream");
        return (Map<String, Object>) position.get("axes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> counts(Map<String, Object> position) {
        assertNotNull(position, "expected a position report on the stream");
        return (Map<String, Object>) position.get("counts");
    }

    /** One axis off a report, as a number - the browser reads these as doubles. */
    private static double coordinate(Map<String, Object> figures, String axis) {
        var value = figures.get(axis);
        assertNotNull(value, "expected axis " + axis + " on " + figures);
        return ((Number) value).doubleValue();
    }
}

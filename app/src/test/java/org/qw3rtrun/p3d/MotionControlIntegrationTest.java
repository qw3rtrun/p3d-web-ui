package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driving the machine by hand - issue #43's {@code MotionControl} feature, deferred as #49.
 *
 * <p>{@code /home}, {@code /move}, {@code /fan}, {@code /position} and {@code /store-settings}: the
 * five things a Marlin screen's buttons do, asked for over HTTP.
 *
 * <p><strong>This is the feature that shipped dead first.</strong> §3.1 of the integration-test
 * spec names three, and motion is the oldest: every translation was tested, every request answered
 * 200, and {@code PrinterState.onCommand}'s {@code default} arm dropped the lot before a byte left
 * the host. So every test here ends at {@link MockPrinter#commands()}, and most of them end at an
 * <em>exact list</em> rather than at "something starting with G1 arrived" - because the two bugs
 * this feature is actually exposed to are both about what is <em>missing</em> from the list or
 * <em>extra</em> in it, and a prefix check sees neither.
 *
 * <h2>The two with teeth</h2>
 *
 * <ul>
 *   <li><strong>The modal restoration.</strong> {@code G91} puts Marlin in relative mode and it
 *       stays there. A jog is therefore three commands - {@code G91}, the move, {@code G90} - and
 *       a lost {@code G90} is not a failed jog, it is every <em>later</em> absolute move landing
 *       somewhere else, including a print's. Nothing observable goes wrong at the moment of the
 *       bug. {@link #aRelativeJogPutsTheMachineBackIntoAbsoluteMode()} and
 *       {@link #aRelativeJogArrivesWholeOnAFirmwareWithNoBufferCounts()}.</li>
 *   <li><strong>Null is not zero.</strong> A null axis is one the request did not mention; a zero
 *       is one it mentioned and asked not to move (relative) or to send to the origin (absolute).
 *       Conflating them turns a single-axis jog into a move of everything, which on a machine with
 *       a part on the bed is a crash rather than a wrong number.
 *       {@link #aSingleAxisJogMovesOnlyThatAxis()} and {@link #aZeroTravelsWhereANullDoesNot()}.</li>
 * </ul>
 *
 * <h2>The configurations</h2>
 *
 * The baseline is {@code marlin()}. The one other row of §5's table that changes any answer here is
 * {@code legacyOk()}: a firmware that acknowledges with a bare {@code ok} reports no buffer counts,
 * so the host holds itself to <strong>one outstanding block</strong> and the three commands of a
 * jog cross the wire one round trip at a time instead of together. The required outcome is
 * identical and the mechanism is not, which is the test §5 asks for.
 *
 * <p>Extruder count, a card in the reader and a machine that never answers change nothing about a
 * {@code G28}, so padding this class with them would be the ceremony §8 declines.
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
 * {@code "axes":{"X":10.00,"Y":20.00,"Z":5.20,"E":5.00}} on the SSE stream. That one line is both
 * rules at once: <strong>Z moved by 0.2 rather than to 0.2</strong>, so {@code G91} was obeyed and
 * {@code G90} put the machine back before the report; and <strong>X and Y are still where the
 * absolute move left them</strong>, so the axes the jog did not name were not sent to the origin.
 */
class MotionControlIntegrationTest extends FarmIntegrationTest {

    // ------------------------------------------------------------------------------- homing

    /**
     * A home with no axis named homes everything, which is Marlin's own rule for a bare
     * {@code G28}.
     *
     * <p>The exact list is the assertion and not {@code sent("G28")}: the bug this guards is
     * {@code HomeAxes.all()} being read the other way round, which would send {@code G28 X Y Z} -
     * also a home of everything on a three-axis machine, and the wrong command on anything with a
     * fourth axis.
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
     * Naming axes sends those axes, as flag words with no value.
     *
     * <p>{@code G28 X Z} and not {@code G28 X1 Z1}: a value after the letter is an override of
     * where that axis's endstop is, which is a different command with the same spelling.
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
     * A relative jog puts the machine back into absolute mode, and that is the point of the test.
     *
     * <p>{@code G91} is <strong>modal</strong>. The three commands have to arrive whole and in
     * order, and the one that matters is the last: without it the machine is left relative and
     * every absolute move afterwards - the next jog, the next home-and-go, a print started from the
     * card - is read as an offset from wherever the nozzle happens to be. Nothing fails at the
     * moment the {@code G90} is lost, which is why no test short of this one would notice.
     *
     * <p>The wait is on {@code G90} <em>arriving</em> rather than on the list being right, so a
     * missing restoration reports as "the machine received [G91, G1 X10]" instead of as a diff
     * nobody can read.
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
     * An absolute move is one command, with no mode switch around it at all.
     *
     * <p>The mirror of the test above: a {@code G90} sent where none was needed is harmless, a
     * {@code G91} is not, and "three commands always" would be as wrong as "one command always".
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
     * A single-axis jog moves that axis and mentions no other.
     *
     * <p>Null is "the request did not name this axis". If a null became a zero the move would carry
     * {@code X0 Y0 E0} as well, which in relative mode is a wordy no-op and in absolute mode sends
     * the toolhead to the origin - across the bed, through whatever is on it. The exact list is
     * therefore asserting the <em>absence</em> of {@code X}, {@code Y} and {@code E} as much as the
     * presence of {@code Z}, which is the whole reason it is a list and not {@code sent("G1 Z")}.
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
     * A zero the request did name travels; a null does not.
     *
     * <p>The other half of the distinction, and the half a test that only checked for absent
     * letters would let through: an implementation that dropped every zero along with every null
     * would pass {@link #aSingleAxisJogMovesOnlyThatAxis()} and silently discard {@code Y0}, which
     * is an operator asking for exactly one thing - do not move Y, and I mean it.
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
     * A fan index travels in {@code P}, and the speed is Marlin's 0..255 PWM scale.
     *
     * <p><strong>{@code I} is the trap.</strong> Marlin's own M106 documentation lists both
     * {@code I} and {@code P} as "index", and {@code :gcode}'s generated class carries both fields
     * with {@code I} first - so the mistake writes {@code M106 I1 S200}, which mainline firmware
     * reads as a fan 0 command with a stray word. The exact string is the only assertion that tells
     * the two apart; both are accepted by the firmware without complaint, which the simulator
     * confirmed.
     *
     * <p>And 200 is 200 and not 78%. The percentage a person reads is the page's business; a
     * percentage reaching this endpoint would be a second unit to get wrong.
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
     * Zero is off, and off is {@code M107}.
     *
     * <p>Marlin treats {@code M106 S0} the same on a plain part fan, so this is a readability
     * decision rather than a functional one - and it is still worth pinning, because it is the kind
     * of thing a refactor "simplifies" into {@code M106 S0} and nobody notices until they are
     * reading a log of a failed print.
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
     * {@code M114}'s answer comes back on the stream, not in the response.
     *
     * <p>Every reply in this application is an event, because a reply can also arrive unasked - and
     * a caller that waited for its own would miss those. So the response to {@code /position} is
     * empty on purpose, and the assertion that it is empty is half the test: an implementation that
     * started answering here would be one a page could not get an unsolicited report from.
     *
     * <p><strong>And they do arrive unasked.</strong> Driving the farm against the simulator
     * produced two {@code PositionRs} for one {@code M114}: MarlinSim reports where it is when a
     * {@code G28} finishes, which nothing requested and which a response-shaped API would have had
     * nowhere to put. {@link MockPrinter} does not imitate that - it is deterministic by design,
     * §4.3 - so this test asks for the one it asserts on.
     *
     * <p>The figures are the ones the machine stated, as JSON numbers under the axis letter.
     * {@code axes} stays a map rather than becoming four fields because the axis set is the
     * machine's business - a delta reports A/B/C, an IDEX reports two carriages.
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

    /**
     * Storing the settings reaches the machine as {@code M500}.
     *
     * <p>The one command on this surface whose effect outlives the connection, which is why it is a
     * {@code POST} with a body rather than something a stray link could fire.
     */
    @Test
    void storingSettingsReachesTheMachine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("eeprom", printer);
        connectAndSettle(id, printer);

        command(id, "/store-settings", "{}").expectStatus().isOk();

        awaitCommands(printer, List.of("M500"));
        assertEquals(List.of("M500"), printer.commands());
    }

    // ------------------------------------------------------------------ over another configuration

    /**
     * The jog still arrives whole against a firmware that reports no buffer counts.
     *
     * <p>{@code legacyOk()} acknowledges with a bare {@code ok}, so the host's send window never
     * widens past the one block {@code AdvancedOkRs(1, 1, null)} starts it at. The three commands
     * therefore cross the wire one at a time, each waiting for the previous to come back, where
     * against {@code marlin()} they go out together - the same required outcome reached by a
     * different mechanism, which is the difference §5 asks a second configuration to be chosen for.
     *
     * <p>It is the configuration where a <em>dropped</em> {@code G90} is most plausible, because it
     * is the one where the restoration spends the longest queued behind something else.
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
     * A machine nobody dialled is not commanded, and is not dialled by being commanded.
     *
     * <p>Worth stating for motion specifically rather than leaving it to
     * {@code TemperatureControlIntegrationTest}: a target for a cold hotend that leaks out is a
     * number, and a {@code G28} that leaks out is a toolhead crossing the bed. The refusal is
     * {@code PrinterState.onCommand}'s connection gate, and what is observable from here is that no
     * socket was opened at all.
     */
    @Test
    void anUnconnectedMachineIsNeitherHomedNorDialled() {
        var printer = machine(MockPrinter.marlin());
        var id = register("not dialled", printer);

        command(id, "/home", "{}").expectStatus().isOk();

        // Bounded, because the claim is that something did NOT happen. A dial and a handshake on
        // loopback are two orders of magnitude inside this.
        quiet();
        assertEquals(0, printer.sessions(),
                "asking a machine to home must not be what connects it");
        assertTrue(printer.commands().isEmpty(),
                "nothing may reach a machine the farm has no link to: " + printer.commands());
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * Waits for the last command of an expected list to arrive, naming the whole list and what
     * actually turned up.
     *
     * <p>Separate from the {@code assertEquals} that follows it, deliberately. Waiting on list
     * equality would report a missing {@code G90} as a timeout with no diff; asserting without
     * waiting would race the write. Waiting on the final element and then comparing gives a
     * readable message in both directions - the evidence is sampled after the wait, which is the
     * only thing a CI-only failure can be diagnosed from.
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

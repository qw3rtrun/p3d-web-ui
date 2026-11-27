package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level Bed Corners - issue #175: the Calibration tab's manual levelling wizard, driven over HTTP.
 *
 * <p>The wizard sends the nozzle to each bed corner in turn so an operator can turn the levelling
 * screw under it. Marlin's own {@code LEVEL_BED_CORNERS} is an LCD-menu feature with
 * <strong>no G-code entry point</strong>, so the sequence is the host's to run out of ordinary
 * moves, and the only new endpoint is {@code POST /bed-corner} carrying one corner's three moves.
 * Auto-levelling ({@code G29}), mesh state ({@code M420}) and babystepping ({@code M290}) are not
 * part of this feature and answer {@code echo:Unknown command} on the firmware this repository
 * measures against - deferred with their captures as issue #174.
 *
 * <p>Like {@link MotionControlIntegrationTest}, every test here asserts an <strong>exact list</strong>
 * on {@link MockPrinter#commands()} rather than a prefix. Motion is the feature that shipped dead
 * first, and the bugs it is exposed to are about what is missing or extra; a prefix check sees
 * neither. Here the ordering carries the whole safety property as well - see
 * {@link #theThreeMovesArriveContiguouslySoNothingCanInterleave()}.
 *
 * <p>The two configurations §5 of #43 asks for are {@code marlin()} and {@code legacyOk()}: the
 * latter's bare {@code ok} never widens the send window past one block, so the triple crosses one
 * command at a time instead of together. Extruder count and a card reader change nothing about a
 * {@code G1}, so §8's ceremony of varying them too is declined.
 *
 * <h2>What was measured</h2>
 *
 * <p>Against MarlinSim {@code Marlin bugfix-2.1.x (Nov 18 2026 11:55:10)} on {@code localhost:8099},
 * 2026-11-27, by {@code BedLevelingProbe} ({@code ./gradlew :backend:terminal:probeBedLeveling}).
 *
 * <p><strong>A host cannot ask whether a machine is homed.</strong> {@code M114} answers a
 * plausible-looking position before any {@code G28}, with no flag saying it is untrusted:
 *
 * <pre>
 * &gt;&gt; M114        (before any G28)
 * &lt;&lt; X:0.00 Y:0.00 Z:5.00 E:0.00 Count X:0 Y:0 Z:2000
 * </pre>
 *
 * so an absolute move against that origin goes somewhere nobody chose, and the browser tracks
 * homing itself rather than inferring it. What makes homing observable is that {@code G28} answers a
 * {@code PositionRs} immediately before its {@code ok} - which is what
 * {@link #homingThenACornerIsTheWholeSequenceAMachineSees()} pins:
 *
 * <pre>
 * &gt;&gt; G28
 * &lt;&lt; echo:busy: processing
 * &lt;&lt; echo:busy: processing
 * &lt;&lt; echo:busy: processing
 * &lt;&lt; X:0.00 Y:0.00 Z:5.00 E:0.00 Count X:0 Y:0 Z:2000
 * &lt;&lt; ok P15 B3
 * ** 5 line(s) in 6778 ms; ended with an ok: true
 * </pre>
 *
 * <p>Three {@code echo:busy: processing} keepalives across 6.8 s, all already absorbed by
 * {@code PrinterState.on(BusyRs)}. {@link MockPrinter} is deterministic by design (§4.3) and does
 * not imitate the keepalives, so what is asserted here is the position report, not the timing.
 */
class BedCornerLevelingIntegrationTest extends FarmIntegrationTest {

    /**
     * A 220x220 bed, the size {@code PrinterManagementIntegrationTest} declares - and the reason the
     * corners below sit at 30 and 190: the inset defaults to 30 mm, so the far corner is
     * {@code 220 - 30}.
     */
    private static final String BED_220 = "{\"bedVolume\":{\"x\":220,\"y\":220,\"z\":250}}";

    /** Front-left of a 220x220 bed at the default 30 mm inset, hop 5 mm, check height 0.2 mm. */
    private static final String FRONT_LEFT =
            "{\"x\":30,\"y\":30,\"travelZ\":5,\"checkZ\":0.2,\"xyFeedRate\":3000,\"zFeedRate\":600}";

    private static final List<String> FRONT_LEFT_WIRE =
            List.of("G1 Z5 F600", "G1 X30 Y30 F3000", "G1 Z0.2 F600");

    // ------------------------------------------------------------------- the corner move

    /**
     * Hop, then travel, then descend - in that order, as three absolute moves with no
     * {@code G90}/{@code G91} around them.
     *
     * <p>No mode switch belongs here: the machine's resting mode is absolute, since a relative jog
     * ends with the {@code G90} that restores it ({@code MoveAxes.toMarlin()}, pinned by
     * {@code MotionControlIntegrationTest.aRelativeJogPutsTheMachineBackIntoAbsoluteMode}). A stray
     * {@code G91} here would turn every later corner into an offset from the last one.
     *
     * <p>The order is the safety property and the exact list is what asserts it. Descend-before-travel
     * drags the nozzle across the bed at 0.2 mm; travel-before-hop does the same from wherever the
     * nozzle already was.
     */
    @Test
    void aCornerMoveIsHopThenTravelThenDescend() {
        var printer = machine(MockPrinter.marlin());
        var id = register("front left", printer, BED_220);
        connectAndSettle(id, printer);

        command(id, "/bed-corner", FRONT_LEFT).expectStatus().isOk();

        awaitCommands(printer, FRONT_LEFT_WIRE);
        assertEquals(FRONT_LEFT_WIRE, withoutSdStatusPoll(printer),
                "the nozzle lifts, crosses the bed at height, and only then comes down");
    }

    /**
     * The reason the three moves are one request and not three {@code POST /move} calls.
     *
     * <p>As three requests, another operator's jog or a second browser tab can land between them,
     * and a descend to check height arriving <em>before</em> the XY travel leaves the nozzle crossing
     * the bed at 0.2 mm. That is a bed crash, and it is a race rather than a theoretical one:
     * {@code PrinterControlService.send} takes a {@code List<GRq<?>>} precisely so a group is applied
     * as one task - the same guarantee {@code MoveAxes} relies on to keep {@code G91} from leaking.
     *
     * <p>So this fires a competing jog immediately behind the corner and asserts the triple is
     * <strong>contiguous</strong>, not merely present. A passing prefix or set check would not see
     * the failure this guards.
     */
    @Test
    void theThreeMovesArriveContiguouslySoNothingCanInterleave() {
        var printer = machine(MockPrinter.marlin());
        var id = register("contiguous", printer, BED_220);
        connectAndSettle(id, printer);

        command(id, "/bed-corner", FRONT_LEFT).expectStatus().isOk();
        command(id, "/move", "{\"x\":5,\"relative\":true}").expectStatus().isOk();

        awaitCommands(printer, List.of("G91", "G1 X5", "G90"));

        var sent = withoutSdStatusPoll(printer);
        var hop = sent.indexOf("G1 Z5 F600");
        assertTrue(hop >= 0, "the corner move never left the host: " + sent);
        assertEquals(FRONT_LEFT_WIRE, sent.subList(hop, hop + FRONT_LEFT_WIRE.size()),
                "the hop, the travel and the descend must be adjacent - a jog that landed inside"
                        + " them would have crossed the bed at check height: " + sent);
    }

    /**
     * A null height is a move the request does not mention, not a zero - {@code MoveAxes}'s rule,
     * and on an absolute move the difference is a nozzle driven to Z0 rather than left alone.
     *
     * <p>This is also the "go to the corner but stay up" case a future row of the Calibration
     * accordion can reuse without a second endpoint.
     */
    @Test
    void aCornerMoveWithNoCheckHeightStopsAfterTheTravel() {
        var printer = machine(MockPrinter.marlin());
        var id = register("no descend", printer, BED_220);
        connectAndSettle(id, printer);

        command(id, "/bed-corner",
                "{\"x\":190,\"y\":30,\"travelZ\":5,\"xyFeedRate\":3000,\"zFeedRate\":600}")
                .expectStatus().isOk();

        var expected = List.of("G1 Z5 F600", "G1 X190 Y30 F3000");
        awaitCommands(printer, expected);
        assertEquals(expected, withoutSdStatusPoll(printer),
                "no check height was asked for, so no third move may appear - least of all G1 Z0");
    }

    /** The mirror: no hop asked for is no hop sent, so "three moves always" would be as wrong as one. */
    @Test
    void aCornerMoveWithNoTravelHeightStartsAtTheTravel() {
        var printer = machine(MockPrinter.marlin());
        var id = register("no hop", printer, BED_220);
        connectAndSettle(id, printer);

        command(id, "/bed-corner",
                "{\"x\":110,\"y\":110,\"checkZ\":0.2,\"xyFeedRate\":3000,\"zFeedRate\":600}")
                .expectStatus().isOk();

        var expected = List.of("G1 X110 Y110 F3000", "G1 Z0.2 F600");
        awaitCommands(printer, expected);
        assertEquals(expected, withoutSdStatusPoll(printer),
                "the centre stop from a nozzle already at height needs no lift first");
    }

    // ------------------------------------------------------------------- the whole wizard

    /**
     * What a machine actually sees across one pass of the wizard: a home, then a corner.
     *
     * <p>The {@code PositionRs} on the stream is the measured signal the browser waits for before it
     * will enable the sequence at all - see this class's doc block for why the host has to track
     * homing itself. Asserting it here is what makes the browser's gate honest rather than a guess
     * about what {@code G28} answers.
     */
    @Test
    void homingThenACornerIsTheWholeSequenceAMachineSees() {
        var printer = machine(MockPrinter.marlin());
        var id = register("whole pass", printer, BED_220);
        var events = connectAndSettle(id, printer);

        command(id, "/home", "{}").expectStatus().isOk();
        awaitCommands(printer, List.of("G28"));

        command(id, "/bed-corner", FRONT_LEFT).expectStatus().isOk();
        awaitCommands(printer, FRONT_LEFT_WIRE);

        var expected = new ArrayList<String>();
        expected.add("G28");
        expected.addAll(FRONT_LEFT_WIRE);
        assertEquals(expected, withoutSdStatusPoll(printer),
                "home once, then one corner - nothing else belongs on the wire");

        assertNotNull(events.await("PositionRs"),
                "G28 answers a position report before its ok, and that report is the only way the"
                        + " browser can know the machine is homed - M114 answers before homing too");
    }

    /**
     * Over {@code legacyOk()}: a firmware with no buffer counts holds the host to one outstanding
     * block, so the three moves cross one round trip at a time rather than together - same outcome,
     * different mechanism, which is what §5 asks a second configuration for. It is also where a
     * dropped third move is most plausible, since the descend queues longest behind the other two.
     */
    @Test
    void theTripleArrivesWholeOnAFirmwareWithNoBufferCounts() {
        var printer = machine(MockPrinter.legacyOk());
        var id = register("legacy corner", printer, BED_220);
        connectAndSettle(id, printer);

        command(id, "/bed-corner", FRONT_LEFT).expectStatus().isOk();

        awaitCommands(printer, FRONT_LEFT_WIRE);
        assertEquals(FRONT_LEFT_WIRE, withoutSdStatusPoll(printer),
                "one outstanding block at a time, and the descend is still the last of them");
    }

    /**
     * Worth stating for this feature specifically, as {@link MotionControlIntegrationTest} states it
     * for a jog: a leaked corner move is a toolhead crossing the bed at 0.2 mm on a machine nobody
     * is watching. The refusal is {@code PrinterState.onCommand}'s connection gate; what is
     * observable is that no socket was opened at all.
     */
    @Test
    void anUnconnectedMachineIsNotDrivenToACorner() {
        var printer = machine(MockPrinter.marlin());
        var id = register("not dialled", printer, BED_220);

        command(id, "/bed-corner", FRONT_LEFT).expectStatus().isOk();

        // Bounded, because the claim is a negative: a dial and handshake on loopback are two orders
        // of magnitude inside this.
        quiet();
        assertEquals(0, printer.sessions(),
                "asking for a corner must not be what connects a machine");
        assertTrue(printer.commands().isEmpty(),
                "nothing may reach a machine the farm has no link to: " + printer.commands());
    }

    // ------------------------------------------------------------------- the tab's gate

    /**
     * The gate, at the only level it is observable from: the rendered page.
     *
     * <p>Corner coordinates are computed from the declared bed, so <strong>a machine with no declared
     * bed has no corners</strong> - and that, not a firmware capability, is what gates the tab.
     * Deliberately not a {@code MachineProfile} flag: every machine can home and move, so a
     * capability for this would be true everywhere and would gate on nothing.
     *
     * <p>Note the gate is a different <em>kind</em> from Storage's and Settings': those ask what the
     * firmware reported ({@code MachineProfile}), this asks what a human typed
     * ({@code MachineSpec}), so {@code printer.profile.isKnown()} is the wrong question here and
     * must not appear in the template.
     *
     * <p>Asserted on the island's mount point rather than on markup, since {@code data-p3d} is the
     * contract between the page and {@code main.ts} while the surrounding classes are styling.
     */
    @Test
    void theCalibrationIslandIsMountedOnlyWhenABedIsDeclared() {
        var withBed = machine(MockPrinter.marlin());
        var declared = register("has a bed", withBed, BED_220);

        var withoutBed = machine(MockPrinter.marlin());
        var undeclared = register("no bed", withoutBed);

        assertTrue(page(declared).contains("data-p3d=\"calibration-tab\""),
                "a machine with a declared bed has corners to move to, so the wizard mounts");
        assertFalse(page(undeclared).contains("data-p3d=\"calibration-tab\""),
                "with no declared bed there are no corner positions, so no island may mount -"
                        + " a mounted one would compute its corners from NaN");
    }

    // ------------------------------------------------------------------- helpers

    /** The monitor page as a browser receives it, for the one assertion that is about rendering. */
    private String page(UUID id) {
        var body = farm.get().uri("/printers/{id}", id).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertNotNull(body, "the monitor page rendered nothing for " + id);
        return body;
    }

    /**
     * Waits on the last expected command, then lets the caller compare the whole list -
     * {@link MotionControlIntegrationTest}'s helper and its reasoning: waiting on list equality
     * reports a missing final move as a timeout with no diff, and asserting without waiting races
     * the write.
     */
    private static void awaitCommands(MockPrinter printer, List<String> expected) {
        var last = expected.getLast();
        await(() -> "the machine to receive " + expected + ". It received " + printer.commands(),
                () -> printer.sent(last));
    }
}

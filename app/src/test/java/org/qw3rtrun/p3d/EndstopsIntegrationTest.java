package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The endstops - issue #35's feature, redone after its first shipped shape was reopened.
 *
 * <h2>Why this is the second version of this test</h2>
 *
 * <p>The first pass (PR #142) correctly rejected issue #35's original proposal - a generic
 * {@code MultilineRs} marker plus a transport-level "accumulate every line until {@code ok}"
 * mechanism - because a rule that generic would swallow unrelated traffic (a {@code busy:} notice,
 * an auto-report) that can genuinely land between some other command and its {@code ok} on this
 * wire. That measurement stood. What it then shipped instead was wrong in a different way: it kept
 * {@code :gcode}'s per-line decode ({@code EndstopReportHeader}, then one {@code EndstopStateRs} per
 * endstop) streaming all the way to the browser as <strong>N separate events</strong>, with
 * {@code EndstopsControl} only <em>holding</em> the latest set for a late subscriber. The repo
 * owner's correction: one {@code M119} read has to cross {@code PrinterState} and reach
 * {@code /stream} as <strong>exactly one event</strong> - an {@code EndstopReport} carrying every
 * state, not a header plus a run of state events a page has to reassemble.
 *
 * <p>The fix is narrower than it sounds: {@code :backend:terminal}'s {@code EndstopReportReader}
 * (mirroring {@code :gcode}'s own {@code SdFileListReader} for {@code M20}) folds every line of one
 * {@code M119} answer into one {@code EndstopReport} before {@code PrinterState} ever sees it, scoped
 * to "lines that belong to this family" rather than "everything until {@code ok}" - which is what
 * keeps this safe against exactly the interleaving the first pass was right to worry about.
 *
 * <h2>What was measured, and what was not</h2>
 *
 * <p>Probed against {@code FIRMWARE_NAME:Marlin bugfix-2.1.x (Nov 18 2026 11:55:10)
 * MACHINE_TYPE:MarlinSim} on {@code localhost:8099} via {@code GCodeLiveProbe} on 2026-11-19:
 * {@code M119} answers {@code Reporting endstop status} then one {@code <name>: open|TRIGGERED}
 * line per endstop - three on that build ({@code x_min}, {@code y_min}, {@code z_min}) - then
 * {@code ok P15 B3}. A second probe, {@code MultilineInterleaveProbe}, ran a continuous
 * {@code M155 S1} temperature auto-report alongside M119 reads on 2026-11-19 and found nothing lands
 * between the first line of an answer and the {@code ok} that ends it - an auto-report tick arrives
 * either just before or just after the whole burst, never inside it, which is the fact that makes
 * family-scoped accumulation safe here. No {@code Cap:} line names {@code M119} and no refusal of it
 * has been observed on any firmware this repository has measured, so {@link MockPrinter} answers it
 * unconditionally and this suite asserts no refusal branch.
 */
class EndstopsIntegrationTest extends FarmIntegrationTest {

    /**
     * One {@code M119} read, one event - the count matters because the failure this guards against
     * is exactly what the previous pass shipped: the right bytes on the wire, decoded correctly, and
     * still N separate events reaching the browser instead of one.
     */
    @Test
    void readingTheEndstopsBringsExactlyOneEventToTheBrowser() {
        var printer = machine(MockPrinter.marlin());
        var id = register("endstops", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/endstops/read").expectStatus().isOk();

        await("M119 to reach the machine", () -> printer.sent("M119"));
        events.await("EndstopReport");

        quiet();
        assertEquals(1, events.allOf("EndstopReport").size(),
                "one M119 answer has to cross as one event, not one per line: " + events.types());

        var report = events.allOf("EndstopReport").getFirst();
        @SuppressWarnings("unchecked")
        var states = (List<Map<String, Object>>) report.get("states");
        var names = states.stream().map(s -> s.get("name")).toList();
        assertEquals(List.of("x_min", "y_min", "z_min"), names,
                "the firmware's own order, not an invented one");

        var zMin = states.stream().filter(s -> "z_min".equals(s.get("name"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, zMin.get("triggered"), "z_min was measured TRIGGERED");
    }

    /**
     * The snapshot half of the aggregate's contract, and the reason {@code EndstopsControl} exists
     * rather than the replies being simply forwarded - without it a panel opened after somebody else
     * pressed the button is handed nothing. Also one event, for the same reason as above: the
     * snapshot replays what the live stream carried, which is now one {@code EndstopReport}.
     */
    @Test
    void aPanelOpenedLaterIsHandedTheReportAlready() {
        var printer = machine(MockPrinter.marlin());
        var id = register("late subscriber", printer);
        var first = connectAndSettle(id, printer);

        command(id, "/endstops/read").expectStatus().isOk();
        first.await("EndstopReport");

        try (var later = watch(id)) {
            later.await("EndstopReport");
            quiet();
            assertEquals(1, later.allOf("EndstopReport").size(),
                    "the snapshot is the report once, not the report twice: "
                            + later.allOf("EndstopReport"));
            @SuppressWarnings("unchecked")
            var states = (List<Map<String, Object>>) later.allOf("EndstopReport").getFirst().get("states");
            assertEquals(3, states.size(), "every endstop, handed to a subscriber that asked nothing");
        }
    }

    /**
     * A second read answers the firmware's current switches, and a late subscriber is handed the
     * report that reflects them - wholesale, not merged line by line, because one {@code M119}
     * answer always states every endstop it has.
     */
    @Test
    void aSecondReadReplacesTheHeldReportRatherThanDuplicatingIt() {
        var printer = machine(MockPrinter.marlin());
        var id = register("moved switch", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/endstops/read").expectStatus().isOk();
        events.await("EndstopReport");

        printer.setEndstop("x_min", true);
        command(id, "/endstops/read").expectStatus().isOk();

        await("the second report to arrive", () -> events.allOf("EndstopReport").size() >= 2);

        try (var later = watch(id)) {
            later.await("EndstopReport");
            quiet();
            assertEquals(1, later.allOf("EndstopReport").size(),
                    "one held report, not one per read: " + later.allOf("EndstopReport"));
            @SuppressWarnings("unchecked")
            var states = (List<Map<String, Object>>) later.allOf("EndstopReport").getFirst().get("states");
            var xMin = states.stream().filter(s -> "x_min".equals(s.get("name"))).findFirst().orElseThrow();
            assertEquals(Boolean.TRUE, xMin.get("triggered"),
                    "the snapshot carries the moved switch, not the first reading");
            assertEquals(3, states.size(), "the full set, not just the endstop that moved");
        }
    }

    /**
     * A link that was down may come back up as a different machine at the same address - a reflash,
     * a cable moved. {@code EndstopsControl} keeps this rule for the same reason
     * {@code EEPROMSettingsControl} does: holding a previous session's switches would show an
     * operator a machine state that may no longer be true.
     */
    @Test
    void aNewSessionForgetsTheLastOnesEndstops() {
        var printer = machine(MockPrinter.marlin());
        var id = register("reconnect", printer);
        var first = connectAndSettle(id, printer);

        command(id, "/endstops/read").expectStatus().isOk();
        first.await("EndstopReport");
        first.close();

        disconnect(id);
        connectAndWait(id);

        try (var fresh = watch(id)) {
            quiet();
            assertTrue(fresh.allOf("EndstopReport").isEmpty(),
                    "the previous session's switches belonged to the previous session: "
                            + fresh.types());
        }
    }

    /** No {@code Cap:} line gates this, unlike {@code M503}: every machine profile answers it. */
    @Test
    void everyMachineAnswersM119RegardlessOfCapabilities() {
        var printer = machine(MockPrinter.withoutEeprom());
        var id = register("no eeprom, still has endstops", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/endstops/read").expectStatus().isOk();

        events.await("EndstopReport");
        quiet();
        assertEquals(1, events.allOf("EndstopReport").size());
    }

    /**
     * The regression the previous pass's own suite could not catch, because it asserted the thing
     * that was wrong: this pins that the header never reaches the browser as a type of its own
     * (it is folded into {@code EndstopReport} before the aggregate sees it), and that no
     * {@code EndstopStateRs} ever reaches {@code /stream} standalone.
     */
    @Test
    void theRawPerLineTypesNeverReachTheBrowser() {
        var printer = machine(MockPrinter.marlin());
        var id = register("no raw lines", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/endstops/read").expectStatus().isOk();
        events.await("EndstopReport");

        quiet();
        assertFalse(events.saw("EndstopReportHeader"),
                "the heading is a decode-layer detail now, not a browser-visible type: " + events.types());
        assertFalse(events.saw("EndstopStateRs"),
                "a state line never travels on its own anymore: " + events.types());
    }
}

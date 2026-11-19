package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The endstops - issue #35's feature, reduced from its original proposal.
 *
 * <h2>What #35 asked for, and what this builds instead</h2>
 *
 * <p>The issue proposed a generic {@code MultilineRs} marker plus a transport-level "accumulate
 * lines until {@code ok}" mechanism, naming {@code M119} and {@code M503} as the examples. Neither
 * needed it: {@code :gcode} already read both as one event per line, because every line of either
 * answer stands on its own - {@code x_min: open} means the same thing whether or not its header
 * arrived, which is exactly the property a list does <strong>not</strong> have ({@code M20}'s
 * listing is the case that genuinely needs accumulation, and {@code :gcode}'s own
 * {@code SdFileListReader} already does it, stateful and owned by the caller, which
 * {@code :backend:terminal} already uses). {@code :backend:terminal} needed no change at all: its
 * {@code HostTerminal.ReplyFlux} already forwards every decoded reply one at a time, and a generic
 * "accumulate until {@code ok}" rule would have been actively wrong there - an unsolicited line
 * (a {@code busy:} notice, an auto-report, {@code Done printing file}) can land between a command
 * and the {@code ok} that answers it without being part of that answer at all.
 *
 * <p>What was missing was narrower: {@code M119}'s reply family had no shared interface for
 * {@code :backend:core} to match in one arm (unlike {@code M503}'s {@code SettingsRs} and the SD
 * card family's {@code SdCardRs}), and nothing held the states for a late subscriber - a diagnostics
 * panel opened after somebody else pressed the button got nothing, unlike {@code M503}'s settings.
 * This test proves the fix the way {@code EepromSettingsIntegrationTest} proves {@code M503}'s: both
 * ends of the round trip, the command reaching the machine and the event reaching the browser -
 * this repository has shipped three complete, green, inert features that a one-ended test would
 * have passed.
 *
 * <h2>What was measured, and what was not</h2>
 *
 * <p>Probed against {@code FIRMWARE_NAME:Marlin bugfix-2.1.x (Nov 18 2026 11:55:10)
 * MACHINE_TYPE:MarlinSim} on {@code localhost:8099} via {@code GCodeLiveProbe} on 2026-11-19:
 * {@code M119} answers {@code Reporting endstop status} then one {@code <name>: open|TRIGGERED}
 * line per endstop - three on that build ({@code x_min}, {@code y_min}, {@code z_min}) - then
 * {@code ok P15 B3}. No {@code Cap:} line names it and no refusal of it has been observed on any
 * firmware this repository has measured, so {@link MockPrinter} answers it unconditionally and this
 * suite asserts no refusal branch - unlike {@code M503}'s {@code DISABLE_M503} case, there is nothing
 * UNMEASURED to avoid asserting on here.
 */
class EndstopsIntegrationTest extends FarmIntegrationTest {

    /**
     * Three lines on the wire become three events reaching the browser, in the firmware's own
     * order and with the header leading them - the count matters because the failure this guards
     * against is a host that reads the first line and republishes nothing else.
     */
    @Test
    void readingTheEndstopsBringsEveryLineToTheBrowser() {
        var printer = machine(MockPrinter.marlin());
        var id = register("endstops", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/endstops/read").expectStatus().isOk();

        await("M119 to reach the machine", () -> printer.sent("M119"));
        events.await("EndstopStateRs");

        await("every endstop line to reach the browser",
                () -> events.allOf("EndstopStateRs").size() >= 3);
        assertTrue(events.saw("EndstopReportHeader"), "the heading arrives live too: " + events.types());

        var names = events.allOf("EndstopStateRs").stream().map(e -> e.get("name")).toList();
        assertEquals(List.of("x_min", "y_min", "z_min"), names,
                "the firmware's own order, not an invented one");

        var zMin = events.allOf("EndstopStateRs").stream()
                .filter(e -> "z_min".equals(e.get("name"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, zMin.get("triggered"), "z_min was measured TRIGGERED");
    }

    /**
     * The snapshot half of the aggregate's contract, and the reason {@code EndstopsControl} exists
     * rather than the replies being simply forwarded - without it a panel opened after somebody else
     * pressed the button shows nothing, with no way out but asking again.
     */
    @Test
    void aPanelOpenedLaterIsHandedTheStatesAlready() {
        var printer = machine(MockPrinter.marlin());
        var id = register("late subscriber", printer);
        var first = connectAndSettle(id, printer);

        command(id, "/endstops/read").expectStatus().isOk();
        await("the report to arrive once", () -> first.allOf("EndstopStateRs").size() >= 3);

        try (var later = watch(id)) {
            later.await("EndstopStateRs");
            await("the snapshot to carry every state",
                    () -> later.allOf("EndstopStateRs").size() >= 3);
            assertEquals(3, later.allOf("EndstopStateRs").size(),
                    "the snapshot is the report once, not the report twice: "
                            + later.allOf("EndstopStateRs"));
            assertFalse(later.saw("EndstopReportHeader"),
                    "the heading is scaffolding and is not held for a late subscriber: "
                            + later.types());
        }
    }

    /**
     * A second read answers the firmware's current switches, and the held state moves with it in
     * place rather than appending a second row for the same endstop.
     */
    @Test
    void aSecondReadReplacesTheHeldStateRatherThanDuplicatingIt() {
        var printer = machine(MockPrinter.marlin());
        var id = register("moved switch", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/endstops/read").expectStatus().isOk();
        await("the first report", () -> events.allOf("EndstopStateRs").size() >= 3);

        printer.setEndstop("x_min", true);
        command(id, "/endstops/read").expectStatus().isOk();

        await("the moved switch to be reported",
                () -> events.allOf("EndstopStateRs").stream()
                        .filter(e -> "x_min".equals(e.get("name")))
                        .anyMatch(e -> Boolean.TRUE.equals(e.get("triggered"))));

        try (var later = watch(id)) {
            later.await("EndstopStateRs");
            await("the snapshot to settle", () -> later.allOf("EndstopStateRs").size() >= 3);
            assertEquals(3, later.allOf("EndstopStateRs").size(),
                    "one row per endstop, not one per read: " + later.allOf("EndstopStateRs"));
            var xMin = later.allOf("EndstopStateRs").stream()
                    .filter(e -> "x_min".equals(e.get("name"))).findFirst().orElseThrow();
            assertEquals(Boolean.TRUE, xMin.get("triggered"),
                    "the snapshot carries the moved switch, not the first reading");
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
        await("the report to arrive", () -> first.allOf("EndstopStateRs").size() >= 3);
        first.close();

        disconnect(id);
        connectAndWait(id);

        try (var fresh = watch(id)) {
            quiet();
            assertTrue(fresh.allOf("EndstopStateRs").isEmpty(),
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

        await("every endstop line to reach the browser",
                () -> events.allOf("EndstopStateRs").size() >= 3);
    }
}

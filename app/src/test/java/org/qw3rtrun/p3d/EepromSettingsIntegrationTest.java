package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine's settings - issue #88's feature, and the five operations of {@code M500}-{@code M504}.
 *
 * <p>Every test here asserts <strong>both</strong> ends of one round trip: the command that reached
 * the machine and the event that reached a browser. That is not thoroughness for its own sake. This
 * repository has shipped three features that were complete, green and inert - the motion commands,
 * then the Storage tab's commands, then its replies - and each of them would have passed a test
 * that checked only one end. {@code SdCardIntegrationTest} says the same thing at more length.
 *
 * <h2>What was measured, and what was not</h2>
 *
 * <p>Probed against {@code FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56)
 * MACHINE_TYPE:MarlinSim} on 2026-11-04. The facts these tests are built on:
 *
 * <ul>
 *   <li>{@code M503} reports live memory as <em>the commands that would restore it</em>, in two
 *       shapes - {@code echo:}-prefixed with section headers, and, under {@code S0}, bare and
 *       indented with no headers at all. A bare single-setting query answers in the second shape,
 *       which is why one reply type covers a dump and a read-back.</li>
 *   <li><strong>{@code M501} and {@code M502} answer a bare {@code ok} and say nothing about what
 *       they changed.</strong> This is the fact the design had to be built around, and
 *       {@link #revertingReReadsTheSettingsBecauseTheFirmwareWillNotSayWhatChanged()} is the test
 *       that pins the answer.</li>
 *   <li>{@code M500} answers {@code //action:notification Settings Stored}, which already decodes
 *       to {@code ActionRs} and needed no new type.</li>
 *   <li>{@code M504} answers {@code echo:EEPROM OK}. Its <em>failure</em> text is UNMEASURED, so
 *       nothing here asserts a negative verdict and the reply carries the message verbatim rather
 *       than a boolean.</li>
 * </ul>
 *
 * <h2>The configuration axis is which half of the EEPROM story the firmware tells</h2>
 *
 * <p>Three profiles, because issue #88's two named "capabilities" turned out not to be the same
 * kind of thing at all:
 *
 * <ul>
 *   <li>{@link MockPrinter#marlin()} - {@code Cap:EEPROM:1} and {@code M503} present. The baseline.</li>
 *   <li>{@link MockPrinter#withoutEeprom()} - {@code Cap:EEPROM:0}. Decided from the capability, so
 *       the tab is never offered and the server is the one deciding.</li>
 *   <li>{@link MockPrinter#withoutSettingsReport()} - EEPROM but no {@code M503}, Marlin's
 *       {@code DISABLE_M503}. <strong>There is no capability line for this under any spelling</strong>
 *       - verified against the simulator's 38 {@code Cap:} lines and against Marlin's own
 *       {@code M115.cpp} - so the farm cannot read it and has to learn it from the reply. That
 *       asymmetry is the subject of {@link #aFirmwareWithoutM503IsLearnedFromItsRefusal()}.</li>
 * </ul>
 */
class EepromSettingsIntegrationTest extends FarmIntegrationTest {

    // ------------------------------------------------------------------ reading the settings

    /**
     * Reading the settings reaches the machine and the whole report reaches the browser.
     *
     * <p>Twenty-five lines on the wire become twenty-five messages, and the count is asserted
     * because the failure this guards against is a host that decodes the first line and drops the
     * rest - which is precisely what happened before {@code SdFileListReader} existed.
     */
    @Test
    void readingTheSettingsBringsTheWholeReportToTheBrowser() {
        var printer = machine(MockPrinter.marlin());
        var id = register("has eeprom", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/settings/read").expectStatus().isOk();

        await("M503 to reach the machine", () -> printer.sent("M503"));
        events.await("SettingsValue");

        await("every reported value to reach the browser",
                () -> events.allOf("SettingsValue").size() >= 13);

        var codes = events.allOf("SettingsValue").stream().map(v -> v.get("code")).toList();
        assertEquals(List.of("G21", "M104", "M149", "M200", "M92", "M203", "M201", "M204",
                        "M205", "M206", "M145", "M145", "M301"), codes,
                "the report in the order the firmware wrote it, both M145 presets kept");

        var sections = events.allOf("SettingsSection").stream().map(s -> s.get("title")).toList();
        assertTrue(sections.contains("Steps per unit"), "sections group the tab: " + sections);
        assertTrue(sections.contains(
                        "Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>)"),
                "a title with brackets and angle brackets survives decoding: " + sections);
    }

    /**
     * A value carries the firmware's own command text, which is what the tab edits.
     *
     * <p>Asserted as the string the browser receives rather than against a parsed model: the report
     * <em>is</em> G-code, and keeping it that way is the decision that let this feature avoid a
     * second number model. A payload that had been helpfully parsed into fields would pass a test
     * written against fields and tell the browser nothing it could round-trip.
     */
    @Test
    void aSettingArrivesAsTheCommandThatWouldRestoreIt() {
        var printer = machine(MockPrinter.marlin());
        var id = register("one setting", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/settings/read").expectStatus().isOk();
        await("the steps-per-unit line",
                () -> events.allOf("SettingsValue").stream()
                        .anyMatch(v -> "M92".equals(v.get("code"))));

        var steps = events.allOf("SettingsValue").stream()
                .filter(v -> "M92".equals(v.get("code")))
                .findFirst().orElseThrow();
        assertEquals("M92 X80.00 Y80.00 Z400.00 E500.00", steps.get("command"));
    }

    /**
     * A browser opening the tab after the report was read is handed it anyway.
     *
     * <p>The snapshot half of the aggregate's contract, and the reason {@code EEPROMSettingsControl}
     * exists at all rather than the replies simply being forwarded. Without it, a page opened a
     * second after somebody else pressed Read shows an empty table and the only way out is to ask
     * the machine again.
     */
    @Test
    void aTabOpenedLaterIsHandedTheSettingsAlready() {
        var printer = machine(MockPrinter.marlin());
        var id = register("late subscriber", printer);
        var first = connectAndSettle(id, printer);

        command(id, "/settings/read").expectStatus().isOk();
        await("the report to arrive once", () -> first.allOf("SettingsValue").size() >= 13);

        try (var later = watch(id)) {
            later.await("SettingsValue");
            await("the snapshot to carry the whole report",
                    () -> later.allOf("SettingsValue").size() >= 13);
            assertEquals(13, later.allOf("SettingsValue").size(),
                    "the snapshot is the report once, not the report twice: "
                            + later.allOf("SettingsValue"));
        }
    }

    // ------------------------------------------------------------------ changing a setting

    /**
     * Setting a value reaches the machine as the setting's own command, and the machine keeps it.
     *
     * <p>Two observables, because either alone has been wrong here before: {@code printer.commands()}
     * is the byte sequence that left the host, and {@code printer.setting} is what the machine holds
     * afterwards. The second is what makes this a round trip rather than an assertion that a POST
     * returned 200.
     */
    @Test
    void settingStepsPerUnitChangesWhatTheMachineHolds() {
        var printer = machine(MockPrinter.marlin());
        var id = register("set live", printer);
        connectAndSettle(id, printer);

        command(id, "/settings/steps-per-unit", "{\"x\":81}").expectStatus().isOk();

        await("M92 to reach the machine", () -> printer.sent("M92 X"));
        assertEquals("M92 X81", printer.commands().stream()
                        .filter(c -> c.startsWith("M92")).findFirst().orElseThrow(),
                "only the axis that was asked for, and no scale this layer invented");
        await("live memory to hold the new value",
                () -> "M92 X81.00 Y80.00 Z400.00 E500.00".equals(printer.setting("M92")));
    }

    /**
     * A setting changed live is <strong>not</strong> in the EEPROM until {@code M500} says so.
     *
     * <p>The distinction issue #88 draws in its own words - "machine live memory, not eeprom" - and
     * the one an operator loses money on if the farm blurs it.
     */
    @Test
    void settingAValueDoesNotStoreIt() {
        var printer = machine(MockPrinter.marlin());
        var id = register("live only", printer);
        connectAndSettle(id, printer);

        command(id, "/settings/max-feedrate", "{\"x\":250}").expectStatus().isOk();
        await("the live value to change",
                () -> printer.setting("M203").startsWith("M203 X250.00"));

        assertEquals("M203 X300.00 Y300.00 Z5.00 E25.00", printer.stored("M203"),
                "nothing asked for it to be stored, so the stored copy is untouched");

        command(id, "/store-settings", "{}").expectStatus().isOk();
        await("M500 to reach the machine", () -> printer.sent("M500"));
        await("the stored copy to catch up",
                () -> printer.stored("M203").startsWith("M203 X250.00"));
    }

    /** The six settings this feature may change each reach the machine as the right M-code. */
    @Test
    void everyEditableSettingTranslatesToItsOwnCommand() {
        var printer = machine(MockPrinter.marlin());
        var id = register("all six", printer);
        connectAndSettle(id, printer);

        command(id, "/settings/steps-per-unit", "{\"x\":81}").expectStatus().isOk();
        command(id, "/settings/max-feedrate", "{\"x\":250}").expectStatus().isOk();
        command(id, "/settings/max-acceleration", "{\"x\":2500}").expectStatus().isOk();
        command(id, "/settings/acceleration", "{\"print\":2000}").expectStatus().isOk();
        command(id, "/settings/advanced", "{\"junctionDeviation\":0.02}").expectStatus().isOk();
        command(id, "/settings/home-offset", "{\"z\":-0.1}").expectStatus().isOk();

        await("all six to reach the machine", () -> printer.sent("M206 Z"));
        var sent = printer.commands().stream()
                .filter(c -> c.startsWith("M92") || c.startsWith("M203") || c.startsWith("M201")
                        || c.startsWith("M204") || c.startsWith("M205") || c.startsWith("M206"))
                .toList();
        assertEquals(List.of("M92 X81", "M203 X250", "M201 X2500", "M204 P2000", "M205 J0.02",
                        "M206 Z-0.1"), sent,
                "the exact wire text of each, because a generated command class's parameter names "
                        + "are no evidence of which letter it writes");
    }

    // ------------------------------------------------------- reverting, saving and defaulting

    /**
     * Reverting rolls live memory back to the EEPROM - and re-reads it, because nothing else will.
     *
     * <p><strong>This is the design decision the probe forced.</strong> {@code M501} answers a bare
     * {@code ok}: the firmware changes every value in live memory and announces none of them. A
     * farm that sent {@code M501} and stopped would be holding a settings report that is now a lie,
     * and the browser would show it until somebody thought to press Read.
     *
     * <p>So the aggregate sends its own {@code M503} behind the {@code M501}, and the browser is
     * told the reverted values. The assertion is the reverted value arriving on the stream, not the
     * {@code M503} being sent - the command is the mechanism and the event is the promise.
     */
    @Test
    void revertingReReadsTheSettingsBecauseTheFirmwareWillNotSayWhatChanged() {
        var printer = machine(MockPrinter.marlin());
        var id = register("revert", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/settings/steps-per-unit", "{\"x\":81}").expectStatus().isOk();
        await("the live value to change",
                () -> "M92 X81.00 Y80.00 Z400.00 E500.00".equals(printer.setting("M92")));

        command(id, "/settings/revert").expectStatus().isOk();

        await("M501 to reach the machine", () -> printer.sent("M501"));
        await("live memory to be back where it started",
                () -> "M92 X80.00 Y80.00 Z400.00 E500.00".equals(printer.setting("M92")));

        await("the browser to be told the reverted value", () -> events.allOf("SettingsValue")
                .stream().anyMatch(v -> "M92 X80.00 Y80.00 Z400.00 E500.00".equals(v.get("command"))));
        assertTrue(printer.sent("M503"),
                "an M501 that is not followed by a re-read leaves every reader stale");
    }

    /**
     * Resetting to the firmware defaults is a different operation from reverting, and is proved so.
     *
     * <p>On the simulator the stored settings and the defaults differ in exactly one line - the
     * auto-temperature line - and that measured difference is the only thing that can tell a
     * correct {@code M502} from an {@code M501} wearing its name. A mock whose defaults equalled
     * its EEPROM would let the two be swapped silently.
     */
    @Test
    void resettingToFirmwareDefaultsIsNotTheSameAsReverting() {
        var printer = machine(MockPrinter.marlin());
        var id = register("defaults", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/settings/defaults").expectStatus().isOk();

        await("M502 to reach the machine", () -> printer.sent("M502"));
        await("the defaults to be in live memory",
                () -> "M104 S210 B250 F0.10".equals(printer.setting("M104")));

        await("the browser to be told the defaulted value", () -> events.allOf("SettingsValue")
                .stream().anyMatch(v -> "M104 S210 B250 F0.10".equals(v.get("command"))));

        command(id, "/settings/revert").expectStatus().isOk();
        await("reverting to undo the reset",
                () -> "M104 S0 B250 F0.00".equals(printer.setting("M104")));
    }

    /** Storing the settings reaches the machine, and its confirmation reaches the browser. */
    @Test
    void storingTheSettingsIsConfirmedToTheBrowser() {
        var printer = machine(MockPrinter.marlin());
        var id = register("store", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/store-settings", "{}").expectStatus().isOk();

        await("M500 to reach the machine", () -> printer.sent("M500"));
        var notification = events.await("ActionRs");
        assertEquals("notification", notification.get("action"));
        assertEquals("Settings Stored", notification.get("argument"));
    }

    /**
     * Validating the EEPROM reaches the machine and its verdict reaches the browser.
     *
     * <p>The message is asserted verbatim because {@code echo:EEPROM OK} is what was measured. The
     * failing verdict's text was not, so there is no test for it and no boolean in the reply to
     * invite one - see the class note.
     */
    @Test
    void validatingTheEepromReportsItsVerdict() {
        var printer = machine(MockPrinter.marlin());
        var id = register("validate", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/settings/validate").expectStatus().isOk();

        await("M504 to reach the machine", () -> printer.sent("M504"));
        assertEquals("OK", events.await("EepromReport").get("message"));
    }

    // ------------------------------------------------------------------ the two capability gates

    /**
     * A firmware that reports no EEPROM is not offered the tab, and the <em>server</em> decides.
     *
     * <p>The same arrangement the Storage tab has: a capability is true at request time, so the
     * page is rendered without the tab rather than the island mounting and discovering it has
     * nothing to do. The assertion is on the HTML a browser is served, because that is where the
     * decision is visible - a test of a Java predicate would be the server agreeing with itself.
     *
     * <p>The capability is {@code EEPROM}. Issue #88 calls it {@code EEPROM_SETTINGS}, which is the
     * build macro; what appears on the wire is {@code Cap:EEPROM:0|1}.
     */
    @Test
    void aFirmwareWithoutEepromIsNotOfferedTheSettingsTab() {
        var withEeprom = machine(MockPrinter.marlin());
        var able = register("eeprom yes", withEeprom);
        connectAndSettle(able, withEeprom);

        var without = machine(MockPrinter.withoutEeprom());
        var unable = register("eeprom no", without);
        connectAndSettle(unable, without);

        var offered = monitorPage(able);
        var refused = monitorPage(unable);

        assertTrue(offered.contains("data-p3d=\"settings-tab\""),
                "a machine with EEPROM gets the island");
        assertFalse(refused.contains("data-p3d=\"settings-tab\""),
                "a machine without it must not mount an island that can do nothing");
        assertTrue(refused.contains("Settings"),
                "the tab is still named, disabled, so the absence is explained rather than hidden");
    }

    /**
     * A firmware without {@code M503} is learned from its refusal, because nothing else tells us.
     *
     * <p><strong>{@code DISABLE_M503} is not a capability.</strong> Marlin's {@code M115} reports
     * nothing about {@code M503} under any spelling - checked against the simulator's 38
     * {@code Cap:} lines and against {@code M115.cpp} on {@code 2.1.x}. So unlike the EEPROM gate
     * above, this one cannot be decided before the question has been asked once and refused.
     *
     * <p>What the browser is handed is the {@code UnknownCommand} the firmware sent, naming
     * {@code M503}. That is deliberately the firmware's own reply rather than a farm-invented
     * "unsupported" event: the thing that announced the fact is the thing that is published, which
     * is the rule the aggregate's snapshot keeps everywhere else.
     */
    @Test
    void aFirmwareWithoutM503IsLearnedFromItsRefusal() {
        var printer = machine(MockPrinter.withoutSettingsReport());
        var id = register("no m503", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/settings/read").expectStatus().isOk();

        await("M503 to be asked once", () -> printer.sent("M503"));
        var refusal = events.await("UnknownCommand");
        assertEquals("M503", refusal.get("command"));

        quiet();
        assertFalse(events.saw("SettingsValue"),
                "a firmware that has no M503 cannot have reported a setting: " + events.types());
    }

    /**
     * Having learned that, the farm stops asking - and says why.
     *
     * <p>A second Read must not put another {@code M503} on the wire. The cost of getting this
     * wrong is small and the reason to get it right is not: a tab that retries a command the
     * machine has already said it does not have will do it on every page load, for the life of the
     * connection, and the only evidence will be traffic.
     */
    @Test
    void theFarmStopsAskingForAReportTheFirmwareDoesNotHave() {
        var printer = machine(MockPrinter.withoutSettingsReport());
        var id = register("stop asking", printer);
        var events = connectAndSettle(id, printer);

        command(id, "/settings/read").expectStatus().isOk();
        events.await("UnknownCommand");

        command(id, "/settings/read").expectStatus().isOk();
        quiet();

        assertEquals(1, printer.commands().stream().filter("M503"::equals).count(),
                "asked once, refused once, and not asked again: " + printer.commands());
    }

    /**
     * A reconnect forgets what the last session's machine said.
     *
     * <p>A link that was down may have been down because the machine was switched off, and what
     * comes back up can be a different firmware on the same address - a reflash, or a cable moved.
     * {@code SdCardControl} keeps this rule for the card and the settings keep it for the same
     * reason: holding a previous session's report would show an operator values this machine does
     * not have.
     */
    @Test
    void aNewSessionForgetsTheLastOnesSettings() {
        var printer = machine(MockPrinter.marlin());
        var id = register("reconnect", printer);
        var first = connectAndSettle(id, printer);

        command(id, "/settings/read").expectStatus().isOk();
        await("the report to arrive", () -> first.allOf("SettingsValue").size() >= 13);
        first.close();

        disconnect(id);
        connectAndWait(id);

        // Watched *after* the redial, which is the honest shape of the question. The aggregate
        // forgets on going online, so a stream opened while the machine was still down is handed
        // the previous session's report in its snapshot and is never told it went stale - see the
        // note below. What this asserts is the promise a page actually depends on: open the tab on
        // a machine that has just reconnected and nothing from before is presented as current.
        try (var fresh = watch(id)) {
            quiet();
            assertTrue(fresh.allOf("SettingsValue").isEmpty(),
                    "the previous session's report belonged to the previous session: "
                            + fresh.types());
        }
    }

    /**
     * A subscriber that was already watching when the link bounced keeps the old report, and that
     * is <strong>known and left alone</strong>.
     *
     * <p>Not a test of a fix - a test of a limitation, so that it is written down somewhere that
     * fails if it ever silently changes. {@code PrinterReactor.updates()} reads the aggregate's
     * snapshot once, at subscribe time. Subscribe while the machine is down and the snapshot is the
     * previous session's; the {@code sessionStarted} that clears it happens afterwards and emits
     * nothing, because forgetting is not something the machine said.
     *
     * <p><strong>Why that is acceptable rather than a bug to fix here.</strong> The reconnect is
     * announced - {@code ConnectionStateChanged} reaches the browser - so the page has the signal it
     * needs to re-read, and re-reading is one {@code M503}. The alternative is an event meaning
     * "forget what I told you", which is a new kind of message in a stream whose every other member
     * is something a machine actually said. {@code SdCardControl} has exactly the same property for
     * a card listing and has had it since the Storage tab shipped. Worth its own issue, not worth
     * widening this one.
     */
    @Test
    void aSubscriberWatchingAcrossAReconnectKeepsTheOldReportAndIsNotToldOtherwise() {
        var printer = machine(MockPrinter.marlin());
        var id = register("watching across", printer);
        var watching = connectAndSettle(id, printer);

        command(id, "/settings/read").expectStatus().isOk();
        await("the report to arrive", () -> watching.allOf("SettingsValue").size() >= 13);

        disconnect(id);
        connectAndWait(id);
        quiet();

        assertFalse(watching.allOf("SettingsValue").isEmpty(),
                "documented limitation: a live subscriber is not told to forget");
    }

    // ------------------------------------------------------------------ the move off the Monitor

    /**
     * The Monitor tab no longer carries the settings, and the Settings tab does.
     *
     * <p>Item 1 of issue #88, asserted on the page a browser is served. The store button moved
     * rather than being duplicated, so this checks both halves - a move that left the original
     * behind would pass a test that only looked for the new one.
     */
    @Test
    void theSettingsBlockHasMovedOffTheMonitorTab() {
        var printer = machine(MockPrinter.marlin());
        var id = register("moved", printer);
        connectAndSettle(id, printer);

        var page = monitorPage(id);

        assertTrue(page.contains("data-p3d=\"settings-tab\""),
                "the Settings tab's island is on the page");
        assertTrue(page.contains("#settings"), "and it has a tab that opens it");
        assertFalse(page.contains("Store settings (M500)"),
                "the Monitor tab's own store button moved rather than being duplicated");
    }

    /** The page a browser is served for one machine. */
    private String monitorPage(java.util.UUID id) {
        var body = farm.get().uri("/printers/" + id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(body, "the monitor page rendered nothing");
        return body;
    }
}

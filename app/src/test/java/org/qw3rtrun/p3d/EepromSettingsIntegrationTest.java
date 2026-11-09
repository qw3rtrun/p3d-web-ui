package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine's settings - issue #88's feature, the five operations of {@code M500}-{@code M504}.
 * Every test asserts <strong>both</strong> ends of one round trip, the command reaching the machine
 * and the event reaching the browser - this repository has shipped three complete, green, inert
 * features (the motion commands, then the Storage tab's commands, then its replies) that a
 * one-ended test would have passed; {@code SdCardIntegrationTest} says the same thing at length.
 *
 * <h2>What was measured, and what was not</h2>
 *
 * <p>Probed against {@code FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56)
 * MACHINE_TYPE:MarlinSim} on 2026-11-04. {@code M503} reports live memory as the commands that
 * would restore it, in two shapes - {@code echo:}-prefixed with section headers, and under
 * {@code S0} bare and indented with no headers, which a single-setting query also answers in, so
 * one reply type covers a dump and a read-back. {@code M501} and {@code M502} answer a bare
 * {@code ok} and say nothing about what changed - the fact the design is built around, pinned by
 * {@link #revertingReReadsTheSettingsBecauseTheFirmwareWillNotSayWhatChanged()}. {@code M500}
 * answers {@code //action:notification Settings Stored}, decoding to the existing {@code ActionRs}.
 * {@code M504} answers {@code echo:EEPROM OK}; its <em>failure</em> text is UNMEASURED, so nothing
 * here asserts a negative verdict and the reply carries the message verbatim rather than a boolean.
 *
 * <h2>The configuration axis is which half of the EEPROM story the firmware tells</h2>
 *
 * <p>Three profiles, because issue #88's two named "capabilities" are not the same kind of thing:
 * {@link MockPrinter#marlin()} ({@code Cap:EEPROM:1} and {@code M503} present, the baseline);
 * {@link MockPrinter#withoutEeprom()} ({@code Cap:EEPROM:0}, so the tab is never offered and the
 * server decides); and {@link MockPrinter#withoutSettingsReport()} (EEPROM but no {@code M503},
 * Marlin's {@code DISABLE_M503}) - there is no capability line for this under any spelling,
 * verified against the simulator's 38 {@code Cap:} lines and Marlin's own {@code M115.cpp}, so the
 * farm learns it only from the reply, which {@link #aFirmwareWithoutM503IsLearnedFromItsRefusal()}
 * covers.
 */
class EepromSettingsIntegrationTest extends FarmIntegrationTest {

    // ------------------------------------------------------------------ reading the settings

    /**
     * Twenty-five lines on the wire become twenty-five messages; the count is asserted because the
     * failure this guards against is a host that decodes the first line and drops the rest -
     * precisely what happened before {@code SdFileListReader} existed.
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
     * Asserted as the string the browser receives, not a parsed model: the report <em>is</em>
     * G-code, which is the decision that let this feature avoid a second number model - a payload
     * parsed into fields would pass a test written against fields and tell the browser nothing it
     * could round-trip.
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
     * The snapshot half of the aggregate's contract, and the reason {@code EEPROMSettingsControl}
     * exists rather than the replies being simply forwarded - without it a page opened after
     * somebody else pressed Read shows an empty table with no way out but asking again.
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
     * Two observables, because either alone has been wrong here before: {@code printer.commands()}
     * is what left the host, {@code printer.setting} is what the machine holds afterwards - the
     * second is what makes this a round trip rather than an assertion that a POST returned 200.
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
     * The distinction issue #88 draws in its own words - "machine live memory, not eeprom" - and
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
     * The aggregate sends its own {@code M503} behind the {@code M501}, so the browser is told the
     * reverted values; the assertion is the value arriving on the stream, not the {@code M503}
     * being sent - the command is the mechanism, the event is the promise.
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
     * On the simulator the stored settings and the defaults differ in exactly one line - the
     * auto-temperature line - the only thing that can tell a correct {@code M502} from an
     * {@code M501} wearing its name; a mock whose defaults equalled its EEPROM would let the two be
     * swapped silently.
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
     * Asserted verbatim because {@code echo:EEPROM OK} is what was measured; the failing verdict's
     * text was not (UNMEASURED), so there is no test for it and no boolean in the reply to invite one.
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
     * The same arrangement as the Storage tab: the page is rendered without the tab rather than the
     * island mounting and discovering it has nothing to do, asserted on the served HTML since a Java
     * predicate test would just be the server agreeing with itself. The capability is
     * {@code EEPROM} - issue #88 calls it {@code EEPROM_SETTINGS}, the build macro; the wire shows
     * {@code Cap:EEPROM:0|1}.
     */
    @Test
    void aFirmwareWithoutEepromIsNotOfferedTheSettingsTab() {
        var withEeprom = machine(MockPrinter.marlin());
        var able = register("eeprom yes", withEeprom);
        connectAndSettle(able, withEeprom);

        var without = machine(MockPrinter.withoutEeprom());
        var unable = register("eeprom no", without);
        connectAndSettle(unable, without);

        // Both halves, not just the positive one: the disabled tab below also renders for a machine
        // that has not reported at all, so without this wait the negative assertion could pass on a
        // page that knows nothing rather than on one that knows there is no EEPROM.
        awaitCapability(able, "EEPROM");
        awaitCapability(unable, "EEPROM");

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
     * Unlike the EEPROM gate above, this cannot be decided before being asked once and refused -
     * what the browser is handed is the firmware's own {@code UnknownCommand} naming {@code M503},
     * not a farm-invented "unsupported" event, since the thing that announced the fact is the thing
     * published.
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
     * A tab that retries a command the machine has already refused will do it on every page load
     * for the life of the connection, with only traffic as evidence - small cost, large reason to
     * get right.
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
     * A link that was down may come back up as a different firmware on the same address - a
     * reflash, a cable moved. {@code SdCardControl} keeps this rule for the card; settings keep it
     * for the same reason - holding a previous session's report would show an operator values this
     * machine lacks.
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

        // Watched *after* the redial - the honest shape of the question. The aggregate forgets on
        // going online; a stream opened while still down would get the previous session's report in
        // its snapshot (see the note below). This asserts the promise a page depends on: open the
        // tab on a just-reconnected machine and nothing from before is presented as current.
        try (var fresh = watch(id)) {
            quiet();
            assertTrue(fresh.allOf("SettingsValue").isEmpty(),
                    "the previous session's report belonged to the previous session: "
                            + fresh.types());
        }
    }

    /**
     * Issue #94, and the <strong>reversal</strong> of what this test asserted when #88 shipped it.
     *
     * <p>It used to pin the opposite - that a live subscriber keeps the old report and is not told
     * otherwise - on the argument that the reconnect is already announced by
     * {@code ConnectionStateChanged}, so the page could re-read with one {@code M503}, and that an
     * event meaning "forget what I told you" would be a new kind of message in a stream whose every
     * other member is something a machine actually said. Three measurements overturned it:
     *
     * <ul>
     *   <li>the premise is not true of this stream. {@code api.msg}'s own package note calls the
     *       package "the farm's own messages: the things that happen to a machine which no firmware
     *       can state", and {@code EventMessage}'s javadoc names six of them already travelling
     *       here - {@code ConnectionStateChanged}, {@code BlockAcknowledged},
     *       {@code BlocksAbandoned}, {@code SdUploadProgressed}, {@code MachineJoinedFarm},
     *       {@code MachineLeftFarm}. A session boundary is one more fact about the host's link, not
     *       a new kind of thing;</li>
     *   <li>the fallback cannot work for the reader it matters to. {@code SecurityConfiguration}
     *       puts {@code POST /api-printer/**} behind {@code OPERATOR}/{@code ADMIN} - "the
     *       {@code M104 S300} boundary" - while a {@code VIEWER} may hold the stream. A viewer
     *       cannot ask for {@code M503} or {@code M20}, so under the re-read design a viewer holds
     *       the previous session's values for as long as the tab is open, with nothing marking
     *       them;</li>
     *   <li>the client-side half had already been written and is keyed off the wrong state.
     *       {@code SettingsTab.vue} cleared its table on {@code to === 'OFFLINE'}, but a link that
     *       drops while a connection is still wanted reaches {@code ConnectionLifecycle.linkFailed}
     *       with {@code wanted} set, which moves to {@code CONNECTING} - see
     *       {@code ConnectivityIntegrationTest.aDroppedLinkThatComesBackNeverAnnouncesOffline}. The
     *       guard fired only on the deliberate disconnect, which is not this issue's case.</li>
     * </ul>
     *
     * <p>So the aggregate states the boundary instead, as {@code SessionStarted}, at the point it
     * already forgets ({@code PrinterState.onOnline}). What the page then does with it - blank the
     * panel or mark it stale - is the page's call; the server's job is only to make the distinction
     * available.
     */
    @Test
    void aSubscriberWatchingAcrossAReconnectIsToldThePreviousSessionEnded() {
        var printer = machine(MockPrinter.marlin());
        var id = register("watching across", printer);
        var watching = connectAndSettle(id, printer);

        command(id, "/settings/read").expectStatus().isOk();
        await("the report to arrive", () -> watching.allOf("SettingsValue").size() >= 13);
        var reportEndedAt = watching.types().lastIndexOf("SettingsValue");

        dropTheLinkAndWait(id, printer);

        watching.await("SessionStarted");
        assertTrue(watching.types().lastIndexOf("SessionStarted") > reportEndedAt,
                "the boundary has to arrive after the report it invalidates, or a page applying it"
                        + " in order would end up marking a report that is current: "
                        + watching.types());
    }

    /**
     * The other half of the same promise, and the reason the fix is an event rather than a cleared
     * snapshot: the aggregate really has forgotten, so a page that re-reads gets the new session's
     * answer rather than a cached one, and a page that never re-reads is not quietly re-handed the
     * old values by a reconnecting stream.
     */
    @Test
    void theAggregateHasActuallyForgottenByTheTimeItSaysSo() {
        var printer = machine(MockPrinter.marlin());
        var id = register("forgot across", printer);
        var watching = connectAndSettle(id, printer);

        command(id, "/settings/read").expectStatus().isOk();
        await("the report to arrive", () -> watching.allOf("SettingsValue").size() >= 13);

        dropTheLinkAndWait(id, printer);
        watching.await("SessionStarted");

        try (var fresh = watch(id)) {
            quiet();
            assertTrue(fresh.allOf("SettingsValue").isEmpty(),
                    "a stream opened after the boundary must carry nothing from before it: "
                            + fresh.types());
        }
    }

    // ------------------------------------------------------------------ the move off the Monitor

    /**
     * Item 1 of issue #88, asserted on the served page. The store button moved rather than being
     * duplicated, so this checks both halves - a move that left the original behind would pass a
     * test that only looked for the new one.
     */
    @Test
    void theSettingsBlockHasMovedOffTheMonitorTab() {
        var printer = machine(MockPrinter.marlin());
        var id = register("moved", printer);
        connectAndSettle(id, printer);
        // The page is drawn from the roster, which learns a capability after the aggregate does -
        // see FarmIntegrationTest.awaitCapability. Without this the tab is legitimately absent.
        awaitCapability(id, "EEPROM");

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

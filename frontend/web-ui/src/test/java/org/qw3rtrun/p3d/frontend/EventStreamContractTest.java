package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MachineJoinedFarm;
import org.qw3rtrun.p3d.api.msg.MachineLeftFarm;
import org.qw3rtrun.p3d.api.msg.SdUploadProgressed;
import org.qw3rtrun.p3d.api.msg.SessionStarted;
import org.qw3rtrun.p3d.api.msg.SdUploadState;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.EepromReport;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.OkTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.PositionRs;
import org.qw3rtrun.p3d.g.marlin.event.SdCardOk;
import org.qw3rtrun.p3d.g.marlin.event.SdFile;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.SdFileOpened;
import org.qw3rtrun.p3d.g.marlin.event.SettingsSection;
import org.qw3rtrun.p3d.g.marlin.event.SettingsValue;
import org.qw3rtrun.p3d.g.marlin.event.UnknownCommand;
import org.qw3rtrun.p3d.g.protocol.ActionRs;
import org.qw3rtrun.p3d.g.protocol.ErrorPrefix;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.qw3rtrun.p3d.g.protocol.SimpleBusyRs;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON the browser actually receives on {@code /api-printer/&#123;id&#125;/stream}.
 *
 * <p>A contract the Java build cannot see: the Vue islands under {@code src/frontend} switch on
 * {@code message.type} and read fields like {@code event.hotend.target}, type-checked by
 * {@code vue-tsc} only against their own declarations in {@code api/types.ts} - nothing on either
 * side notices a rename here stop matching a string there. Not hypothetical: the UI once checked for
 * {@code "TemperatureReported"} while the stream carried {@code "OkTemperatureRs"}, so every
 * temperature fell through its {@code if} and the page silently stopped updating.
 *
 * <p>{@code type} is the protocol's own name again: a reply travels as the {@code GRs} {@code :gcode}
 * decoded, so {@code BareTemperatureRs}/{@code OkTemperatureRs} are both possible for one reading and
 * the UI matches the family rather than one name. The contract can still drift; these assertions are
 * what would catch it.
 */
class EventStreamContractTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private String streamed(Object event) {
        return mapper.writeValueAsString(new EventMessage(UUID.randomUUID(), event));
    }

    private static BareTemperatureRs temperatures(HeaterReading hotend, HeaterReading bed) {
        return new BareTemperatureRs(hotend, bed, null, null, null, null, null, Map.of());
    }

    private static HeaterReading reading(String current, String target, Integer power) {
        return new HeaterReading(new BigDecimal(current),
                target == null ? null : new BigDecimal(target), power);
    }

    // The farm stream's membership events (plan item D2) - same envelope as everything else, and
    // the only two events on that stream that are about the farm rather than about one machine.
    @Test
    void aMachineJoiningOrLeavingTheFarmCarriesTheTypeAndItsName() {
        var joined = mapper.readTree(streamed(
                new MachineJoinedFarm("big-PETG")));

        assertEquals("MachineJoinedFarm", joined.get("type").asString());
        assertEquals("big-PETG", joined.get("event").get("name").asString());
        assertTrue(joined.get("id").asString().length() == 36, "the machine it is about");

        var left = mapper.readTree(streamed(new MachineLeftFarm()));
        assertEquals("MachineLeftFarm", left.get("type").asString());
    }

    // payload.type, then payload.event.hotend.target and payload.event.bed.current - field names
    // that survived the move to the protocol's own reply; only the type string and nullability moved.
    @Test
    void aTemperatureCarriesTheTypeAndTheFieldsTheUiReads() {
        var json = streamed(temperatures(
                reading("210.0", "205.0", 127),
                reading("60.0", "55.0", 80)));

        var payload = mapper.readTree(json);

        assertEquals("BareTemperatureRs", payload.get("type").asString());
        assertEquals(210.0, payload.get("event").get("hotend").get("current").asDouble());
        assertEquals(205.0, payload.get("event").get("hotend").get("target").asDouble());
        assertEquals(127, payload.get("event").get("hotend").get("power").asInt());
        assertEquals(60.0, payload.get("event").get("bed").get("current").asDouble());
        assertEquals(55.0, payload.get("event").get("bed").get("target").asDouble());
    }

    // M105 answers on the ok itself and M155 auto-reports on a line of its own, so one reading has
    // two classes and a different type string - the exact drift that broke the page once. The
    // TypeScript side matches the family with isTemperature rather than either name.
    @Test
    void aTemperatureOnAnOkIsTheOtherTypeString() {
        var payload = mapper.readTree(streamed(new OkTemperatureRs(
                reading("45.26", "45.00", 3), null, null, null, null, null, null, Map.of())));

        assertEquals("OkTemperatureRs", payload.get("type").asString());
        assertEquals(45.26, payload.get("event").get("hotend").get("current").asDouble());
    }

    // A printer with no bed is not a printer with a cold bed: no sensor serialises as null, not
    // zeroes, so the UI needs a guard on bed.current rather than trusting a number.
    @Test
    void anAbsentBedIsNullAndNotZero() {
        var payload = mapper.readTree(
                streamed(temperatures(reading("24.31", "0.00", 0), null)));

        assertTrue(payload.get("event").get("bed").isNull());
    }

    // An unstated target is null, not 0.0 - which a page could not tell from a hotend told to cool
    // to zero. The UI has to treat null as unknown, not as a number.
    @Test
    void anUnstatedTargetIsNullRatherThanZero() {
        var payload = mapper.readTree(
                streamed(temperatures(reading("24.31", null, null), null)));

        assertTrue(payload.get("event").get("hotend").get("target").isNull());
        assertTrue(payload.get("event").get("hotend").get("power").isNull());
    }

    // Replaced four event names (plan item B3) that the page reduced to a pair of booleans it could
    // not keep in step - a connected-but-quiet printer read as offline. One of the farm's own
    // events, not a reply, so it is named by this application rather than any firmware.
    @Test
    void aConnectionChangeCarriesTheStateTheUiSwitchesOn() {
        var json = streamed(new ConnectionStateChanged(
                MachineConnectionState.CONNECTING, MachineConnectionState.ONLINE, 2, "connection reset"));

        var payload = mapper.readTree(json);

        assertEquals("ConnectionStateChanged", payload.get("type").asString());
        assertEquals("CONNECTING", payload.get("event").get("from").asString());
        assertEquals("ONLINE", payload.get("event").get("to").asString());
        assertEquals(2, payload.get("event").get("attempt").asInt());
        assertEquals("connection reset", payload.get("event").get("reason").asString());
    }

    /** A transition with nothing to explain says so with a null rather than an empty string. */
    @Test
    void aConnectionChangeWithNoReasonCarriesNull() {
        var payload = mapper.readTree(streamed(new ConnectionStateChanged(
                MachineConnectionState.ONLINE, MachineConnectionState.IDLE, 0, null)));

        assertTrue(payload.get("event").get("reason").isNull());
    }

    /**
     * Issue #94. The aggregate forgets the previous session's EEPROM report and card listing on a
     * new link; this is how a browser that was already watching is told, since
     * {@code PrinterReactor.updates()} reads the snapshot once and only a newly attached subscriber
     * ever saw the cleared state.
     *
     * <p>The payload is an empty object by design - the boundary itself is the whole content, and
     * which machine it is about travels on {@code id}. Asserted to be an object carrying no fields
     * so that adding one has to come through here and think about the browser first.
     */
    @Test
    void aSessionBoundaryCarriesItsTypeAndNothingElse() {
        var payload = mapper.readTree(streamed(new SessionStarted()));

        assertEquals("SessionStarted", payload.get("type").asString());
        assertTrue(payload.get("event").isObject(), "an object, not a null or a string");
        assertEquals(0, payload.get("event").size(),
                "a field here is a field the TypeScript client has to learn: "
                        + payload.get("event"));
    }

    // ErrorRs carries the firmware's own words in message and the resend line in lastLine.
    @Test
    void theOtherEventsTheStreamCarriesAreNamedAfterTheirReply() {
        var error = mapper.readTree(
                streamed(new ErrorRs(ErrorPrefix.ERROR, "checksum mismatch, Last Line: 2")));
        assertEquals("ErrorRs", error.get("type").asString());
        assertEquals("checksum mismatch, Last Line: 2", error.get("event").get("message").asString());
        assertEquals(2, error.get("event").get("lastLine").asInt());

        var busy = mapper.readTree(streamed(new SimpleBusyRs("processing")));
        assertEquals("SimpleBusyRs", busy.get("type").asString());
        assertEquals("processing", busy.get("event").get("reason").asString());
    }

    // The firmware writes "Begin file list", an entry each, "End file list"; the transport
    // assembles them into one SdFileList. name is the card's spelling (DOS 8.3 upper case without
    // long-filename support), which is what M23/M30 take - the page sends back what it was given.
    @Test
    void aCardListingCarriesItsFilesAndTheirSizes() {
        var payload = mapper.readTree(streamed(new SdFileList(List.of(
                new SdFile("BENCHY.GCO", 2847561),
                new SdFile("TEST.GCO", 47)))));

        assertEquals("SdFileList", payload.get("type").asString());
        assertEquals("BENCHY.GCO", payload.get("event").get("files").get(0).get("name").asString());
        assertEquals(2847561, payload.get("event").get("files").get(0).get("size").asLong());
        assertEquals(47, payload.get("event").get("files").get(1).get("size").asLong());
    }

    // An empty card sends an empty list, not no event at all - the page must tell that apart from
    // a machine that never replied (an interrupted listing).
    @Test
    void anEmptyCardListingIsAnEmptyArrayAndNotAbsence() {
        var payload = mapper.readTree(streamed(new SdFileList(List.of())));

        assertEquals("SdFileList", payload.get("type").asString());
        assertTrue(payload.get("event").get("files").isArray());
        assertEquals(0, payload.get("event").get("files").size());
    }

    // SdCardOk carries nothing and is the whole of "there is a card" - no command asks that
    // directly. SdFileOpened carries the size a progress bar divides by.
    @Test
    void theOtherTwoCardRepliesCarryTheirNamesAndFields() {
        var ok = mapper.readTree(streamed(SdCardOk.INSTANCE));
        assertEquals("SdCardOk", ok.get("type").asString());

        var opened = mapper.readTree(streamed(new SdFileOpened("BENCHY.GCO", 2847561)));
        assertEquals("SdFileOpened", opened.get("type").asString());
        assertEquals("BENCHY.GCO", opened.get("event").get("file").asString());
        assertEquals(2847561, opened.get("event").get("size").asLong());
    }

    // bytesSent/bytesTotal is the exact progress bar - the file's size is known before a line goes
    // out. blocksSent has no total on purpose: sliced files drop comments/blank lines, so the line
    // count is only an upper bound. file is the name on the card, which may differ from what was
    // requested if the server had to add a printable extension.
    @Test
    void anUploadProgressReportCarriesBothCountsAndTheNameOnTheCard() {
        var payload = mapper.readTree(streamed(new SdUploadProgressed(
                "BENCHY.GCO", SdUploadState.UPLOADING, 1_200_000, 2_847_561, 31_006, null)));

        assertEquals("SdUploadProgressed", payload.get("type").asString());
        assertEquals("BENCHY.GCO", payload.get("event").get("file").asString());
        assertEquals("UPLOADING", payload.get("event").get("state").asString());
        assertEquals(1_200_000, payload.get("event").get("bytesSent").asLong());
        assertEquals(2_847_561, payload.get("event").get("bytesTotal").asLong());
        assertEquals(31_006, payload.get("event").get("blocksSent").asLong());
        assertTrue(payload.get("event").get("reason").isNull());
    }

    // How a transfer ended decides what is on the card: a cancel sends M29, leaving a short but
    // closed file (safe to delete, not to print); a failure may not - an emergency stop preempts
    // the transfer with no close behind it, leaving the file open until the next reset.
    @Test
    void theUploadStatesReachTheBrowserByName() {
        for (var state : SdUploadState.values()) {
            var payload = mapper.readTree(streamed(
                    new SdUploadProgressed("B.GCO", state, 1, 2, 3, null)));
            assertEquals(state.name(), payload.get("event").get("state").asString());
        }

        var failed = mapper.readTree(streamed(new SdUploadProgressed(
                "B.GCO", SdUploadState.FAILED, 1, 2, 3, "the connection went away")));
        assertEquals("the connection went away", failed.get("event").get("reason").asString());
    }

    // Axes are a map keyed by the letter Marlin used, not named fields, since which axes exist is
    // the machine's business (a delta reports X/Y/Z, an IDEX two carriages). Values are BigDecimal,
    // reaching the browser as JSON numbers with the firmware's own scale (0.00, not 0).
    @Test
    void aPositionReportCarriesItsAxesByLetter() {
        var axes = new LinkedHashMap<Character, BigDecimal>();
        axes.put('X', new BigDecimal("0.00"));
        axes.put('Y', new BigDecimal("10.50"));

        var payload = mapper.readTree(streamed(new PositionRs(axes, Map.of())));

        assertEquals("PositionRs", payload.get("type").asString());
        assertEquals(0.0, payload.get("event").get("axes").get("X").asDouble());
        assertEquals(10.5, payload.get("event").get("axes").get("Y").asDouble());
    }

    // ---------------------------------------------------------------- the settings report

    // M503's report as the two type strings the Settings tab switches on. Sections and values are
    // separate events, interleaved in firmware order with nothing nesting one in the other, so a
    // value belongs to the most recent section ahead of it. command is the firmware's own line,
    // asserted as a string - deliberately no parsed model, so the tab tokenises this text itself.
    @Test
    void theSettingsReportCarriesItsSectionsAndItsCommandText() {
        var section = mapper.readTree(streamed(new SettingsSection("Steps per unit")));
        assertEquals("SettingsSection", section.get("type").asString());
        assertEquals("Steps per unit", section.get("event").get("title").asString());

        var value = mapper.readTree(streamed(
                new SettingsValue("M92", "M92 X80.00 Y80.00 Z400.00 E500.00")));
        assertEquals("SettingsValue", value.get("type").asString());
        assertEquals("M92", value.get("event").get("code").asString());
        assertEquals("M92 X80.00 Y80.00 Z400.00 E500.00", value.get("event").get("command").asString());
    }

    // A real heading off the measured build. Angle brackets reach the browser verbatim and would be
    // read as markup by unescaped interpolation, so the tab must render the title as text.
    @Test
    void aSectionTitleKeepsTheBracketsTheFirmwareWrote() {
        var payload = mapper.readTree(streamed(new SettingsSection(
                "Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>)")));

        assertEquals("Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>)",
                payload.get("event").get("title").asString());
    }

    // code is not a key: the measured build's "Material heatup parameters" section holds two M145
    // lines, one preset each, so two events share a code with different commands - both must show.
    @Test
    void twoValuesCanShareACode() {
        var first = mapper.readTree(streamed(
                new SettingsValue("M145", "M145 S0 H180 B60 F0")));
        var second = mapper.readTree(streamed(
                new SettingsValue("M145", "M145 S1 H240 B110 F0")));

        assertEquals("M145", first.get("event").get("code").asString());
        assertEquals("M145", second.get("event").get("code").asString());
        assertTrue(!first.get("event").get("command").asString()
                        .equals(second.get("event").get("command").asString()),
                "two presets under one code, which is why the code is not a key");
    }

    // M504's verdict is a message on purpose: echo:EEPROM OK is the only answer ever measured (the
    // simulator cannot be asked for a corrupt EEPROM), so there is no boolean to colour a page from.
    @Test
    void theEepromVerdictIsTheFirmwaresOwnMessage() {
        var payload = mapper.readTree(streamed(new EepromReport("OK")));

        assertEquals("EepromReport", payload.get("type").asString());
        assertEquals("OK", payload.get("event").get("message").asString());
    }

    // DISABLE_M503 is not a capability: M115 says nothing about M503 under any spelling, checked
    // against the simulator's 38 Cap: lines and against Marlin's own M115.cpp. So the only way to
    // learn there is no report is asking and being refused, as UnknownCommand("M503") - pinned
    // because the tab switches on type and payload together, and a refusal of anything else must
    // not read as "no settings report".
    @Test
    void aRefusedCommandNamesWhatTheFirmwareDidNotKnow() {
        var payload = mapper.readTree(streamed(new UnknownCommand("M503")));

        assertEquals("UnknownCommand", payload.get("type").asString());
        assertEquals("M503", payload.get("event").get("command").asString());
    }

    // M500's confirmation is a //action: line, not a reply of its own, and the whole of the
    // acknowledgement for the one command whose effect outlives the connection. argument is
    // nullable in general - plenty of actions carry no payload.
    @Test
    void theStoredSettingsNotificationReachesTheBrowserAsAnAction() {
        var payload = mapper.readTree(streamed(new ActionRs("notification", "Settings Stored")));

        assertEquals("ActionRs", payload.get("type").asString());
        assertEquals("notification", payload.get("event").get("action").asString());
        assertEquals("Settings Stored", payload.get("event").get("argument").asString());

        var bare = mapper.readTree(streamed(new ActionRs("pause", null)));
        assertTrue(bare.get("event").get("argument").isNull());
    }
}

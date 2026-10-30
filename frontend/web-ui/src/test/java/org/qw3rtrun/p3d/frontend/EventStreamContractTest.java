package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MachineJoinedFarm;
import org.qw3rtrun.p3d.api.msg.MachineLeftFarm;
import org.qw3rtrun.p3d.api.msg.SdUploadProgressed;
import org.qw3rtrun.p3d.api.msg.SdUploadState;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.OkTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.PositionRs;
import org.qw3rtrun.p3d.g.marlin.event.SdCardOk;
import org.qw3rtrun.p3d.g.marlin.event.SdFile;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.SdFileOpened;
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
 * <p>This is a contract with code the Java build does not compile: the Vue islands under
 * {@code src/frontend} switch on {@code message.type} and then read {@code event.hotend.target}.
 * They are type-checked by {@code vue-tsc}, but <strong>only against their own declarations</strong>
 * in {@code src/frontend/src/api/types.ts} - nothing on either side can notice when a rename here
 * stops matching a string there.
 *
 * <p>That is not hypothetical: the UI checked for {@code "TemperatureReported"} while the stream
 * had started carrying {@code "OkTemperatureRs"}, so every temperature fell through its {@code if}
 * and the page quietly stopped updating. Nothing in the build said a word.
 *
 * <p><strong>The names are the protocol's again, deliberately.</strong> A reply travels as the
 * {@code GRs} {@code :gcode} decoded, because a Marlin farm has no use for a second model of what a
 * Marlin says - so {@code type} is {@code BareTemperatureRs} or {@code OkTemperatureRs}, and the UI
 * matches the family rather than one name. That is a contract that can still drift, which is
 * exactly why these assertions exist; what changed is that there is no longer a translation layer
 * to drift as well.
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

    /**
     * The farm stream's membership events - plan item D2.
     *
     * <p>Same envelope as everything else, so a dashboard routes on {@code id} and switches on
     * {@code type} exactly as a monitor page does. These two are the only events on that stream
     * that are about the farm rather than about a machine, and a page that misses them shows a
     * printer that is gone or misses one that arrived until somebody reloads.
     */
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

    /**
     * The shape the temperature controls read, field for field.
     *
     * <p>{@code payload.type}, then {@code payload.event.hotend.target} and
     * {@code payload.event.bed.current}. Those two field names survived the move to the protocol's
     * own reply - {@code TemperatureRs} calls them the same thing - so what moved for the browser
     * is the type string and the nullability.
     */
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

    /**
     * A temperature answered on an {@code ok} is a <strong>different type string</strong>.
     *
     * <p>{@code M105} answers on the {@code ok} itself and an {@code M155} auto-report arrives on a
     * line of its own, so one reading has two classes - and both reach the browser. This is the
     * exact drift that broke the page: the UI matched one name and every reading of the other kind
     * fell through its {@code if}. The TypeScript side matches the family with {@code isTemperature}
     * rather than a name, and these two strings are what that function lists.
     */
    @Test
    void aTemperatureOnAnOkIsTheOtherTypeString() {
        var payload = mapper.readTree(streamed(new OkTemperatureRs(
                reading("45.26", "45.00", 3), null, null, null, null, null, null, Map.of())));

        assertEquals("OkTemperatureRs", payload.get("type").asString());
        assertEquals(45.26, payload.get("event").get("hotend").get("current").asDouble());
    }

    /**
     * A reply with no bed sensor serialises the bed as null rather than as zeroes.
     *
     * <p>Worth pinning because the two statements are different - a printer with no bed is not a
     * printer with a cold bed - and the UI reads {@code bed.current}, so a bedless machine needs a
     * guard on that side.
     */
    @Test
    void anAbsentBedIsNullAndNotZero() {
        var payload = mapper.readTree(
                streamed(temperatures(reading("24.31", "0.00", 0), null)));

        assertTrue(payload.get("event").get("bed").isNull());
    }

    /**
     * A target the firmware did not state is null, and that is new.
     *
     * <p>The record this replaced had no way to say "not reported": an absent target arrived as
     * {@code 0.0}, which a page cannot tell from a hotend that has been told to cool to zero. The
     * UI has to treat null as unknown rather than as a number.
     */
    @Test
    void anUnstatedTargetIsNullRatherThanZero() {
        var payload = mapper.readTree(
                streamed(temperatures(reading("24.31", null, null), null)));

        assertTrue(payload.get("event").get("hotend").get("target").isNull());
        assertTrue(payload.get("event").get("hotend").get("power").isNull());
    }

    /**
     * The connection event, field for field, as the UI reads it.
     *
     * <p>A contract that was <strong>four</strong> event names until plan item B3:
     * {@code ConnectedEvent}, {@code DisconnectedEvent}, {@code MachineOnlineEvent} and
     * {@code MachineOfflineEvent}, which the page reduced to a pair of booleans it could not keep
     * in step - a printer that was connected but quiet read as offline, because the only thing that
     * ever set {@code online} was the arrival of some unrelated event. One event carrying one state
     * is what replaced them, and these assertions are what keep the two sides agreeing.
     *
     * <p>It is one of the farm's own events rather than a reply, so no firmware has a word for it
     * and it is still named by this application.
     */
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
     * An error and a busy notice, as the protocol names them.
     *
     * <p>{@code ErrorRs} carries the firmware's own words in {@code message} and the line a resend
     * refers to in {@code lastLine}, which is what the old {@code ErrorReport} carried - under the
     * same two names, because that record was copied from this one.
     */
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

    /**
     * The card's listing, which is the one reply on this stream that was never a single line.
     *
     * <p>The firmware writes {@code Begin file list}, an entry each, {@code End file list}; the
     * transport assembles them and publishes one of these. So the browser sees one event with a
     * list in it, and the type string it switches on is {@code SdFileList}.
     *
     * <p><strong>{@code size} is a number and {@code name} is the card's spelling.</strong> A build
     * without long-filename support reports DOS 8.3 in upper case, and that same text is what
     * {@code M23} and {@code M30} take - so the page sends back what the listing gave it rather
     * than what the user typed.
     */
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

    /**
     * A card with nothing printable on it sends an empty list, and that is an answer.
     *
     * <p>Different from no event at all, which is what an interrupted listing produces - so the
     * page can tell an empty card from a machine that never replied, and must not render the two
     * the same way.
     */
    @Test
    void anEmptyCardListingIsAnEmptyArrayAndNotAbsence() {
        var payload = mapper.readTree(streamed(new SdFileList(List.of())));

        assertEquals("SdFileList", payload.get("type").asString());
        assertTrue(payload.get("event").get("files").isArray());
        assertEquals(0, payload.get("event").get("files").size());
    }

    /**
     * The two other SD replies the Storage tab reads, by the names it switches on.
     *
     * <p>{@code SdCardOk} carries nothing and is the whole of "there is a card": no command asks
     * that question, so mounting and watching for this is how the tab finds out.
     * {@code SdFileOpened} carries the size a progress bar divides by.
     */
    @Test
    void theOtherTwoCardRepliesCarryTheirNamesAndFields() {
        var ok = mapper.readTree(streamed(SdCardOk.INSTANCE));
        assertEquals("SdCardOk", ok.get("type").asString());

        var opened = mapper.readTree(streamed(new SdFileOpened("BENCHY.GCO", 2847561)));
        assertEquals("SdFileOpened", opened.get("type").asString());
        assertEquals("BENCHY.GCO", opened.get("event").get("file").asString());
        assertEquals(2847561, opened.get("event").get("size").asLong());
    }

    /**
     * An upload's progress, as the Storage tab reads it.
     *
     * <p><strong>Two counts, and the UI must not confuse them.</strong> {@code bytesSent} over
     * {@code bytesTotal} is the progress bar - exact, because the stored file's size is known
     * before a line goes out. {@code blocksSent} has <em>no</em> total on purpose: a tenth of a
     * sliced file is comments and blank lines that are dropped rather than sent, so the file's line
     * count is an upper bound and a bar driven by it would stop short every time.
     *
     * <p>{@code file} is the name on the <em>card</em>, which may not be the name that was
     * requested - the server adds a printable extension to one that lacks it, and this is how the
     * page learns what it became.
     */
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

    /**
     * The four end states, because <strong>how a transfer ended decides what is on the card</strong>.
     *
     * <p>A cancel sent {@code M29}, so the file is short but closed - safe to delete, not safe to
     * print. A failure may not have: an emergency stop preempts a transfer and there is no close
     * behind it, so the file is left open until the next reset. A page that showed both as "did not
     * finish" would be hiding the difference that matters.
     */
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

    /**
     * The position report the move controls read - {@code M114}'s answer.
     *
     * <p>Axes are a map keyed by the letter Marlin used, because which axes exist is the machine's
     * business: a delta reports X/Y/Z, an IDEX reports two carriages. A page renders what it was
     * given, so the shape must stay a map rather than becoming four named fields the day a
     * five-axis machine appears.
     *
     * <p>The values are {@code BigDecimal} and reach the browser as JSON numbers with the scale the
     * firmware wrote - {@code 0.00} rather than {@code 0}. That is a string-formatting matter for
     * whoever renders it, and it is why this asserts the parsed number rather than the text.
     */
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
}

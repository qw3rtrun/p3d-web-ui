package org.qw3rtrun.p3d.frontend;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.BusyReport;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.ErrorReport;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReported;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON the browser actually receives on {@code /api-printer/&#123;id&#125;/stream}.
 *
 * <p>This is a contract with code that is not compiled: {@code static/js/component/printer.js}
 * switches on {@code payload.type} and then reads {@code payload.event.hotend.target}. Nothing
 * else in the build can notice when a rename on this side silently stops matching a string on that
 * side, which is exactly what happened once already - the UI has been checking for
 * {@code "TemperatureReported"} since the {@code marlin/decoder} classes were deleted, while what
 * the stream actually carried was {@code "OkTemperatureRs"}, so every temperature the UI received
 * fell through its {@code if} and the page stopped updating.
 *
 * <p>It works again because the application now has its own event model and the transport converts
 * into it: {@code TemperatureReported} is a name the host chose, rather than whatever the protocol
 * happened to call its decoded reply. These assertions are what keeps the two sides agreeing.
 */
class EventStreamContractTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private String streamed(Object event) {
        return mapper.writeValueAsString(new EventMessage(UUID.randomUUID(), (org.qw3rtrun.p3d.api.msg.GEvent) event));
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
                new org.qw3rtrun.p3d.api.msg.MachineJoinedFarm("big-PETG")));

        assertEquals("MachineJoinedFarm", joined.get("type").asString());
        assertEquals("big-PETG", joined.get("event").get("name").asString());
        assertTrue(joined.get("id").asString().length() == 36, "the machine it is about");

        var left = mapper.readTree(streamed(new org.qw3rtrun.p3d.api.msg.MachineLeftFarm()));
        assertEquals("MachineLeftFarm", left.get("type").asString());
    }

    /**
     * The shape {@code printer.js} reads, field for field.
     *
     * <p>{@code payload.type}, then {@code payload.event.hotend.target} and
     * {@code payload.event.bed.current}. Change any of those names and this fails here rather than
     * in a browser.
     */
    @Test
    void aTemperatureCarriesTheTypeAndTheFieldsTheUiReads() {
        var json = streamed(new TemperatureReported(
                new TemperatureReport(210.0, 205.0, 127),
                new TemperatureReport(60.0, 55.0, 80)));

        var payload = mapper.readTree(json);

        assertEquals("TemperatureReported", payload.get("type").asString());
        assertEquals(210.0, payload.get("event").get("hotend").get("current").asDouble());
        assertEquals(205.0, payload.get("event").get("hotend").get("target").asDouble());
        assertEquals(127, payload.get("event").get("hotend").get("power").asInt());
        assertEquals(60.0, payload.get("event").get("bed").get("current").asDouble());
        assertEquals(55.0, payload.get("event").get("bed").get("target").asDouble());
    }

    /**
     * A reply with no bed sensor serialises the bed as null rather than as zeroes.
     *
     * <p>Worth pinning because the two statements are different - a printer with no bed is not a
     * printer with a cold bed - and because {@code printer.js} reads {@code bedReport.current}
     * without checking, so a bedless machine is a live bug on that side. Recorded here rather than
     * fixed: the JSON is right and the UI is what needs the guard.
     */
    @Test
    void anAbsentBedIsNullAndNotZero() {
        var payload = mapper.readTree(
                streamed(new TemperatureReported(new TemperatureReport(24.31, 0.0, 0), null)));

        assertTrue(payload.get("event").get("bed").isNull());
    }

    /**
     * The connection event, field for field, as {@code connection.js} reads it.
     *
     * <p>A contract that was <strong>four</strong> event names until plan item B3:
     * {@code ConnectedEvent}, {@code DisconnectedEvent}, {@code MachineOnlineEvent} and
     * {@code MachineOfflineEvent}, which the page reduced to a pair of booleans it could not keep
     * in step - a printer that was connected but quiet read as offline, because the only thing that
     * ever set {@code online} was the arrival of some unrelated event. One event carrying one state
     * is what replaced them, and these assertions are what keep the two sides agreeing.
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

    @Test
    void theOtherEventsTheStreamCarriesAreNamedAfterTheirRecord() {
        assertEquals("ErrorReport",
                mapper.readTree(streamed(new ErrorReport("checksum mismatch, Last Line: 2", 2)))
                        .get("type").asString());
        assertEquals("BusyReport",
                mapper.readTree(streamed(new BusyReport("processing"))).get("type").asString());
    }
}

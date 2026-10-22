package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.BusyReport;
import org.qw3rtrun.p3d.api.msg.CapabilityReport;
import org.qw3rtrun.p3d.api.msg.ErrorReport;
import org.qw3rtrun.p3d.api.msg.FirmwareReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReported;
import org.qw3rtrun.p3d.api.msg.UnknownReply;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.protocol.GRs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The bridge between the two models, driven from real wire lines.
 *
 * <p>Every case starts as text and goes through {@code MarlinRsDecoder}, because a mapping asserted
 * against a hand-built {@code GRs} proves only that the mapper compiles. What is worth pinning is
 * the whole path a reply takes: the bytes a printer sends, read by {@code :gcode} into its model,
 * converted here into the application's.
 */
class ReplyEventsTest {

    private static GRs<?> decode(String line) {
        GRs<?> reply = MarlinRsDecoder.INSTANCE.decode(line);
        assertInstanceOf(GRs.class, reply, "nothing decoded: " + line);
        return reply;
    }

    @Test
    void anOkCarryingTemperaturesBecomesATemperatureEvent() {
        var event = assertInstanceOf(TemperatureReported.class,
                ReplyEvents.of(decode("ok T:210.00 /205.00 B:60.00 /55.00 @:127 B@:80")));

        assertEquals(new TemperatureReport(210.0, 205.0, 127), event.hotend());
        assertEquals(new TemperatureReport(60.0, 55.0, 80), event.bed());
    }

    @Test
    void anUnsolicitedReportBecomesTheSameEvent() {
        // The ok-ness is the send window's business and is already spent by the time a reply
        // reaches the bridge, so an M105 answer and an M155 auto-report arrive as one shape.
        var event = assertInstanceOf(TemperatureReported.class,
                ReplyEvents.of(decode("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0")));

        assertEquals(new TemperatureReport(24.31, 0.0, 0), event.hotend());
    }

    /**
     * The lossy half of the conversion, stated rather than discovered.
     *
     * <p>{@code :gcode} keeps "the firmware did not say" apart from "the firmware said zero" -
     * {@code target} and {@code power} are nullable there. The application's record has neither,
     * so an absent target lands on 0.0 and an absent power on 0. A sensor that was not mentioned at
     * all stays null, because that is a different statement again: no bed reading is not a bed
     * reading of zero.
     */
    @Test
    void anAbsentFieldFlattensButAnAbsentSensorStaysNull() {
        var event = assertInstanceOf(TemperatureReported.class, ReplyEvents.of(decode("T:24.31")));

        assertEquals(new TemperatureReport(24.31, 0.0, 0), event.hotend());
        assertNull(event.bed());
    }

    @Test
    void firmwareInfoKeepsEveryKeyTheMachineSent() {
        var event = assertInstanceOf(FirmwareReport.class,
                ReplyEvents.of(decode("FIRMWARE_NAME:Marlin 2.1.2 EXTRUDER_COUNT:1")));

        assertEquals("Marlin 2.1.2", event.firmwareName());
        assertEquals(1, event.extruderCount());
    }

    @Test
    void aCapabilityLineBecomesACapabilityReport() {
        assertEquals(new CapabilityReport("Cap:EEPROM:1", "EEPROM", true),
                ReplyEvents.of(decode("Cap:EEPROM:1")));
    }

    @Test
    void anErrorKeepsTheMachinesOwnWordsAndItsLineNumber() {
        assertEquals(new ErrorReport("checksum mismatch, Last Line: 2", 2),
                ReplyEvents.of(decode("Error:checksum mismatch, Last Line: 2")));
    }

    /**
     * Both spellings of the busy notice converge on one event.
     *
     * <p>The RepRap protocol writes {@code busy: processing}; Marlin writes the identical notice
     * through its echo channel. They are two classes in {@code :gcode} under one interface, and
     * that interface existing is what lets the bridge produce one thing.
     */
    @Test
    void bothSpellingsOfBusyBecomeOneEvent() {
        assertEquals(new BusyReport("processing"), ReplyEvents.of(decode("busy: processing")));
        assertEquals(new BusyReport("processing"), ReplyEvents.of(decode("echo:busy: processing")));
    }

    /**
     * A decoded reply the application has no event for still reaches the bus.
     *
     * <p>Dropping it would make the application's model look complete when it is merely small.
     * Seeing one of these is the signal that a reply has earned a typed event - the position report
     * below is the current best candidate.
     */
    @Test
    void aReplyWithNoApplicationEventBecomesAnUnknownReply() {
        var event = assertInstanceOf(UnknownReply.class,
                ReplyEvents.of(decode("X:0.00 Y:0.00 Z:5.00 E:0.00 Count X:0 Y:0 Z:2000")));

        assertEquals("X:0.00 Y:0.00 Z:5.00 E:0.00 Count X:0 Y:0 Z:2000", event.text());
    }
}

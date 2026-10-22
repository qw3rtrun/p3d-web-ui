package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.BusyReport;
import org.qw3rtrun.p3d.api.msg.CapabilityReport;
import org.qw3rtrun.p3d.api.msg.FirmwareReport;
import org.qw3rtrun.p3d.api.msg.GEvent;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReported;
import org.qw3rtrun.p3d.api.msg.UnknownReply;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.terminal.GSender;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The aggregate's own dispatch: which messages it acts on, and what happens to the rest.
 *
 * <p>This used to live in {@code PrinterReactor}, as reflection. The reactor read this class's
 * declared methods at construction, keyed them by parameter type, and resolved each message by a
 * breadth-first walk of its supertype graph. Nothing about it was checked by anything: renaming a
 * handler unregistered it, and a message type that gained an interface changed which handler won.
 * The only symptom was a {@code No invoker for} at WARN - nothing threw, the temperature simply
 * stopped moving, which is how a real regression shipped once.
 *
 * <p>It is a {@code switch} now, in the class that owns the decision, so a renamed or deleted
 * handler is a compile error. What is left for a test is the thing a compiler still cannot check:
 * that the arms route where they claim to, and that an unhandled message is survived rather than
 * thrown on.
 */
class PrinterStateDispatchTest {

    private final List<String> sent = new ArrayList<>();
    private final List<GEvent> emitted = new ArrayList<>();

    /** A method reference, not a lambda - {@link GSender} has two single-argument constructors. */
    private void capture(String gcode) {
        sent.add(gcode);
    }

    private PrinterState online() {
        PrinterState printer = new PrinterState(UUID.randomUUID());
        printer.setEmitter(emitted::add);
        printer.onOnline(new GSender(this::capture));
        sent.clear();
        emitted.clear();
        return printer;
    }

    @Test
    void aTemperatureEventReachesTheAggregate() {
        var printer = online();

        printer.onEvent(new TemperatureReported(
                new TemperatureReport(210.0, 205.0, 127),
                new TemperatureReport(60.0, 55.0, 80)));

        assertEquals(210.0, printer.getTemperature().current());
        assertEquals(205.0, printer.getTemperature().target());
        assertEquals(127, printer.getTemperature().power());
    }

    @Test
    void firmwareAndCapabilityEventsReachItToo() {
        var printer = online();

        printer.onEvent(new FirmwareReport(Map.of("FIRMWARE_NAME", "Marlin 2.1.2", "EXTRUDER_COUNT", "2")));
        printer.onEvent(new CapabilityReport("Cap:EEPROM:1", "EEPROM", true));

        assertEquals(2, emitted.size(), "both should have been republished as state changes");
    }

    /**
     * Most events are deliberately not the aggregate's business.
     *
     * <p>An error, a busy notice and a reply with no application event at all all fall to the
     * default arm. That is the normal case rather than a gap, which is why it is a debug line and
     * not a warning - and why none of them may throw: the event stream has to survive a message
     * nobody wanted.
     */
    @Test
    void anEventWithNoArmIsSurvivedQuietly() {
        var printer = online();

        printer.onEvent(new BusyReport("processing"));
        printer.onEvent(new UnknownReply("X:0.00 Y:0.00 Z:5.00"));

        assertEquals(List.of(), sent);
        assertEquals(List.of(), emitted);
    }

    /**
     * The command direction, which the reflective version never had a test for.
     *
     * <p>Routed through {@code onCommand} rather than called directly, a command still reaches its
     * handler and still reaches the wire - which is the half of the dispatch a caller actually
     * depends on.
     */
    @Test
    void aCommandRoutedThroughTheDispatchStillReachesTheWire() {
        var printer = online();

        printer.onCommand(MarlinG.INSTANCE.m140(new BigDecimal("60.0")));
        printer.onCommand(MarlinG.INSTANCE.m155(1));

        assertEquals(List.of("M140 S60.00", "M155 S1"), sent);
    }

    /**
     * A command with no arm is a warning and not a crash.
     *
     * <p>The REST surface accepts any Marlin command class and this aggregate acts on four, so an
     * unhandled command is a gap in this layer. It must still not take the caller's request down
     * with it - and nothing is sent, which is the honest outcome rather than a half-executed one.
     */
    @Test
    void aCommandWithNoArmSendsNothingAndDoesNotThrow() {
        var printer = online();

        printer.onCommand(MarlinG.INSTANCE.firmwareInfo());

        assertEquals(List.of(), sent);
    }
}

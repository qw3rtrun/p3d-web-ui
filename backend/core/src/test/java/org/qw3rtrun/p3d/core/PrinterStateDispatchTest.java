package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.api.msg.BusyReport;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.ErrorReport;
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

    /**
     * Firmware and capability reports are republished, and prove nothing about the link.
     *
     * <p>Two events and not three. An {@code M115} answer is the most convincing-looking reply
     * there is - it names the firmware - and it is still only something the machine said. See
     * {@link #aReplyWithNoArmSendsNothingAndProvesNothingEither}.
     */
    @Test
    void firmwareAndCapabilityEventsReachItToo() {
        var printer = online();

        printer.onEvent(new FirmwareReport(Map.of("FIRMWARE_NAME", "Marlin 2.1.2", "EXTRUDER_COUNT", "2")));
        printer.onEvent(new CapabilityReport("Cap:EEPROM:1", "EEPROM", true));

        assertEquals(List.of(FirmwareReport.class, CapabilityReport.class),
                emitted.stream()
                        .filter(event -> !(event instanceof ConnectionStateChanged))
                        .map(Object::getClass).toList(),
                "both should have been republished as state changes");
        assertEquals(MachineConnectionState.ONLINE, printer.getConnection().state());
    }

    /**
     * A reply nothing claims is survived, sends nothing, and moves the connection nowhere.
     *
     * <p>A reply with no application event at all - a position report, an echo, half of an
     * {@code M503} dump - falls to the default arm and sends nothing, as it always has. What it no
     * longer does is advance the connection: B3 put <em>any</em> reply on the {@code ONLINE} to
     * {@code IDLE} arrow on the grounds that it proved a firmware was there, and a live run proved
     * that it does not. Auto-report traffic an earlier session left running arrives at a host whose
     * outbound half is dead, and reads exactly like this.
     *
     * <p>Nothing may throw here either way: the event stream has to survive a message nobody wanted.
     */
    @Test
    void aReplyWithNoArmSendsNothingAndProvesNothingEither() {
        var printer = online();

        printer.onEvent(new UnknownReply("X:0.00 Y:0.00 Z:5.00"));

        assertEquals(List.of(), sent);
        assertEquals(MachineConnectionState.ONLINE, printer.getConnection().state());
        assertEquals(List.of(), emitted);
    }

    /**
     * An acknowledged block is what takes a machine to {@code IDLE}, and it arrives unprompted.
     *
     * <p>Nothing has to ask for one. {@code onOnline} sends {@code M115} and {@code M155 S1} the
     * moment the socket comes up, so a healthy printer acknowledges a block within a round trip of
     * connecting and the stricter arrow costs nothing in the normal case - which is the only reason
     * it can afford to be this strict.
     */
    @Test
    void anAcknowledgedBlockIsWhatProvesTheMachineIsThere() {
        var printer = online();

        printer.onEvent(new BlockAcknowledged(1, 0));

        assertEquals(List.of(), sent);
        assertEquals(MachineConnectionState.IDLE, printer.getConnection().state());
        assertEquals(List.of(new ConnectionStateChanged(
                MachineConnectionState.ONLINE, MachineConnectionState.IDLE, 0, null)), emitted);
    }

    /**
     * A busy notice and an error report are the aggregate's business now, and were not before.
     *
     * <p>Both used to fall through to the debug line, so the only thing the application ever did
     * with {@code echo:busy: processing} was log it. They are states on the connection lifecycle -
     * see {@link ConnectionLifecycle} - which is what lets a page show a machine as working rather
     * than as idle-and-ignoring-you.
     */
    @Test
    void aBusyNoticeAndAnErrorAreStatesRatherThanNoise() {
        var printer = online();

        printer.onEvent(new BusyReport("processing"));
        assertEquals(MachineConnectionState.BUSY, printer.getConnection().state());

        printer.onEvent(new ErrorReport("checksum mismatch, Last Line: 2", 2));
        assertEquals(MachineConnectionState.ERROR, printer.getConnection().state());
        assertEquals("checksum mismatch, Last Line: 2", printer.getConnection().reason());

        assertEquals(List.of(), sent, "neither is answered on the wire by this layer");
    }

    /**
     * A command to a machine that is not connected is refused rather than crashing.
     *
     * <p>It was a 500 and a {@code NullPointerException}: the {@code GSender} is held only while a
     * link is up and every arm below dereferences it, so a request that had bound and routed
     * perfectly blew up pointing at the aggregate instead of at the machine being unreachable. The
     * known-debt entry for this named B3 as its owner.
     */
    @Test
    void aCommandToAnUnconnectedMachineIsRefusedAndNotAnNpe() {
        var printer = new PrinterState(UUID.randomUUID());
        printer.setEmitter(emitted::add);

        printer.onCommand(MarlinG.INSTANCE.m140(new BigDecimal("60.0")));

        assertEquals(List.of(), sent);
        assertEquals(MachineConnectionState.OFFLINE, printer.getConnection().state());
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

    /**
     * A position report reaches whoever is watching.
     *
     * <p><strong>This is a regression test for a silent drop.</strong> The transport decoded
     * {@code M114}'s answer and converted it to a {@link org.qw3rtrun.p3d.api.msg.PositionReported}
     * correctly, and it still never appeared in a browser: the aggregate republishes only what it
     * has an arm for, and everything else reaches {@code default}, which logs at debug and stops.
     * Every layer in between looked right, which is why it took a live run to find.
     *
     * <p>So the rule for any new reply is two changes, not one: map it in {@code ReplyEvents}
     * <em>and</em> give the aggregate a case for it.
     */
    @Test
    void aPositionReportIsRepublishedToSubscribers() {
        var printer = online();

        printer.onEvent(new org.qw3rtrun.p3d.api.msg.PositionReported(
                Map.of("X", 15.0, "Y", 20.0)));

        var published = emitted.stream()
                .filter(org.qw3rtrun.p3d.api.msg.PositionReported.class::isInstance)
                .map(org.qw3rtrun.p3d.api.msg.PositionReported.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the position never reached a subscriber; emitted " + emitted));

        assertEquals(15.0, published.axes().get("X"));
        assertEquals(20.0, published.axes().get("Y"));
    }
}

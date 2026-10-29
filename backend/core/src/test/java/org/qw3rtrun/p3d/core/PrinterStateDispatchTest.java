package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.PositionRs;
import org.qw3rtrun.p3d.g.protocol.DebugRs;
import org.qw3rtrun.p3d.g.protocol.ErrorPrefix;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.qw3rtrun.p3d.g.protocol.SimpleBusyRs;
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
 *
 * <p><strong>The replies here are {@code :gcode}'s own.</strong> They used to be {@code api.msg}
 * records that {@code ReplyEvents} built from them - a second model of a Marlin's replies - so what
 * these tests fed the aggregate was a translation rather than a reply. They are the decoded values
 * now, which is also what a live machine produces.
 */
class PrinterStateDispatchTest {

    private final List<String> sent = new ArrayList<>();
    private final List<Object> emitted = new ArrayList<>();

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

    private static BareTemperatureRs temperatures(HeaterReading hotend, HeaterReading bed) {
        return new BareTemperatureRs(hotend, bed, null, null, null, null, null, Map.of());
    }

    @Test
    void aTemperatureEventReachesTheAggregate() {
        var printer = online();

        printer.onEvent(temperatures(
                new HeaterReading(new BigDecimal("210.0"), new BigDecimal("205.0"), 127),
                new HeaterReading(new BigDecimal("60.0"), new BigDecimal("55.0"), 80)));

        assertEquals(new BigDecimal("210.0"), printer.getTemperature().getCurrent());
        assertEquals(new BigDecimal("205.0"), printer.getTemperature().getTarget());
        assertEquals(127, printer.getTemperature().getPower());
    }

    /**
     * A target the firmware did not state stays absent.
     *
     * <p>The record this replaced had no way to say "not reported" - an absent target arrived as
     * {@code 0.0}, which is indistinguishable from a hotend that has been told to cool down. One of
     * those is a machine nobody has asked for anything and the other is a machine on its way off,
     * and a page drawing both the same way is wrong about one of them.
     */
    @Test
    void aReadingWithNoTargetKeepsSayingSo() {
        var printer = online();

        printer.onEvent(temperatures(new HeaterReading(new BigDecimal("24.5"), null, null), null));

        assertEquals(new BigDecimal("24.5"), printer.getTemperature().getCurrent());
        assertEquals(null, printer.getTemperature().getTarget());
        assertEquals(null, printer.getTemperature().getPower());
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

        printer.onEvent(new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1.2", "EXTRUDER_COUNT", "2")));
        printer.onEvent(new CapabilityRs("EEPROM", true));

        assertEquals(List.of(FirmwareInfoRs.class, CapabilityRs.class),
                emitted.stream()
                        .filter(event -> !(event instanceof ConnectionStateChanged))
                        .map(Object::getClass).toList(),
                "both should have been republished as state changes");
        assertEquals(MachineConnectionState.ONLINE, printer.getConnection().state());
    }

    /**
     * A reply nothing claims is survived, sends nothing, and moves the connection nowhere.
     *
     * <p>A reply with no arm at all - an echo, a debug line, half of an {@code M503} dump - falls to
     * the default arm and sends nothing, as it always has. What it no longer does is advance the
     * connection: B3 put <em>any</em> reply on the {@code ONLINE} to {@code IDLE} arrow on the
     * grounds that it proved a firmware was there, and a live run proved that it does not.
     * Auto-report traffic an earlier session left running arrives at a host whose outbound half is
     * dead, and reads exactly like this.
     *
     * <p>Nothing may throw here either way: the event stream has to survive a message nobody wanted.
     */
    @Test
    void aReplyWithNoArmSendsNothingAndProvesNothingEither() {
        var printer = online();

        printer.onEvent(new DebugRs("busy heating"));

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

        printer.onEvent(new SimpleBusyRs("processing"));
        assertEquals(MachineConnectionState.BUSY, printer.getConnection().state());

        printer.onEvent(new ErrorRs(ErrorPrefix.ERROR, "checksum mismatch, Last Line: 2"));
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
     * <p>{@code PrinterControl} accepts any {@code GRq} and this aggregate acts on a dozen, so an
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
     * {@code M114}'s answer correctly and it still never appeared in a browser: the aggregate
     * republishes only what it has an arm for, and everything else reaches {@code default}, which
     * logs at debug and stops. Every layer in between looked right, which is why it took a live run
     * to find.
     *
     * <p>Adding a reply is one change fewer than it was - the transport propagates whatever
     * {@code :gcode} decoded, so there is nothing to map - but it is still a change here: a reply
     * the aggregate has no case for reaches nobody.
     */
    @Test
    void aPositionReportIsRepublishedToSubscribers() {
        var printer = online();

        printer.onEvent(new PositionRs(
                Map.of('X', new BigDecimal("15.0"), 'Y', new BigDecimal("20.0")), Map.of()));

        var published = emitted.stream()
                .filter(PositionRs.class::isInstance)
                .map(PositionRs.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the position never reached a subscriber; emitted " + emitted));

        assertEquals(new BigDecimal("15.0"), published.getAxes().get('X'));
        assertEquals(new BigDecimal("20.0"), published.getAxes().get('Y'));
    }
}

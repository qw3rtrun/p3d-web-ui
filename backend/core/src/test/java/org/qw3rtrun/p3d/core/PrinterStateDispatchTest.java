package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.marlin.command.FactoryReset;
import org.qw3rtrun.p3d.g.marlin.command.FullShutdown;
import org.qw3rtrun.p3d.g.marlin.command.PrintTravelMoveLimits;
import org.qw3rtrun.p3d.g.marlin.command.ReportSettings;
import org.qw3rtrun.p3d.g.marlin.command.RestoreSettings;
import org.qw3rtrun.p3d.g.marlin.command.SaveSettings;
import org.qw3rtrun.p3d.g.marlin.command.SetAdvancedSettings;
import org.qw3rtrun.p3d.g.marlin.command.SetAxisStepsPerUnit;
import org.qw3rtrun.p3d.g.marlin.command.SetGetLineNumber;
import org.qw3rtrun.p3d.g.marlin.command.SetHomeOffsets;
import org.qw3rtrun.p3d.g.marlin.command.SetMaxFeedrate;
import org.qw3rtrun.p3d.g.marlin.command.SetStartingAcceleration;
import org.qw3rtrun.p3d.g.marlin.command.StartSDWrite;
import org.qw3rtrun.p3d.g.marlin.command.StopSDWrite;
import org.qw3rtrun.p3d.g.marlin.command.ValidateEEPROMContents;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.EepromReport;
import org.qw3rtrun.p3d.g.marlin.event.PositionRs;
import org.qw3rtrun.p3d.g.marlin.event.SettingsSection;
import org.qw3rtrun.p3d.g.marlin.event.SettingsValue;
import org.qw3rtrun.p3d.g.marlin.event.UnknownCommand;
import org.qw3rtrun.p3d.g.protocol.DebugRs;
import org.qw3rtrun.p3d.g.protocol.GRq;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The aggregate's own dispatch: which messages it acts on, and what happens to the rest. Used to be
 * reflection in {@code PrinterReactor} - a renamed handler silently unregistered, with no symptom
 * beyond a {@code No invoker for} at WARN, which is how a real regression once shipped. It is a
 * {@code switch} now, so a renamed or deleted handler is a compile error; what is left for a test is
 * what the compiler cannot check - that the arms route where they claim to, and that an unhandled
 * message is survived rather than thrown on. The replies fed here are {@code :gcode}'s own decoded
 * values, the same as a live machine produces - not the {@code api.msg} translation this suite used
 * to feed instead.
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

    /** What reached a subscriber, with the connection transitions filtered out. */
    private List<Object> published() {
        return emitted.stream().filter(e -> !(e instanceof ConnectionStateChanged)).toList();
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

    // the record this replaced had no way to say "not reported" - an absent target arrived as 0.0,
    // indistinguishable from a hotend told to cool down.
    @Test
    void aReadingWithNoTargetKeepsSayingSo() {
        var printer = online();

        printer.onEvent(temperatures(new HeaterReading(new BigDecimal("24.5"), null, null), null));

        assertEquals(new BigDecimal("24.5"), printer.getTemperature().getCurrent());
        assertEquals(null, printer.getTemperature().getTarget());
        assertEquals(null, printer.getTemperature().getPower());
    }

    // an M115 answer is the most convincing-looking reply there is - it names the firmware - and is
    // still only something the machine said, not proof of a link.
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
     * A reply nothing claims is passed on, sends nothing, and proves nothing. The first claim is the
     * inversion: this used to assert the opposite, and pinning "dropped" as correct is how three
     * finished features shipped dead (a position report, then both halves of the Storage tab). The
     * default republishes now, so a missing arm costs the aggregate's opinion rather than the whole
     * feature. The third claim was expensive to learn: B3 put any reply on {@code ONLINE -> IDLE} on
     * the grounds that it proved a firmware was there, and a live run proved it does not - auto-report
     * traffic an earlier session left running reads exactly like this.
     */
    @Test
    void aReplyWithNoArmIsPassedOnAndStillProvesNothing() {
        var printer = online();
        var echo = new DebugRs("busy heating");

        printer.onEvent(echo);

        assertEquals(List.of(echo), emitted, "a reply with no arm must still reach a subscriber");
        assertEquals(List.of(), sent, "but the aggregate answers nothing on the wire");
        assertEquals(MachineConnectionState.ONLINE, printer.getConnection().state());
    }

    // the one deliberate exception to republishing: emitting the reply beside the transition would
    // put one fault on the stream twice, in two shapes, for a browser to reconcile.
    @Test
    void anErrorIsAnnouncedOnceAsAStateChange() {
        var printer = online();

        printer.onEvent(new ErrorRs(ErrorPrefix.ERROR, "checksum mismatch, Last Line: 2"));

        assertEquals(List.of(ConnectionStateChanged.class),
                emitted.stream().map(Object::getClass).toList());
        assertEquals("checksum mismatch, Last Line: 2", printer.getConnection().reason());
    }

    // arrives unprompted: onOnline sends M115/M155 S1 the moment the socket comes up, so a healthy
    // printer acknowledges within a round trip of connecting - the only reason the arrow can afford
    // to be this strict.
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
     * Blocks the transport threw away are passed on, and move nothing - issue #60. A host-side fact
     * rather than a reply, like {@link BlockAcknowledged}, so the arm republishes and does nothing
     * else (same shape as {@code on(PositionRs)}); {@code JobFeeder} is what fails the job. The
     * machine is left in {@code ERROR} on purpose: the {@code Error:}/{@code Resend:} that always
     * precedes this already drove {@code connection.faulted(...)}, so moving it again would count one
     * fault twice - as a repeated {@code faulted()}, or as a {@code heard()} clearing it, which is
     * what the {@code default} arm did before this handler existed and what this test caught.
     */
    @Test
    void abandonedBlocksArePassedOnAndMoveNoConnectionState() {
        var printer = online();
        printer.onEvent(new ErrorRs(ErrorPrefix.ERROR,
                "Line Number is not Last Line Number+1, Last Line: 14"));
        emitted.clear();

        var abandoned = new BlocksAbandoned(15, 3);
        printer.onEvent(abandoned);

        assertEquals(List.of(abandoned), emitted,
                "the fact has to reach whatever fails the job, unchanged");
        assertEquals(List.of(), sent, "the aggregate answers nothing on the wire");
        assertEquals(MachineConnectionState.ERROR, printer.getConnection().state(),
                "the Error: that preceded this already moved the connection; moving it again here"
                        + " would count one fault twice");
        assertEquals("Line Number is not Last Line Number+1, Last Line: 14",
                printer.getConnection().reason());
    }

    // both used to fall through to the debug line, so the only thing the application ever did with
    // echo:busy: processing was log it.
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

    // used to be a 500 and a NullPointerException: GSender is held only while a link is up, so a
    // request that bound and routed perfectly blew up pointing at the aggregate, not the unreachable
    // machine.
    @Test
    void aCommandToAnUnconnectedMachineIsRefusedAndNotAnNpe() {
        var printer = new PrinterState(UUID.randomUUID());
        printer.setEmitter(emitted::add);

        printer.onCommand(MarlinG.INSTANCE.m140(new BigDecimal("60.0")));

        assertEquals(List.of(), sent);
        assertEquals(MachineConnectionState.OFFLINE, printer.getConnection().state());
    }

    // the command direction, which the reflective version never had a test for: routed through
    // onCommand rather than called directly, and still reaches the wire.
    @Test
    void aCommandRoutedThroughTheDispatchStillReachesTheWire() {
        var printer = online();

        printer.onCommand(MarlinG.INSTANCE.m140(new BigDecimal("60.0")));
        printer.onCommand(MarlinG.INSTANCE.m155(1));

        assertEquals(List.of("M140 S60.00", "M155 S1"), sent);
    }

    /**
     * A command with no arm is sent - the inversion of what this test used to assert (that an
     * unlisted command was dropped with a warning). Pinning the drop as correct meant no test could
     * also say a particular command must not be dropped, and motion shipped dead once, then the whole
     * Storage tab. {@code M115} is the case in point: nothing about asking a machine its firmware
     * needs this layer's permission.
     */
    @Test
    void aCommandWithNoArmIsSentAsItWasGiven() {
        var printer = online();

        printer.onCommand(MarlinG.INSTANCE.firmwareInfo());

        assertEquals(List.of("M115"), sent);
    }

    /**
     * The commands that are refused - the half of the bargain that makes the inversion safe. Each
     * would be accepted by the firmware and each would break something this host guarantees, so they
     * are declined by name with a reason, unlike the old default's silent drop: {@code M28} makes the
     * wire a file sink until {@code M29} (#31); {@code M29} ends a transfer this route never began;
     * {@code M110} moves the firmware's line counter and not the send window's; {@code M112} queued
     * behind a print is not an emergency stop.
     */
    @Test
    void theCommandsThisRouteCannotCarryAreRefusedRatherThanWritten() {
        var printer = online();

        printer.onCommand(new StartSDWrite("JOB.GCO"));
        printer.onCommand(new StopSDWrite());
        printer.onCommand(new SetGetLineNumber());
        printer.onCommand(new FullShutdown());

        assertEquals(List.of(), sent, "none of these may reach the wire through onCommand");
    }

    // ------------------------------------------------------------- the EEPROM settings, issue #88

    // what the aggregate wrote, given the commands PrinterControl.send handed it.
    private List<String> wire(PrinterState printer, GRq<?>... commands) {
        for (var command : commands) {
            printer.onCommand(command);
        }
        return List.copyOf(sent);
    }

    /**
     * Each of the EEPROM operations as the exact bytes it puts on the wire - the string, not the
     * class, since class names are no guide to what they write and a generated parameter name must
     * have no vote. {@code M500} is included because it is the one of the five that writes the
     * EEPROM, in the sequence an operator performs: read, validate, save.
     */
    @Test
    void theEepromOperationsReachTheWireAsThemselves() {
        var printer = online();

        assertEquals(List.of("M503", "M504", "M500"),
                wire(printer, new ReportSettings(), new ValidateEEPROMContents(), new SaveSettings()));
    }

    /**
     * {@code M501} puts two commands on the wire, in order: the restore, then an {@code M503} the
     * aggregate asked for itself - the test that would have caught the stale-settings bug.
     * {@code M501} answers a bare {@code ok} and announces nothing (measured) while genuinely
     * replacing every live setting, so forgetting the held report shows an empty tab and keeping it
     * shows a wrong one; the re-ask fixes it within one round trip.
     */
    @Test
    void aRestoreAsksForTheSettingsAgain() {
        var printer = online();

        assertEquals(List.of("M501", "M503"), wire(printer, new RestoreSettings()));
    }

    // and so does M502, for the same reason and with the same measurement behind it.
    @Test
    void aFactoryResetAsksForTheSettingsAgain() {
        var printer = online();

        assertEquals(List.of("M502", "M503"), wire(printer, new FactoryReset()));
    }

    // DISABLE_M503 removes the report from a build that stores settings fine, and only the refusal
    // says so; asking again once it has arrived would put a known-refused command on the wire.
    @Test
    void aFirmwareWithNoM503IsNotAskedForTheReport() {
        var printer = online();
        printer.onEvent(new UnknownCommand("M503"));
        sent.clear();

        assertEquals(List.of(), wire(printer, new ReportSettings()),
                "a command this machine has already refused must not be sent again");

        sent.clear();
        assertEquals(List.of("M501"), wire(printer, new RestoreSettings()),
                "the restore still goes; only the re-ask is skipped");
    }

    /**
     * The six settings this feature may change, as the exact bytes each one writes. Every one would
     * be wrong if it trusted the generated parameter name: {@code SetAxisStepsPerUnit.steps} and
     * {@code PrintTravelMoveLimits.accel} write {@code X}, {@code SetStartingAcceleration.accel} and
     * {@code SetHomeOffsets.offset} write {@code P}, {@code SetAdvancedSettings.jerk} writes
     * {@code X} and its {@code deviation} writes {@code J} - the generator names fields after
     * Marlin's documentation, not the wire letter.
     */
    @Test
    void theSixSettersReachTheWireAsTheBytesTheyActuallyWrite() {
        var printer = online();

        assertEquals(
                List.of("M92 X81.00",
                        "M203 X300.00",
                        "M201 X3000.00",
                        "M204 P3000.00",
                        "M205 X10.00 J0.01",
                        "M206 P0.00"),
                wire(printer,
                        new SetAxisStepsPerUnit(new BigDecimal("81.00"), null, null, null, null,
                                null, null, null, null, null, null),
                        new SetMaxFeedrate(new BigDecimal("300.00"), null, null, null, null),
                        new PrintTravelMoveLimits(new BigDecimal("3000.00"), null, null, null, null,
                                null, null),
                        new SetStartingAcceleration(new BigDecimal("3000.00"), null, null, null),
                        new SetAdvancedSettings(new BigDecimal("10.00"), null, null, null, null,
                                null, null, new BigDecimal("0.01")),
                        new SetHomeOffsets(new BigDecimal("0.00"), null, null, null, null, null,
                                null, null, null, null, null)));
    }

    /**
     * A settings line is republished and held - the pair of claims that matters. Republished since
     * these answer an {@code M503} a page pressed a button for, so a repeat is still news (the same
     * reading {@code on(SdCardRs)} takes, opposite the temperature's); held so a page opened later
     * sees the settings without pressing the button again. Asserting only the first half is how both
     * halves of the Storage tab shipped dead.
     */
    @Test
    void aSettingsLineIsRepublishedAndHeld() {
        var printer = online();
        var section = new SettingsSection("Steps per unit");
        var value = new SettingsValue("M92", "M92 X80.00 Y80.00 Z400.00 E500.00");

        printer.onEvent(section);
        printer.onEvent(value);

        assertEquals(List.of(section, value), published(),
                "a settings line has to reach the page that asked for it");
        assertTrue(printer.snapshot().containsAll(List.of(section, value)),
                "and a page opened later has to be handed it: " + printer.snapshot());
    }

    // M504's verdict travels the same way, as the message the firmware wrote.
    @Test
    void theEepromVerdictIsRepublishedAndHeld() {
        var printer = online();
        var verdict = new EepromReport("OK");

        printer.onEvent(verdict);

        assertEquals(List.of(verdict), published());
        assertTrue(printer.snapshot().contains(verdict));
    }

    /**
     * An {@code Unknown command:} reply is republished whatever it names - the regression this
     * feature could most easily have caused: giving it a named arm so the settings control can learn
     * from an {@code M503} refusal is exactly the shape of change that could quietly consume every
     * other command's refusal too.
     */
    @Test
    void anUnknownCommandStillReachesTheBrowserWhateverItNames() {
        var printer = online();
        var aboutSettings = new UnknownCommand("M503");
        var aboutSomethingElse = new UnknownCommand("M851");

        printer.onEvent(aboutSettings);
        printer.onEvent(aboutSomethingElse);

        assertEquals(List.of(aboutSettings, aboutSomethingElse), published(),
                "a refusal of any command is still news to whoever sent it");
        assertEquals(List.of(), sent, "and the aggregate answers none of them on the wire");
    }

    // onOnline is where every control is told the session changed - what comes back up on the same
    // address can be a different firmware, or a different machine on a moved cable.
    @Test
    void aNewSessionAsksAFirmwareWithNoM503Again() {
        var printer = online();
        printer.onEvent(new UnknownCommand("M503"));
        printer.onEvent(new SettingsValue("M92", "M92 X80.00"));

        printer.onOffline("the cable was moved");
        printer.onOnline(new GSender(this::capture));
        sent.clear();

        assertEquals(List.of("M503"), wire(printer, new ReportSettings()),
                "a reflashed machine may well answer M503 now");
        assertFalse(printer.snapshot().contains(new SettingsValue("M92", "M92 X80.00")),
                "and the last session's settings are not this session's: " + printer.snapshot());
    }

    /**
     * A position report reaches whoever is watching - a regression test for a silent drop, the first
     * of three: {@code M114}'s answer decoded correctly and still never appeared in a browser, back
     * when the default logged at debug and stopped rather than republishing. Every layer in between
     * looked right, which is why it took a live run to find. Still worth pinning that the axes arrive
     * intact and under their own letters.
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

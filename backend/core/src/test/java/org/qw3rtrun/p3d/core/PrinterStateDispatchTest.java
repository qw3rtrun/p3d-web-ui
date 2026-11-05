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
     * A reply nothing claims is <strong>passed on</strong>, sends nothing, and proves nothing.
     *
     * <p>Three claims, and the first is the inversion. This used to assert that a reply with no arm
     * reached nobody, and that assertion is how three finished features came to ship dead: a
     * position report decoded all the way through the transport and never appeared in a browser,
     * and then both halves of the Storage tab. A suite that pins dropping as correct cannot also
     * say that a particular reply must not be dropped. The default republishes now, so the cost of
     * a missing arm is the aggregate's opinion rather than the whole feature.
     *
     * <p>The second is unchanged and is the point of the aggregate: it answers nothing on the wire.
     * Passing a reply to a subscriber is not the same as acting on it.
     *
     * <p>The third is unchanged and was expensive to learn: a reply does <em>not</em> advance the
     * connection. B3 put <em>any</em> reply on the {@code ONLINE} to {@code IDLE} arrow on the
     * grounds that it proved a firmware was there, and a live run proved that it does not.
     * Auto-report traffic an earlier session left running arrives at a host whose outbound half is
     * dead, and reads exactly like this.
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

    /**
     * An error is announced as a state change and not also as itself.
     *
     * <p>The one deliberate exception to republishing. {@code ErrorRs} moves the connection to
     * {@code ERROR} carrying the firmware's own words, and emitting the reply beside the transition
     * would put one fault on the stream twice in two shapes for a browser to reconcile.
     */
    @Test
    void anErrorIsAnnouncedOnceAsAStateChange() {
        var printer = online();

        printer.onEvent(new ErrorRs(ErrorPrefix.ERROR, "checksum mismatch, Last Line: 2"));

        assertEquals(List.of(ConnectionStateChanged.class),
                emitted.stream().map(Object::getClass).toList());
        assertEquals("checksum mismatch, Last Line: 2", printer.getConnection().reason());
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
     * Blocks the transport threw away are passed on, and move nothing - issue #60.
     *
     * <p><strong>The aggregate has nothing to decide about this and the handler exists to say
     * so.</strong> It is a host-side fact rather than a reply - like {@link BlockAcknowledged}, it
     * is something only the send window can know - and what acts on it is {@code JobFeeder}, which
     * fails the job. So the arm republishes and does nothing else, which is the same shape as
     * {@code on(PositionRs)}.
     *
     * <p><strong>The machine is left in {@code ERROR} on purpose, and that is the claim.</strong>
     * A resynchronisation never arrives alone: the firmware says
     * {@code Error:Line Number is not Last Line Number+1}, then {@code Resend:}, and the host
     * concludes it cannot answer. The {@code Error:} already drove {@code connection.faulted(...)},
     * so anything this handler did to the connection would be the same fault counted twice - a
     * {@code faulted()} re-announcing it, or a {@code heard()} <em>clearing</em> it, which is what
     * the {@code default} arm did before this handler existed and is what this test caught.
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
     * A command with no arm is <strong>sent</strong>, which is the inversion of what this test
     * used to assert.
     *
     * <p>It read {@code aCommandWithNoArmSendsNothingAndDoesNotThrow} and pinned the old default:
     * an unlisted command was dropped with a warning, on the argument that nothing is honester
     * than a command that does not half-happen. The trouble is that it made the suite agree with
     * the bug - it asserted that silently dropping a command the user asked for was correct
     * behaviour, so no test could also say that a <em>particular</em> command must not be dropped.
     * Motion shipped dead once and the whole Storage tab shipped dead after it.
     *
     * <p>{@code M115} is the case in point: nothing about asking a machine what firmware it runs
     * needs this layer's permission, and there was never a reason beyond an unwritten arm for it to
     * be thrown away.
     */
    @Test
    void aCommandWithNoArmIsSentAsItWasGiven() {
        var printer = online();

        printer.onCommand(MarlinG.INSTANCE.firmwareInfo());

        assertEquals(List.of("M115"), sent);
    }

    /**
     * The commands that are refused, which is the half of the bargain that makes the inversion
     * safe.
     *
     * <p>Each of these would be accepted by the firmware and each would break something this host
     * guarantees, so they are declined rather than written - and declined by name, with the reason
     * in the log, which is a different thing from the old default's "nobody got round to it".
     *
     * <ul>
     *   <li>{@code M28} makes the wire a file sink until {@code M29}, so everything behind it is
     *       written into the file rather than run. #31 measured that. An upload goes through
     *       {@code PrinterControl.uploadToCard}, which holds the connection.</li>
     *   <li>{@code M29} ends a transfer this route never began.</li>
     *   <li>{@code M110} moves the firmware's line counter and not the send window's, and every
     *       block after it is rejected for the rest of the session.</li>
     *   <li>{@code M112} is an emergency stop, and one queued behind a print is not one.</li>
     * </ul>
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

    /** What the aggregate wrote, given the commands {@code PrinterControl.send} handed it. */
    private List<String> wire(PrinterState printer, GRq<?>... commands) {
        for (var command : commands) {
            printer.onCommand(command);
        }
        return List.copyOf(sent);
    }

    /**
     * Each of the EEPROM operations as the exact bytes it puts on the wire.
     *
     * <p><strong>The string and not the class</strong>, because the class names are no guide to
     * what they write. These three happen to be bare, but the six setters below are not, and the
     * whole point of asserting at this level is that a generated parameter name has no vote.
     *
     * <p>{@code M500} is here although it had a handler before this feature: it is the one command
     * of the five that writes the EEPROM, and this is the sequence an operator performs - read,
     * validate, save.
     */
    @Test
    void theEepromOperationsReachTheWireAsThemselves() {
        var printer = online();

        assertEquals(List.of("M503", "M504", "M500"),
                wire(printer, new ReportSettings(), new ValidateEEPROMContents(), new SaveSettings()));
    }

    /**
     * {@code M501} puts <strong>two</strong> commands on the wire, in order: the restore, then an
     * {@code M503} the aggregate asked for itself.
     *
     * <p><strong>This is the test that would have caught the stale-settings bug.</strong>
     * {@code M501} answers a bare {@code ok} and announces nothing at all - measured - while
     * genuinely replacing every live setting, so a host that merely forgets its held report shows
     * an empty tab and a host that keeps it shows a wrong one. Neither is correctable by anything
     * the firmware will ever say next. The re-ask puts the tab right within one round trip, and it
     * costs one command and about 25 reply lines.
     */
    @Test
    void aRestoreAsksForTheSettingsAgain() {
        var printer = online();

        assertEquals(List.of("M501", "M503"), wire(printer, new RestoreSettings()));
    }

    /** And so does {@code M502}, for the same reason and with the same measurement behind it. */
    @Test
    void aFactoryResetAsksForTheSettingsAgain() {
        var printer = online();

        assertEquals(List.of("M502", "M503"), wire(printer, new FactoryReset()));
    }

    /**
     * On a firmware that has no {@code M503}, the request is refused and the re-ask is skipped.
     *
     * <p>{@code DISABLE_M503} removes the report from a build that stores settings perfectly well,
     * and the only thing that says so is the refusal. Once one has arrived, asking again would put
     * a command on the wire that is known to be refused - once per reset, for the rest of the
     * session.
     */
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
     * The six settings this feature may change, as the exact bytes each one writes.
     *
     * <p><strong>Every one of these would be wrong if it trusted the generated parameter
     * name.</strong> {@code SetAxisStepsPerUnit.steps} writes {@code X},
     * {@code PrintTravelMoveLimits.accel} writes {@code X},
     * {@code SetStartingAcceleration.accel} writes {@code P}, {@code SetAdvancedSettings.jerk}
     * writes {@code X} and its {@code deviation} writes {@code J}, and
     * {@code SetHomeOffsets.offset} writes {@code P}. The generator names a parameter after the
     * value {@code commands.json} documents rather than after its letter, so the field names record
     * Marlin's documentation and not the wire. Asserting the string is the only way this test can
     * disagree with the code.
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
     * A settings line is republished <em>and</em> held, which is the pair of claims that matters.
     *
     * <p>Republished because somebody just asked: these are answers to an {@code M503} a page
     * pressed a button for, so a repeat is still news to that page - the same reading
     * {@code on(SdCardRs)} takes and the opposite of the temperature's. Held because a page opened
     * a minute later has to see the settings without pressing the button again.
     *
     * <p>Asserting only the first half is how both halves of the Storage tab shipped dead: the
     * replies arrived, decoded perfectly, and the aggregate dropped them.
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

    /** {@code M504}'s verdict travels the same way, as the message the firmware wrote. */
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
     * feature could most easily have caused.
     *
     * <p>Before issue #88 {@code UnknownCommand} reached {@code republish} through the
     * {@code default} arm and the browser saw it. Giving it a named arm so the settings control can
     * learn from an {@code M503} refusal is exactly the shape of change that quietly stops that:
     * the handler consumes the reply and the page loses every refusal of every other command. So
     * the arm offers it to the control and republishes regardless.
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

    /**
     * A new session forgets a refusal, so a reflashed machine is asked again.
     *
     * <p>{@code onOnline} is where every control is told the session changed, and this one has to
     * be told for the same reason the card is: what comes back up on the same address can be a
     * different firmware, or a different machine on a cable that was moved.
     */
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
     * A position report reaches whoever is watching.
     *
     * <p><strong>This is a regression test for a silent drop, and it was the first of three.</strong>
     * The transport decoded {@code M114}'s answer correctly and it still never appeared in a
     * browser, because back then the aggregate republished only what it had an arm for and
     * everything else reached a {@code default} that logged at debug and stopped. Every layer in
     * between looked right, which is why it took a live run to find - and the same drop then took
     * the Storage tab's commands and its replies in turn.
     *
     * <p>The default republishes now, so this arm is no longer what stands between a position and
     * oblivion. It is still the named handler the aggregate rule asks for, and what it asserts -
     * that the axes arrive intact and under their own letters - is worth pinning either way.
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

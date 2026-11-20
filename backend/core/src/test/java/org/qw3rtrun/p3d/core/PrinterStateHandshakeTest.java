package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.g.marlin.event.CapabilitiesReport;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.terminal.GSender;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the aggregate puts on the wire when it gets on the line, and what it turns off before it
 * hangs up - issue #127, the {@code :backend:core} half.
 *
 * <p>Asserted as the blocks a fake {@link GSender} received, never as "a handler ran": the three
 * features this layer shipped complete and dead were each routed correctly and wrote no bytes.
 * {@code HandshakeIntegrationTest} in {@code :app} makes the same claims at the machine's own
 * command log; neither module can test the other's, so both exist.
 */
class PrinterStateHandshakeTest {

    private final List<String> sent = new ArrayList<>();

    /** A method reference, not a lambda - {@link GSender} has two single-argument constructors. */
    private void capture(String gcode) {
        sent.add(gcode);
    }

    /** A machine nothing has ever asked about itself, with the link up. */
    private PrinterState online() {
        return online(MachineProfile.unknown());
    }

    private PrinterState online(MachineProfile seed) {
        var printer = new PrinterState(UUID.randomUUID(), seed);
        printer.setEmitter(event -> {
        });
        printer.onOnline(new GSender(this::capture));
        return printer;
    }

    /**
     * What an {@code M115} answer holding one capability caused, with the opening pair cleared away.
     * A batch of one since #144 - the decision is taken over a whole answer now, so a single
     * capability is the one-element case of it rather than a different code path.
     */
    private List<String> gatedBy(String capability, boolean enabled) {
        return gatedBy(MachineProfile.unknown(), capability, enabled);
    }

    private List<String> gatedBy(MachineProfile seed, String capability, boolean enabled) {
        var printer = online(seed);
        sent.clear();
        printer.onEvent(new CapabilitiesReport(List.of(new CapabilityRs(capability, enabled))));
        return List.copyOf(sent);
    }

    // ------------------------------------------------------------------ phase 1, ungated

    /**
     * The socket opening asks what the machine is and starts temperatures, and nothing else:
     * everything after this is reached by a reply, so a machine that acknowledges nothing is asked
     * nothing further.
     *
     * <p>{@code M155 S1} is ungated on purpose. {@code AUTOREPORT_TEMP} cannot be known before the
     * {@code M115} answer arrives, so gating it would cost a round trip on every machine and would
     * lose temperature entirely on a firmware that reports no {@code Cap:} lines at all - and
     * temperature is the one reading the UI cannot work without. The price is one command a
     * pre-1.1.9 firmware answers {@code echo:Unknown command}, which is benign, measured and
     * republished to the page.
     */
    @Test
    void theSocketOpeningAsksWhatTheMachineIsAndStartsTemperatures() {
        online();

        assertEquals(List.of("M115", "M155 S1"), sent);
    }

    // ------------------------------------------------------------------ phase 2, capability-gated

    /**
     * {@code Cap:AUTOREPORT_POS:0} - no report to start, so the position is read once. A read is not
     * a subscription and leaves nothing to undo; {@link #aTeardownUndoesOnlyWhatThisSessionStarted}
     * is the other half of that.
     */
    @Test
    void aMachineThatCannotReportItsPositionIsPolledOnce() {
        assertEquals(List.of("M114"), gatedBy("AUTOREPORT_POS", false));
    }

    /**
     * {@code Cap:AUTOREPORT_POS:1} <em>replaces</em> the one-shot read rather than adding to it: a
     * report every second supersedes a single {@code M114}. One second and not three - a toolhead
     * moves as fast as a hotend heats.
     */
    @Test
    void aMachineThatCanReportItsPositionIsAskedToInsteadOfBeingPolled() {
        assertEquals(List.of("M154 S1"), gatedBy("AUTOREPORT_POS", true));
    }

    /**
     * {@code Cap:AUTOREPORT_SD_STATUS:1} <em>adds</em> {@code M27 S3} and does not suppress the
     * one-shot {@code M27} {@code Cap:SDCARD:1} causes - the opposite of the position rule above,
     * and measured: {@code Cap:SDCARD} is line 22 of the simulator's answer and
     * {@code Cap:AUTOREPORT_SD_STATUS} is line 26, so the card is announced <em>before</em> the
     * report that would supersede polling it. A rule that suppressed the one-shot would have to
     * know a fact that has not arrived yet. One extra {@code Not SD printing} on a machine with
     * both is the price of a rule that does not depend on the order Marlin happens to write its
     * capabilities in.
     *
     * <p>Three seconds and not one: a print's percentage does not move at 1 Hz, and every report is
     * a message on every open SSE stream.
     */
    @Test
    void aMachineThatCanReportItsPrintIsAskedToEveryThreeSeconds() {
        assertEquals(List.of("M27 S3"), gatedBy("AUTOREPORT_SD_STATUS", true));
    }

    /** {@code Cap:AUTOREPORT_SD_STATUS:0} - there is nothing to start and nothing to read; {@code Cap:SDCARD} decides the read. */
    @Test
    void aMachineThatCannotReportItsPrintIsAskedNothingByThatLine() {
        assertEquals(List.of(), gatedBy("AUTOREPORT_SD_STATUS", false));
    }

    /** {@code Cap:SDCARD:1} - there may be a print already running, so the status is read once. */
    @Test
    void aMachineWithACardIsAskedOnceWhetherItIsPrinting() {
        assertEquals(List.of("M27"), gatedBy("SDCARD", true));
    }

    /** {@code Cap:SDCARD:0} - no reader, so no question about a print on one. */
    @Test
    void aMachineWithNoCardIsNotAskedWhetherItIsPrinting() {
        assertEquals(List.of(), gatedBy("SDCARD", false));
    }

    /** The other thirty-five {@code Cap:} lines cause nothing. */
    @Test
    void aCapabilityThisFeatureIsNotAboutCausesNothing() {
        assertEquals(List.of(), gatedBy("EEPROM", true));
        assertEquals(List.of(), gatedBy("AUTOREPORT_TEMP", true));
        assertEquals(List.of(), gatedBy("BINARY_FILE_TRANSFER", true));
    }

    /**
     * <strong>The trap this feature is most likely to fall into.</strong> A machine's
     * {@link MachineProfile} is seeded from the roster, which persisted every capability the first
     * session reported, and {@code ProfileControl.on(CapabilitiesReport)} answers {@code false} for
     * a batch whose capabilities have not changed. So from the second connect onwards - and after
     * every restart - no capability is news, and a gate written as
     * {@code if (profile.on(report)) { ...send... }} sends nothing at all in production while
     * passing every first-connect test.
     *
     * <p>The gate is therefore the answer <em>arriving</em>, not the answer being news.
     * {@code HandshakeIntegrationTest.aSecondSessionAsksEverythingTheFirstOneDid} is the same claim
     * over a real socket, and {@link #aBatchWhereEveryCapabilityWasAlreadyKnownStillSendsEveryBlock}
     * is the whole-answer form of this one.
     */
    @Test
    void aCapabilityTheProfileAlreadyHeldStillSendsItsBlock() {
        var seeded = new MachineProfile(Map.of(),
                Map.of("AUTOREPORT_POS", false, "SDCARD", true), LocalDateTime.now());

        assertEquals(List.of("M114"), gatedBy(seeded, "AUTOREPORT_POS", false));
        assertEquals(List.of("M27"), gatedBy(seeded, "SDCARD", true));
    }

    // ------------------------------------------------------------------ the whole answer, #144

    /**
     * The decision is taken over one whole {@code M115} answer, in the firmware's own order - which
     * {@code CapabilitiesReportReader} preserves. Iterating the batch and applying the per-line
     * logic to each element produces the same calls in the same order as the per-line gate did, so
     * the command sequence is identical by construction; this is what says so.
     */
    @Test
    void aWholeBatchFiresEveryGatedBlockInTheFirmwaresOwnOrder() {
        var printer = online();
        sent.clear();

        printer.onEvent(new CapabilitiesReport(List.of(
                new CapabilityRs("AUTOREPORT_POS", false),
                new CapabilityRs("AUTOREPORT_TEMP", true),
                new CapabilityRs("SDCARD", true),
                new CapabilityRs("AUTOREPORT_SD_STATUS", false))));

        assertEquals(List.of("M114", "M27"), sent,
                "the whole batch decided at once, in the firmware's own order; AUTOREPORT_TEMP"
                        + " causes nothing here since M155 S1 is sent ungated from onOnline");
    }

    /**
     * The batch form of the trap above: every capability in the answer was already in the seeded
     * profile, so {@code ProfileControl.on} answers false and nothing is announced - and every
     * gated block still goes out. Arrival, not change, exactly as the per-line gate always did.
     */
    @Test
    void aBatchWhereEveryCapabilityWasAlreadyKnownStillSendsEveryBlock() {
        var seeded = new MachineProfile(Map.of(),
                Map.of("AUTOREPORT_POS", false, "SDCARD", true), LocalDateTime.now());
        var printer = online(seeded);
        sent.clear();

        printer.onEvent(new CapabilitiesReport(List.of(
                new CapabilityRs("AUTOREPORT_POS", false),
                new CapabilityRs("SDCARD", true))));

        assertEquals(List.of("M114", "M27"), sent,
                "the roster already held both capabilities; the batch must still send their blocks -"
                        + " arrival, not change, same as the per-line gate always did");
    }

    /** A pre-1.1.9 firmware with no {@code Cap:} lines at all: an empty answer starts nothing. */
    @Test
    void aFirmwareThatReportsNoCapabilitiesAtAllIsAskedNothingFurther() {
        var printer = online();
        sent.clear();

        printer.onEvent(new CapabilitiesReport(List.of()));

        assertEquals(List.of(), sent, "an empty batch - a pre-1.1.9 firmware with no Cap: lines at"
                + " all - gets nothing beyond M115/M155 S1, same as before");
    }

    // ------------------------------------------------------------------ the teardown

    /**
     * Every auto-report the session started, in the order it started them, and no off-switch for one
     * it never began - see {@link AutoReportControlTest}, which states the rule; this says the
     * aggregate actually writes it.
     */
    @Test
    void aTeardownUndoesOnlyWhatThisSessionStarted() {
        var printer = online();
        printer.onEvent(new CapabilitiesReport(List.of(
                new CapabilityRs("AUTOREPORT_POS", true),
                new CapabilityRs("SDCARD", true))));
        sent.clear();

        assertTrue(printer.quiesce(), "two reports were started, so something had to be sent");

        assertEquals(List.of("M155 S0", "M154 S0"), sent,
                "M27 was a read rather than a subscription, so there is no M27 S0 to send");
    }

    /** A session that only ever started temperatures - the ordinary machine, and what this replaces. */
    @Test
    void theOrdinaryTeardownIsTheOneThingTheOpeningStarted() {
        var printer = online();
        sent.clear();

        assertTrue(printer.quiesce());

        assertEquals(List.of("M155 S0"), sent);
    }

    /** Idempotent: a reactor's goodbye and a farm shutdown's can both reach the same aggregate. */
    @Test
    void aSecondQuiesceSendsNothing() {
        var printer = online();
        printer.quiesce();
        sent.clear();

        assertFalse(printer.quiesce(), "there is nothing left that this session started");

        assertEquals(List.of(), sent);
    }

    /**
     * A machine that was never dialled has no sender to write to - this used to be the
     * {@code NullPointerException} a command with no link was.
     */
    @Test
    void aMachineThatWasNeverOnlineSendsNoTeardown() {
        var printer = new PrinterState(UUID.randomUUID());
        printer.setEmitter(event -> {
        });

        assertFalse(printer.quiesce());

        assertEquals(List.of(), sent);
    }

    /**
     * A link that failed rather than being given up reaches this too ({@code onOffline},
     * {@code onGaveUp}), and the socket is already gone: a block enqueued here would sit in a queue
     * nothing will ever drain.
     */
    @Test
    void aLinkThatHasAlreadyGoneSendsNoTeardown() {
        var printer = online();
        printer.onOffline("the cable was pulled");
        sent.clear();

        assertFalse(printer.quiesce());

        assertEquals(List.of(), sent);
    }

    /**
     * The goodbye has moved out of {@code handle(ConnectCmd)} and into {@link PrinterState#quiesce()},
     * which {@code PrinterReactor} calls <em>before</em> the transition - it is two or three commands
     * now rather than one, it has to be the same operation the shutdown path uses, and the socket
     * must not close until it has reached the wire, which is the transport's decision and therefore
     * the reactor's.
     */
    @Test
    void theDisconnectIntentSendsNothingItself() {
        var printer = online();
        sent.clear();

        printer.handle(new ConnectCmd(false));

        assertEquals(List.of(), sent,
                "the goodbye belongs to quiesce(), which the reactor sends while the link is still "
                        + "up; one sent from here could not be waited for");
    }
}

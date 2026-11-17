package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What the farm asks a machine when it gets on the line, and what it turns off before it hangs up -
 * issue #127, feature 14 of the integration-test spec.
 *
 * <h2>The measurement this is built on</h2>
 * Probed 2026-11-17 with {@code :backend:terminal:probeHandshake} against
 * {@code Marlin bugfix-2.1.x (Oct 29 2026 10:28:56)} on {@code localhost:8099}. Marlin's capability
 * vocabulary has exactly three auto-reports and the simulator advertises one:
 *
 * <pre>
 * Cap:AUTOREPORT_TEMP:1        M155 S1  -&gt; 3 unsolicited ` T:25.00 /0.00 ...` in 3400 ms
 *                              M155 S0  -&gt; 0 in the next 3400 ms            (the control)
 * Cap:AUTOREPORT_POS:0         M154 S1  -&gt; echo:Unknown command: "M154 S1", ok P15 B3; 0 lines
 * Cap:AUTOREPORT_SD_STATUS:0   M27 S1   -&gt; Not SD printing, ok P15 B3;       0 lines
 * </pre>
 *
 * <p><strong>The third line is why this feature reads capabilities instead of just asking.</strong>
 * {@code M154} on a firmware without it is <em>refused</em>, so "send it and let the firmware say
 * no" would work - the same way {@code EEPROMSettingsControl} learns about {@code M503}. {@code M27 S1}
 * on a firmware without it is <em>accepted</em>, byte-for-byte as a bare {@code M27}, and then
 * reports nothing ever. There is no signal on the wire at all, so a host that did not read
 * {@code Cap:AUTOREPORT_SD_STATUS} would believe it had print-progress reporting and quietly never
 * get a single report.
 *
 * <p><strong>And note the spelling: {@code AUTOREPORT_POS}.</strong> The build macro is
 * {@code AUTOREPORT_POSITION} and the G-code is {@code M154}; the wire says {@code AUTOREPORT_POS},
 * and no line under any longer spelling appears among the simulator's 38.
 *
 * <h2>The ordering problem, and how it is solved</h2>
 * A capability is only known once the {@code M115} answer has arrived, so nothing gated can be sent
 * in the same breath as {@code M115} itself. The farm does not wait for the answer and does not
 * guess: <strong>each gated block is sent by the arrival of the {@code Cap:} line that decides
 * it</strong> - {@code PrinterState.on(CapabilityRs)} - so its precondition is exactly the fact that
 * permits it, with no "is the handshake finished yet" state and no timer.
 *
 * <p>Two consequences this class asserts directly, because both are the kind of thing that ships a
 * feature dead:
 *
 * <ul>
 *   <li>the gate is driven by the line <em>arriving</em>, not by it being <em>news</em>.
 *       {@code ProfileControl.on} answers false for a capability it already holds, and a machine's
 *       profile is seeded from the roster - so on every connect after the first, every
 *       capability is already known and a gate keyed on "did this change anything" would send
 *       nothing at all. {@link #aSecondSessionAsksEverythingTheFirstOneDid()} is that case;</li>
 *   <li>{@code M155 S1} stays <em>ungated</em>. It is the one reading a page cannot work without,
 *       {@code AUTOREPORT_TEMP} cannot be known before it would be sent, and a firmware that reports
 *       no capabilities at all would lose temperature entirely. The cost is one command a
 *       pre-1.1.9 firmware answers {@code echo:Unknown command}, which is benign and republished.</li>
 * </ul>
 *
 * <h2>What is deliberately not asked on connect</h2>
 * {@code M20} (measured 7 lines for 4 files, and it scales with the card - only the Storage tab
 * reads it, and it asks for itself), {@code M503} (measured 26 lines on a bare simulator; #88
 * already decided this is asked when a person wants it, and {@code EEPROMSettingsControl.sessionStarted()}
 * forgets rather than re-reads), {@code M31} (meaningful only mid-print and wrapped in a
 * {@code //action:notification}), and {@code M105} (covered within a second by {@code M155 S1}).
 * {@link #connectingAsksForNeitherTheFileListNorTheSettings()} holds that line, so a later change
 * that adds one has to argue with a test rather than slip past.
 *
 * @see GracefulShutdownIntegrationTest#aFarmShutdownTurnsOffTheAutoReportsItStarted
 */
class HandshakeIntegrationTest extends FarmIntegrationTest {

    // ------------------------------------------------------------------ the connect sequence

    /**
     * The baseline. {@code M110 N0} opens the numbering (spec 7.2), {@code M115} asks what the
     * machine is, {@code M155 S1} starts temperatures ungated, and then the two capability-gated
     * blocks: {@code M114} because {@code Cap:AUTOREPORT_POS:0} means there is no position report to
     * start, and {@code M27} because {@code Cap:SDCARD:1} means there may be a print already
     * running.
     *
     * <p>The whole list, not a prefix: §3.3 of the spec, and the only form that catches a sixth
     * block arriving that nobody asked for.
     */
    @Test
    void aCapableMachineIsAskedWhatItIsAndThenForWhatItCannotReport() {
        var printer = machine(MockPrinter.marlin());
        var id = register("the baseline handshake", printer);

        connectAndLearn(id);

        awaitCommands(printer, List.of("M110 N0", "M115", "M155 S1", "M114", "M27"));
        assertEquals(List.of("M110 N0", "M115", "M155 S1", "M114", "M27"), printer.commands(),
                "the session must open its numbering, ask what the machine is, start temperatures,"
                        + " then read once what it cannot have reported to it");
    }

    /**
     * {@code Cap:AUTOREPORT_POS:1} replaces the one-shot read rather than adding to it: a report
     * every second supersedes a single {@code M114}, so sending both would be a block for nothing.
     *
     * <p>The pair with {@link #aCapableMachineIsAskedWhatItIsAndThenForWhatItCannotReport()} is the
     * point - the same farm, the same request, and the opposite block on the wire. One capable
     * profile alone would pass with the capability check deleted.
     */
    @Test
    void aMachineThatCanReportItsPositionIsAskedToInsteadOfBeingPolled() {
        var printer = machine(MockPrinter.withPositionAutoReport());
        var id = register("reports its own position", printer);

        connectAndLearn(id);

        awaitCommands(printer, List.of("M110 N0", "M115", "M155 S1", "M154 S1", "M27"));
        assertEquals(List.of("M110 N0", "M115", "M155 S1", "M154 S1", "M27"), printer.commands(),
                "a machine that reports its position must be asked to and must not also be polled");
        assertFalse(printer.sent("M114"),
                "M114 is the fallback for a machine that cannot report, and this one can: "
                        + printer.commands());
    }

    /**
     * {@code Cap:AUTOREPORT_SD_STATUS:1} <em>adds</em> {@code M27 S3} and does not replace the
     * one-shot {@code M27}, which is the opposite of the position rule above and is deliberate.
     *
     * <p>Measured, and the reason: {@code Cap:SDCARD} is line 22 of the simulator's {@code M115}
     * answer and {@code Cap:AUTOREPORT_SD_STATUS} is line 26, so the card is announced <em>before</em>
     * the auto-report that would supersede polling it. A rule that suppressed the one-shot would have
     * to know a fact that has not arrived yet. One extra {@code Not SD printing} on a machine with
     * both is the price of a rule that does not depend on the order Marlin happens to write its
     * capabilities in.
     *
     * <p>Three seconds and not one: a print's percentage does not move at 1 Hz, and every report is
     * a message on every open SSE stream.
     */
    @Test
    void aMachineThatCanReportItsPrintIsAskedToAsWellAsBeingReadOnce() {
        var printer = machine(MockPrinter.withSdStatusAutoReport());
        var id = register("reports its own print", printer);

        connectAndLearn(id);

        awaitCommands(printer, List.of("M110 N0", "M115", "M155 S1", "M114", "M27", "M27 S3"));
        assertEquals(List.of("M110 N0", "M115", "M155 S1", "M114", "M27", "M27 S3"),
                printer.commands(),
                "the card is announced before the auto-report, so the one-shot read is already on"
                        + " the wire by the time the farm learns the report exists");
    }

    /**
     * No card, no question about one. {@code Cap:SDCARD:0} is the same server-side gate the Storage
     * tab has: the farm decides from the capability and never from a reply, so what a machine with
     * no reader answers an {@code M27} is never needed and is never measured.
     */
    @Test
    void aMachineWithNoCardIsNotAskedWhetherItIsPrinting() {
        var printer = machine(MockPrinter.withoutCard());
        var id = register("nothing in the reader", printer);

        connectAndLearn(id);

        awaitCommands(printer, List.of("M110 N0", "M115", "M155 S1", "M114"));
        assertEquals(List.of("M110 N0", "M115", "M155 S1", "M114"), printer.commands(),
                "a machine that reported no card must not be asked about a print on it");
    }

    /**
     * A terminal server with nothing behind it: the socket opens, {@code M110 N0} goes out and the
     * send window will not release another block until one comes back. So no {@code M115} answer,
     * no {@code Cap:} line, and therefore nothing capability-gated - the whole second phase of the
     * handshake is reached by a reply or not at all.
     *
     * <p>{@link #quiet()} rather than an await, per §3.2: the claim is that nothing happened.
     */
    @Test
    void aMachineThatAnswersNothingIsAskedNothingFurther() {
        var printer = machine(MockPrinter.mute());
        var id = register("says nothing", printer);

        connect(id);
        await(() -> "the first block to reach the machine. It received " + printer.commands(),
                () -> printer.sent("M110 N0"));
        quiet();

        assertEquals(List.of("M110 N0"), printer.commands(),
                "nothing after the first block can be sent to a machine that acknowledges none of"
                        + " it, and nothing gated can be sent to one that reported no capabilities");
    }

    /**
     * The file list and the settings dump are not part of getting on the line - see the class
     * javadoc. A negative stated with {@link #quiet()}, and stated at all because "while we are
     * here, read everything" is the obvious next change and is the wrong one: both grow with the
     * machine rather than being a fixed cost, and both already have an endpoint a person drives.
     */
    @Test
    void connectingAsksForNeitherTheFileListNorTheSettings() {
        var printer = machine(MockPrinter.marlin());
        var id = register("asked only what it is", printer);

        connectAndLearn(id);
        awaitCommands(printer, List.of("M110 N0", "M115", "M155 S1", "M114", "M27"));
        quiet();

        assertFalse(printer.sent("M20"),
                "M20 is 7 lines for 4 files and scales with the card; the Storage tab asks for"
                        + " itself: " + printer.commands());
        assertFalse(printer.sent("M503"),
                "M503 is 26 lines on a bare simulator, and #88 settled that it is asked when a"
                        + " person wants it: " + printer.commands());
        assertFalse(printer.sent("M31"),
                "print time means nothing on a machine that is not printing: " + printer.commands());
    }

    /**
     * <strong>The trap this feature is most likely to fall into.</strong>
     *
     * <p>A machine's {@code MachineProfile} is seeded from the roster, which persisted every
     * capability the first session reported, and {@code ProfileControl.on(CapabilityRs)} answers
     * {@code false} for a capability whose value has not changed. So on the second connect - and on
     * every connect after a restart - the capability lines are no longer news. A gate written as
     * {@code if (profile.on(report)) { ... send the gated block ... }} passes this class's first
     * test and sends nothing at all in production from the second session onwards.
     *
     * <p>Asserted as the whole list again, from a forgotten log, so an extra or a missing block both
     * fail.
     */
    @Test
    void aSecondSessionAsksEverythingTheFirstOneDid() {
        var printer = machine(MockPrinter.marlin());
        var id = register("dialled twice", printer);
        connectAndSettle(id, printer);
        awaitCapability(id, "AUTOREPORT_POS");
        disconnect(id);
        await("the first session to end", () -> !printer.sessionOpen());
        printer.forget();

        connectAndLearn(id);

        awaitCommands(printer, List.of("M110 N0", "M115", "M155 S1", "M114", "M27"));
        assertEquals(List.of("M110 N0", "M115", "M155 S1", "M114", "M27"), printer.commands(),
                "the roster already held every capability, so a gate keyed on what changed would"
                        + " have sent nothing after M155 S1");
    }

    // ------------------------------------------------------------------ the disconnect teardown

    /**
     * <strong>The other half of the issue, and it was already broken for the one auto-report that
     * existed.</strong>
     *
     * <p>{@code PrinterState.handle(ConnectCmd)} has sent {@code M155 S0} before a voluntary
     * disconnect since long before #127, and nothing ever asserted it reached the machine.
     * {@code PrinterReactor.applyConnectCmd} enqueues it and then calls {@code disconnect()} -
     * {@code terminal.stop()}, the socket shut - in the same task, with nothing between them that
     * waits for the block to be taken by the command queue. This test is that claim at the far
     * boundary: the machine's own command log, after the socket is closed.
     *
     * <p>It is the same shape as the three features this repository shipped complete, green and
     * dead. The host's side of it looks right either way.
     */
    @Test
    void aVoluntaryDisconnectTurnsTheTemperatureReportOff() {
        var printer = machine(MockPrinter.marlin());
        var id = register("hung up on politely", printer);
        connectAndSettle(id, printer);

        disconnect(id);

        await(() -> "M155 S0 to reach the machine before the socket closes. It received "
                        + printer.commands(),
                () -> printer.commands().contains("M155 S0"));
        await("the socket to close at the machine", () -> !printer.sessionOpen());
        assertEquals(List.of("M155 S0"), printer.commands(),
                "a hang-up turns off what it turned on and sends nothing else");
    }

    /**
     * Every auto-report the session started, and only those. A machine that reports its position
     * was told {@code M154 S1} and must be told {@code M154 S0}; it was never told {@code M27 S<n>},
     * and must not be told {@code M27 S0} either - the teardown undoes what was done rather than
     * sending the off-switch of everything that exists.
     *
     * <p>That distinction is the reason the aggregate remembers rather than recomputes: a
     * capability could have flipped mid-session on a reflashed machine at the same address, and an
     * off-switch for a report that was never on is a block for nothing at the one moment the farm
     * has a budget.
     */
    @Test
    void aDisconnectTurnsOffEveryReportItStartedAndNoOthers() {
        var printer = machine(MockPrinter.withPositionAutoReport());
        var id = register("two reports to turn off", printer);
        connectAndSettle(id, printer);

        disconnect(id);

        await(() -> "both off-switches to reach the machine. It received " + printer.commands(),
                () -> printer.commands().containsAll(List.of("M155 S0", "M154 S0")));
        await("the socket to close at the machine", () -> !printer.sessionOpen());
        assertEquals(List.of("M155 S0", "M154 S0"), printer.commands(),
                "the teardown undoes what this session started, in the order it started it, and"
                        + " must not send M27 S0 for a report it never began");
    }

    /**
     * A machine that reported a print-status auto-report gets that one turned off too, and the
     * one-shot {@code M27} it was also sent leaves nothing to undo - a read is not a subscription.
     */
    @Test
    void aDisconnectTurnsOffThePrintStatusReportButNotTheReadThatWentWithIt() {
        var printer = machine(MockPrinter.withSdStatusAutoReport());
        var id = register("a print report to turn off", printer);
        connectAndSettle(id, printer);

        disconnect(id);

        await(() -> "both off-switches to reach the machine. It received " + printer.commands(),
                () -> printer.commands().containsAll(List.of("M155 S0", "M27 S0")));
        assertEquals(List.of("M155 S0", "M27 S0"), printer.commands(),
                "M27 S3 was a subscription and is turned off; the bare M27 was a read and is not");
    }

    /**
     * A link that fails rather than being given up has nothing to send the teardown over, and must
     * not try: the socket is already gone, so a block enqueued here would sit in a queue nothing
     * will ever drain and - before #127 made {@code quiesce()} check - would have been a command
     * sent against a null sender.
     *
     * <p>The observable is the machine's own session count and command log across a redial: the
     * second session must open with a fresh {@code M110 N0} handshake and carry no off-switch from
     * the first, because the first's was never sendable.
     */
    @Test
    void aDroppedLinkSendsNoTeardownOverASocketThatHasGone() {
        var printer = machine(MockPrinter.marlin());
        var id = register("cable pulled", printer);
        connectAndSettle(id, printer);

        dropTheLinkAndWait(id, printer);

        awaitCommands(printer, List.of("M110 N0", "M115", "M155 S1", "M114", "M27"));
        assertEquals(List.of("M110 N0", "M115", "M155 S1", "M114", "M27"), printer.commands(),
                "the second session is a whole new handshake and nothing else; an M155 S0 here"
                        + " would be the host answering a dead socket");
    }

    /** Waits for a whole list to have arrived, so the equality that follows reads a settled log. */
    private static void awaitCommands(MockPrinter printer, List<String> expected) {
        await(() -> "the machine to have received " + expected + ". It received "
                        + printer.commands(),
                () -> printer.commands().containsAll(expected));
    }
}

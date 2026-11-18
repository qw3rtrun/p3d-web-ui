package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Terminal tab - issue #135: a console an operator types raw G-code into, showing what crossed
 * the wire in both directions, with the decode as an annotation rather than a substitute.
 *
 * <h2>Why the raw line is carried at all, measured</h2>
 *
 * {@code TerminalConsoleProbe} ({@code ./gradlew :backend:terminal:probeTerminalConsole}) ran the
 * whole reply surface past this farm's own decoders against MarlinSim
 * {@code bugfix-2.1.x (Nov 18 2026 11:55:10)} on 2026-11-18 and censused 106 received lines:
 *
 * <pre>
 * UNCLAIMED - nothing reaches a subscriber    10
 * RENDERABLE from the decoded event           81
 * LOSSY - encode() is not the line             3
 * CONSUMED by the transport as flow control   12
 * -- total received                          106
 * </pre>
 *
 * <p><strong>25 of 106 lines cannot be rendered from the decoded event at all</strong>, in three
 * different ways, and the three tests named after them are the reason this feature is not just a
 * second view of {@code /stream}:
 *
 * <ul>
 *   <li>{@link #aLineNoDecoderClaimsStillReachesTheTerminal()} - {@code ReplyFlux.decode} drops a
 *       line no decoder claims to the {@code #traffic} logger and emits <em>nothing</em>. The whole
 *       simulator boot banner is in this set.</li>
 *   <li>{@link #theAcknowledgementTheMonitorStreamSwallowsIsOnTheTerminal()} - every acknowledgement
 *       is withheld from {@code messageFlux()} as flow control, correctly. It is also <em>the answer
 *       to the command the operator just typed</em>. The probe counted these as {@code ok P15 B3}
 *       because it wrote unnumbered lines over a plain socket; through this farm the same line is
 *       {@code ok N<n> P15 B3}, which is the distinction {@link MockPrinter#ok()} exists to make and
 *       the one that test's own javadoc records being corrected.</li>
 *   <li>{@link #aSentLineIsShownFramedAsItWentOnTheWire()} - the outbound direction had no
 *       subscriber-visible form at all before this feature.</li>
 * </ul>
 *
 * <h2>The bypass, and how it is observable from outside</h2>
 *
 * Issue point 4 asks for "direct send to terminal, not through PrinterState", and
 * {@link #theAggregatesOwnJudgementIsNotAppliedToATypedLine()} is the whole of it as an endpoint
 * assertion: the same machine refuses {@code POST /set-temp} for tool 1 (the aggregate's
 * {@code extruderCount()} check, on a {@code EXTRUDER_COUNT:1} machine) and sends
 * {@code M104 T1 S200} typed at the terminal. Two routes, one effect asked for, two outcomes - and
 * the difference *is* the bypass. Nothing softer than that proves it; a test that only sent
 * {@code M105} would pass against an implementation that routed everything through the aggregate.
 *
 * <h2>The validation gates, and their control</h2>
 *
 * Five gates, each with its own test below, all answering <strong>400 with nothing on the
 * wire</strong>. They would all pass against a server that refused every line, so
 * {@link #aCommandTheFirmwareDoesNotHaveIsSentAnyway()} is the control that makes them mean
 * something: {@code M851} is not a command this build has, it is sent regardless, and the machine's
 * own {@code echo:Unknown command} comes back. A terminal that second-guessed the firmware could not
 * be used to explore a printer, which is what a terminal is for.
 *
 * <p><strong>{@code NOTGCODE} drew no reply at all</strong> from the simulator - measured in the same
 * probe run. So a line that is not a command is sent, echoed as {@code SENT}, and then nothing
 * happens. That is honest, and no test here asserts a host-side rejection of it.
 *
 * <h2>What is deliberately not tested here</h2>
 *
 * <p><strong>Issue point 3, "do not store communication logs to DB", has no test</strong> and that
 * is the honest position: it is satisfied by there being no persistence code, and a test asserting
 * the absence of a table it has no endpoint to ask about would be testing Spring's routing rather
 * than this feature. The surface is one {@code POST} and one stream, which is visible in this file;
 * a later change that adds a history endpoint is reversing a decision, and the place that decision
 * is written down is {@code PrinterControl.traffic}'s javadoc.
 *
 * <p>Role enforcement is {@code FarmSecurityTest}'s matrix in {@code :frontend:web-ui}, not a second
 * copy here. Both new endpoints fall under rules that already exist -
 * {@code POST /api-printer/**} is {@code OPERATOR}/{@code ADMIN} and {@code GET /api-printer/**} is
 * {@code VIEWER}+ - so no security configuration changed, which is itself worth an assertion over
 * there rather than here.
 *
 * <h2>Configurations</h2>
 *
 * {@code marlin()} is the baseline. {@code legacyOk()} is the second row of §5's table that changes
 * anything here: its bare {@code ok} decodes to {@code SimpleOkRs} rather than {@code AdvancedOkRs},
 * so {@link #theDecodeAnnotationFollowsTheMachineAndNotTheHost()} proves the annotation is read off
 * the reply rather than assumed. A card reader and an extruder count change nothing about typing a
 * line, except in the one test that uses the extruder count as the aggregate's own judgement.
 */
class TerminalIntegrationTest extends FarmIntegrationTest {

    // ------------------------------------------------------- the round trip, both directions

    /**
     * The feature in one test: a line typed, the machine's answer back, raw and annotated.
     *
     * <p>{@code M105} because its answer <em>is</em> its acknowledgement
     * ({@code ok T:210.00 /210.00 …}), so one reply exercises both halves of what a terminal shows -
     * and it is the one {@code ok} the monitor stream also publishes, which keeps this test about the
     * round trip rather than about the swallowed-acknowledgement case two tests below.
     */
    @Test
    void aTypedCommandReachesTheMachineAndItsAnswerComesBackRawAndParsed() {
        var printer = machine(MockPrinter.marlin());
        var id = register("typing", printer);
        connectAndSettle(id, printer);
        var console = watchTerminal(id);

        type(id, "M105").expectStatus().isOk();

        awaitCommand(printer, "M105");
        assertEquals(List.of("M105"), printer.commands(),
                "the line the operator typed, and nothing the host added of its own");

        var sent = awaitLine(console, "SENT", "M105");
        assertTrue(raw(sent).matches("N\\d+ M105\\*\\d+"),
                "a terminal shows what went on the wire, which is the framed block: " + raw(sent));

        var received = awaitLine(console, "RECEIVED", "ok T:");
        assertEquals("ok T:210.00 /210.00 B:25.00 /0.00 @:11 B@:0", raw(received),
                "the machine's own bytes, not a re-rendering of what was decoded from them");
        assertEquals("OkTemperatureRs", received.get("decodedAs"),
                "parsed as usual, and the parse travels beside the line rather than instead of it");
    }

    /**
     * The outbound direction, stated on its own because it had no subscriber-visible form at all
     * before this feature - {@code CommandProcessor.write0} logged the framed block to the
     * {@code #traffic} logger and nothing else.
     *
     * <p>The {@code N} and the {@code *} checksum are the point. The operator typed four characters
     * and nine went on the wire; a console showing the four would be showing what was asked for
     * rather than what happened, and the difference is exactly where a line-numbering fault lives.
     * Asserted as a pattern because the number depends on how long this machine's handshake was,
     * which is a property of its capabilities.
     */
    @Test
    void aSentLineIsShownFramedAsItWentOnTheWire() {
        var printer = machine(MockPrinter.marlin());
        var id = register("framing", printer);
        connectAndSettle(id, printer);
        var console = watchTerminal(id);

        type(id, "M114").expectStatus().isOk();

        var sent = awaitLine(console, "SENT", "M114");
        assertTrue(raw(sent).matches("N\\d+ M114\\*\\d+"),
                "the N field and the checksum the send window added have to be visible,"
                        + " or the console cannot be used to diagnose the thing they go wrong in: "
                        + raw(sent));
        assertNull(sent.get("decodedAs"),
                "an outbound block is not a reply and has nothing to decode it as");
    }

    // ------------------------------------- the three ways the monitor stream loses a line

    /**
     * The central measurement. {@code ReplyFlux.decode} answers {@code null} for a line no decoder
     * claims and drops it to the traffic logger, so it reaches no subscriber of
     * {@code messageFlux()} - and ten of the probe's 106 lines are in that set, including the whole
     * of the simulator's boot banner.
     *
     * <p>{@code Sim Startup!} is one of them, measured {@code [NONE]} in the probe run. Both halves
     * are asserted: it is on the terminal with no annotation, and it is <em>not</em> on the monitor
     * stream - because without the second half this test would pass against an implementation that
     * simply forwarded {@code /stream}.
     */
    @Test
    void aLineNoDecoderClaimsStillReachesTheTerminal() {
        var printer = machine(MockPrinter.marlin());
        var id = register("unclaimed", printer);
        var monitor = connectAndSettle(id, printer);
        var console = watchTerminal(id);

        // Measured [NONE] by TerminalConsoleProbe: no decoder in this farm claims it.
        printer.push("Sim Startup!");

        var line = awaitLine(console, "RECEIVED", "Sim Startup!");
        assertEquals("Sim Startup!", raw(line));
        assertNull(line.get("decodedAs"),
                "nothing claimed it, and saying so is more use to an operator than hiding it");

        quiet();
        assertFalse(monitor.types().stream().anyMatch(type -> type.contains("Startup")),
                "the monitor stream drops an unclaimed line, correctly - if it carried this one,"
                        + " this feature would not need a stream of its own. It carried "
                        + monitor.types());
    }

    /**
     * The acknowledgement. {@code HostTerminal.publishPayload} holds back {@code SimpleOkRs} and
     * {@code AdvancedOkRs} because they are window credit rather than news - right for a monitor,
     * and wrong for a console, where the {@code ok} is the answer to what was just typed.
     *
     * <p>Twelve of the probe's 106 lines are consumed this way. The second assertion is again the
     * one that makes the test mean something.
     *
     * <p><strong>It is {@code ok N<n> P15 B3} here and not the probe's {@code ok P15 B3}</strong>,
     * and that correction is the one {@link MockPrinter#ok()} warns about in so many words: a census
     * line is a line in <em>the exchange it was captured from</em> (#61). {@code TerminalConsoleProbe}
     * wrote bare lines over a plain socket, so every {@code ok} it measured was unnumbered; this farm
     * numbers every block, so the acknowledgement it actually gets carries the {@code N} of the block
     * it answers. The string was real and the exchange was not this one. Asserted as a pattern
     * because which number it is depends on how long this machine's handshake was.
     */
    @Test
    void theAcknowledgementTheMonitorStreamSwallowsIsOnTheTerminal() {
        var printer = machine(MockPrinter.marlin());
        var id = register("acknowledgements", printer);
        var monitor = connectAndSettle(id, printer);
        var console = watchTerminal(id);

        type(id, "M114").expectStatus().isOk();

        var ack = awaitLine(console, "RECEIVED", "ok N");
        assertTrue(raw(ack).matches("ok N\\d+ P15 B3"),
                "this farm numbers every block, so its acknowledgement names the one it answers: "
                        + raw(ack));
        assertEquals("AdvancedOkRs", ack.get("decodedAs"),
                "consumed by the send window, and still decoded - the annotation is what tells an"
                        + " operator this line was flow control rather than an answer");

        assertFalse(monitor.saw("AdvancedOkRs"),
                "the monitor stream must go on withholding it; it is credit, not news. It carried "
                        + monitor.types());
    }

    /**
     * The decode is read off the reply, not assumed from the host's own idea of what a reply is.
     *
     * <p>{@code legacyOk()} is a firmware built without {@code ADVANCED_OK}: a bare {@code ok} to
     * everything, no buffer counts, no line number. The same position in the same exchange therefore
     * carries a different type, and an implementation that hard-coded the annotation would pass the
     * test above and fail this one.
     */
    @Test
    void theDecodeAnnotationFollowsTheMachineAndNotTheHost() {
        var printer = machine(MockPrinter.legacyOk());
        var id = register("bare ok", printer);
        connectAndSettle(id, printer);
        var console = watchTerminal(id);

        type(id, "M114").expectStatus().isOk();

        var ack = awaitLine(console, "RECEIVED", "ok");
        assertEquals("ok", raw(ack), "this firmware reports no buffer counts and no line number");
        assertEquals("SimpleOkRs", ack.get("decodedAs"));
    }

    // ------------------------------------------------------------------ the bypass (point 4)

    /**
     * The whole of issue point 4, as something observable from outside the process.
     *
     * <p>{@code PrinterState.handle(SetHotendTemperature)} checks the tool index against
     * {@code extruderCount()} and <em>drops</em> a command for a tool the machine does not have -
     * this profile reports {@code EXTRUDER_COUNT:1}, so {@code POST /set-temp} for index 1 reaches
     * the aggregate and stops there. The same effect typed at the terminal goes out.
     *
     * <p>That asymmetry is the design, not a defect of it: the aggregate's contribution is
     * interpretation, and a console exists to send what was typed. It is also the only assertion
     * available at this boundary that a second send path exists at all - the send window, the queue
     * and the socket are all shared, so nothing else differs between the two routes.
     */
    @Test
    void theAggregatesOwnJudgementIsNotAppliedToATypedLine() {
        var printer = machine(MockPrinter.marlin());
        var id = register("one extruder", printer);
        connectAndSettle(id, printer);
        awaitCapability(id, "EEPROM");

        // The aggregate's route: refused for a tool this machine does not have, and silently.
        command(id, "/set-temp", "{\"index\":1,\"temp\":200}").expectStatus().isOk();
        quiet();
        assertTrue(printer.commands().isEmpty(),
                "the aggregate checks the tool index and drops this one; it reached " + id
                        + " as " + printer.commands());

        // The terminal's route: the same thing asked for, and it goes.
        type(id, "M104 T1 S200").expectStatus().isOk();

        awaitCommand(printer, "M104 T1 S200");
        assertEquals(List.of("M104 T1 S200"), printer.commands(),
                "a typed line is not interpreted, so there is no judgement to apply to it");
    }

    // ------------------------------------------------------------------ validation

    /**
     * A {@code ;} starts a comment that runs to the end of the line, and the transport appends the
     * checksum <em>behind</em> what it is given - so this line would reach the firmware as
     * {@code N7 M117 hi;M112*83} with the checksum commented out, be rejected, and be resent
     * verbatim for ever. The same four-character table {@code bareString} refuses, applied to a whole
     * typed line (#38, #82).
     *
     * <p>The refusal carries the reason, because {@code client.ts} shows it to whoever typed it.
     */
    @Test
    void aLineCarryingASemicolonIsRefusedBecauseItWouldCommentOutTheChecksum() {
        var printer = machine(MockPrinter.marlin());
        var id = register("semicolon", printer);
        connectAndSettle(id, printer);

        var refusal = json(type(id, "M117 hi;M112").expectStatus().isBadRequest());
        assertEquals("MALFORMED", refusal.get("refusal"));
        assertTrue(String.valueOf(refusal.get("reason")).contains(";"),
                "the character that was refused belongs in the message: " + refusal.get("reason"));

        quiet();
        assertTrue(printer.commands().isEmpty(),
                "refused means not sent: " + printer.commands());
    }

    /**
     * A newline would split one typed line into two blocks, with the {@code N} word on one side of
     * the break and the checksum on the other - and the second half would be a command the operator
     * did not knowingly send, which here is an emergency stop.
     */
    @Test
    void aLineCarryingANewlineIsRefusedBecauseItWouldBeTwoBlocks() {
        var printer = machine(MockPrinter.marlin());
        var id = register("newline", printer);
        connectAndSettle(id, printer);

        type(id, "M105\\nM112").expectStatus().isBadRequest();

        quiet();
        assertTrue(printer.commands().isEmpty(),
                "one typed line is one block, or it is refused: " + printer.commands());
    }

    /**
     * Spec 1.3: Marlin reads a line into {@code MAX_CMD_SIZE} and truncates the rest, checksum
     * included, so an over-long block is rejected - and it is the one error a resend cannot repair,
     * because the replay truncates at the same byte. A host that sends one stops making progress
     * rather than losing a line, which is why this is measured before sending.
     *
     * <p>The gate is {@code GLineLimit.INTEROPERABLE_PAYLOAD} (76) and not
     * {@code payloadBudget(n)}: the line number is not known when the operator types, because the
     * transport assigns it at write time.
     */
    @Test
    void anOverlongLineIsRefusedBeforeItCanJamTheResendLoop() {
        var printer = machine(MockPrinter.marlin());
        var id = register("too long", printer);
        connectAndSettle(id, printer);

        type(id, "M117 " + "x".repeat(80)).expectStatus().isBadRequest();

        quiet();
        assertTrue(printer.commands().isEmpty(),
                "an over-long block jams the session for good, so it may not be sent: "
                        + printer.commands());
    }

    /**
     * The line counter belongs to the send window. A caller-supplied {@code N} would be framed
     * behind the window's own, reaching the firmware as {@code N7 N5 M105*xx} - and spec 9 makes
     * {@code N} not being first a structural error.
     */
    @Test
    void aCallerSuppliedLineNumberIsRefusedBecauseTheSendWindowOwnsIt() {
        var printer = machine(MockPrinter.marlin());
        var id = register("numbered", printer);
        connectAndSettle(id, printer);

        type(id, "N5 M105").expectStatus().isBadRequest();

        quiet();
        assertTrue(printer.commands().isEmpty(),
                "the host numbers blocks; a typed N is two counters that disagree: "
                        + printer.commands());
    }

    /**
     * The four commands the <em>transport</em> owns, refused with the same reasons
     * {@code PrinterState.onCommand} already gives for each. These break the host's own session
     * rather than the machine, which is why they are refused where everything else is let through:
     *
     * <ul>
     *   <li>{@code M110} moves the firmware's counter and not the window's, so every block after it
     *       is rejected for the rest of the session - measured by {@code ResendDriftProbe};</li>
     *   <li>{@code M28} turns the wire into a file sink until {@code M29}, and this route takes no
     *       exclusive hold of the queue, so everything typed after it would be written into the file
     *       instead of run;</li>
     *   <li>{@code M29} ends a transfer this route never began;</li>
     *   <li>{@code M112} needs {@code Priority.EMERGENCY}; queued behind a print at
     *       {@code REGULAR} it is not an emergency stop, and Marlin's emergency parser reads it out
     *       of the serial bytes anyway - so a half-working one is worse than a refusal.</li>
     * </ul>
     */
    @Test
    void theFourCommandsTheTransportOwnsAreRefused() {
        var printer = machine(MockPrinter.marlin());
        var id = register("transport's own", printer);
        connectAndSettle(id, printer);

        for (var line : List.of("M110 N0", "M112", "M28 hack.gco", "M29")) {
            var refusal = json(type(id, line).expectStatus().isBadRequest());
            assertEquals("MALFORMED", refusal.get("refusal"), "for " + line);
            assertNotNull(refusal.get("reason"), "a refusal has to say why, for " + line);
        }

        quiet();
        assertTrue(printer.commands().isEmpty(),
                "none of the four may reach the machine: " + printer.commands());
        assertNull(printer.writingTo(),
                "and in particular no file may have been opened on the card");
    }

    /**
     * The control for every test above. {@code M851} is a command this build does not have -
     * measured, {@code echo:Unknown command: "M851"} then {@code ok P15 B3} - and it is sent anyway.
     *
     * <p>Without this test, "refuse everything" passes the whole validation section. With it, the
     * gates are pinned to exactly the five things that break the host, and the machine keeps the
     * right to refuse its own commands in its own words.
     */
    @Test
    void aCommandTheFirmwareDoesNotHaveIsSentAnyway() {
        var printer = machine(MockPrinter.marlin());
        var id = register("exploring", printer);
        connectAndSettle(id, printer);
        var console = watchTerminal(id);

        type(id, "M851").expectStatus().isOk();

        awaitCommand(printer, "M851");
        assertEquals(List.of("M851"), printer.commands(),
                "a terminal that only sent commands the host knows could not be used to"
                        + " explore a printer, which is what a terminal is for");

        var refusal = awaitLine(console, "RECEIVED", "Unknown command");
        assertEquals("echo:Unknown command: \"M851\"", raw(refusal),
                "and the machine's own refusal is what the operator should see");
        assertEquals("UnknownCommand", refusal.get("decodedAs"));
    }

    // ------------------------------------------------------------------ no link

    /**
     * 409 and not 400: the line was well formed and the operator was entitled to send it, the
     * machine simply is not there - {@code Outcomes}' "not now". And nothing dials: asking a machine
     * to run a command must not be what connects it, the same rule
     * {@code MotionControlIntegrationTest} pins for {@code /home}.
     */
    @Test
    void anUnconnectedMachineRefusesWithoutDialling() {
        var printer = machine(MockPrinter.marlin());
        var id = register("not dialled", printer);

        type(id, "M105").expectStatus().isEqualTo(409);

        quiet();
        assertEquals(0, printer.sessions(),
                "typing a line must not be what opens a socket");
        assertTrue(printer.commands().isEmpty(), printer.commands().toString());
    }

    /** A machine the farm has never heard of is 404, not 409 - there is nothing to be in a state. */
    @Test
    void anUnknownMachineIsNotFound() {
        type(java.util.UUID.randomUUID(), "M105").expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * Types one line at the terminal, the way the browser does - the one place a line becomes JSON.
     *
     * <p>Note what the escaping means in the two tests that rely on it: the Java literal
     * {@code "M105\\nM112"} is the ten characters {@code M105\nM112}, which is a JSON string
     * carrying a real line break. That is the thing being refused, so it has to arrive as one.
     */
    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec type(
            java.util.UUID id, String line) {
        return command(id, "/terminal", "{\"line\":\"" + line + "\"}");
    }

    private static void awaitCommand(MockPrinter printer, String command) {
        await(() -> "the machine to receive " + command + ". It received " + printer.commands(),
                () -> printer.sent(command));
    }

    /**
     * Waits for a {@code TerminalTraffic} in {@code direction} whose raw text contains
     * {@code needle}, and hands back its payload.
     *
     * <p>Matched on content and not by position, because the terminal sink replays its scrollback
     * and a test opening the console after the handshake legitimately sees the handshake first - see
     * {@link FarmIntegrationTest#watchTerminal}. The failure message carries every raw line the
     * stream held, which is the only thing a CI-only failure here can be read from.
     */
    private static Map<String, Object> awaitLine(Events console, String direction, String needle) {
        await(() -> "a " + direction + " terminal line containing '" + needle + "'. The console held "
                        + console.allOf("TerminalTraffic").stream()
                        .map(line -> line.get("direction") + " " + line.get("raw")).toList(),
                () -> find(console, direction, needle) != null);
        var found = find(console, direction, needle);
        assertNotNull(found);
        return found;
    }

    private static Map<String, Object> find(Events console, String direction, String needle) {
        for (var line : console.allOf("TerminalTraffic")) {
            if (direction.equals(line.get("direction"))
                    && String.valueOf(line.get("raw")).contains(needle)) {
                return line;
            }
        }
        return null;
    }

    private static String raw(Map<String, Object> line) {
        return (String) line.get("raw");
    }
}

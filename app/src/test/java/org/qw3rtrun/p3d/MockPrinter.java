package org.qw3rtrun.p3d;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A printer, mocked at the far end of a real socket.
 *
 * <p><strong>Why a socket and not a stubbed bean.</strong> Issue #43 asks for tests that call
 * endpoints and that mock the simulator. The obvious place to mock a printer would be a Spring bean,
 * and there is not one: {@code PrinterReactorManager.startReactor} says
 * {@code new TerminalManager(entry.connection())} inline, so nothing between the aggregate and the
 * wire is overridable. What <em>is</em> overridable is the address - a machine is registered through
 * {@code POST /api-manager} with a host and a port, and this binds one.
 *
 * <p>That turns out to be the better seam anyway. Mocking {@code PrinterControl} would skip
 * {@code :backend:core}; mocking {@code TerminalManager} would skip {@code :backend:terminal} and
 * {@code :gcode}. Mocking the machine itself skips nothing: the send window numbers and checksums
 * real blocks, {@code MarlinRsDecoder} reads real reply lines, and the aggregate routes them. It is
 * also the only arrangement that needs <strong>no production change at all</strong>.
 *
 * <p><strong>What it says is measured, and where it is not it says so.</strong> Every reply string
 * below is a line from {@code gcode/src/test/resources/marlin-replies.census.txt}, which was
 * captured from a running MarlinSim and carries the decoded type beside each line. Where a line is
 * <em>not</em> in the census it is marked UNMEASURED here and in
 * {@code doc/specs/INTEGRATION_TESTS_spec.md}, and no test is allowed to assert on its exact text -
 * see {@link Profile#sdCard} for the one that matters.
 *
 * <p>That claim used to be made of the whole file and it was not true of {@link #ok()}, which is
 * what issue #61 is about. <strong>A census line is a line in the exchange it was captured
 * from</strong>, and carrying one into a different exchange can be wrong even though the string is
 * real. Most of the census's {@code ok P15 B3} lines answer blocks {@code ReplyCorpusProbe} sent
 * <em>without</em> a number, and this farm numbers every block it sends - so the mock was speaking
 * a shape that never occurs in production. Being measured is therefore a property of the rule a
 * string is chosen by, and not of the string alone.
 *
 * <p><strong>It is an observable, not a probe.</strong> {@link #commands()} is what reached the
 * machine, which is the far boundary of the system under test, exactly as the HTTP response is the
 * near one. This repository has shipped three features that were green end to end and put nothing on
 * the wire; asserting the bytes is the only thing that has ever caught that.
 *
 * <h2>Threading</h2>
 * One daemon acceptor thread serves one session at a time, reading lines and writing replies on that
 * thread. {@link #commands()} and {@link #blocks()} hand back copies under the monitor, and
 * {@link #push(String)} writes under the same writer lock the responder uses, so an unsolicited
 * report can never interleave halfway through a reply.
 *
 * <h2>Deciding when it answers</h2>
 * {@link #holdRepliesFrom(String)} stops the conversation at a named command and lets a test move it
 * on one acknowledgement at a time. See that method: it is what makes a feeding print observable,
 * because a print that is not stopped is over before anything can be asked of it.
 *
 * <h2>It is also a file sink, between {@code M28} and {@code M29}</h2>
 * A real Marlin stops <em>executing</em> what it is sent while a file is open for write and stores
 * it instead, acknowledging each line as it goes. {@link #written(String)} is what came out of that,
 * and it is a second observable at the same boundary: an upload is proved by the file on the card
 * and not only by the traffic that produced it. See {@link #respond(String)}.
 */
public final class MockPrinter implements AutoCloseable {

    /**
     * What kind of machine this is - the capability axis issue #43 item 6 asks tests to vary over.
     *
     * @param label        what to call it in a test name and in a failure message
     * @param firmware     the {@code M115} line, or null for a machine that answers {@code M115}
     *                     with a bare {@code ok} - a firmware too old to report anything
     * @param capabilities the {@code Cap:} lines that follow it, in order
     * @param sdCard       whether a card is in the reader. <strong>UNMEASURED when false:</strong>
     *                     the simulator always has a card, so the no-card replies could not be
     *                     captured. The text below is Marlin's documented wording; a test must
     *                     assert on the <em>absence</em> of {@code SdCardOk}/{@code SdFileList},
     *                     which is what the Storage tab actually keys on, and never on the echo
     *                     string itself
     * @param advancedOk   whether this firmware was built with {@code ADVANCED_OK}. True is
     *                     MarlinSim: {@code ok N2 P15 B3} for a numbered block and {@code ok P15 B3}
     *                     for an unnumbered one, which is {@link #ok()}'s whole subject. False is a
     *                     bare {@code ok} to everything - no number and no buffer counts, which
     *                     holds the send window at one block
     * @param answers      false for a machine that accepts the socket and then says nothing. That
     *                     is a terminal server with nothing plugged into it, and it is the case
     *                     that distinguishes {@code ONLINE} from {@code IDLE}
     */
    public record Profile(String label,
                          String firmware,
                          Map<String, Boolean> capabilities,
                          boolean sdCard,
                          boolean advancedOk,
                          boolean answers) {

        public Profile {
            capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        }

        /** The same machine with a different {@code EXTRUDER_COUNT}, which is what gates tool 1. */
        public Profile withExtruders(String label, int count) {
            return new Profile(label,
                    firmware.replaceAll("EXTRUDER_COUNT:\\d+", "EXTRUDER_COUNT:" + count),
                    capabilities, sdCard, advancedOk, answers);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The {@code M115} line MarlinSim answers with, verbatim from the census.
     *
     * <p>Kept whole rather than assembled from parts: {@code FirmwareInfoRs} reads it as key-values
     * and the roster stores what it read, so a hand-built line that happened to parse differently
     * would make every profile assertion a test of the test.
     */
    private static final String MARLIN_SIM_M115 =
            "FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56) "
                    + "SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 "
                    + "MACHINE_TYPE:MarlinSim KINEMATICS:Cartesian EXTRUDER_COUNT:1 "
                    + "UUID:00000000-0000-0000-0000-000000000000";

    /** The subset of the census's forty-odd {@code Cap:} lines that anything in this farm reads. */
    private static Map<String, Boolean> simCapabilities(boolean sdCard) {
        var caps = new LinkedHashMap<String, Boolean>();
        caps.put("AUTOREPORT_TEMP", true);
        caps.put("EEPROM", true);
        caps.put("SDCARD", sdCard);
        caps.put("SD_WRITE", sdCard);
        caps.put("EMERGENCY_PARSER", true);
        return caps;
    }

    // ------------------------------------------------------------------ the configuration matrix

    /** A MarlinSim with a card in it: the baseline every feature is proved against first. */
    public static Profile marlin() {
        return new Profile("marlin-sd", MARLIN_SIM_M115, simCapabilities(true), true, true, true);
    }

    /** The same firmware with an empty reader - the Storage tab's other state. */
    public static Profile withoutCard() {
        return new Profile("marlin-no-sd", MARLIN_SIM_M115, simCapabilities(false),
                false, true, true);
    }

    /** Two tools, so that a command for tool 1 is a command the aggregate may let through. */
    public static Profile dualExtruder() {
        return marlin().withExtruders("marlin-dual", 2);
    }

    /**
     * A firmware that acknowledges with a bare {@code ok} - no buffer counts and no line number.
     *
     * <p>The host's window then stays at the one block {@code AdvancedOkRs(1, 1, null)} starts it
     * at, so this is the configuration that proves the farm does not <em>require</em> Marlin's
     * advanced acknowledgement to make progress.
     *
     * <p><strong>It is also the un-numbered dialect, and issue #61 asked for that to be kept rather
     * than deleted.</strong> Two readings of "acknowledges without a number" were available and
     * this is the first of them:
     *
     * <ul>
     *   <li><strong>a firmware that does not report numbers</strong> - {@code ADVANCED_OK} off, so
     *       {@code ok} carries nothing, ever. That is this profile, it is a build option a real
     *       Marlin ships with, and it is a property of the machine, which is what a {@link Profile}
     *       is allowed to be;</li>
     *   <li><strong>a session that is not numbering</strong> - rejected, on two grounds. It is not
     *       a property of a machine, so a {@link Profile} is the wrong place for it; and no
     *       endpoint can put the farm in it anyway, because {@code GSendWindow} numbers every
     *       block. It is also not a second dialect: put to the simulator, an unnumbered block in
     *       the middle of a numbered session is answered {@code ok P15 B3} and the numbering
     *       carries straight on, which is the arm {@link #ok()} already has for it. §4.2.1 of the
     *       integration-test spec has the transcript.</li>
     * </ul>
     *
     * <p>So the cost of this dialect is real and is asserted rather than described -
     * {@code ConnectivityIntegrationTest.aStrayAcknowledgementResynchronisesAnUnnumberedSession}
     * drives the same stray {@code ok} against this profile and against {@link #marlin()}, and the
     * two outcomes differ.
     */
    public static Profile legacyOk() {
        return new Profile("legacy-bare-ok", MARLIN_SIM_M115, simCapabilities(true),
                true, false, true);
    }

    /**
     * Accepts the connection and then says nothing at all.
     *
     * <p>A terminal server with no printer behind it. The farm must reach {@code ONLINE} and stop
     * there, because {@code IDLE} means a block this session sent came back acknowledged.
     */
    public static Profile mute() {
        return new Profile("mute", MARLIN_SIM_M115, simCapabilities(true), true, true, false);
    }

    // ------------------------------------------------------------------ state

    private final Profile profile;
    private final ServerSocket server;
    private final Thread acceptor;

    /** Framed blocks exactly as they arrived, e.g. {@code N1 M110 N0*124}. */
    private final List<String> blocks = Collections.synchronizedList(new ArrayList<>());

    /** The same blocks with the number and checksum stripped, e.g. {@code M110 N0}. */
    private final List<String> commands = Collections.synchronizedList(new ArrayList<>());

    /** The card's contents: the card's own spelling to its size in bytes. */
    private final Map<String, Integer> card = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * What was stored in each file written with {@code M28}, one entry per line, under the monitor
     * of {@link #card}.
     *
     * <p>Kept apart from {@link #commands()} on purpose: the whole claim of an upload is that these
     * two lists are <em>different</em>. A command that reached the machine during a transfer went
     * into the file rather than being run, and nothing on either end says which happened - so a
     * test that only had the traffic could not tell the two apart, which is exactly the fault
     * exclusivity exists to prevent.
     */
    private final Map<String, List<String>> stored =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /** The file {@code M28} opened and {@code M29} has not yet closed, or null. */
    private volatile String openFile;

    /** The lines that have gone into {@link #openFile} so far. */
    private List<String> openLines = new ArrayList<>();

    private final AtomicInteger sessions = new AtomicInteger();

    private final Object writing = new Object();
    private volatile Writer out;
    private volatile Socket live;
    private volatile boolean closed;

    /**
     * The {@code N} of the block being answered right now, or null when it carried none.
     *
     * <p>A field rather than a parameter threaded through {@link #respond(String)}, and the reason
     * is ownership rather than convenience: {@link #serve()} reads one line at a time on one
     * acceptor thread, and {@link #receive(String)} is the only writer. Every read is on that same
     * thread, inside the {@link #respond(String)} call {@link #receive(String)} makes, so it is
     * plain and needs no {@code volatile}.
     *
     * <p>It also keeps {@link #ok()} a no-argument method, which is what lets a new arm in
     * {@link #respond(String)} answer with the right number without knowing this exists.
     *
     * <p>A parked answer carries its number with it, because {@link #respond(String)} renders the
     * whole answer to strings before {@link #answer(String, List)} decides whether to write it -
     * so {@link #answerOne()} releasing a reply much later still releases the number that reply
     * was built with.
     */
    private Integer answering;

    /** The command at which the machine stops answering, or null while it answers everything. */
    private String holdFrom;

    /** Whether that command has arrived, so everything from here on is parked. */
    private boolean holding;

    /**
     * The command at which the line-number check is armed, or null while it is not.
     *
     * <p>{@code volatile}, where {@link #holdFrom} beside it is plain, and the difference is where
     * they are read. The gate is consulted inside {@link #answer(String, List)}, under the
     * {@link #writing} monitor the test thread also sets it under; this one is consulted in
     * {@link #rejectsLineNumber(String)}, which runs before that monitor is taken. Three rare
     * writes from a test thread, read on every block by the acceptor - the cheapest honest answer.
     */
    private volatile String checkFrom;

    /** Whether that command has arrived, so the check applies from here on. */
    private volatile boolean checking;

    /** The line number this firmware last accepted - Marlin's {@code gcode_LastN}. */
    private volatile int lastLine;

    /** One received command's answer, parked whole - the acknowledgement travels with its reply. */
    private final Deque<List<String>> parked = new ArrayDeque<>();

    private MockPrinter(Profile profile) throws IOException {
        this.profile = profile;
        this.server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        this.acceptor = new Thread(this::serve, "mock-printer-" + profile.label());
        this.acceptor.setDaemon(true);
        this.acceptor.start();
    }

    /** Binds an ephemeral loopback port and starts answering. */
    public static MockPrinter listening(Profile profile) {
        try {
            return new MockPrinter(profile);
        } catch (IOException e) {
            throw new UncheckedIOException("the mock printer could not bind a port", e);
        }
    }

    /**
     * A port with nothing behind it, for the machine that cannot be reached.
     *
     * <p>Bound and immediately released, so the number is one the operating system has handed out
     * and is now free - which is as close to "unplugged" as a test can get without picking a
     * constant and hoping.
     */
    public static int deadPort() {
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("could not reserve a dead port", e);
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    public Profile profile() {
        return profile;
    }

    /** How many times something has dialled this machine - a redial is a second session. */
    public int sessions() {
        return sessions.get();
    }

    /**
     * Whether a socket to this machine is open right now.
     *
     * <p>Worth having its own observable because closing it has been got wrong before: cancelling
     * a terminal's two halves does not close the connection, and a disconnect once reported
     * {@code OFFLINE} while leaving the socket {@code Established} on the printer. From here that
     * is visible; from the host it is not.
     */
    public boolean sessionOpen() {
        var socket = live;
        return socket != null && !socket.isClosed();
    }

    /** Puts a file on the card before anything connects, so a listing has something to list. */
    public MockPrinter withFile(String name, int size) {
        card.put(name, size);
        return this;
    }

    // ------------------------------------------------------------------ what the host said

    /** Every framed block received, oldest first. */
    public List<String> blocks() {
        synchronized (blocks) {
            return List.copyOf(blocks);
        }
    }

    /** Every block received as the bare command it carried, oldest first. */
    public List<String> commands() {
        synchronized (commands) {
            return List.copyOf(commands);
        }
    }

    /**
     * Whether a command starting with {@code prefix} has reached the machine.
     *
     * <p>A prefix and not an equality, because a command carries its parameters:
     * {@code sent("M104")} is "the hotend was set", {@code sent("M104 S210")} is "to 210".
     */
    public boolean sent(String prefix) {
        synchronized (commands) {
            return commands.stream().anyMatch(c -> c.startsWith(prefix));
        }
    }

    /** The first command starting with {@code prefix}, or null - for asserting its parameters. */
    public String firstSent(String prefix) {
        synchronized (commands) {
            return commands.stream().filter(c -> c.startsWith(prefix)).findFirst().orElse(null);
        }
    }

    /** Forgets the conversation so far, so a test can assert on what a single request caused. */
    public void forget() {
        blocks.clear();
        commands.clear();
    }

    // ------------------------------------------------------------------ what is on the card

    /**
     * The lines stored in a file written with {@code M28}, or an empty list if there is no such
     * <em>closed</em> file.
     *
     * <p>Only a closed file answers. That is the distinction the cancel case turns on: a transfer
     * that was stopped properly has an {@code M29} behind it and so has a file here, and one that
     * was torn out leaves {@link #writingTo()} naming it forever.
     */
    public List<String> written(String name) {
        synchronized (card) {
            return List.copyOf(stored.getOrDefault(name, List.of()));
        }
    }

    /**
     * The file {@code M28} opened and {@code M29} has not closed, or null when the wire is a
     * command channel again.
     *
     * <p>A test asserts this is null after a cancel. A file left open is the failure
     * {@code SdUploader}'s {@code concat} exists to prevent, and from the host it is invisible -
     * every subsequent command is acknowledged exactly as if it had been run.
     */
    public String writingTo() {
        return openFile;
    }

    // ------------------------------------------------------------------ what the machine says

    /**
     * Writes a line the host did not ask for - an auto-report, a busy notice, a fault.
     *
     * <p>Deterministic by design: nothing here runs on a timer. A real Marlin with {@code M155 S1}
     * writes a temperature every second, and a test that waited for one would be asserting on a
     * clock. A test that wants a reading asks for one.
     */
    public void push(String line) {
        write(line);
    }

    /** An {@code M155} auto-report, in the census's exact shape - note the leading space. */
    public void reportTemperature(String hotend, String hotendTarget, String bed, String bedTarget) {
        push(" T:" + hotend + " /" + hotendTarget + " B:" + bed + " /" + bedTarget + " @:11 B@:0");
    }

    /** {@code echo:busy: processing} - what Marlin says while it is not taking commands. */
    public void reportBusy() {
        push("echo:busy: processing");
    }

    /** A fault in the firmware's own words. Routine traffic on a Marlin; the printer carries on. */
    public void reportError(String text) {
        push("Error:" + text);
    }

    // ------------------------------------------------------------------ the acknowledgement gate

    /**
     * Stops answering from the first command starting with {@code prefix}, inclusive.
     *
     * <p><strong>Why a test needs this at all.</strong> Everything else here answers instantly, so a
     * print job started against this machine is finished before the test's next line runs - and the
     * two facts #51 exists to prove are both about a print that is <em>still going</em>: that an
     * interactive command overtakes it, and that it then carries on from the block it had reached.
     * Timing the window by hand would be a race on a loaded runner, and a file big enough to be slow
     * would make it a slower race rather than a settled one.
     *
     * <p><strong>What makes it deterministic is the send window, not a clock.</strong> The host may
     * not have more blocks outstanding than the printer's buffer count allows - {@code B3} on this
     * profile, one on {@link #legacyOk()} - so a machine that stops acknowledging stops the
     * conversation within a few blocks and stops it for good. Nothing further can reach the machine
     * until {@link #answerOne()} or {@link #answerFreely()} lets it, which is what lets a test
     * sample what is on the wire and know the number will not move under it.
     *
     * <p>Set before the machine is dialled: the opening handshake is answered normally, because
     * {@code M110 N0}, {@code M115} and {@code M155 S1} do not start with a block out of a file.
     */
    public MockPrinter holdRepliesFrom(String prefix) {
        synchronized (writing) {
            holdFrom = prefix;
            holding = false;
        }
        return this;
    }

    // -------------------------------------------------------------- the line-number continuity check

    /**
     * Arms Marlin's line-number continuity check from the first command starting with
     * {@code prefix}, with this firmware's counter already standing at {@code lastLine}.
     *
     * <p><strong>This is the one machine state the rest of this mock cannot be in, and issue #60
     * is the bug that hid behind it.</strong> A fresh mock starts at line 0 and a fresh host
     * numbers from 1, so the two agree by construction and every block is accepted - which is why
     * nothing in this suite had ever seen a firmware reject one. A real machine outlives host
     * sessions and remembers the number it last accepted, so the disagreement is the ordinary case
     * on a bench and the unreachable one here.
     *
     * <p><strong>Everything it answers is measured</strong>, by {@code ResendDriftProbe} against
     * MarlinSim bugfix-2.1.x on 2026-11-01, with the counter pushed to 14 - see §4.2.2 of
     * {@code doc/specs/INTEGRATION_TESTS_spec.md}:
     *
     * <pre>
     *   &gt;&gt; N1 G21*27      &lt;&lt; Error:Line Number is not Last Line Number+1, Last Line: 14
     *                     &lt;&lt; Resend: 15
     *                     &lt;&lt; ok
     *   &gt;&gt; N7 M110 N0*122 &lt;&lt; ok N7 P15 B3
     *   &gt;&gt; N1 G21*27      &lt;&lt; ok N1 P15 B3
     * </pre>
     *
     * <p>Three facts out of that transcript are reproduced exactly, and each one matters:
     *
     * <ul>
     *   <li><strong>the {@code Resend:} names {@code last_N + 1}</strong>, which is a block the
     *       host has never framed - so {@code GSendWindow.resendFrom} finds nothing and
     *       {@code CommandProcessor.resynchronise} abandons what it is holding. A {@code Resend:}
     *       for a number the host <em>does</em> hold is an entirely different thing and is fully
     *       recoverable; that is the control in the same probe run;</li>
     *   <li><strong>{@code M110} repairs it, and the firmware reads the parameter</strong> and not
     *       the line's own {@code N}: {@code N7 M110 N0} leaves the counter at 0, so the next block
     *       may be {@code N1}. That is why {@code GSendWindow.reset(0)} framing the {@code M110}
     *       as {@code N1} and then numbering the next block {@code N1} as well is harmless on the
     *       wire;</li>
     *   <li><strong>the rejection's {@code ok} is bare</strong> - no {@code N}, no buffer counts.
     *       It is the firmware saying it is ready again and acknowledges no block, which is what
     *       {@code CommandProcessor.rewinding} consumes as credit only.</li>
     * </ul>
     *
     * <p><strong>A rejected block reaches {@link #blocks()} and not {@link #commands()}</strong>,
     * because it arrived on the wire and was thrown away unread. That difference is the whole
     * observable of issue #60: a print that skipped its {@code G28} homed nowhere.
     *
     * <p>Not a {@link Profile}, for the reason the unnumbered dialect <em>is</em> one: where a
     * firmware's counter stands is a property of a session rather than of a machine.
     */
    public MockPrinter checkLineNumbersFrom(String prefix, int lastLine) {
        synchronized (writing) {
            this.checkFrom = prefix;
            this.checking = false;
            this.lastLine = lastLine;
        }
        return this;
    }

    /** How many received commands are waiting for an answer. */
    public int parkedReplies() {
        synchronized (writing) {
            return parked.size();
        }
    }

    /** Answers the oldest command the gate is holding, which frees exactly one slot in the window. */
    public void answerOne() {
        synchronized (writing) {
            var lines = parked.pollFirst();
            if (lines != null) {
                lines.forEach(this::write);
            }
        }
    }

    /** Lifts the gate: everything parked is answered, oldest first, and the machine speaks again. */
    public void answerFreely() {
        synchronized (writing) {
            holdFrom = null;
            holding = false;
            for (List<String> lines; (lines = parked.pollFirst()) != null; ) {
                lines.forEach(this::write);
            }
        }
    }

    // ------------------------------------------------------------------ the session

    private void serve() {
        while (!closed) {
            try (var socket = server.accept()) {
                sessions.incrementAndGet();
                live = socket;
                var reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
                synchronized (writing) {
                    out = writer;
                }
                for (String line; (line = reader.readLine()) != null; ) {
                    receive(line);
                }
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            } finally {
                synchronized (writing) {
                    out = null;
                }
                live = null;
            }
        }
    }

    private void receive(String framed) {
        var block = framed.trim();
        if (block.isEmpty()) {
            return;
        }
        blocks.add(block);
        var command = bare(block);
        answering = number(block);
        if (rejectsLineNumber(command)) {
            // On blocks() because it was on the wire, and deliberately not on commands(), because
            // the firmware threw it away unread. See checkLineNumbersFrom.
            answer(command, rejection());
            return;
        }
        commands.add(command);
        if (!profile.answers()) {
            return;
        }
        answer(command, respond(command));
    }

    /**
     * Marlin's continuity check: whether this block is thrown away unread, advancing the counter
     * when it is not.
     *
     * <p>Disarmed unless {@link #checkLineNumbersFrom(String, int)} armed it, so every other test
     * in this module sees exactly what it saw before. A machine that does not answer at all cannot
     * answer a rejection either, so {@link Profile#answers()} disarms it too.
     *
     * <p>An unnumbered block is ordinary traffic and costs the sequence nothing - §4.2.1 of the
     * integration-test spec measures that against a running simulator. Nothing in this farm sends
     * one.
     */
    private boolean rejectsLineNumber(String command) {
        if (checkFrom == null || !profile.answers()) {
            return false;
        }
        if (!checking && command.startsWith(checkFrom)) {
            checking = true;
        }
        if (!checking || answering == null) {
            return false;
        }
        if ("M110".equals(word(command))) {
            // Measured: the parameter is what the counter takes, not the line's own N.
            var target = numberAfterN(command);
            lastLine = target == null ? 0 : target;
            return false;
        }
        if (answering != lastLine + 1) {
            return true;
        }
        lastLine = answering;
        return false;
    }

    /** The rejection triple, verbatim from the probe run in {@link #checkLineNumbersFrom}. */
    private List<String> rejection() {
        return List.of(
                "Error:Line Number is not Last Line Number+1, Last Line: " + lastLine,
                "Resend: " + (lastLine + 1),
                "ok");
    }

    /** The value of the {@code N} parameter in {@code M110 N0}, or null when it carries none. */
    private static Integer numberAfterN(String command) {
        var at = command.indexOf('N', word(command).length());
        if (at < 0) {
            return null;
        }
        var i = at + 1;
        while (i < command.length() && Character.isDigit(command.charAt(i))) {
            i++;
        }
        return i == at + 1 ? null : Integer.valueOf(command.substring(at + 1, i));
    }

    /**
     * Writes one command's answer, or parks it whole when the gate is shut.
     *
     * <p>Parked whole rather than line by line, because a command's answer and its acknowledgement
     * are one thing on the wire: {@code M115} is a firmware line, five capabilities and then an
     * {@code ok}, and releasing half of that would be a machine no firmware resembles.
     */
    private void answer(String command, List<String> lines) {
        synchronized (writing) {
            if (holdFrom != null && !holding && command.startsWith(holdFrom)) {
                holding = true;
            }
            if (holding) {
                parked.addLast(lines);
                return;
            }
            lines.forEach(this::write);
        }
    }

    /**
     * One command as the lines a Marlin answers it with, the acknowledgement last.
     *
     * <p>{@code M105} is the exception and has no separate {@code ok}: its answer <em>is</em> the
     * acknowledgement - {@code ok T:210.00 /210.00 B:25.00 /0.00 @:11 B@:0} - which is the whole
     * reason {@code OkTemperatureRs} exists as a type apart from {@code BareTemperatureRs}.
     */
    private List<String> respond(String command) {
        var lines = new ArrayList<String>();
        var word = word(command);

        // Between M28 and M29 the wire is a file sink and not a command channel: the line is
        // stored rather than run, and it is still acknowledged. That acknowledgement is what makes
        // the fault invisible from the host - the transfer and the stray command are answered
        // identically, and the difference only shows up much later as a line of nonsense mid-print.
        // Measured, with the firmware numbering requirement alongside it (SdWriteProbe, below).
        if (openFile != null && !"M29".equals(word)) {
            synchronized (card) {
                openLines.add(command);
            }
            lines.add(ok());
            return lines;
        }

        switch (word) {
            // Open a file for write. Three lines then the acknowledgement, verbatim from a
            // SdWriteProbe run against MarlinSim bugfix-2.1.x on 2026-11-01:
            //
            //   >> N1 M28 test.gco*107  << echo:Now fresh file: test.gco
            //                           << Writing to file: test.gco
            //                           << //action:notification test.gco
            //                           << ok N1 P15 B3
            //
            // All three are reproduced rather than trimmed to the one the feature reads. Unlike
            // the busy notices around an M84 - timing chatter, and left out - these are a fixed
            // part of M28's answer, and all three decode: SdFreshFile, SdWritingToFile, ActionRs.
            case "M28" -> {
                var name = argument(command);
                synchronized (card) {
                    openFile = name;
                    openLines = new ArrayList<>();
                }
                lines.add("echo:Now fresh file: " + name);
                lines.add("Writing to file: " + name);
                lines.add("//action:notification " + name);
                lines.add(ok());
            }
            // Close it. Measured in the same run:
            //
            //   >> N6 M29*30            << Done saving file.
            //                           << ok N6 P15 B3
            //
            // The size the card then reports is the stored payloads plus a CRLF each and nothing
            // else - that run wrote G28, M117 Created, M118 Done and M118 P0 Done-P0, and M20
            // answered `TEST.GCO 47`, which is 5 + 14 + 11 + 17. So the N word and the checksum
            // are stripped before the line is stored, and that is why `written` can be compared
            // against the blocks of a fixture directly.
            //
            // An M29 with no file open is UNMEASURED - the probe never sent one - so it is
            // acknowledged and nothing more, and no test drives it.
            case "M29" -> {
                synchronized (card) {
                    if (openFile != null) {
                        card.put(openFile, sizeOnCard(openLines));
                        stored.put(openFile, List.copyOf(openLines));
                        openFile = null;
                        openLines = new ArrayList<>();
                        lines.add("Done saving file.");
                    }
                }
                lines.add(ok());
            }
            case "M115" -> {
                if (profile.firmware() != null) {
                    lines.add(profile.firmware());
                    profile.capabilities().forEach((cap, on) ->
                            lines.add("Cap:" + cap + ":" + (on ? "1" : "0")));
                }
                lines.add(ok());
            }
            // The answer is the acknowledgement. No second line.
            case "M105" -> lines.add("ok T:210.00 /210.00 B:25.00 /0.00 @:11 B@:0");
            case "M114" -> {
                lines.add("X:10.00 Y:10.00 Z:5.00 E:10.00 Count X:800 Y:800 Z:2000");
                lines.add(ok());
            }
            case "M21" -> {
                lines.add(profile.sdCard() ? "echo:SD card ok" : "echo:No SD card");
                lines.add(ok());
            }
            case "M20" -> {
                if (profile.sdCard()) {
                    lines.add("Begin file list");
                    synchronized (card) {
                        card.forEach((name, size) -> lines.add(name + " " + size));
                    }
                    lines.add("End file list");
                } else {
                    lines.add("echo:No SD card");
                }
                lines.add(ok());
            }
            case "M23" -> {
                var name = argument(command);
                var size = card.get(name);
                if (profile.sdCard() && size != null) {
                    lines.add("File opened: " + name + " Size: " + size);
                    lines.add("File selected");
                } else {
                    lines.add("open failed, File: " + name + ".");
                }
                lines.add(ok());
            }
            case "M30" -> {
                var name = argument(command);
                var had = card.remove(name) != null;
                lines.add(had ? "File deleted:" + name : "Deletion failed, File: " + name + ".");
                lines.add(ok());
            }
            // Acknowledged and nothing else. G21, M83 and M84 joined this list with #51, because
            // they are what a print job and the cool-down after a cancelled one actually send, and
            // they were falling to the default arm below - so every print the suite drove would have
            // put three "echo:Unknown command" lines on the event stream that a real machine does
            // not send. Measured against MarlinSim rather than assumed:
            //
            //   >> N2 G21*24          << ok N2 P15 B3
            //   >> N9 M84*22          << echo:busy: processing (x2), ok N9 P15 B3
            //   >> N13 M83*42         << ok N13 P15 B3
            //   >> N14 G1 E-2 F2400*7 << echo: cold extrusion prevented, ok N14 P15 B3
            //
            // The busy notices and the cold-extrusion echo are the simulator's own chatter around an
            // acknowledgement rather than instead of one, and are not reproduced here - see the
            // class note on this being a mock and not a simulator.
            case "M110", "M22", "M24", "M83", "M84", "M104", "M140", "M155", "M106", "M107",
                 "M500", "G21", "G28", "G1", "G0", "G90", "G91" -> lines.add(ok());
            default -> {
                lines.add("echo:Unknown command: \"" + command + "\"");
                lines.add(ok());
            }
        }
        return lines;
    }

    /**
     * The acknowledgement, in whichever shape this machine speaks - issue #61.
     *
     * <p><strong>Marlin's rule, not an assumption.</strong> {@code ok_to_send} writes the advanced
     * fields when the firmware was built with {@code ADVANCED_OK}, and inside that it copies the
     * {@code N} field <em>only if the command it is answering carried one</em>. So one firmware
     * speaks two shapes and the block decides which:
     *
     * <pre>
     * N2 M114*21  -&gt; ok N2 P15 B3     a numbered block, answered with its number
     * M114        -&gt; ok P15 B3        the same firmware, an unnumbered block
     * </pre>
     *
     * <p><strong>Both halves are in the census, from the same probe run.</strong>
     * {@code ReplyCorpusProbe} sends most of its steps unnumbered and five of them framed, and
     * {@code marlin-replies.census.txt} records the answers side by side - twenty-odd
     * {@code AdvancedOkRs ok P15 B3}, and {@code ok N2 P15 B3} / {@code ok N3 P15 B3} against the
     * probe's "framed position" and "the resend" steps. §4.2.1 of
     * {@code doc/specs/INTEGRATION_TESTS_spec.md} has that table and two further transcripts that
     * measure the same rule against a running MarlinSim. Nothing here is hand-written.
     *
     * <p><strong>What that fixes.</strong> This used to answer {@code ok P15 B3} always, which is
     * a reply shape a real session never sees - the farm numbers every block - and it meant
     * {@code CommandProcessor.applyWindowEvents} took the FIFO {@code window.acknowledge()} branch
     * in every test in this module, so {@code GSendWindow.acknowledge(number)} was exercised by
     * nothing. See {@code ConnectivityIntegrationTest.aStrayAcknowledgementDoesNotShiftTheSendWindow}
     * for what that branch is worth.
     *
     * <p>The unnumbered arm stays reachable in principle and is not reached in practice, because
     * nothing in this farm sends an unnumbered block. It is kept because it is the firmware's rule
     * and deleting half of a rule is how the first version of this method went wrong.
     *
     * <p>{@code M105} is the one acknowledgement that does <strong>not</strong> come through here,
     * and that is measured too: Marlin answers {@code M105} from the gcode handler and returns
     * before {@code ok_to_send}, so a framed {@code N1 M105*…} is answered
     * {@code ok T:210.00 /210.00 …} with neither a number nor buffer counts - which is the census
     * line the {@code M105} arm above replies with, verbatim.
     *
     * @see #answering
     */
    private String ok() {
        if (!profile.advancedOk()) {
            return "ok";
        }
        return answering == null ? "ok P15 B3" : "ok N" + answering + " P15 B3";
    }

    private void write(String line) {
        synchronized (writing) {
            var writer = out;
            if (writer == null) {
                return;
            }
            try {
                writer.write(line);
                writer.write("\n");
                writer.flush();
            } catch (IOException e) {
                // The host hung up mid-reply. That is a disconnect, not a test failure.
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        var socket = live;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        acceptor.interrupt();
    }

    // ------------------------------------------------------------------ framing, in reverse

    /**
     * A framed block as the command it carries: {@code N1 M110 N0*124} to {@code M110 N0}.
     *
     * <p>The inverse of {@code GEncoder.frame}, and deliberately a dumb one - it does not verify
     * the checksum. Verifying it here would make every test that reads {@link #commands()} also a
     * test of {@code :gcode}'s checksum, which has its own tests and a spec section; a failure
     * would then surface as "the temperature was not set".
     */
    static String bare(String block) {
        var text = block;
        var star = text.lastIndexOf('*');
        if (star >= 0) {
            text = text.substring(0, star);
        }
        text = text.trim();
        if (text.length() > 1 && text.charAt(0) == 'N') {
            var i = 1;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                i++;
            }
            if (i > 1 && i < text.length() && text.charAt(i) == ' ') {
                text = text.substring(i + 1).trim();
            }
        }
        return text;
    }

    /**
     * What {@code M20} will report for a file written with {@code M28}: the payloads plus a
     * {@code CRLF} each.
     *
     * <p>Measured rather than assumed - see the {@code M29} arm. It matters because a listing is
     * how a test can say the file is really there at really that length, which is the only thing
     * that distinguishes a finished transfer from a cancelled one from outside.
     */
    private static int sizeOnCard(List<String> lines) {
        var size = 0;
        for (var line : lines) {
            size += line.length() + 2;
        }
        return size;
    }

    /**
     * The line number a framed block carries: {@code N1 M110 N0*124} to {@code 1}, or null.
     *
     * <p>The other half of {@link #bare(String)} and written to agree with it exactly - the same
     * walk over the same leading digits, so a block one of them accepts as numbered is one the
     * other does too. They answer the two halves of one question, which is why this is a sibling
     * and not a second parser: {@code bare} is what the host asked for and this is what it called
     * it, and a block the firmware acknowledges by number has to have both read the same way.
     *
     * <p>Deliberately not a checksum check, for {@link #bare(String)}'s reason: verifying it here
     * would make every acknowledgement in this module a test of {@code :gcode}'s checksum, and a
     * failure would surface as a stalled send window rather than as a bad checksum.
     *
     * <p>Null for a block with no {@code N}, which is what {@link #ok()} answers without a number.
     * Nothing in this farm sends one - see {@link #ok()}.
     */
    static Integer number(String block) {
        var text = block.trim();
        if (text.length() < 2 || text.charAt(0) != 'N') {
            return null;
        }
        var i = 1;
        while (i < text.length() && Character.isDigit(text.charAt(i))) {
            i++;
        }
        if (i == 1 || i >= text.length() || text.charAt(i) != ' ') {
            return null;
        }
        return Integer.valueOf(text.substring(1, i));
    }

    /** The command word - {@code M104} out of {@code M104 S210 T0}. */
    private static String word(String command) {
        var space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    /** Everything after the command word, which for the card commands is a filename. */
    private static String argument(String command) {
        var space = command.indexOf(' ');
        return space < 0 ? "" : command.substring(space + 1).trim();
    }
}

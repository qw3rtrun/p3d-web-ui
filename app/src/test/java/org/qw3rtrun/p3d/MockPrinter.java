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
 * <p><strong>What it says is measured, not invented.</strong> Every reply string below is a line
 * from {@code gcode/src/test/resources/marlin-replies.census.txt}, which was captured from a running
 * MarlinSim and carries the decoded type beside each line. Where a line is <em>not</em> in the
 * census it is marked UNMEASURED here and in {@code doc/specs/INTEGRATION_TESTS_spec.md}, and no
 * test is allowed to assert on its exact text - see {@link Profile#sdCard} for the one that matters.
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
     * @param advancedOk   {@code ok P15 B3} when true (what MarlinSim sends), a bare {@code ok}
     *                     when false - a firmware that reports no buffer counts, which holds the
     *                     send window at one block
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
     * A firmware that acknowledges with a bare {@code ok} and reports no buffer counts.
     *
     * <p>The host's window then stays at the one block {@code AdvancedOkRs(1, 1, null)} starts it
     * at, so this is the configuration that proves the farm does not <em>require</em> Marlin's
     * advanced acknowledgement to make progress.
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

    private final AtomicInteger sessions = new AtomicInteger();

    private final Object writing = new Object();
    private volatile Writer out;
    private volatile Socket live;
    private volatile boolean closed;

    /** The command at which the machine stops answering, or null while it answers everything. */
    private String holdFrom;

    /** Whether that command has arrived, so everything from here on is parked. */
    private boolean holding;

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
        commands.add(command);
        if (!profile.answers()) {
            return;
        }
        answer(command, respond(command));
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
        switch (word) {
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
     * The acknowledgement, in whichever of the two shapes this machine speaks.
     *
     * <p>No {@code N} on the advanced form, because the census shows MarlinSim does not write one
     * on an ordinary {@code ok} - {@code ok P15 B3}. The host's send window then falls back to its
     * FIFO order to decide which block was released, which is the common case on real firmware and
     * the one worth exercising.
     */
    private String ok() {
        return profile.advancedOk() ? "ok P15 B3" : "ok";
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

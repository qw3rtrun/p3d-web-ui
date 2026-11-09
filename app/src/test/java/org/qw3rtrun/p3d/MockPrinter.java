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
 * A printer, mocked at the far end of a real socket — the harness issue #43 asks for.
 *
 * <p>The address is the only seam: {@code PrinterReactorManager.startReactor} says
 * {@code new TerminalManager(entry.connection())} inline, so nothing between the aggregate and the
 * wire is overridable, but a machine is registered through {@code POST /api-manager} with a host and
 * a port. It is also the best seam — mocking the machine skips no layer (the send window numbers and
 * checksums real blocks, {@code MarlinRsDecoder} reads real reply lines) and needs no production
 * change. {@link #commands()} and {@link #written(String)} are the far boundary of the system under
 * test, as the HTTP response is the near one; three features here shipped green end to end with
 * nothing on the wire, and asserting the bytes is the only thing that has caught that.
 *
 * <p><strong>Every reply string below is measured, or says it is not.</strong> They come from
 * {@code gcode/src/test/resources/marlin-replies.census.txt}, captured from a running MarlinSim.
 * Lines absent from the census are marked UNMEASURED here and in
 * {@code doc/specs/INTEGRATION_TESTS_spec.md}, and no test may assert their exact text — see
 * {@link Profile#sdCard}.
 *
 * <p><strong>A census line is a line in the exchange it was captured from</strong> (issue #61).
 * Carrying one into a different exchange can be wrong even though the string is real: most census
 * {@code ok P15 B3} lines answer blocks {@code ReplyCorpusProbe} sent <em>without</em> a number, and
 * this farm numbers every block, so the mock spoke a shape production never produces. See
 * {@link #ok()}. Measured is a property of the rule a string is chosen by, not of the string.
 *
 * <p><strong>And a reading is not a measurement.</strong> What the census proves about a line is
 * its fields and their order; the numbers in them are what the machine was doing that afternoon.
 * The {@code M105} and {@code M114} answers below are the 2026-10-29 capture's, from a machine
 * whose hotend had been left at 210 degrees; #66 recaptured against one sitting at 25 and the same
 * two replies now read {@code ok T:25.00 /0.00 B:25.00 /0.00 @:0 B@:0} and {@code … E:20.00 …}.
 * Both are kept as they were. A reply <em>shape</em> that is not in the census still has to be
 * measured; a captured shape whose readings moved does not.
 *
 * <h2>Threading</h2>
 * One daemon acceptor thread serves one session at a time, reading lines and writing replies on that
 * thread. {@link #commands()} and {@link #blocks()} copy under the monitor; {@link #push(String)}
 * writes under the responder's writer lock, so an unsolicited report cannot interleave halfway
 * through a reply.
 *
 * <h2>Holding the conversation, and the file sink</h2>
 * {@link #holdRepliesFrom(String)} stops at a named command and lets a test advance one
 * acknowledgement at a time — a print that is not stopped is over before anything can be asked of
 * it. Between {@code M28} and {@code M29} a real Marlin stores what it is sent instead of executing
 * it, acknowledging each line; {@link #written(String)} is that file. See {@link #respond(String)}.
 */
public final class MockPrinter implements AutoCloseable {

    /**
     * What kind of machine this is — the capability axis issue #43 item 6 asks tests to vary over.
     *
     * @param firmware     the {@code M115} line, or null for a firmware too old to report anything,
     *                     which answers {@code M115} with a bare {@code ok}
     * @param capabilities the {@code Cap:} lines that follow it, in order
     * @param sdCard       a card in the reader. <strong>UNMEASURED when false</strong> — the
     *                     simulator always has one, so the text below is Marlin's documented
     *                     wording. Assert on the <em>absence</em> of {@code SdCardOk}/
     *                     {@code SdFileList}, which is what the Storage tab keys on, never on the
     *                     echo string
     * @param advancedOk   built with {@code ADVANCED_OK}. True is MarlinSim: {@code ok N2 P15 B3}
     *                     numbered, {@code ok P15 B3} unnumbered — see {@link #ok()}. False is a
     *                     bare {@code ok} to everything, which holds the send window at one block
     * @param answers      false for a socket that accepts and then says nothing — a terminal server
     *                     with nothing plugged in, the case that separates {@code ONLINE} from
     *                     {@code IDLE}
     * @param reportsSettings whether {@code M503} exists at all; Marlin's {@code DISABLE_M503}
     *                     compiles it out. <strong>UNMEASURED when false</strong> — the simulator
     *                     cannot be built that way, so {@code echo:Unknown command: "M503"} is
     *                     assumed from how it answers codes it does not have, measured for
     *                     {@code M851}, {@code M304} and {@code M998}. Not a capability: Marlin's
     *                     {@code M115} reports nothing about {@code M503}, so the farm must learn
     *                     it from the reply
     */
    public record Profile(String label,
                          String firmware,
                          Map<String, Boolean> capabilities,
                          boolean sdCard,
                          boolean advancedOk,
                          boolean answers,
                          boolean reportsSettings) {

        public Profile {
            capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        }

        /** The same machine with a different {@code EXTRUDER_COUNT}, which is what gates tool 1. */
        public Profile withExtruders(String label, int count) {
            return new Profile(label,
                    firmware.replaceAll("EXTRUDER_COUNT:\\d+", "EXTRUDER_COUNT:" + count),
                    capabilities, sdCard, advancedOk, answers, reportsSettings);
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
        return new Profile("marlin-sd", MARLIN_SIM_M115, simCapabilities(true),
                true, true, true, true);
    }

    /** The same firmware with an empty reader - the Storage tab's other state. */
    public static Profile withoutCard() {
        return new Profile("marlin-no-sd", MARLIN_SIM_M115, simCapabilities(false),
                false, true, true, true);
    }

    /** Two tools, so that a command for tool 1 is a command the aggregate may let through. */
    public static Profile dualExtruder() {
        return marlin().withExtruders("marlin-dual", 2);
    }

    /**
     * A firmware built with {@code ADVANCED_OK} off: a bare {@code ok}, no buffer counts, no line
     * number, ever. The window stays at the one block {@code AdvancedOkRs(1, 1, null)} starts it at,
     * which proves the farm does not <em>require</em> the advanced acknowledgement to progress.
     *
     * <p>This is the un-numbered dialect issue #61 asked to keep, in its only valid reading — a
     * property of the machine, which is what a {@link Profile} may be. "A session that is not
     * numbering" was rejected: no endpoint can reach it ({@code GSendWindow} numbers every block),
     * and it is not a second dialect anyway — an unnumbered block mid-session is answered
     * {@code ok P15 B3} and the numbering carries on, the arm {@link #ok()} already has. Transcript
     * in §4.2.1 of the integration-test spec. The cost is asserted, not described:
     * {@code ConnectivityIntegrationTest.aStrayAcknowledgementResynchronisesAnUnnumberedSession}
     * drives the same stray {@code ok} against this profile and {@link #marlin()}, and they differ.
     */
    public static Profile legacyOk() {
        return new Profile("legacy-bare-ok", MARLIN_SIM_M115, simCapabilities(true),
                true, false, true, true);
    }

    /**
     * Accepts the connection and then says nothing at all.
     *
     * <p>A terminal server with no printer behind it. The farm must reach {@code ONLINE} and stop
     * there, because {@code IDLE} means a block this session sent came back acknowledged.
     */
    public static Profile mute() {
        return new Profile("mute", MARLIN_SIM_M115, simCapabilities(true),
                true, true, false, true);
    }

    /**
     * A firmware built without {@code EEPROM_SETTINGS} — the Settings tab's "nothing to settle".
     *
     * <p><strong>The capability is spelled {@code EEPROM}, not {@code EEPROM_SETTINGS}</strong>;
     * the latter is the build macro, while the wire carries {@code Cap:EEPROM:0|1}, measured on the
     * simulator and cross-checked against Marlin's {@code M115.cpp}. Issue #88's checklist names the
     * macro, which is why it is worth saying twice.
     *
     * <p><strong>UNMEASURED</strong> — the simulator always has EEPROM. So this changes only the
     * capability line and keeps answering the settings commands: the farm decides from the
     * capability, never from the reply. Assert the gate, not a reply string.
     */
    public static Profile withoutEeprom() {
        var caps = new LinkedHashMap<>(simCapabilities(true));
        caps.put("EEPROM", false);
        return new Profile("marlin-no-eeprom", MARLIN_SIM_M115, caps, true, true, true, true);
    }

    /**
     * A firmware with EEPROM but no {@code M503} - Marlin's {@code DISABLE_M503}.
     *
     * <p>The configuration that proves the farm <em>learns</em> this rather than reading it. There
     * is no {@code Cap:} line for it under any spelling, so a host cannot know until it has asked
     * once and been told the code does not exist. See {@link Profile#reportsSettings}.
     */
    public static Profile withoutSettingsReport() {
        return new Profile("marlin-no-m503", MARLIN_SIM_M115, simCapabilities(true),
                true, true, true, false);
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
     * What was stored in each file written with {@code M28}, one entry per line, under
     * {@link #card}'s monitor. Kept apart from {@link #commands()} because an upload's whole claim
     * is that the two lists <em>differ</em>: a command that arrived during a transfer went into the
     * file rather than being run, nothing on the wire says which happened, and a test holding only
     * the traffic could not tell — which is the fault exclusivity exists to prevent.
     */
    private final Map<String, List<String>> stored =
            Collections.synchronizedMap(new LinkedHashMap<>());

    // ------------------------------------------------------- the settings, in two memories

    /**
     * The settings report, section title to the command lines under it, captured verbatim from
     * {@code FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56) MACHINE_TYPE:MarlinSim} on
     * 2026-11-04:
     *
     * <pre>
     * &gt;&gt; M503   &lt;&lt; echo:; Steps per unit:
     *           &lt;&lt; echo:  M92 X80.00 Y80.00 Z400.00 E500.00
     *           ... 24 lines ...
     *           &lt;&lt; ok P15 B3
     * </pre>
     *
     * <p>The section titles are data, not decoration — the Settings tab groups by them, so invented
     * ones would test the test. The awkward brackets and angle brackets are kept because they are
     * what the decoder has to survive.
     */
    private static final List<Map.Entry<String, List<String>>> SETTINGS_REPORT = List.of(
            Map.entry("Linear Units", List.of("G21 ; (mm)")),
            Map.entry("Auto Temp Control", List.of("M104 S0 B250 F0.00")),
            Map.entry("Temperature Units", List.of("M149 C ; Units in Celsius")),
            Map.entry("Filament settings (Disabled)", List.of("M200 S0 D1.75")),
            Map.entry("Steps per unit", List.of("M92 X80.00 Y80.00 Z400.00 E500.00")),
            Map.entry("Max feedrates (units/s)", List.of("M203 X300.00 Y300.00 Z5.00 E25.00")),
            Map.entry("Max Acceleration (units/s2)",
                    List.of("M201 X3000.00 Y3000.00 Z100.00 E10000.00")),
            Map.entry("Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>)",
                    List.of("M204 P3000.00 R3000.00 T3000.00")),
            Map.entry("Advanced (B<min_segment_time_us> S<min_feedrate> T<min_travel_feedrate> "
                            + "J<junc_dev>)",
                    List.of("M205 B20000.00 S0.00 T0.00 J0.01")),
            Map.entry("Home offset", List.of("M206 X0.00 Y0.00 Z0.00")),
            Map.entry("Material heatup parameters",
                    List.of("M145 S0 H180.00 B70.00 F0", "M145 S1 H240.00 B110.00 F0")),
            Map.entry("Hotend PID", List.of("M301 P22.20 I1.08 D114.00")));

    /**
     * What a factory reset puts in live memory, where it differs from the EEPROM. On the simulator
     * the two genuinely differ in one line, which is the only thing that makes {@code M501} and
     * {@code M502} distinguishable:
     *
     * <pre>
     * &gt;&gt; M503   &lt;&lt; echo:  M104 S0 B250 F0.00      (from the EEPROM)
     * &gt;&gt; M502   &lt;&lt; ok P15 B3
     * &gt;&gt; M503   &lt;&lt; echo:  M104 S210 B250 F0.10    (the defaults, and nothing announced the change)
     * &gt;&gt; M501   &lt;&lt; ok P15 B3
     * &gt;&gt; M503   &lt;&lt; echo:  M104 S0 B250 F0.00      (back)
     * </pre>
     *
     * <p>A mock whose defaults equalled its EEPROM would let a reversed {@code M501}/{@code M502}
     * pass every test.
     */
    private static final Map<String, String> FIRMWARE_DEFAULTS =
            Map.of("M104", "M104 S210 B250 F0.10");

    /**
     * Marlin's live memory, which {@code M503} reports. Keyed by command code, except {@code M145} —
     * the one code the firmware reports <em>twice</em> in a dump, so its key carries an index. See
     * {@link #settingsKey(String)}.
     */
    private final Map<String, String> liveSettings =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /** What {@code M500} wrote and {@code M501} reads back. A separate memory, deliberately. */
    private final Map<String, String> eeprom = Collections.synchronizedMap(new LinkedHashMap<>());

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
     * The {@code N} of the block being answered right now, or null when it carried none. Plain, not
     * {@code volatile}: {@link #receive(String)} is the only writer and every read happens on the
     * same acceptor thread inside the {@link #respond(String)} call it makes. Being a field keeps
     * {@link #ok()} a no-argument method, so a new arm in {@link #respond(String)} gets the right
     * number without knowing this exists. A parked answer carries its number because
     * {@link #respond(String)} renders the whole answer before {@link #answer(String, List)} decides
     * whether to write it.
     */
    private Integer answering;

    /** The command at which the machine stops answering, or null while it answers everything. */
    private String holdFrom;

    /** Whether that command has arrived, so everything from here on is parked. */
    private boolean holding;

    /**
     * The command at which the line-number check is armed, or null. {@code volatile} where
     * {@link #holdFrom} beside it is plain, because {@link #rejectsLineNumber(String)} reads this
     * <em>before</em> the {@link #writing} monitor is taken, while the hold gate is read under it.
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
        SETTINGS_REPORT.forEach(section -> section.getValue()
                .forEach(line -> liveSettings.put(settingsKey(line), line)));
        eeprom.putAll(liveSettings);
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
     * A port with nothing behind it: bound and immediately released, so the number is one the OS
     * handed out and has freed - as close to "unplugged" as a test gets without guessing a constant.
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
     * Whether a socket to this machine is open right now. Its own observable because closing has
     * been got wrong before: cancelling a terminal's two halves does not close the connection, and
     * a disconnect once reported {@code OFFLINE} while leaving the socket {@code Established} on
     * the printer - visible from here, not from the host.
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
     * Whether a command starting with {@code prefix} has reached the machine. A prefix, not an
     * equality, because a command carries its parameters: {@code sent("M104")} is "the hotend was
     * set", {@code sent("M104 S210")} is "to 210".
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
     * The lines stored in a file written with {@code M28}, empty if there is no such <em>closed</em>
     * file. Only a closed file answers, which is what the cancel case turns on: a transfer stopped
     * properly has an {@code M29} behind it, one torn out leaves {@link #writingTo()} naming it.
     */
    public List<String> written(String name) {
        synchronized (card) {
            return List.copyOf(stored.getOrDefault(name, List.of()));
        }
    }

    /**
     * The file {@code M28} opened and {@code M29} has not closed, or null when the wire is a command
     * channel again. A file left open is the failure {@code SdUploader}'s {@code concat} prevents,
     * and it is invisible from the host - every later command is acknowledged as if it had run.
     */
    public String writingTo() {
        return openFile;
    }

    // ------------------------------------------------------------------ what the machine says

    /**
     * Writes a line the host did not ask for - an auto-report, a busy notice, a fault. Nothing here
     * runs on a timer: a real Marlin with {@code M155 S1} reports every second, and a test that
     * waited for one would be asserting on a clock.
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
     * <p>Everything else here answers instantly, so a print finishes before the test's next line
     * runs — and both facts #51 exists to prove are about a print that is <em>still going</em>.
     * Determinism comes from the send window, not a clock: the host may not hold more blocks
     * outstanding than the buffer count allows ({@code B3} here, one on {@link #legacyOk()}), so a
     * machine that stops acknowledging halts the conversation within a few blocks and for good,
     * until {@link #answerOne()} or {@link #answerFreely()}.
     *
     * <p>Set before the machine is dialled; the handshake still answers normally, since
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
     * <p>The one machine state the rest of this mock cannot reach, and issue #60 is the bug that
     * hid behind it: a fresh mock starts at line 0 and a fresh host numbers from 1, so they agree
     * by construction and no block is ever rejected. A real machine outlives host sessions and
     * remembers its counter, making the disagreement ordinary on a bench.
     *
     * <p>Everything it answers is measured by {@code ResendDriftProbe} against MarlinSim
     * bugfix-2.1.x on 2026-11-01 with the counter pushed to 14 — §4.2.2 of
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
     * <p>Three facts from that transcript are reproduced exactly:
     *
     * <ul>
     *   <li>the {@code Resend:} names {@code last_N + 1}, a block the host never framed, so
     *       {@code GSendWindow.resendFrom} finds nothing and
     *       {@code CommandProcessor.resynchronise} abandons what it holds. A {@code Resend:} for a
     *       number the host <em>does</em> hold is fully recoverable — the control in the same run;</li>
     *   <li>{@code M110} repairs it and the firmware reads the <em>parameter</em>, not the line's
     *       own {@code N}: {@code N7 M110 N0} leaves the counter at 0, so the next block may be
     *       {@code N1}. Hence {@code GSendWindow.reset(0)} framing the {@code M110} as {@code N1}
     *       and then numbering the next block {@code N1} too is harmless;</li>
     *   <li>the rejection's {@code ok} is bare — no {@code N}, no buffer counts — the firmware
     *       saying it is ready and acknowledging no block, which
     *       {@code CommandProcessor.rewinding} consumes as credit only.</li>
     * </ul>
     *
     * <p>A rejected block reaches {@link #blocks()} and not {@link #commands()}: it arrived and was
     * thrown away unread. That difference is the whole observable of issue #60 — a print that
     * skipped its {@code G28} homed nowhere. Not a {@link Profile}, because where a counter stands
     * is a property of a session, not of a machine.
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
     * <p>Disarmed unless {@link #checkLineNumbersFrom(String, int)} armed it, and disarmed by
     * {@link Profile#answers()} being false - a machine that says nothing cannot say no either. An
     * unnumbered block is ordinary traffic and costs the sequence nothing, measured in §4.2.1 of
     * the integration-test spec.
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
     * Writes one command's answer, or parks it whole when the gate is shut - whole, because an
     * answer and its acknowledgement are one thing on the wire ({@code M115} is a firmware line,
     * five capabilities and an {@code ok}), and half of one is a machine no firmware resembles.
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
     * One command as the lines a Marlin answers it with, the acknowledgement last. {@code M105} is
     * the exception: its answer <em>is</em> the acknowledgement,
     * {@code ok T:210.00 /210.00 B:25.00 /0.00 @:11 B@:0}, which is why {@code OkTemperatureRs}
     * exists apart from {@code BareTemperatureRs}.
     */
    private List<String> respond(String command) {
        var lines = new ArrayList<String>();
        var word = word(command);

        // Between M28 and M29 the wire is a file sink: the line is stored rather than run, and is
        // still acknowledged. That identical acknowledgement is what makes a stray command during a
        // transfer invisible from the host until it shows up as nonsense mid-print. Measured by
        // SdWriteProbe, below.
        if (openFile != null && !"M29".equals(word)) {
            synchronized (card) {
                openLines.add(command);
            }
            lines.add(ok());
            return lines;
        }

        switch (word) {
            // Open a file for write. Three lines then the acknowledgement, verbatim from a
            // SdWriteProbe run against MarlinSim bugfix-2.1.x on 2026-11-01, and in the reply
            // census since #66 at `marlin-replies.census.txt:132-135`:
            //
            //   >> N1 M28 test.gco*107  << echo:Now fresh file: test.gco
            //                           << Writing to file: test.gco
            //                           << //action:notification test.gco
            //                           << ok N1 P15 B3
            //
            // All three reproduced, not trimmed to the one the feature reads: unlike the busy
            // notices around an M84 (timing chatter, left out) they are a fixed part of M28's
            // answer, and all three decode - SdFreshFile, SdWritingToFile, ActionRs.
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
            // The card then reports the stored payloads plus a CRLF each and nothing else: that run
            // wrote G28, M117 Created, M118 Done, M118 P0 Done-P0 and M20 answered `TEST.GCO 47` =
            // 5 + 14 + 11 + 17. The N word and checksum are stripped before storing, which is why
            // `written` compares directly against a fixture's blocks.
            //
            // An M29 with no file open is measured since #66: ReplyCorpusProbe sends one, and
            // `marlin-replies.census.txt:146` is the whole of the answer - `ok N8 P15 B3` and
            // nothing else. So acknowledging and saying nothing more is what the machine does.
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
            // Acknowledged and nothing else. G21, M83 and M84 joined with #51: they are what a
            // print and the cool-down after a cancelled one send, and they were falling to the
            // default arm, putting three "echo:Unknown command" lines on the event stream that a
            // real machine never sends. Measured, not assumed:
            //
            //   >> N2 G21*24          << ok N2 P15 B3
            //   >> N9 M84*22          << echo:busy: processing (x2), ok N9 P15 B3
            //   >> N13 M83*42         << ok N13 P15 B3
            //   >> N14 G1 E-2 F2400*7 << echo: cold extrusion prevented, ok N14 P15 B3
            //
            // The busy notices and the cold-extrusion echo are chatter around an acknowledgement
            // rather than instead of one, and are not reproduced: this is a mock, not a simulator.
            // ------------------------------------------------------------------ the settings

            // M503 - the whole of live memory, as the commands that would restore it. Two shapes,
            // both measured on 2026-11-04 and both reproduced, because a decoder that only handles
            // one of them is a decoder that breaks the day a caller passes S0:
            //
            //   >> M503     << echo:; Steps per unit:
            //               << echo:  M92 X80.00 Y80.00 Z400.00 E500.00
            //   >> M503 S0  <<   M92 X80.00 Y80.00 Z400.00 E500.00     (no prefix, no sections)
            //
            // S, S1 and C are measured to be byte-identical to a bare M503, so only S0 branches.
            case "M503" -> {
                if (!profile.reportsSettings()) {
                    lines.add("echo:Unknown command: \"" + command + "\"");
                } else if ("S0".equals(argument(command))) {
                    settingsLines().forEach(line -> lines.add("  " + line));
                } else {
                    for (var section : SETTINGS_REPORT) {
                        lines.add("echo:; " + section.getKey() + ":");
                        for (var line : section.getValue()) {
                            lines.add("echo:  " + liveOrReported(line));
                        }
                    }
                }
                lines.add(ok());
            }

            // M500 - live memory into the EEPROM. The notification is part of the answer, not
            // chatter: measured `//action:notification Settings Stored` then `ok P15 B3`. It
            // decodes to ActionRs and is the only confirmation M500 gives.
            case "M500" -> {
                synchronized (liveSettings) {
                    eeprom.clear();
                    eeprom.putAll(liveSettings);
                }
                lines.add("//action:notification Settings Stored");
                lines.add(ok());
            }

            // M501 - EEPROM back into live memory; M502 - the firmware defaults into it. Both
            // answer a BARE ok and say nothing about what changed. Measured, and the fact the
            // feature was designed around: a host holding a settings report is silently wrong
            // after either.
            case "M501" -> {
                synchronized (liveSettings) {
                    liveSettings.clear();
                    liveSettings.putAll(eeprom);
                }
                lines.add(ok());
            }
            case "M502" -> {
                synchronized (liveSettings) {
                    liveSettings.clear();
                    SETTINGS_REPORT.forEach(section -> section.getValue().forEach(line -> {
                        var key = settingsKey(line);
                        liveSettings.put(key, FIRMWARE_DEFAULTS.getOrDefault(key, line));
                    }));
                }
                lines.add(ok());
            }

            // M504 - measured `echo:EEPROM OK` then `ok`. The FAILURE text is UNMEASURED: the
            // simulator's EEPROM is always valid, so there is no honest way to make this say no,
            // and nothing here guesses at one.
            case "M504" -> {
                lines.add("echo:EEPROM OK");
                lines.add(ok());
            }

            // The six settings a caller may change. With words they are a write to live memory;
            // bare they are a read, and the read answers in M503 S0's shape - measured:
            //
            //   >> M92 X81  << ok P15 B3
            //   >> M92      <<   M92 X81.00 Y80.00 Z400.00 E500.00
            //
            // That shared shape is why one reply type covers a dump and a single read-back.
            case "M92", "M203", "M201", "M204", "M205", "M206" -> {
                if (argument(command).isEmpty()) {
                    lines.add("  " + liveSettings.getOrDefault(word, word));
                } else {
                    applySetting(word, argument(command));
                }
                lines.add(ok());
            }

            case "M110", "M22", "M24", "M83", "M84", "M104", "M140", "M155", "M106", "M107",
                 "G21", "G28", "G1", "G0", "G90", "G91" -> lines.add(ok());
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
     * <p>Marlin's rule, not an assumption: {@code ok_to_send} writes the advanced fields when built
     * with {@code ADVANCED_OK}, and inside that copies {@code N} <em>only if the command it answers
     * carried one</em>. One firmware, two shapes, the block deciding:
     *
     * <pre>
     * N2 M114*21  -&gt; ok N2 P15 B3     a numbered block, answered with its number
     * M114        -&gt; ok P15 B3        the same firmware, an unnumbered block
     * </pre>
     *
     * <p>Both halves are in {@code marlin-replies.census.txt} from one {@code ReplyCorpusProbe} run
     * — twenty-odd {@code ok P15 B3}, plus {@code ok N2 P15 B3} / {@code ok N3 P15 B3} against its
     * framed steps. §4.2.1 of {@code doc/specs/INTEGRATION_TESTS_spec.md} has the table and two
     * further transcripts. Nothing here is hand-written.
     *
     * <p>This used to answer {@code ok P15 B3} always — a shape a real session never sees, since the
     * farm numbers every block — so {@code CommandProcessor.applyWindowEvents} took the FIFO
     * {@code window.acknowledge()} branch in every test here and
     * {@code GSendWindow.acknowledge(number)} was exercised by nothing. See
     * {@code ConnectivityIntegrationTest.aStrayAcknowledgementDoesNotShiftTheSendWindow}. The
     * unnumbered arm is unreachable in this farm but kept: it is half of the firmware's rule.
     *
     * <p>{@code M105} does <strong>not</strong> come through here, also measured: Marlin answers it
     * from the gcode handler and returns before {@code ok_to_send}, so a framed {@code N1 M105*…}
     * gets {@code ok T:210.00 /210.00 …} with neither number nor buffer counts, which is the shape
     * the {@code M105} arm replies with. The readings are the 2026-10-29 capture's; the census now
     * holds the same shape at 25 degrees — see the class comment.
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
     * Deliberately does not verify the checksum - that would make every test reading
     * {@link #commands()} a test of {@code :gcode}'s checksum, surfacing as "the temperature was
     * not set".
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
     * What {@code M20} reports for a file written with {@code M28}: the payloads plus a
     * {@code CRLF} each, measured - see the {@code M29} arm. The listing is the only thing that
     * distinguishes a finished transfer from a cancelled one from outside.
     */
    private static int sizeOnCard(List<String> lines) {
        var size = 0;
        for (var line : lines) {
            size += line.length() + 2;
        }
        return size;
    }

    /**
     * The line number a framed block carries: {@code N1 M110 N0*124} to {@code 1}, or null when it
     * has none. The other half of {@link #bare(String)}, walking the same leading digits so the two
     * always agree on whether a block is numbered. No checksum check, for {@link #bare(String)}'s
     * reason.
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

    // ----------------------------------------------------------------- the settings, observable

    /**
     * Live memory as the lines {@code M503 S0} would print, in report order. An observable like
     * {@link #written(String)}: the difference between proving {@code M501} reverted a value and
     * proving an {@code M501} was sent.
     */
    public List<String> settingsLines() {
        var ordered = new ArrayList<String>();
        synchronized (liveSettings) {
            SETTINGS_REPORT.forEach(section -> section.getValue()
                    .forEach(line -> ordered.add(liveSettings.get(settingsKey(line)))));
        }
        return ordered;
    }

    /** One setting as live memory currently holds it, e.g. {@code setting("M92")}. */
    public String setting(String code) {
        return liveSettings.get(code);
    }

    /** The same from the stored copy - what a power cycle would come back to. */
    public String stored(String code) {
        return eeprom.get(code);
    }

    /** The reported line for a code, read from live memory, or the captured line if untouched. */
    private String liveOrReported(String reported) {
        var held = liveSettings.get(settingsKey(reported));
        return held == null ? reported : held;
    }

    /**
     * The key a reported line is held under: the code, except {@code M145}, the only code MarlinSim
     * reports twice in one dump ({@code S0} and {@code S1} presets), whose index joins the key. A
     * named exception rather than a general rule, because the general rules guess -
     * {@code M200 S0 D1.75} also has an {@code S} word and is reported once.
     */
    private static String settingsKey(String reported) {
        var code = word(reported);
        if (!"M145".equals(code)) {
            return code;
        }
        for (var token : argument(reported).split(" ")) {
            if (token.startsWith("S")) {
                return code + " " + token;
            }
        }
        return code;
    }

    /**
     * Writes words into live memory, letter by letter, keeping the rest of the line.
     *
     * <p>{@code M92 X81} against {@code M92 X80.00 Y80.00 Z400.00 E500.00} leaves
     * {@code M92 X81.00 Y80.00 Z400.00 E500.00} - measured, including the two decimals the firmware
     * reformats to. A mock that replaced the whole line would make a partial write look total, and
     * a partial write is what every control on the tab sends.
     */
    private void applySetting(String code, String words) {
        synchronized (liveSettings) {
            var current = liveSettings.getOrDefault(code, code);
            var merged = new LinkedHashMap<String, String>();
            var tokens = current.split(" ");
            for (var i = 1; i < tokens.length; i++) {
                if (!tokens[i].isEmpty()) {
                    merged.put(tokens[i].substring(0, 1), tokens[i].substring(1));
                }
            }
            for (var token : words.split(" ")) {
                if (token.length() < 2) {
                    continue;
                }
                merged.put(token.substring(0, 1), twoDecimals(token.substring(1)));
            }
            var rebuilt = new StringBuilder(code);
            merged.forEach((letter, value) -> rebuilt.append(' ').append(letter).append(value));
            liveSettings.put(code, rebuilt.toString());
        }
    }

    /** The scale MarlinSim prints a settings value at - {@code X81} comes back as {@code X81.00}. */
    private static String twoDecimals(String value) {
        try {
            return new java.math.BigDecimal(value)
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException notANumber) {
            return value;
        }
    }
}

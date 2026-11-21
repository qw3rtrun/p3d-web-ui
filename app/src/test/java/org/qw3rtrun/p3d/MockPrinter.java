package org.qw3rtrun.p3d;

import org.qw3rtrun.p3d.g.marlin.binary.MbpControlType;
import org.qw3rtrun.p3d.g.marlin.binary.MbpFrame;
import org.qw3rtrun.p3d.g.marlin.binary.MbpPacket;
import org.qw3rtrun.p3d.g.marlin.binary.MbpPacketReader;
import org.qw3rtrun.p3d.g.marlin.binary.MbpProtocol;
import org.qw3rtrun.p3d.g.marlin.binary.MbpTransferType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
     * @param binaryTransfer built with {@code BINARY_FILE_TRANSFER}, which is what
     *                     {@code Cap:BINARY_FILE_TRANSFER} reports and what gates the Storage tab's
     *                     Binary Transfer checkbox. True is MarlinSim, measured 2026-11-16:
     *                     {@code Cap:BINARY_FILE_TRANSFER:1}, and {@code M28 B1} really does switch
     *                     the stream. <strong>UNMEASURED when false</strong> — the simulator cannot
     *                     be built without it, so a machine that reports {@code 0} here simply never
     *                     reaches the binary path: the server refuses the request before anything is
     *                     written. Assert the refusal, never a reply string
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
                          boolean binaryTransfer,
                          boolean advancedOk,
                          boolean answers,
                          boolean reportsSettings) {

        public Profile {
            capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
        }

        /**
         * The last block this machine's opening handshake puts on the wire - what a test waits for
         * before the conversation may be assumed to be its own ({@code connectAndSettle}).
         *
         * <p>Derived from {@link #capabilities} by the same rule {@code PrinterState.on(CapabilityRs)}
         * applies, in the same order, because since #127 the handshake's tail is capability-gated and
         * is therefore a property of the machine rather than a constant. It used to be {@code M155 S1}
         * for everything, and a helper that cleared the command log there would now clear it with
         * {@code M114} and {@code M27} still in flight and let them land as the test's own blocks.
         *
         * <p>This is a <em>derivation</em> and could drift from the farm's rule in silence, so
         * {@code HandshakeIntegrationTest} spells the whole expected list out literally for every
         * profile below rather than calling this - one independent statement of the rule per
         * configuration.
         */
        public String lastHandshakeBlock() {
            if (!answers) {
                // nothing is ever acknowledged, so nothing after the first block is ever sent
                return "M110 N0";
            }
            var last = "M155 S1";
            if (firmware == null) {
                // no M115 line means no Cap: lines, so nothing gated is reached
                return last;
            }
            for (var capability : capabilities.entrySet()) {
                var block = gatedBlock(capability.getKey(), capability.getValue());
                if (block != null) {
                    last = block;
                }
            }
            return last;
        }

        /** The handshake block one {@code Cap:} line causes, or null for one that causes none. */
        private static String gatedBlock(String capability, boolean on) {
            return switch (capability) {
                case "AUTOREPORT_POS" -> on ? "M154 S1" : "M114";
                case "AUTOREPORT_SD_STATUS" -> on ? "M27 S3" : null;
                case "SDCARD" -> on ? "M27" : null;
                default -> null;
            };
        }

        /** The same machine with a different {@code EXTRUDER_COUNT}, which is what gates tool 1. */
        public Profile withExtruders(String label, int count) {
            return new Profile(label,
                    firmware.replaceAll("EXTRUDER_COUNT:\\d+", "EXTRUDER_COUNT:" + count),
                    capabilities, sdCard, binaryTransfer, advancedOk, answers, reportsSettings);
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

    /**
     * The subset of the census's forty-odd {@code Cap:} lines that anything in this farm reads,
     * <strong>in the order MarlinSim writes them</strong> - which is load-bearing since #127, because
     * the opening handshake's capability-gated blocks go out as each line arrives, so the order of
     * this map is the order of the wire. Measured 2026-11-17 against
     * {@code Marlin bugfix-2.1.x (Oct 29 2026 10:28:56)}: {@code AUTOREPORT_POS} at line 6 of 38,
     * {@code SDCARD} at 22, {@code AUTOREPORT_SD_STATUS} at 26 - so a card is announced before the
     * auto-report that would supersede polling it, which is why the handshake cannot decide between
     * them and sends both. {@link Profile#lastHandshakeBlock()} depends on this order.
     *
     * <p>{@code AUTOREPORT_POS} and {@code AUTOREPORT_SD_STATUS} are both {@code 0} on the
     * simulator, measured - see {@link #withPositionAutoReport()} for what is and is not known
     * about the other side of each.
     */
    private static Map<String, Boolean> simCapabilities(boolean sdCard, boolean binaryTransfer) {
        var caps = new LinkedHashMap<String, Boolean>();
        caps.put("AUTOREPORT_TEMP", true);
        caps.put("AUTOREPORT_POS", false);
        caps.put("EEPROM", true);
        caps.put("SDCARD", sdCard);
        caps.put("SD_WRITE", sdCard);
        caps.put("AUTOREPORT_SD_STATUS", false);
        caps.put("BINARY_FILE_TRANSFER", binaryTransfer);
        caps.put("EMERGENCY_PARSER", true);
        return caps;
    }

    private static Map<String, Boolean> simCapabilities(boolean sdCard) {
        return simCapabilities(sdCard, sdCard);
    }

    // ------------------------------------------------------------------ the configuration matrix

    /** A MarlinSim with a card in it: the baseline every feature is proved against first. */
    public static Profile marlin() {
        return new Profile("marlin-sd", MARLIN_SIM_M115, simCapabilities(true),
                true, true, true, true, true);
    }

    /** The same firmware with an empty reader - the Storage tab's other state. */
    public static Profile withoutCard() {
        return new Profile("marlin-no-sd", MARLIN_SIM_M115, simCapabilities(false),
                false, false, true, true, true);
    }

    /**
     * A card reader, {@code SD_WRITE}, and no {@code BINARY_FILE_TRANSFER} - the configuration that
     * must still upload, over ASCII, and must refuse a binary request rather than downgrade it.
     *
     * <p>The pair this and {@link #marlin()} make is the point: the same request with the same
     * {@code binary} flag has to be accepted by one and refused by the other, which is the only way
     * to prove the capability is read rather than assumed. A single capable profile would pass with
     * the check deleted.
     */
    public static Profile withoutBinaryTransfer() {
        return new Profile("marlin-no-binary", MARLIN_SIM_M115, simCapabilities(true, false),
                true, false, true, true, true);
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
                true, true, false, true, true);
    }

    /**
     * Accepts the connection and then says nothing at all.
     *
     * <p>A terminal server with no printer behind it. The farm must reach {@code ONLINE} and stop
     * there, because {@code IDLE} means a block this session sent came back acknowledged.
     */
    public static Profile mute() {
        return new Profile("mute", MARLIN_SIM_M115, simCapabilities(true),
                true, true, true, false, true);
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
        return new Profile("marlin-no-eeprom", MARLIN_SIM_M115, caps, true, true, true, true, true);
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
                true, true, true, true, false);
    }

    /**
     * A firmware built with {@code AUTOREPORT_POSITION}: {@code Cap:AUTOREPORT_POS:1}, so the host
     * starts {@code M154 S1} instead of reading the position once with {@code M114}.
     *
     * <p><strong>Note the spelling.</strong> The capability on the wire is {@code AUTOREPORT_POS};
     * Marlin's build macro is {@code AUTOREPORT_POSITION} and the G-code is {@code M154}. Three
     * different names for one feature, measured 2026-11-17 - the simulator's {@code M115} carries
     * {@code Cap:AUTOREPORT_POS:0} and no line under any longer spelling, so a host keyed on
     * {@code AUTOREPORT_POSITION} would silently find nothing and conclude "not supported" for a
     * machine that supports it.
     *
     * <p><strong>Half of this is UNMEASURED and the halves are different.</strong> The
     * <em>refused</em> side is measured: on the simulator, {@code M154 S1} answers
     * {@code echo:Unknown command: "M154 S1"} then {@code ok P15 B3}, and three and a half seconds
     * of silence followed - which is why the farm gates this on the capability rather than sending
     * it and hoping. The <em>supported</em> side cannot be measured here at all: the simulator is
     * not built with it, so what a capable firmware answers {@code M154 S1} is Marlin's documented
     * behaviour and not a capture. This profile therefore answers a bare {@code ok} and pushes no
     * unsolicited position lines; assert on {@code M154 S1} reaching the wire and on {@code M114}
     * not, never on a report arriving.
     */
    public static Profile withPositionAutoReport() {
        var caps = new LinkedHashMap<>(simCapabilities(true));
        caps.put("AUTOREPORT_POS", true);
        return new Profile("marlin-autoreport-pos", MARLIN_SIM_M115, caps,
                true, true, true, true, true);
    }

    /**
     * A firmware built with {@code AUTO_REPORT_SD_STATUS}: {@code Cap:AUTOREPORT_SD_STATUS:1}, so
     * the host starts {@code M27 S3} as well as reading the status once.
     *
     * <p><strong>This profile exists because of the one measurement that decided the whole design.</strong>
     * On the simulator, with {@code Cap:AUTOREPORT_SD_STATUS:0}, {@code M27 S1} is <em>not</em>
     * refused - it answers {@code Not SD printing} then {@code ok P15 B3}, exactly as a bare
     * {@code M27} does, and then reports nothing for the next three and a half seconds. So an
     * unsupported auto-report period is accepted in silence, and "send it and let the firmware
     * refuse what it cannot do" - which works for {@code M154} and for {@code M503} - is invisible
     * here. Only the capability can tell the farm, which is why the farm reads it.
     *
     * <p><strong>UNMEASURED when true</strong>, for the same reason as
     * {@link #withPositionAutoReport()}: the simulator is not built with it. The answer to
     * {@code M27 S3} here is the measured answer to {@code M27 S1}, which is the honest thing to
     * reproduce - it is the same code path on the firmware with one branch taken differently, and
     * nothing here invents a periodic report.
     */
    public static Profile withSdStatusAutoReport() {
        var caps = new LinkedHashMap<>(simCapabilities(true));
        caps.put("AUTOREPORT_SD_STATUS", true);
        return new Profile("marlin-autoreport-sd", MARLIN_SIM_M115, caps,
                true, true, true, true, true);
    }

    /**
     * A firmware built with {@code LONG_FILENAME_WRITE_SUPPORT}: {@code Cap:LFN_WRITE:1}, so an
     * upload keeps the name exactly as asked rather than being shortened to fit DOS 8.3.
     *
     * <p><strong>The absent/false side is measured, and this profile's true side is not.</strong>
     * {@code LongFileNameProbe} against MarlinSim (bugfix-2.1.x, compiled Nov 18 2026), 2026-11-21,
     * found {@code Cap:LFN_WRITE:0} and {@code Cap:LONG_FILENAME:0} - which is also what
     * {@link #marlin()}'s {@code simCapabilities} leaves absent, so the baseline profile already
     * reproduces the measured "cannot write a long name" machine. The simulator is not built with
     * {@code LONG_FILENAME_WRITE_SUPPORT}, so a capable firmware's own behaviour here is not a
     * capture: this profile simply opens, writes and closes whatever name {@code SdUploader} sends
     * it, unshortened, which is enough to prove the capability is <em>read</em> rather than ignored -
     * the same pairing {@link #withoutBinaryTransfer()} makes with {@link #marlin()}.
     */
    public static Profile withLongFilenameWrite() {
        var caps = new LinkedHashMap<>(simCapabilities(true));
        caps.put("LFN_WRITE", true);
        return new Profile("marlin-lfn-write", MARLIN_SIM_M115, caps,
                true, true, true, true, true);
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

    /**
     * {@code M119}'s answer: which of this machine's endstops are triggered, in the order the
     * firmware reports them. Captured verbatim by {@code GCodeLiveProbe} against MarlinSim
     * (bugfix-2.1.x, Nov 18 2026) on {@code localhost:8099}:
     *
     * <pre>
     * &gt;&gt; M119   &lt;&lt; Reporting endstop status
     *            &lt;&lt; x_min: open
     *            &lt;&lt; y_min: open
     *            &lt;&lt; z_min: TRIGGERED
     *            &lt;&lt; ok P15 B3
     * </pre>
     *
     * <p>A {@link LinkedHashMap} so the order is reproducible - three on this build, which is a
     * property of the machine it was measured on and not a rule {@code M119} itself states.
     * {@link #setEndstop(String, boolean)} lets a test move a switch between two reads.
     */
    private static final Map<String, Boolean> DEFAULT_ENDSTOPS = new LinkedHashMap<>();

    static {
        // Map.of() does not preserve insertion order - explicit puts, in the measured order.
        DEFAULT_ENDSTOPS.put("x_min", false);
        DEFAULT_ENDSTOPS.put("y_min", false);
        DEFAULT_ENDSTOPS.put("z_min", true);
    }

    private final Map<String, Boolean> endstops =
            Collections.synchronizedMap(new LinkedHashMap<>(DEFAULT_ENDSTOPS));

    /**
     * Moves one endstop so the next {@code M119} answers differently - the only way a test can show
     * that a second read genuinely updates the held state rather than coincidentally repeating the
     * first.
     */
    public MockPrinter setEndstop(String name, boolean triggered) {
        endstops.put(name, triggered);
        return this;
    }

    /** The file {@code M28} opened and {@code M29} has not yet closed, or null. */
    private volatile String openFile;

    /** The lines that have gone into {@link #openFile} so far. */
    private List<String> openLines = new ArrayList<>();

    // ------------------------------------------------- the binary protocol, measured 2026-11-16

    /**
     * The firmware's payload buffer as its {@code ss} handshake reports it.
     *
     * <p><strong>96, not the 512 the reference implementation and {@code MbpSendWindow}'s default
     * assume.</strong> Measured against MarlinSim (Marlin bugfix-2.1.x, compiled Oct 29 2026) on
     * 2026-11-16:
     *
     * <pre>
     * &gt;&gt; AD B5 00 01 00 00 01 03     (SYNC, sync=0)
     * &lt;&lt; ss1,96,0.1.0
     * </pre>
     *
     * Reproduced rather than rounded up because it is the number that exercises
     * {@code MbpSendWindow}'s clamp: a host that kept 512 would slice blocks the firmware cannot
     * hold, and a mock that reported 512 would never catch it. A 250-byte file is three blocks here.
     */
    private static final int MBP_BUFFER = 96;

    /** The version string the {@code ss} and {@code PFT:version:} replies carry, measured. */
    private static final String MBP_VERSION = "0.1.0";

    /**
     * The line that both answers {@code M28 B1} and <em>is</em> the mode change - see
     * {@link #write(String)}. Measured 2026-11-16, and decoded by {@code :gcode}'s
     * {@code BinaryModeSwitched}, which is the typed signal the transport waits for.
     */
    private static final String BINARY_MODE_ECHO = "echo:Switching to Binary Protocol";

    /**
     * Whether the stream is reading packets rather than lines. Set when {@code M28 B1} is answered,
     * cleared by a {@code CONTROL}/{@code CLOSE} packet.
     *
     * <p>The mock switches the moment it has written its answer, which is the <em>lenient</em>
     * ordering: a real Marlin reads serial into a command queue and executes {@code M28 B1} later,
     * so bytes written in between are consumed by the ASCII reader and become garbage. That race
     * cannot be reproduced here, so {@link #packetsBeforeSwitch} counts the host's mistake instead -
     * see its javadoc.
     */
    private volatile boolean binaryMode;

    /**
     * How many packet tokens arrived while the stream was still reading lines.
     *
     * <p>Zero is the assertion: a host that started writing packets before the firmware confirmed
     * the switch would be relying on an ordering a real Marlin does not give it. The count exists
     * because the mock itself cannot fail that way (see {@link #binaryMode}), so the only way to
     * prove the host waited is to notice if it did not.
     */
    private final AtomicInteger packetsBeforeSwitch = new AtomicInteger();

    /** How many packets to answer before the gate shuts; -1 for never. See holdPacketsAfter. */
    private int holdAfterPackets = -1;

    /** How many packets have been answered this session, counted from the SYNC. */
    private int packetsAnswered;

    /** The sync id the firmware expects next. Global and surviving a reconnect, measured: three
     * consecutive sessions answered {@code ss1}, {@code ss8} and {@code ss0} rather than always 0. */
    private int mbpSync;

    /** The binary file being written, or null; {@code OPEN} sets it and {@code CLOSE} stores it. */
    private String mbpOpenFile;

    private ByteArrayOutputStream mbpOpenBytes = new ByteArrayOutputStream();

    /**
     * What each binary transfer put on the card, byte for byte. Kept apart from {@link #stored},
     * which holds <em>lines</em>: the ASCII path re-assembles G-code and strips each block's
     * {@code N} and {@code *} fields, so a file that went up over ASCII is not a copy of the one
     * that was uploaded, while a binary one is. That difference is the feature.
     */
    private final Map<String, byte[]> binaryStored =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Whether a card is mounted, as distinct from {@link Profile#sdCard} - whether the machine has a
     * reader at all.
     *
     * <p>Modelled because the firmware releases the card when a binary transfer closes, and an
     * {@code M20} after that answers {@code echo:No media} until an {@code M21} re-mounts it.
     * Measured 2026-11-16:
     *
     * <pre>
     * &gt;&gt; PFT CLOSE   &lt;&lt; ok6
     *                &lt;&lt; echo:SD card released
     *                &lt;&lt; PFT:success
     * &gt;&gt; M20         &lt;&lt; echo:No media
     * &gt;&gt; M21         &lt;&lt; echo:SD card ok
     * &gt;&gt; M20         &lt;&lt; Begin file list ... MBP.GCO 250 ... End file list
     * </pre>
     *
     * Without this the {@code M21} the upload sends afterwards would be untested ceremony that a
     * later tidy-up could delete, and the Storage tab would silently stop listing after every
     * binary upload.
     */
    private volatile boolean cardMounted = true;

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

    /**
     * Drops the open connection from <em>the machine's end</em>, leaving the port listening.
     *
     * <p>The difference from {@code disconnect(id)} is the whole of issue #94. A disconnect is an
     * intent, and {@code ConnectionLifecycle.disconnectRequested()} takes the machine to
     * {@code OFFLINE}; a cable pulled out while a connection is still wanted reaches
     * {@code linkFailed}, which with {@code wanted} set goes to {@code CONNECTING} and redials -
     * so a drop-and-return never announces {@code OFFLINE} at all. Anything keyed off that state
     * is keyed off the case that does not happen.
     *
     * <p>The acceptor loop is a {@code while} around {@code accept()}, so closing the live socket
     * ends one session and the next dial is served normally: {@link #sessions()} is how a test
     * waits for the new one, since the host's own state walks {@code ONLINE -> IDLE} twice with
     * nothing in between to distinguish the runs.
     */
    public void hangUp() {
        var socket = live;
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // the point is that it is gone; a socket that would not close is gone either way
        }
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
     * The bytes a binary transfer put into {@code name}, or null if nothing did.
     *
     * <p>The counterpart of {@link #written(String)} and deliberately a different type: that one
     * returns the <em>lines</em> an ASCII upload stored, because the ASCII path re-assembles G-code
     * through {@code GcodeLines} and the firmware strips each block's {@code N} and {@code *}
     * fields, so what lands is not a copy of what was uploaded. A binary upload is byte for byte,
     * so asserting anything less than the exact array would miss the feature's whole claim.
     */
    public byte[] binaryWritten(String name) {
        synchronized (card) {
            var bytes = binaryStored.get(name);
            return bytes == null ? null : bytes.clone();
        }
    }

    /** Whether the stream is reading packets right now - true only inside a binary transfer. */
    public boolean inBinaryMode() {
        return binaryMode;
    }

    /** Whether a card is mounted, which a binary transfer's close releases - see {@code cardMounted}. */
    public boolean cardMounted() {
        return cardMounted;
    }

    /**
     * How many packet tokens arrived while the stream was still reading lines - zero unless the host
     * started writing packets before being told the mode had changed. See {@code packetsBeforeSwitch}.
     */
    public int packetsBeforeSwitch() {
        return packetsBeforeSwitch.get();
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

    /**
     * Stops answering once {@code packets} binary packets have been answered.
     *
     * <p>The binary counterpart of {@link #holdRepliesFrom(String)}, and it needs a different
     * trigger because a packet is not a command: nothing a binary transfer sends reaches
     * {@link #commands()}, so there is no prefix to name. Determinism comes from the same place -
     * the protocol's window is <strong>one packet deep</strong>, so a machine that stops
     * acknowledging halts the transfer at the next packet and for good, until {@link #answerOne()}
     * or {@link #answerFreely()}.
     *
     * <p>Counted from the {@code SYNC}, so {@code holdPacketsAfter(3)} withholds the reply to the
     * first {@code WRITE}: the file is open on the card and the host is waiting on a block it cannot
     * get an answer for, which is the state an exclusivity claim has to be tested in. The packet is
     * still <em>processed</em> before its reply is parked, which is what a firmware does too - the
     * reply is what goes missing, not the write.
     */
    public MockPrinter holdPacketsAfter(int packets) {
        synchronized (writing) {
            holdAfterPackets = packets;
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
            // Disarmed too, or the next packet past the threshold would shut the gate again and
            // the transfer released here would stall for good - the window is one packet deep.
            holdAfterPackets = -1;
            holding = false;
            for (List<String> lines; (lines = parked.pollFirst()) != null; ) {
                lines.forEach(this::write);
            }
        }
    }

    // ------------------------------------------------------------------ the session

    /**
     * One session, read a byte at a time.
     *
     * <p>A byte loop rather than a {@code BufferedReader.readLine()} one because a binary packet is
     * not a line: it carries {@code AD B5} and arbitrary payload bytes, and a reader that cut at
     * {@code \n} and decoded US-ASCII would split and mangle it. In line mode the bytes are
     * accumulated here instead, which is the same behaviour the reader had.
     */
    private void serve() {
        while (!closed) {
            try (var socket = server.accept()) {
                sessions.incrementAndGet();
                live = socket;
                var in = socket.getInputStream();
                var writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
                synchronized (writing) {
                    out = writer;
                }
                read(in);
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

    /**
     * The session's read loop: lines until {@code M28 B1}, packets until a {@code CONTROL}/
     * {@code CLOSE}, lines again.
     *
     * <p>{@code MbpPacketReader} is {@code :gcode}'s own, so the mock reads exactly what the
     * encoder writes and the two cannot agree on a wrong layout - and it is built with
     * {@link #MBP_BUFFER} as its limit, so a host that ignored the handshake's 96 and sent a
     * 512-byte block gets {@code MbpPayloadTooLarge} here rather than a silently accepted packet.
     */
    private void read(InputStream in) throws IOException {
        var line = new StringBuilder();
        var packets = new MbpPacketReader(MBP_BUFFER);
        for (int b; (b = in.read()) >= 0; ) {
            if (binaryMode) {
                var frame = packets.read(b);
                if (frame != null) {
                    receive(frame);
                }
                continue;
            }
            if (b == '\n') {
                receive(line.toString());
                line.setLength(0);
                continue;
            }
            if (b == '\r') {
                continue;
            }
            // The low byte of the packet token. In line mode it can only mean the host started
            // writing packets before the firmware said it had switched - see packetsBeforeSwitch.
            if (b == 0xAD) {
                packetsBeforeSwitch.incrementAndGet();
            }
            line.append((char) (b & 0xff));
        }
    }

    /**
     * One binary frame as the lines a Marlin answers it with.
     *
     * <p>Measured against MarlinSim (Marlin bugfix-2.1.x, compiled Oct 29 2026) on 2026-11-16,
     * a 250-byte file as three 96-byte blocks:
     *
     * <pre>
     * &gt;&gt; SYNC            &lt;&lt; ss1,96,0.1.0                     (no ok for this one, ever)
     * &gt;&gt; QUERY  (sync=1) &lt;&lt; ok1
     *                    &lt;&lt; PFT:version:0.1.0:compression:heatshrink,8,4
     * &gt;&gt; OPEN   (sync=2) &lt;&lt; ok2
     *                    &lt;&lt; echo:SD card ok
     *                    &lt;&lt; echo:Now fresh file: mbp.gco
     *                    &lt;&lt; Writing to file: mbp.gco
     *                    &lt;&lt; //action:notification mbp.gco
     *                    &lt;&lt; PFT:success
     * &gt;&gt; WRITE  (sync=3) &lt;&lt; ok3                               (and nothing else, ever)
     * &gt;&gt; PFT CLOSE       &lt;&lt; ok6, echo:SD card released, PFT:success
     * &gt;&gt; CONTROL CLOSE   &lt;&lt; ok7
     * &gt;&gt; OPEN again      &lt;&lt; ok10, PFT:busy                   (a second open while one is held)
     * </pre>
     *
     * <p>Two orderings in there are the protocol and not incidental. The {@code ok} comes
     * <em>before</em> the {@code PFT:} line because the firmware acknowledges a packet before it
     * dispatches it - which is why an {@code ok} is block progress only while writing. And
     * {@code SYNC} is answered by {@code ss} alone, so a host that waited for an {@code ok} for it
     * would retransmit until it gave up.
     *
     * <p>A corrupt or over-wide frame is answered {@code rs<sync>}, which is what the firmware does
     * and what {@code MbpSendWindow.retransmit} exists for.
     */
    private void receive(MbpFrame frame) {
        var lines = answerTo(frame);
        synchronized (writing) {
            packetsAnswered++;
            if (holdAfterPackets >= 0 && packetsAnswered > holdAfterPackets) {
                holding = true;
            }
            if (holding) {
                parked.addLast(lines);
                return;
            }
            lines.forEach(this::write);
        }
    }

    /** One frame's reply lines, in the order the firmware writes them. */
    private List<String> answerTo(MbpFrame frame) {
        var lines = new ArrayList<String>();
        if (!(frame instanceof MbpPacket packet)) {
            lines.add("rs" + mbpSync);
            return lines;
        }
        if (packet.getProtocol() == MbpProtocol.CONTROL) {
            if (packet.getType() == MbpControlType.SYNC) {
                // Answered while the header is still being read, before the sync id is checked,
                // so it is the one packet that works on a desynchronised stream.
                lines.add("ss" + mbpSync + "," + MBP_BUFFER + "," + MBP_VERSION);
                return lines;
            }
            lines.add("ok" + packet.getSync());
            mbpSync = (packet.getSync() + 1) & 0xff;
            binaryMode = false;
            return lines;
        }

        lines.add("ok" + packet.getSync());
        mbpSync = (packet.getSync() + 1) & 0xff;

        switch (packet.getType()) {
            case MbpTransferType.QUERY ->
                // heatshrink is what the simulator advertises; nothing here compresses, and
                // MbpTransfer opens with the flag clear regardless, which the measured run proves.
                    lines.add("PFT:version:" + MBP_VERSION + ":compression:heatshrink,8,4");
            case MbpTransferType.OPEN -> {
                if (mbpOpenFile != null) {
                    lines.add("PFT:busy");
                    return lines;
                }
                // dummy(1) | compression(1) | name | NUL
                var payload = packet.getPayload();
                var end = 2;
                while (end < payload.length && payload[end] != 0) {
                    end++;
                }
                var name = new String(payload, 2, end - 2, StandardCharsets.US_ASCII);
                mbpOpenFile = name;
                mbpOpenBytes = new ByteArrayOutputStream();
                cardMounted = true;
                lines.add("echo:SD card ok");
                lines.add("echo:Now fresh file: " + name);
                lines.add("Writing to file: " + name);
                lines.add("//action:notification " + name);
                lines.add("PFT:success");
            }
            // An ok and nothing more: the one packet type with no PFT: line of its own, which is
            // what makes an ok mean "one block is on the card" while writing and nothing elsewhere.
            case MbpTransferType.WRITE -> {
                if (mbpOpenFile == null) {
                    lines.add("PFT:invalid");
                    return lines;
                }
                mbpOpenBytes.writeBytes(packet.getPayload());
            }
            case MbpTransferType.CLOSE -> {
                if (mbpOpenFile == null) {
                    lines.add("PFT:invalid");
                    return lines;
                }
                var bytes = mbpOpenBytes.toByteArray();
                synchronized (card) {
                    card.put(mbpOpenFile, bytes.length);
                    binaryStored.put(mbpOpenFile, bytes);
                }
                mbpOpenFile = null;
                mbpOpenBytes = new ByteArrayOutputStream();
                // The firmware lets the card go here, which is why an upload sends M21 afterwards.
                cardMounted = false;
                lines.add("echo:SD card released");
                lines.add("PFT:success");
            }
            case MbpTransferType.ABORT -> {
                mbpOpenFile = null;
                mbpOpenBytes = new ByteArrayOutputStream();
                lines.add("PFT:success");
            }
            default -> lines.add("PTF:invalid");
        }
        return lines;
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
                // M28 B1 is the opposite command: it opens no file, it switches the wire to
                // packets. Measured 2026-11-16:
                //
                //   >> M28 B1   << echo:Switching to Binary Protocol
                //               << ok P15 B3
                //
                // The flag has to come first - the firmware reads it off the start of the string
                // argument, so `M28 test.gco B1` is a file called "test.gco B1". That is also why
                // :gcode builds this through the DSL rather than through StartSDWrite, whose
                // generated model reads everything after M28 as one bare filename.
                if ("B1".equals(name)) {
                    if (!profile.binaryTransfer()) {
                        // UNMEASURED: the simulator cannot be built without the capability. The
                        // server refuses before anything is written, so nothing should reach here.
                        lines.add("echo:Unknown command: \"" + command + "\"");
                        lines.add(ok());
                        return lines;
                    }
                    lines.add(BINARY_MODE_ECHO);
                    lines.add(ok());
                    return lines;
                }
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
            // M119 - which endstops are triggered. No capability gates it on the measured build and
            // no refusal of it has been observed, so it is answered unconditionally - see
            // EndstopsControl's own javadoc for why nothing in the farm assumes a fixed set of names.
            case "M119" -> {
                lines.add("Reporting endstop status");
                synchronized (endstops) {
                    endstops.forEach((name, triggered) ->
                            lines.add(name + ": " + (triggered ? "TRIGGERED" : "open")));
                }
                lines.add(ok());
            }
            case "M21" -> {
                if (profile.sdCard()) {
                    // Re-mounts a card a binary transfer released - see cardMounted.
                    cardMounted = true;
                }
                lines.add(profile.sdCard() ? "echo:SD card ok" : "echo:No SD card");
                lines.add(ok());
            }
            case "M20" -> {
                if (profile.sdCard() && cardMounted) {
                    lines.add("Begin file list");
                    synchronized (card) {
                        card.forEach((name, size) -> lines.add(name + " " + size));
                    }
                    lines.add("End file list");
                } else if (profile.sdCard()) {
                    // A reader with a released card. Measured 2026-11-16, straight after a binary
                    // transfer closed: `echo:No media`, which is a different line from the
                    // `echo:No SD card` a machine with no reader answers.
                    lines.add("echo:No media");
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

            // ------------------------------------------------------- the auto-reports, #127

            // M154 - position auto-report. The refusal is MEASURED on the simulator, which is built
            // without it:
            //
            //   >> M154 S1   << echo:Unknown command: "M154 S1"
            //                << ok P15 B3
            //                   then 0 lines in the next 3400 ms
            //   >> M154 S0   << echo:Unknown command: "M154 S0"   (the control, also 0 lines)
            //
            // The capable arm is UNMEASURED - see withPositionAutoReport() - so it acknowledges and
            // reports nothing rather than inventing a periodic line.
            case "M154" -> {
                if (Boolean.TRUE.equals(profile.capabilities().get("AUTOREPORT_POS"))) {
                    lines.add(ok());
                } else {
                    lines.add("echo:Unknown command: \"" + command + "\"");
                    lines.add(ok());
                }
            }

            // M27 - SD print status, bare or with a period. MEASURED, and the measurement is the
            // reason the handshake gates on Cap:AUTOREPORT_SD_STATUS instead of just asking:
            //
            //   >> M27      << Not SD printing      << ok P15 B3
            //   >> M27 S1   << Not SD printing      << ok P15 B3
            //                  then 0 lines in the next 3400 ms, on a build with
            //                  Cap:AUTOREPORT_SD_STATUS:0
            //
            // So the period is accepted in silence by a firmware that cannot honour it: identical
            // bytes back, and no report. There is nothing on the wire for a host to key on.
            //
            // The no-card arm is UNMEASURED and unreachable: the handshake only asks a machine that
            // answered Cap:SDCARD:1, and the simulator always has a card. It is here so a profile
            // that reaches it does not fall to the Unknown-command default, which would be a reply
            // no Marlin sends.
            case "M27" -> {
                lines.add(profile.sdCard() ? "Not SD printing" : "echo:No SD card");
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

    /**
     * One reply line, and the one place the stream turns binary.
     *
     * <p>The mode flips when the host is <em>told</em>, not when {@link #respond(String)} decides -
     * because {@link #holdRepliesFrom(String)} can park that answer, and a mock that had already
     * switched would read the host's next ASCII block as packet noise. It is also what the firmware
     * does: {@code echo:Switching to Binary Protocol} is written from the G-code execution stage, as
     * the mode changes, which is exactly why it is the signal a host may rely on.
     */
    private void write(String line) {
        synchronized (writing) {
            var writer = out;
            if (writer == null) {
                return;
            }
            if (BINARY_MODE_ECHO.equals(line)) {
                binaryMode = true;
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

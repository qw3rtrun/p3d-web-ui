package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.marlin.command.AutoHome;
import org.qw3rtrun.p3d.g.marlin.command.Babystep;
import org.qw3rtrun.p3d.g.marlin.command.BedLeveling;
import org.qw3rtrun.p3d.g.marlin.command.BedLevelingState;
import org.qw3rtrun.p3d.g.marlin.command.XYZProbeOffset;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.protocol.BaseRsDecoder;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.g.protocol.GRs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Measures what Marlin's bed-leveling and babystepping surface actually answers - {@code G29},
 * {@code M420} and {@code M290} - so the Calibration tab can be designed against a capture rather
 * than against marlinfw.org. Issue for the feature: see the branch this landed on.
 *
 * <p>A hand-run probe and not a test: it homes the machine, runs a full leveling pass and nudges the
 * Z offset, has no assertions, and must never be part of {@code check}. {@link SettingsProbe} is the
 * model for the shape - a battery, then controls that make an unobservable change observable.
 *
 * <p>Usage: {@code BedLevelingProbe [host] [port]}, defaulting to {@code localhost 8099}.
 *
 * <h2>The three questions this exists to answer</h2>
 *
 * <ol>
 *   <li><b>Which {@code Cap:} lines report leveling, and do any of them name <em>which</em> of
 *       Marlin's five mutually exclusive {@code G29} forms is built?</b> The whole {@code Cap:}
 *       roster is printed, and the leveling-relevant ones lifted out, so a re-run can answer this
 *       itself rather than trusting the summary below.</li>
 *   <li><b>What does a {@code G29} reply look like on the wire</b> - per-point progress, a parseable
 *       mesh, or a bare {@code ok} after a long silence? Timed, because how long the {@code ok}
 *       takes is what decides whether the send window can carry it.</li>
 *   <li><b>What does {@code M290} require and what does it answer?</b> Measured un-homed first (the
 *       control), then homed.</li>
 * </ol>
 *
 * <h2>Why {@code M420 V1 T1} is in the battery three times</h2>
 *
 * <p>If no {@code Cap:} line names the variant, the fallback has to be a command whose <em>output
 * shape</em> differs per variant, and in Marlin's {@code M420.cpp} / {@code bedlevel.cpp} that is
 * the verbose leveling report: {@code MESH_BED_LEVELING} prints a mesh grid, bilinear prints
 * {@code Bilinear Leveling Grid:}, linear and 3-point print a
 * {@code Bed Level Correction Matrix:} plane, and UBL prints its own version banner and a
 * valid/invalid verdict. So it is read before {@code G29} (what the variant is), after {@code G29}
 * (what a completed pass leaves behind), and after a babystep (whether a nudge shows up in it).
 *
 * <h2>UNMEASURED - what this simulator cannot show</h2>
 *
 * <ul>
 *   <li><b>Four of the five {@code G29} variants.</b> A build has exactly one, so one run of this
 *       probe measures one variant. Which one MarlinSim has is printed; the other four are inference
 *       from Marlin's source until somebody runs this against a machine that has them.</li>
 *   <li><b>A firmware with no leveling at all.</b> Whatever MarlinSim reports, the "capability
 *       absent" path cannot be captured here - same limitation {@code MachineProfile.hasEeprom()}
 *       records for {@code Cap:EEPROM}.</li>
 *   <li><b>Manual leveling's move-by-move prompting.</b> Only reachable on a
 *       {@code PROBE_MANUALLY}/{@code MESH_BED_LEVELING} build; if this run reports a probe
 *       ({@code Cap:Z_PROBE:1}) then the unattended path is the only one measured.</li>
 * </ul>
 */
public final class BedLevelingProbe {

    private BedLevelingProbe() {
    }

    /** Lines that were {@code M155} temperature auto-reports, counted rather than printed. */
    private static int suppressed;

    /** Every {@code Cap:} line of the run, name to value, in the order the firmware wrote them. */
    private static final Map<String, String> caps = new LinkedHashMap<>();

    /** The leveling surface's capabilities - the ones this feature's gate could be built on. */
    private static final List<String> LEVELING_CAPS = List.of(
            "AUTOLEVEL", "LEVELING_DATA", "Z_PROBE", "BABYSTEPPING", "MESH_REPORT",
            "SOFTWARE_POWER", "AUTOREPORT_POS", "HOST_ACTION_COMMANDS", "PROMPT_SUPPORT");

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        // One connection for the whole run: the simulator reboots when a client disconnects, so a
        // second connection would land mid-boot and measure a half-started machine.
        try (var socket = new Socket(host, port)) {
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            System.out.println("=== banner, and whatever the previous session left queued ===");
            socket.setSoTimeout(1500);
            for (var line : drainUntilQuiet(in)) report(line);
            System.out.println("  (" + suppressed + " temperature auto-report lines suppressed)");

            socket.setSoTimeout(12000);
            send(out, in, "M155 S0 - stop the auto-reports", "M155 S0");
            socket.setSoTimeout(1500);
            var trailing = drainUntilQuiet(in);
            if (!trailing.isEmpty()) {
                System.out.println("  -- still arriving after M155 S0 --");
                for (var line : trailing) report(line);
            }
            socket.setSoTimeout(12000);

            capabilities(out, in);
            unhomedControls(out, in);
            levelingState(out, in);
            levelingPass(out, in);
            babystepControls(out, in);

            System.out.println();
            System.out.println("total temperature auto-report lines suppressed: " + suppressed);
        }
    }

    // ----- question 1: the capability roster -------------------------------------------------

    /**
     * {@code M115}, with every {@code Cap:} line kept and the leveling-relevant ones lifted out.
     * This block <em>is</em> the evidence for the capability gate, so it prints the whole roster and
     * its size, letting a re-run contradict the summary in this class's javadoc.
     */
    private static void capabilities(OutputStream out, BufferedReader in) {
        System.out.println();
        System.out.println("##### QUESTION 1: which Cap: lines report leveling? #####");

        var lines = send(out, in, "M115 firmware info", "M115");
        for (var line : lines) {
            if (!line.startsWith("Cap:")) continue;
            var body = line.substring(4);
            var colon = body.lastIndexOf(':');
            if (colon < 0) continue;
            caps.put(body.substring(0, colon), body.substring(colon + 1).trim());
        }

        System.out.println();
        System.out.printf("  --- %d Cap: lines in total ---%n", caps.size());
        System.out.println("  --- the ones a leveling gate could be built on ---");
        for (var name : LEVELING_CAPS) {
            var value = caps.get(name);
            System.out.printf("  Cap:%-22s %s%n", name, value == null ? "(ABSENT from the roster)" : value);
        }

        System.out.println();
        System.out.println("  --- does ANY Cap: line name a G29 variant? (UBL/BILINEAR/LINEAR/3POINT/MANUAL) ---");
        var named = new ArrayList<String>();
        for (var entry : caps.entrySet()) {
            var n = entry.getKey().toUpperCase();
            if (n.contains("UBL") || n.contains("UNIFIED") || n.contains("BILINEAR")
                    || n.contains("LINEAR") || n.contains("3POINT") || n.contains("THREE")
                    || n.contains("MANUAL") || n.contains("MESH")) {
                named.add(entry.getKey() + ":" + entry.getValue());
            }
        }
        System.out.println(named.isEmpty()
                ? "  NO Cap: line names a variant - the gate can only say 'some leveling exists'"
                : "  candidates: " + named);

        System.out.println();
        System.out.println("  --- the full roster, for a re-run to diff ---");
        caps.forEach((name, value) -> System.out.printf("    Cap:%-26s %s%n", name, value));
    }

    // ----- the control: what do these commands do on an un-homed machine? --------------------

    /**
     * Both commands before {@code G28}, which is the control for "what does the operator see when
     * the machine is not ready": a UI that offers a leveling button has to know whether the firmware
     * refuses, silently auto-homes, or accepts and produces nonsense.
     */
    private static void unhomedControls(OutputStream out, BufferedReader in) {
        System.out.println();
        System.out.println("##### CONTROL: M290 and G29 on an un-homed machine #####");
        System.out.println("# Sent BEFORE G28 on purpose. Whether the firmware refuses, auto-homes or");
        System.out.println("# accepts decides whether the tab needs a homed-state gate of its own.");

        send(out, in, "C1. M290 Z0.01 - babystep before homing", enc(new Babystep(null, null, new BigDecimal("0.01"), null, null)));
        send(out, in, "C2. M114 - where does the firmware think it is?", "M114");
    }

    // ----- question 1 fallback + question 2 pre-state ----------------------------------------

    /** {@code M420} in its reporting forms, which is the variant fallback if no {@code Cap:} names one. */
    private static void levelingState(OutputStream out, BufferedReader in) {
        System.out.println();
        System.out.println("##### QUESTION 1 (fallback): M420's report, whose SHAPE names the variant #####");

        var bare = send(out, in, "L1. M420 bare - leveling state", enc(new BedLevelingState()));
        var v = send(out, in, "L2. M420 V1 - verbose report", "M420 V1");
        var vt = send(out, in, "L3. M420 V1 T1 - verbose + topography [RAW: no class produces T1]", "M420 V1 T1");
        send(out, in, "L4. M851 - probe Z offset (was 'Unknown command' in SettingsProbe)", enc(new XYZProbeOffset()));

        System.out.println();
        System.out.println("  --- M420's report shapes, which is how the variant is told apart ---");
        System.out.printf("  M420        %3d lines%n", bare.size());
        System.out.printf("  M420 V1     %3d lines%n", v.size());
        System.out.printf("  M420 V1 T1  %3d lines%n", vt.size());
        System.out.println("  variant marker in M420 V1 T1: " + variantOf(vt));
    }

    /**
     * The marker Marlin's own verbose leveling report prints, which differs per build - see this
     * class's javadoc. Returns the matched wording so a reader can see the bytes, not a verdict.
     */
    private static String variantOf(List<String> lines) {
        for (var line : lines) {
            var l = line.toLowerCase();
            if (l.contains("unified bed leveling")) return "UBL -> " + line.trim();
            if (l.contains("bilinear leveling grid")) return "BILINEAR -> " + line.trim();
            if (l.contains("bed level correction matrix")) return "LINEAR or 3POINT -> " + line.trim();
            if (l.contains("mesh bed leveling")) return "MANUAL/MESH_BED_LEVELING -> " + line.trim();
        }
        return "(no variant marker in this report)";
    }

    // ----- question 2: what a G29 pass looks like, and how long it takes ---------------------

    /**
     * {@code G28} then {@code G29}, both timed, with {@code M420 V1 T1} on either side of the
     * {@code G29} so a completed pass is observable - the leveling itself may announce nothing
     * useful, and then the before/after report is the only evidence it did anything.
     */
    private static void levelingPass(OutputStream out, BufferedReader in) throws Exception {
        System.out.println();
        System.out.println("##### QUESTION 2: a G29 pass - its reply, and how long the ok takes #####");

        // Homing and leveling both move the machine and can take far longer than a command reply:
        // the window has to cover the mechanical time, not the serial time.
        var before = send(out, in, "P1. M420 V1 T1 - before anything", "M420 V1 T1");

        timed(out, in, "P2. G28 - home all axes", enc(new AutoHome()), 180_000);
        timed(out, in, "P3. G29 - a full leveling pass", enc(new BedLeveling()), 600_000);

        var after = send(out, in, "P4. M420 V1 T1 - after the pass", "M420 V1 T1");
        send(out, in, "P5. M420 S1 - enable leveling", "M420 S1");
        var enabled = send(out, in, "P6. M420 bare - state with leveling on", enc(new BedLevelingState()));

        System.out.println();
        System.out.println("  --- did the pass change the held mesh? ---");
        System.out.printf("  before  %3d lines, variant marker: %s%n", before.size(), variantOf(before));
        System.out.printf("  after   %3d lines, variant marker: %s%n", after.size(), variantOf(after));
        System.out.println("  identical: " + (before.equals(after) ? "YES - the pass left no trace in the report"
                : "no - the report differs, so the pass is observable"));
        System.out.println("  M420 bare after S1: " + (enabled.isEmpty() ? "(nothing)" : enabled.get(0).trim()));
    }

    // ----- question 3: M290 ------------------------------------------------------------------

    /**
     * {@code M290} on a homed machine, with a bare {@code M290} and an {@code M851} on either side:
     * a nudge may announce nothing, and then the only way to see it took effect is the total that
     * some other command reports.
     */
    private static void babystepControls(OutputStream out, BufferedReader in) {
        System.out.println();
        System.out.println("##### QUESTION 3: M290 - what it requires and what it answers #####");

        var beforePos = send(out, in, "B1. M114 - position before any nudge", "M114");
        var bare = send(out, in, "B2. M290 bare - is it a query?", enc(new Babystep()));
        send(out, in, "B3. M290 Z0.05 - nudge up", enc(new Babystep(null, null, new BigDecimal("0.05"), null, null)));
        var afterUp = send(out, in, "B4. M114 - position after the nudge", "M114");
        send(out, in, "B5. M290 Z-0.05 - nudge back", enc(new Babystep(null, null, new BigDecimal("-0.05"), null, null)));
        var afterBack = send(out, in, "B6. M114 - position after the undo", "M114");

        // Babystep's first parameter is named `pos` and writes `X` - a generator artifact, the same
        // one SettingsProbe records for SetAxisStepsPerUnit: commands.json documents every axis word
        // as `<pos>` and the generator names the first parameter after its value, not its letter.
        send(out, in, "B7. M290 X0.1 - does this build take X? (BABYSTEP_XY)",
                enc(new Babystep(new BigDecimal("0.1"), null, null, null, null)));
        send(out, in, "B8. M290 Y0.1 - and Y?",
                enc(new Babystep(null, new BigDecimal("0.1"), null, null, null)));
        send(out, in, "B9. M290 X-0.1 - undo X",
                enc(new Babystep(new BigDecimal("-0.1"), null, null, null, null)));
        send(out, in, "B10. M290 Y-0.1 - undo Y",
                enc(new Babystep(null, new BigDecimal("-0.1"), null, null, null)));

        send(out, in, "B11. M290 S0.02 - the S form (same as Z)", enc(new Babystep(null, null, null, new BigDecimal("0.02"), null)));
        send(out, in, "B12. M290 Z-0.02 P0 - nudge with probe-offset update suppressed",
                enc(new Babystep(null, null, new BigDecimal("-0.02"), null, false)));

        System.out.println();
        System.out.println("  --- control: does a babystep show up anywhere a host can read? ---");
        System.out.printf("  M290 bare answered %d line(s): %s%n", bare.size(), bare.isEmpty() ? "(nothing)" : bare.get(0).trim());
        System.out.printf("  M114 before    %s%n", pick(beforePos, "X:"));
        System.out.printf("  M114 after up  %s%n", pick(afterUp, "X:"));
        System.out.printf("  M114 after back %s%n", pick(afterBack, "X:"));
        System.out.println("  --- if all three M114s match, M290 is unobservable from M114 alone ---");
    }

    // ----- plumbing --------------------------------------------------------------------------

    /** As {@link #send}, but with its own read window and the elapsed time printed - the point of a
     * timed step is that the wall clock to the {@code ok} is the measurement. */
    private static List<String> timed(OutputStream out, BufferedReader in, String label, String wire,
                                      long budgetMs) throws Exception {
        System.out.println();
        System.out.println("=== " + label + " ===");
        System.out.println("  >> " + wire);
        var started = System.nanoTime();
        try {
            out.write((wire + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception failed) {
            System.out.println("  !! write failed: " + failed);
            return List.of();
        }
        var replies = drain(in, budgetMs, 4000, BedLevelingProbe::endsAnAnswer);
        var elapsed = (System.nanoTime() - started) / 1_000_000L;
        for (var line : replies) report(line);
        var acked = !replies.isEmpty() && endsAnAnswer(replies.get(replies.size() - 1));
        System.out.printf("  ** %d line(s) in %d ms; ended with an ok: %s%n", replies.size(), elapsed, acked);
        if (!acked) {
            System.out.println("  !! NO ok inside " + budgetMs + "ms - the send window would still be waiting");
        }
        return replies;
    }

    /** Sends one line, prints it, and prints every line that came back with what the module made of it. */
    private static List<String> send(OutputStream out, BufferedReader in, String label, String wire) {
        System.out.println();
        System.out.println("=== " + label + " ===");
        System.out.println("  >> " + wire);
        try {
            out.write((wire + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception failed) {
            System.out.println("  !! write failed: " + failed);
            return List.of();
        }
        var replies = drain(in, 20_000, 1000, BedLevelingProbe::endsAnAnswer);
        if (replies.isEmpty()) System.out.println("  !! no reply within the read window");
        for (var line : replies) report(line);
        return replies;
    }

    private static List<String> drainUntilQuiet(BufferedReader in) {
        return drain(in, 6_000, 2000, line -> false);
    }

    /**
     * Reads until {@code isEnd}, until the far end goes quiet, until {@code cap} kept lines, or
     * until {@code budgetMs} of wall clock - whichever comes first. The wall-clock budget is the one
     * bound a flooding far end cannot outrun; see {@link SettingsProbe#drain} and issue #92.
     */
    private static List<String> drain(BufferedReader in, long budgetMs, int cap, Predicate<String> isEnd) {
        var lines = new ArrayList<String>();
        var deadline = System.nanoTime() + budgetMs * 1_000_000L;
        try {
            String line;
            while (System.nanoTime() < deadline && (line = in.readLine()) != null) {
                if (isAutoReport(line)) {
                    suppressed++;
                    continue;
                }
                lines.add(line);
                if (isEnd.test(line)) break;
                if (lines.size() > cap) break;
            }
        } catch (Exception quiet) {
            // the read window elapsed: for a drain-until-quiet that is how it ends, and for a
            // drain-until-ok it means there is no answer, or it is slower than the window
        }
        return lines;
    }

    private static boolean endsAnAnswer(String line) {
        var trimmed = line.trim();
        return trimmed.length() >= 2 && trimmed.regionMatches(true, 0, "ok", 0, 2);
    }

    private static boolean isAutoReport(String line) {
        return MarlinRsDecoder.INSTANCE.decode(line) instanceof BareTemperatureRs;
    }

    /** Prints one received line and what this module decodes it to, {@code NONE} included. */
    private static void report(String line) {
        GRs<?> marlin = MarlinRsDecoder.INSTANCE.decode(line);
        GRs<?> base = marlin == null ? BaseRsDecoder.INSTANCE.decode(line) : null;
        var decoded = marlin != null ? marlin : base;
        var who = marlin != null ? "marlin" : base != null ? "base" : "NONE";
        var shown = line.length() > 72 ? line.substring(0, 69) + "..." : line;
        System.out.printf("  << %-72s [%-6s] %s%n", shown, who, decoded == null ? "" : decoded);
    }

    private static String enc(GRq<?> rq) {
        return GEncoder.INSTANCE.encode(rq.encode());
    }

    private static String pick(List<String> lines, String needle) {
        for (var line : lines) if (line.contains(needle)) return line;
        return "(no line containing '" + needle + "')";
    }
}

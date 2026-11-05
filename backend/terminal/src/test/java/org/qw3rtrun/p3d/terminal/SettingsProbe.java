package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.marlin.command.FactoryReset;
import org.qw3rtrun.p3d.g.marlin.command.PrintTravelMoveLimits;
import org.qw3rtrun.p3d.g.marlin.command.ReportSettings;
import org.qw3rtrun.p3d.g.marlin.command.RestoreSettings;
import org.qw3rtrun.p3d.g.marlin.command.SaveSettings;
import org.qw3rtrun.p3d.g.marlin.command.SetAdvancedSettings;
import org.qw3rtrun.p3d.g.marlin.command.SetAxisStepsPerUnit;
import org.qw3rtrun.p3d.g.marlin.command.SetHomeOffsets;
import org.qw3rtrun.p3d.g.marlin.command.SetMaxFeedrate;
import org.qw3rtrun.p3d.g.marlin.command.SetStartingAcceleration;
import org.qw3rtrun.p3d.g.marlin.command.ValidateEEPROMContents;
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
import java.util.List;

/**
 * Measures what Marlin's EEPROM settings commands - {@code M500}-{@code M504} and the bare
 * single-setting queries behind them - actually answer, over a plain socket, decoding every
 * received line through this module's own {@link MarlinRsDecoder}.
 *
 * <p>A hand-run probe and not a test: it writes to the machine's live memory and - once - to its
 * EEPROM, and has no assertions, so it must never be part of {@code check}. It exists because
 * issue #88 was designed from an ad-hoc measurement that lived in a scratch directory, and a fact
 * the feature rests on has to be re-runnable from the repository. {@link GCodeLiveProbe} is the
 * model for the shape; this one narrows to the settings surface and adds the before/after controls
 * that make the measurement mean something.
 *
 * <p>Usage: {@code SettingsProbe [host] [port]}, defaulting to {@code localhost 8099}.
 *
 * <h2>The firmware this was captured against</h2>
 *
 * <pre>
 * FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56)
 * SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0
 * MACHINE_TYPE:MarlinSim KINEMATICS:Cartesian EXTRUDER_COUNT:1
 * </pre>
 *
 * <h2>What the reference run measured, to compare a re-run against</h2>
 *
 * <table>
 *   <caption>Captured 2026-11-04 against MarlinSim on {@code localhost:8099}</caption>
 *   <tr><th>sent</th><th>answered</th></tr>
 *   <tr><td>{@code M500}</td><td>{@code //action:notification Settings Stored} + {@code ok}</td></tr>
 *   <tr><td>{@code M501}</td><td>a bare {@code ok}, and nothing else</td></tr>
 *   <tr><td>{@code M502}</td><td>a bare {@code ok}, and nothing else</td></tr>
 *   <tr><td>{@code M503}</td><td>24 {@code echo:} lines + {@code ok}</td></tr>
 *   <tr><td>{@code M503 S0}</td><td>13 unprefixed, two-space-indented lines + {@code ok}</td></tr>
 *   <tr><td>{@code M504}</td><td>{@code echo:EEPROM OK} + {@code ok}</td></tr>
 * </table>
 *
 * <p>Two numbers in that table did not reproduce exactly, recorded rather than reconciled. This
 * probe's first run counted <strong>25</strong> {@code echo:} lines from a bare {@code M503}, not
 * 24: twelve {@code ; <section>:} headers and thirteen value lines. The thirteen match
 * {@code M503 S0} exactly (the reference run's own figure), so the likely reading is that 24 counted
 * one value line per section and {@code Material heatup parameters} has two
 * ({@code M145 S0} and {@code M145 S1}). Everything else in the table reproduced verbatim, including
 * {@code M500}'s {@code //action:notification} and the bare {@code ok}s.
 *
 * <p>The second is the backlog: the reference run was met by 601 {@code M155} auto-reports, this one
 * by <strong>none at all</strong> - no boot banner either, on two consecutive runs. So the flood is
 * a property of what the previous client left running and not of the simulator, and a re-run that
 * prints {@code 0 temperature auto-report lines suppressed} has not lost anything.
 *
 * <h2>Why there are three controls and not just a transcript</h2>
 *
 * <p>{@code M501} and {@code M502} both answer a bare {@code ok} and announce nothing about what
 * they changed - "it worked" is unobservable from the reply, so the only way to see that the two are
 * different operations is to bracket each with an {@code M503} and read the values on either side.
 * Each control is printed as a labelled before/after pair with the differing line lifted out at the
 * end of the section.
 *
 * <ul>
 *   <li><b>Control A</b> - {@code M503}/{@code M502}/{@code M503}/{@code M501}/{@code M503}:
 *       the EEPROM values, the firmware defaults, and the EEPROM values again.</li>
 *   <li><b>Control B</b> - {@code M92}/{@code M92 X81}/{@code M92}/{@code M501}/{@code M92}: a
 *       live set takes effect and {@code M501} undoes it. The firmware reformats {@code X81} to
 *       {@code X81.00}, so the echo is not the request's own bytes.</li>
 *   <li><b>Control C</b> - {@code M503} against {@code M503 S0}: the report has two output shapes,
 *       and only an adjacent print makes that visible.</li>
 * </ul>
 *
 * <h2>Where the temperature lines went</h2>
 *
 * <p>A fresh connection can carry a backlog of {@code M155} auto-reports a previous session left
 * running - the reference run was greeted by 601 of them before a single command was sent. So this
 * probe sends {@code M155 S0} first to stop them, drains whatever was already queued, and suppresses
 * every line that decodes to a {@link BareTemperatureRs} from then on, printing a count instead. The
 * filter is the module's own decoder, not a text match - an {@code ok T:...} is an acknowledgement
 * and still prints. A reader comparing this to the reference run should expect the 600 lines to be a
 * single counted line here.
 *
 * <h2>This probe restores the machine before it exits</h2>
 *
 * <p>The run writes to live memory and {@code M500} writes to the EEPROM, so the last command sent
 * is an {@code M501}, and the battery's {@code M502} is followed immediately by one too - without
 * that, the second run would start from the firmware defaults and Control A would compare two
 * identical reports. {@code M500} is deliberately sent <em>before</em> the first {@code M502}, so it
 * never persists the defaults.
 *
 * <h2>UNMEASURED - what this simulator cannot show</h2>
 *
 * <ul>
 *   <li><b>A firmware built without {@code EEPROM_SETTINGS}.</b> MarlinSim always has it
 *       ({@code Cap:EEPROM:1}), so the "no EEPROM at all" answers to {@code M500}/{@code M501}
 *       cannot be captured here.</li>
 *   <li><b>A firmware built with {@code DISABLE_M503}.</b> Not configurable on this build, so the
 *       shape of a refused {@code M503} is unknown.</li>
 *   <li><b>No {@code Cap:} line names {@code M503} under any spelling.</b> Verified against the
 *       simulator's 38 {@code Cap:} lines and against Marlin's {@code M115.cpp} on {@code 2.1.x}, so
 *       a farm cannot learn from the capability roster whether a printer will answer {@code M503} -
 *       it has to learn it from {@code echo:Unknown command: "M503"} coming back instead. That is
 *       why {@code M115} is first in the battery: the roster printed <em>is</em> the evidence, and a
 *       re-run can count the 38 lines itself.</li>
 *   <li><b>{@code M851} (probe offset) and {@code M304} (bed PID) answer
 *       {@code echo:Unknown command:} on this build</b>, so neither is in the battery - a probe that
 *       only ever prints "unknown" teaches a reader to skim the column that matters. Re-verified on
 *       2026-11-04 over a bare socket: {@code echo:Unknown command: "M851"} then {@code ok P15 B3},
 *       and the same for {@code M304}.</li>
 * </ul>
 */
public final class SettingsProbe {

    private SettingsProbe() {
    }

    /** Lines the firmware sent that were temperature auto-reports, suppressed from the output. */
    private static int suppressed;

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

            // Stop the auto-reports before anything is measured, then drain the ones already sent.
            socket.setSoTimeout(12000);
            send(out, in, "M155 S0 - stop the auto-reports", enc(MarlinG.INSTANCE.m155(0)));
            socket.setSoTimeout(1500);
            var trailing = drainUntilQuiet(in);
            if (!trailing.isEmpty()) {
                System.out.println("  -- still arriving after M155 S0 --");
                for (var line : trailing) report(line);
            }
            socket.setSoTimeout(12000);

            battery(out, in);
            controlA(out, in);
            controlB(out, in);
            controlC(out, in);

            System.out.println();
            System.out.println("=== restore: the machine goes back to its EEPROM values ===");
            send(out, in, "M501 restore", enc(new RestoreSettings()));

            System.out.println();
            System.out.println("total temperature auto-report lines suppressed: " + suppressed);
        }
    }

    // ----- the plain battery ---------------------------------------------------------------

    /** Every settings command once, in the order issue #88 names them, built through its generated
     * command class and rendered by {@link GEncoder} so the probe also exercises the encoder.
     * {@code M503 S0} is the exception, sent as a raw string, since
     * {@code ReportSettings(s = true)} encodes {@code M503 S} with no way to express {@code S0}. */
    private static void battery(OutputStream out, BufferedReader in) {
        System.out.println();
        System.out.println("##### battery: each settings command once #####");

        send(out, in, "M115 firmware info", enc(MarlinG.INSTANCE.m115()));
        send(out, in, "M503 report settings", enc(new ReportSettings()));
        send(out, in, "M503 S0 report settings, unprefixed shape [RAW STRING: no class produces S0]", "M503 S0");
        send(out, in, "M503 S1 report settings", "M503 S1");
        send(out, in, "M504 validate EEPROM contents", enc(new ValidateEEPROMContents()));
        // M500 goes out before any M502, so it can only ever persist the values already loaded.
        send(out, in, "M500 save settings", enc(new SaveSettings()));
        send(out, in, "M501 restore settings", enc(new RestoreSettings()));
        send(out, in, "M502 factory reset", enc(new FactoryReset()));
        send(out, in, "M501 restore - undoes the M502 above, so the controls start from the EEPROM",
                enc(new RestoreSettings()));

        send(out, in, "M92 bare - steps per unit", enc(new SetAxisStepsPerUnit()));
        send(out, in, "M203 bare - max feedrate", enc(new SetMaxFeedrate()));
        send(out, in, "M201 bare - max acceleration", enc(new PrintTravelMoveLimits()));
        send(out, in, "M204 bare - starting acceleration", enc(new SetStartingAcceleration()));
        send(out, in, "M205 bare - advanced settings", enc(new SetAdvancedSettings()));
        send(out, in, "M206 bare - home offsets", enc(new SetHomeOffsets()));
    }

    // ----- control A: M501 and M502 are different operations --------------------------------

    private static void controlA(OutputStream out, BufferedReader in) {
        System.out.println();
        System.out.println("##### CONTROL A: M501 and M502 are different operations #####");
        System.out.println("# Both answer a bare ok and announce nothing. The M503 on either side is the only");
        System.out.println("# way to see that one loaded the EEPROM and the other loaded the firmware defaults.");

        var fromEeprom = send(out, in, "A1. M503 - before anything", enc(new ReportSettings()));
        send(out, in, "A2. M502 - factory reset", enc(new FactoryReset()));
        var afterReset = send(out, in, "A3. M503 - after the factory reset", enc(new ReportSettings()));
        send(out, in, "A4. M501 - restore from the EEPROM", enc(new RestoreSettings()));
        var afterRestore = send(out, in, "A5. M503 - after the restore", enc(new ReportSettings()));

        System.out.println();
        System.out.println("  --- control A, the same M104 line from each of the three M503s ---");
        System.out.printf("  A1 before M502  %s%n", pick(fromEeprom, " M104 "));
        System.out.printf("  A3 after  M502  %s%n", pick(afterReset, " M104 "));
        System.out.printf("  A5 after  M501  %s%n", pick(afterRestore, " M104 "));
        System.out.println("  --- A1 == A5 and A3 differs is what makes M501 and M502 distinguishable ---");
    }

    // ----- control B: a live set takes effect, and M501 undoes it ---------------------------

    private static void controlB(OutputStream out, BufferedReader in) {
        System.out.println();
        System.out.println("##### CONTROL B: a live set takes effect, and M501 undoes it #####");

        var before = send(out, in, "B1. M92 - the current steps per unit", enc(new SetAxisStepsPerUnit()));
        // SetAxisStepsPerUnit's first parameter is named `steps` and writes `X` - a generator
        // artifact: commands.json documents every axis word as `<steps>` and the generator names the
        // first parameter after its value, not its letter, so `steps` is X here.
        send(out, in, "B2. M92 X81 - set X, built through SetAxisStepsPerUnit(steps = 81)",
                enc(new SetAxisStepsPerUnit(new BigDecimal("81"), null, null, null, null, null,
                        null, null, null, null, null)));
        var afterSet = send(out, in, "B3. M92 - after the set", enc(new SetAxisStepsPerUnit()));
        send(out, in, "B4. M501 - restore from the EEPROM", enc(new RestoreSettings()));
        var afterRestore = send(out, in, "B5. M92 - after the restore", enc(new SetAxisStepsPerUnit()));

        System.out.println();
        System.out.println("  --- control B, the M92 line at each step ---");
        System.out.printf("  B1 before the set  %s%n", pick(before, "M92 "));
        System.out.printf("  B3 after  M92 X81  %s%n", pick(afterSet, "M92 "));
        System.out.printf("  B5 after  M501     %s%n", pick(afterRestore, "M92 "));
        System.out.println("  --- the firmware reformats X81 to X81.00, so the echo is not the bytes sent ---");
    }

    // ----- control C: M503's two output shapes ----------------------------------------------

    private static void controlC(OutputStream out, BufferedReader in) {
        System.out.println();
        System.out.println("##### CONTROL C: M503's two output shapes, printed adjacently #####");

        var bare = send(out, in, "C1. M503 - the prefixed shape", enc(new ReportSettings()));
        var s0 = send(out, in, "C2. M503 S0 - the unprefixed shape [RAW STRING]", "M503 S0");
        var s1 = send(out, in, "C3. M503 S1", "M503 S1");
        var sFlag = send(out, in, "C4. M503 S - what ReportSettings(s = true) encodes",
                enc(new ReportSettings(true, false)));

        System.out.println();
        System.out.println("  --- control C, shape by shape ---");
        System.out.printf("  M503      %3d lines, first value line: %s%n", bare.size(), firstIndented(bare));
        System.out.printf("  M503 S0   %3d lines, first value line: %s%n", s0.size(), firstIndented(s0));
        System.out.printf("  M503 S1   %3d lines, identical to bare M503: %s%n", s1.size(), same(bare, s1));
        System.out.printf("  M503 S    %3d lines, identical to bare M503: %s%n", sFlag.size(), same(bare, sFlag));
        System.out.printf("  headers (`; <section>:`) in M503: %d, in M503 S0: %d%n",
                countHeaders(bare), countHeaders(s0));
    }

    // ----- plumbing --------------------------------------------------------------------------

    /**
     * Sends one line, prints it, and prints every line that came back with what the module made of
     * it. Returns the received lines minus the suppressed temperature reports.
     */
    private static List<String> send(OutputStream out, BufferedReader in,
                                     String label, String wire) {
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
        var replies = drainUntilOk(in);
        if (replies.isEmpty()) System.out.println("  !! no reply within the read window");
        for (var line : replies) report(line);
        return replies;
    }

    /** Everything readable up to and including the {@code ok} that ends the answer, with temperature
     * auto-reports counted out. Marlin does not frame its replies, so the acknowledgement is the
     * only end marker - a fixed timeout instead would mis-attribute a slow answer to the next label. */
    private static List<String> drainUntilOk(BufferedReader in) {
        var lines = new ArrayList<String>();
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (isAutoReport(line)) {
                    suppressed++;
                    continue;
                }
                lines.add(line);
                var trimmed = line.trim();
                if (trimmed.length() >= 2 && trimmed.regionMatches(true, 0, "ok", 0, 2)) break;
                if (lines.size() > 1000) break;
            }
        } catch (Exception quiet) {
            // a read timeout: either there is no answer, or it is slower than the window
        }
        return lines;
    }

    /**
     * Everything readable until the far end goes quiet. Used for the banner and for a backlog,
     * neither of which is terminated by an {@code ok} of its own.
     */
    private static List<String> drainUntilQuiet(BufferedReader in) {
        var lines = new ArrayList<String>();
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (isAutoReport(line)) {
                    suppressed++;
                    continue;
                }
                lines.add(line);
                if (lines.size() > 2000) break;
            }
        } catch (Exception quiet) {
            // the read window elapsed, which is how a drain-until-quiet ends
        }
        return lines;
    }

    /**
     * A temperature report on a line of its own - an {@code M155} auto-report. Decided by the
     * module's own decoder rather than by a text match, and {@code ok T:...} is deliberately not
     * one of these: that is an acknowledgement and belongs in the transcript.
     */
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

    /** The first received line containing {@code needle}, for the side-by-side summaries. */
    private static String pick(List<String> lines, String needle) {
        for (var line : lines) if (line.contains(needle)) return line;
        return "(no line containing '" + needle + "')";
    }

    /** The first line that carries the two-space indent an {@code M503} value line has. */
    private static String firstIndented(List<String> lines) {
        for (var line : lines) {
            var body = line.startsWith("echo:") ? line.substring(5) : line;
            if (body.startsWith("  ") && !body.trim().isEmpty() && !body.trim().startsWith(";")) return line;
        }
        return "(none)";
    }

    private static int countHeaders(List<String> lines) {
        var n = 0;
        for (var line : lines) {
            var body = line.startsWith("echo:") ? line.substring(5) : line;
            if (body.trim().startsWith(";")) n++;
        }
        return n;
    }

    private static String same(List<String> a, List<String> b) {
        return a.equals(b) ? "yes" : "NO - they differ";
    }
}

package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.code.core.block.XorCheckSum;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.marlin.command.AutoHome;
import org.qw3rtrun.p3d.g.marlin.command.EndstopStates;
import org.qw3rtrun.p3d.g.marlin.command.FinishMoves;
import org.qw3rtrun.p3d.g.marlin.command.GetCurrentPosition;
import org.qw3rtrun.p3d.g.marlin.command.LinearMoveG1;
import org.qw3rtrun.p3d.g.marlin.command.ReportPrintTime;
import org.qw3rtrun.p3d.g.marlin.command.ReportSDPrintStatus;
import org.qw3rtrun.p3d.g.marlin.command.ReportSettings;
import org.qw3rtrun.p3d.g.marlin.command.SerialPrint;
import org.qw3rtrun.p3d.g.marlin.command.SetGetLineNumber;
import org.qw3rtrun.p3d.g.marlin.command.SetLCDMessage;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.protocol.BaseRsDecoder;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.g.protocol.GRs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives a running Marlin - in practice the simulator - through this repo's own encoder and reply
 * decoders, one command at a time, and prints what each answered and whether the module read it.
 *
 * <p>A hand-run probe and not a test: it needs a listener on the far end, so it must never be part
 * of {@code check}. Where {@link EmulatorTemperatureProbe} exercises the Reactor transport for one
 * command, this sweeps the command surface over a plain socket, so a failure is the protocol's and
 * not the stack's.
 *
 * <p>Usage: {@code GCodeLiveProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class GCodeLiveProbe {

    private GCodeLiveProbe() {
    }

    private record Probe(String label, String wire) {
    }

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        try (var socket = new Socket(host, port)) {
            socket.setSoTimeout(12000);
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            socket.setSoTimeout(1500);
            System.out.println("=== banner ===");
            for (var line : drain(in)) report(line);
            socket.setSoTimeout(12000);

            for (var probe : battery()) {
                System.out.println();
                System.out.println("=== " + probe.label() + " ===");
                System.out.println("  >> " + probe.wire());
                out.write((probe.wire() + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                var replies = drain(in);
                if (replies.isEmpty()) System.out.println("  !! no reply within the read window");
                for (var line : replies) report(line);
            }
        }
    }

    /**
     * Everything readable up to and including the `ok` that ends the answer.
     *
     * Marlin does not frame its replies, so the only end marker is the acknowledgement. Reading to
     * a fixed timeout instead mis-attributes a slow command's answer to the next label: `G28` takes
     * seconds, and its `ok` otherwise lands against whatever was sent after it.
     */
    private static List<String> drain(BufferedReader in) {
        var lines = new ArrayList<String>();
        try {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
                var trimmed = line.trim();
                if (trimmed.length() >= 2 && trimmed.regionMatches(true, 0, "ok", 0, 2)) break;
                if (lines.size() > 400) break;
            }
        } catch (Exception quiet) {
            // a read timeout: either there is no answer, or it is slower than the window
        }
        return lines;
    }

    /** Prints one received line and what this module decodes it to. */
    private static void report(String line) {
        GRs<?> marlin = MarlinRsDecoder.INSTANCE.decode(line);
        GRs<?> base = marlin == null ? BaseRsDecoder.INSTANCE.decode(line) : null;
        var decoded = marlin != null ? marlin : base;
        var who = marlin != null ? "marlin" : base != null ? "base" : "NONE";
        var shown = line.length() > 56 ? line.substring(0, 53) + "..." : line;
        System.out.printf("  << %-56s [%-6s] %s%n", shown, who, decoded == null ? "" : decoded);
    }

    private static String enc(GRq<?> rq) {
        return GEncoder.INSTANCE.encode(rq.encode());
    }

    private static List<Probe> battery() {
        var p = new ArrayList<Probe>();

        // Queries, each built by its generated command class and rendered by GEncoder.
        p.add(new Probe("M115 firmware info", enc(MarlinG.INSTANCE.m115())));
        p.add(new Probe("M105 temperature", enc(MarlinG.INSTANCE.m105(null))));
        p.add(new Probe("M105 T0 temperature, one tool", enc(MarlinG.INSTANCE.m105(0))));
        p.add(new Probe("M114 position", enc(new GetCurrentPosition())));
        p.add(new Probe("M114 D detailed position", enc(new GetCurrentPosition(true, false, false))));
        p.add(new Probe("M119 endstops", enc(new EndstopStates())));
        p.add(new Probe("M27 SD status", enc(new ReportSDPrintStatus())));
        p.add(new Probe("M31 print time", enc(new ReportPrintTime())));
        p.add(new Probe("M503 settings report", enc(new ReportSettings())));

        // Rest-of-line string arguments - spec 3.4a, the shape words alone cannot express.
        p.add(new Probe("M117 LCD message", enc(new SetLCDMessage("hello from :gcode"))));
        p.add(new Probe("M118 serial print", enc(new SerialPrint(null, "probe speaking"))));

        // Line numbering and checksums: the framing half of the module.
        p.add(new Probe("M110 N0 reset the counter", enc(new SetGetLineNumber(0))));
        p.add(new Probe("N1 M105 framed + checksum",
                GEncoder.INSTANCE.frame(1, MarlinG.INSTANCE.m105(null).encode(), new XorCheckSum())));
        p.add(new Probe("N2 M114 framed + checksum",
                GEncoder.INSTANCE.frame(2, new GetCurrentPosition().encode(), new XorCheckSum())));
        p.add(new Probe("N3 M105 with a bad checksum", "N3 M105*1"));
        p.add(new Probe("N3 M114 framed, after the resend",
                GEncoder.INSTANCE.frame(3, new GetCurrentPosition().encode(), new XorCheckSum())));
        p.add(new Probe("N4 M31 framed",
                GEncoder.INSTANCE.frame(4, new ReportPrintTime().encode(), new XorCheckSum())));
        p.add(new Probe("N99 M105 out of sequence",
                GEncoder.INSTANCE.frame(99, MarlinG.INSTANCE.m105(null).encode(), new XorCheckSum())));

        // Motion. Safe on the simulator; these are what a host actually sends.
        p.add(new Probe("G28 auto home", enc(new AutoHome())));
        p.add(new Probe("G1 X10 Y10 F3000", enc(new LinearMoveG1(
                new BigDecimal("10"), new BigDecimal("10"), null, null, null, null, null, null,
                null, null, new BigDecimal("3000"), null))));
        p.add(new Probe("M400 finish moves", enc(new FinishMoves())));
        p.add(new Probe("M114 position after the move", enc(new GetCurrentPosition())));

        // Error paths.
        p.add(new Probe("M998 unknown command", "M998"));
        p.add(new Probe("G998.5 unknown with a subcode", "G998.5"));

        return p;
    }
}

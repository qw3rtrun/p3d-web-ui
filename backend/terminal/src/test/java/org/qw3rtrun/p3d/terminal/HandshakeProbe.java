package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.marlin.command.GetCurrentPosition;
import org.qw3rtrun.p3d.g.marlin.command.ListSDCard;
import org.qw3rtrun.p3d.g.marlin.command.PositionAutoReport;
import org.qw3rtrun.p3d.g.marlin.command.ReportPrintTime;
import org.qw3rtrun.p3d.g.marlin.command.ReportSDPrintStatus;
import org.qw3rtrun.p3d.g.marlin.command.ReportSettings;
import org.qw3rtrun.p3d.g.marlin.command.TemperatureAutoReport;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.protocol.BaseRsDecoder;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.qw3rtrun.p3d.g.protocol.GRs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures the connect/disconnect handshake of issue #127 against a running Marlin - in practice
 * the simulator on {@code localhost:8099}. Answers four questions the design could otherwise only
 * guess at:
 *
 * <ol>
 *   <li>which {@code Cap:} lines this firmware actually advertises, printed verbatim;</li>
 *   <li>whether {@code M155 S<n>}, {@code M154 S<n>} and {@code M27 S<n>} really produce
 *       unsolicited reports, and whether {@code S0} really stops them - each measured as a count of
 *       lines arriving in a silent window, with the {@code S0} window as the control;</li>
 *   <li>what a one-shot {@code M114}/{@code M20}/{@code M27}/{@code M503}/{@code M31} answers on
 *       connect, so the sequence can be judged on what it is worth rather than on what it costs;</li>
 *   <li>what an unsupported command answers, which is what decides whether capability-gating can be
 *       replaced by "send it and let it refuse".</li>
 * </ol>
 *
 * <p>A hand-run probe and not a test: it needs a listener on the far end, so it must never be part
 * of {@code check}. It prints and does not assert - reading it is the measurement.
 *
 * <p>Two properties of the simulator shape what can be concluded. It <strong>reboots when a client
 * disconnects</strong>, so a run started immediately after another can land mid-boot and read a
 * banner as an answer; and it <strong>always has an SD card</strong>, so the no-card forms of
 * {@code M20}/{@code M27} cannot be measured here at all.
 *
 * <p>Usage: {@code HandshakeProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class HandshakeProbe {

    private HandshakeProbe() {
    }

    /** How long to sit silent counting unsolicited lines - three periods of a 1 s auto-report. */
    private static final int LISTEN_MS = 3400;

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        try (var socket = new Socket(host, port)) {
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            banner(socket, in);
            capabilities(socket, in, out);
            autoReports(socket, in, out);
            oneShotReads(socket, in, out);
            unsupported(socket, in, out);
        }
    }

    // ------------------------------------------------------------------ 1. the boot banner

    private static void banner(Socket socket, BufferedReader in) throws Exception {
        System.out.println("=== boot banner (everything the machine says before being asked) ===");
        for (var line : listen(socket, in, 2000)) System.out.println("  << " + line);
    }

    // --------------------------------------------------------- 2. what M115 advertises, verbatim

    private static void capabilities(Socket socket, BufferedReader in, OutputStream out) throws Exception {
        System.out.println();
        System.out.println("=== M115: firmware info and every Cap: line, verbatim ===");
        var replies = exchange(socket, in, out, enc(MarlinG.INSTANCE.m115()));
        for (var line : replies) System.out.println("  << " + line);

        System.out.println();
        System.out.println("  -- the four this design asks about --");
        for (var want : List.of("AUTOREPORT_TEMP", "AUTOREPORT_POS", "AUTOREPORT_POSITION",
                "AUTOREPORT_SD_STATUS", "SDCARD", "EEPROM")) {
            var found = replies.stream().filter(l -> l.contains(want + ":")).findFirst();
            System.out.printf("  %-22s %s%n", want, found.orElse("(not advertised)"));
        }
    }

    // ------------------- 3. does each auto-report actually report, and does S0 actually stop it

    private static void autoReports(Socket socket, BufferedReader in, OutputStream out) throws Exception {
        measureAutoReport(socket, in, out, "M155 temperature",
                new TemperatureAutoReport(1), new TemperatureAutoReport(0));
        measureAutoReport(socket, in, out, "M154 position",
                new PositionAutoReport(1), new PositionAutoReport(0));
        measureAutoReport(socket, in, out, "M27 SD print status",
                new ReportSDPrintStatus(1, false), new ReportSDPrintStatus(0, false));
    }

    /**
     * Turns one auto-report on, counts what arrives unasked in a silent window, turns it off and
     * counts again. The second count is the control: "it worked" means nothing without a window in
     * which nothing should arrive and nothing did.
     */
    private static void measureAutoReport(Socket socket, BufferedReader in, OutputStream out,
                                          String label, GRq<?> on, GRq<?> off) throws Exception {
        System.out.println();
        System.out.println("=== " + label + " auto-report ===");

        var ack = exchange(socket, in, out, enc(on));
        System.out.println("  ON  >> " + enc(on));
        for (var line : ack) System.out.println("      << " + line);

        var during = listen(socket, in, LISTEN_MS);
        System.out.printf("  %d unsolicited line(s) in %d ms with it on%n", during.size(), LISTEN_MS);
        for (var line : during) report(line);

        var stop = exchange(socket, in, out, enc(off));
        System.out.println("  OFF >> " + enc(off));
        for (var line : stop) System.out.println("      << " + line);

        var after = listen(socket, in, LISTEN_MS);
        System.out.printf("  CONTROL: %d unsolicited line(s) in %d ms with it off%n", after.size(), LISTEN_MS);
        for (var line : after) report(line);
    }

    // --------------------------------- 4. what a one-shot state read on connect actually answers

    private static void oneShotReads(Socket socket, BufferedReader in, OutputStream out) throws Exception {
        for (var probe : List.of(
                new Named("M114 where is the toolhead", new GetCurrentPosition()),
                new Named("M27 SD print status, one shot", new ReportSDPrintStatus()),
                new Named("M20 list the card", new ListSDCard()),
                new Named("M31 print time", new ReportPrintTime()),
                new Named("M503 settings report", new ReportSettings()),
                new Named("M105 temperature, one shot", MarlinG.INSTANCE.m105(null)))) {
            System.out.println();
            System.out.println("=== " + probe.label() + " ===");
            System.out.println("  >> " + enc(probe.rq()));
            var replies = exchange(socket, in, out, enc(probe.rq()));
            System.out.printf("  %d line(s)%n", replies.size());
            for (var line : replies) report(line);
        }
    }

    // ------------------------------------------- 5. how this firmware refuses what it cannot do

    private static void unsupported(Socket socket, BufferedReader in, OutputStream out) throws Exception {
        for (var wire : List.of("M9999", "M154 S1 Q", "M7357")) {
            System.out.println();
            System.out.println("=== unsupported: " + wire + " ===");
            for (var line : exchange(socket, in, out, wire)) report(line);
        }
    }

    // ------------------------------------------------------------------------------- plumbing

    private record Named(String label, GRq<?> rq) {
    }

    /** Writes one line and reads up to and including the {@code ok} that ends the answer. */
    private static List<String> exchange(Socket socket, BufferedReader in, OutputStream out, String wire)
            throws Exception {
        socket.setSoTimeout(8000);
        out.write((wire + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        var lines = new ArrayList<String>();
        try {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
                var trimmed = line.trim();
                if (trimmed.length() >= 2 && trimmed.regionMatches(true, 0, "ok", 0, 2)) break;
                if (lines.size() > 400) break;
            }
        } catch (SocketTimeoutException quiet) {
            lines.add("!! no ok within 8000 ms");
        }
        return lines;
    }

    /**
     * Everything that arrives unasked within {@code millis} - the measurement an auto-report is.
     * The read timeout is reset to what is left of the window on every pass, or a silent window
     * would cost the 8 s {@link #exchange} set instead of the one asked for here.
     */
    private static List<String> listen(Socket socket, BufferedReader in, int millis) throws Exception {
        var lines = new ArrayList<String>();
        var deadline = System.currentTimeMillis() + millis;
        while (true) {
            var remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                socket.setSoTimeout((int) remaining);
                String line = in.readLine();
                if (line == null) break;
                lines.add(line);
            } catch (SocketTimeoutException quiet) {
                break;
            }
        }
        return lines;
    }

    /** Prints one line and what this module decodes it to - NONE is a decoder gap worth seeing. */
    private static void report(String line) {
        GRs<?> marlin = MarlinRsDecoder.INSTANCE.decode(line);
        GRs<?> base = marlin == null ? BaseRsDecoder.INSTANCE.decode(line) : null;
        var decoded = marlin != null ? marlin : base;
        var who = marlin != null ? "marlin" : base != null ? "base" : "NONE";
        var shown = line.length() > 60 ? line.substring(0, 57) + "..." : line;
        System.out.printf("      << %-60s [%-6s] %s%n", shown, who, decoded == null ? "" : decoded);
    }

    private static String enc(GRq<?> rq) {
        return GEncoder.INSTANCE.encode(rq.encode());
    }
}

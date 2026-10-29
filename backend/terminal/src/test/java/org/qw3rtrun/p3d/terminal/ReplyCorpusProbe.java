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
import org.qw3rtrun.p3d.g.marlin.command.SetGetLineNumber;
import org.qw3rtrun.p3d.g.marlin.command.SetLCDMessage;
import org.qw3rtrun.p3d.g.protocol.GRq;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures what a printer <i>says</i> into {@code gcode/src/test/resources/marlin-replies.txt} - the
 * reply-side counterpart of {@code marlin.gcode}, which is what it was <i>told</i>.
 *
 * <p>A hand-run tool and not a test: it needs a listener on the far end, so it must never be part of
 * {@code check}. Where {@link GCodeLiveProbe} sweeps the command surface and prints what each answer
 * decoded to, and {@link GCodeCorpusProbe} replays the whole command corpus and histograms what
 * nothing claimed, this one keeps the bytes. Those two tell you how much of a conversation the module
 * understands today; this one makes the conversation re-runnable after the module changes.
 *
 * <p><b>Every received line is written exactly as it arrived</b>, in arrival order, including the
 * ones this module cannot read - those are the point. The only lines in the file this probe did not
 * receive are the {@code ;}-prefixed header it writes first, which records what was sent and when.
 * Marlin never begins a reply with {@code ;}, so the census can drop that prefix without a rule that
 * could swallow a real line; {@code //} and {@code echo:} are real reply prefixes and are kept.
 *
 * <p>The session is the one <a href="https://github.com/qw3rtrun/p3d-web-ui/issues/10">issue 10</a>
 * asks for: a connect, {@code M115}, an {@code M155}/{@code M154} auto-report run, a homing move, a
 * deliberate checksum failure, and a disconnect - plus the queries whose replies have types in this
 * module, so the corpus can tell a decoder that stopped claiming a line from one that never did.
 *
 * <p>Usage: {@code ReplyCorpusProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class ReplyCorpusProbe {

    private ReplyCorpusProbe() {
    }

    /**
     * One step of the session.
     *
     * @param label  what this step is for, written into the header
     * @param wire   the line to send
     * @param listen how long to keep reading after the answer, for a step that starts an
     *               auto-report; zero for a step whose answer ends at its {@code ok}
     */
    private record Step(String label, String wire, long listen) {

        Step(String label, String wire) {
            this(label, wire, 0);
        }
    }

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        var received = new ArrayList<String>();
        var header = new ArrayList<String>();
        var steps = session();
        var silent = new ArrayList<String>();

        header.add("; Marlin reply corpus - every line below is a received line, exactly as it arrived.");
        header.add("; Captured by backend/terminal/src/test/java/.../ReplyCorpusProbe.java");
        header.add("; " + Instant.now() + "  against " + host + ":" + port);
        header.add(";");
        header.add("; Sent, in order:");

        try (var socket = connect(host, port)) {
            var in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            // The banner the simulator writes on connect, if the handshake left any of it.
            socket.setSoTimeout(1500);
            received.addAll(drain(in, 3000));

            for (var step : steps) {
                header.add(";   " + step.wire() + "   (" + step.label() + ")");
                socket.setSoTimeout(12000);
                send(out, step.wire());

                var answer = drain(in, 20000);
                if (step.listen() > 0) answer.addAll(listen(in, socket, step.listen()));
                if (answer.isEmpty()) silent.add(step.wire());
                received.addAll(answer);

                System.out.printf("  %-34s %3d lines%n", step.wire(), answer.size());
            }
        }

        header.add(";");
        header.add("; " + received.size() + " received lines.");

        var target = resourceDir();
        var corpus = new File(target, "marlin-replies.txt");
        var text = new StringBuilder();
        for (var line : header) text.append(line).append('\n');
        for (var line : received) text.append(line).append('\n');
        Files.writeString(corpus.toPath(), text.toString(), StandardCharsets.UTF_8);

        System.out.println();
        System.out.printf("wrote %d received lines to %s%n", received.size(), corpus);
        if (!silent.isEmpty()) {
            System.out.println("no reply within the window for: " + silent);
        }
    }

    /**
     * The session, in order.
     *
     * <p>Commands that have a class in this module are built and encoded through it, the way
     * {@link GCodeLiveProbe} does, so the corpus is a record of this project talking to a printer
     * rather than of hand-typed lines. The rest are typed because the point of them is a shape no
     * command class would produce.
     */
    private static List<Step> session() {
        var s = new ArrayList<Step>();

        // Quiet the machine first. An auto-report survives a client disconnect, so without this the
        // capture opens mid-stream with reports belonging to whatever ran last - and the shape of
        // the corpus then depends on what someone else did, which is the one thing a fixture must
        // not do. Their own replies are captured deliberately further down.
        s.add(new Step("stop any temperature auto-report still running", "M155 S0"));
        s.add(new Step("stop any position auto-report still running", "M154 S0"));

        // Identity and capabilities - the M115 block, which is several lines and one ok.
        s.add(new Step("firmware info and capability report", enc(MarlinG.INSTANCE.m115())));

        // The queries whose answers have types here.
        s.add(new Step("temperature", enc(MarlinG.INSTANCE.m105(null))));
        s.add(new Step("position", enc(new GetCurrentPosition())));
        s.add(new Step("detailed position", enc(new GetCurrentPosition(true, false, false))));
        s.add(new Step("endstop states", enc(new EndstopStates())));
        s.add(new Step("SD print status", enc(new ReportSDPrintStatus())));

        // The SD surface, because issue 12 is holding a format question open on it: the RepRap wiki
        // lists `SD init fail` and Marlin 2.1.x is said to send `No SD card`. Captured rather than
        // argued - and if this machine answers neither, that is itself the answer for this firmware.
        s.add(new Step("initialise the card", "M21"));
        s.add(new Step("list the card", "M20"));
        s.add(new Step("the file the card is positioned on", "M27 C"));
        s.add(new Step("print time", enc(new ReportPrintTime())));
        s.add(new Step("settings report", enc(new ReportSettings())));
        s.add(new Step("LCD message", enc(new SetLCDMessage("corpus capture"))));

        // Auto-reports: the unbidden lines, which no request/response sweep ever sees. The ok comes
        // back at once and the reports follow on their own, so these are the steps that listen.
        s.add(new Step("temperature auto-report on", "M155 S1", 4500));
        s.add(new Step("temperature auto-report off", "M155 S0"));
        s.add(new Step("position auto-report on", "M154 S1", 3500));
        s.add(new Step("position auto-report off", "M154 S0"));

        // Motion, and the busy keepalive that comes with a move long enough to need one.
        s.add(new Step("auto home", enc(new AutoHome())));
        s.add(new Step("linear move", enc(new LinearMoveG1(
                new BigDecimal("10"), new BigDecimal("10"), null, null, null, null, null, null,
                null, null, new BigDecimal("3000"), null))));
        s.add(new Step("finish moves", enc(new FinishMoves())));
        s.add(new Step("position after the move", enc(new GetCurrentPosition())));

        // The framing half: a reset counter, two good framed lines, then a deliberate checksum
        // failure and the resend it provokes. This is the only way to capture Error:/Resend:.
        s.add(new Step("reset the line counter", enc(new SetGetLineNumber(0))));
        s.add(new Step("framed temperature", framed(1, MarlinG.INSTANCE.m105(null))));
        s.add(new Step("framed position", framed(2, new GetCurrentPosition())));
        s.add(new Step("a bad checksum, on purpose", "N3 M105*1"));
        s.add(new Step("the resend the error asked for", framed(3, new GetCurrentPosition())));
        s.add(new Step("a line number out of sequence", framed(99, MarlinG.INSTANCE.m105(null))));

        // Rejections: both of the echo lines a host must not ignore.
        s.add(new Step("unknown command", "M998"));
        s.add(new Step("unknown command with a subcode", "G998.5"));
        s.add(new Step("a tool this machine does not have", "M104 T5 S100"));
        s.add(new Step("a cold extrude, refused", "G1 E10 F100"));

        return s;
    }

    private static String enc(GRq<?> rq) {
        return GEncoder.INSTANCE.encode(rq.encode());
    }

    private static String framed(int number, GRq<?> rq) {
        return GEncoder.INSTANCE.frame(number, rq.encode(), new XorCheckSum());
    }

    private static void send(OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Everything up to and including the {@code ok} that ends the answer, or until [budget] runs out.
     *
     * <p>Marlin does not frame its replies, so the acknowledgement is the only end marker. Reading to
     * a fixed timeout instead mis-attributes a slow command's answer to the next step: {@code G28}
     * takes seconds, and its {@code ok} would otherwise land after whatever was sent next.
     *
     * <p><b>The budget is not belt-and-braces.</b> A machine with an auto-report left running from
     * an earlier session talks continuously and the {@code ok} being waited for has already gone
     * past, so every individual read succeeds inside the socket timeout and the loop never ends -
     * which is exactly how the first run of this probe hung. The socket timeout bounds one read; the
     * budget bounds the wait, and that is a different thing.
     */
    private static List<String> drain(BufferedReader in, long budget) {
        var lines = new ArrayList<String>();
        var deadline = System.currentTimeMillis() + budget;
        try {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
                var trimmed = line.trim();
                if (trimmed.length() >= 2 && trimmed.regionMatches(true, 0, "ok", 0, 2)) break;
                if (lines.size() > 600) break;
                if (System.currentTimeMillis() > deadline) break;
            }
        } catch (Exception quiet) {
            // a read timeout: either there is no answer, or it is slower than the window
        }
        return lines;
    }

    /**
     * Everything that arrives in the next [millis], unbidden - which is what an auto-report is.
     *
     * <p>There is no end marker to read to here, because nothing was asked: the firmware simply
     * talks until it is told to stop. So this is the one place a fixed window is the right read.
     */
    private static List<String> listen(BufferedReader in, Socket socket, long millis) throws Exception {
        var lines = new ArrayList<String>();
        var deadline = System.currentTimeMillis() + millis;
        socket.setSoTimeout(400);
        while (System.currentTimeMillis() < deadline) {
            try {
                var line = in.readLine();
                if (line == null) break;
                lines.add(line);
            } catch (Exception quiet) {
                // nothing in this slice of the window; keep waiting for the next report
            }
        }
        return lines;
    }

    /**
     * A socket with a printer on the other end that is ready to answer.
     *
     * <p>The simulator reboots when a client disconnects, so a connection right after a previous
     * probe lands on a machine that is still starting and drops the first write. Connecting is
     * therefore not enough: this sends an {@code M115} and waits for an answer, and tries again if
     * none comes.
     */
    private static Socket connect(String host, int port) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            Socket socket = null;
            try {
                socket = new Socket(host, port);
                socket.setSoTimeout(2500);
                var in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                drain(in, 2500);                            // the boot banner, if there is one
                send(socket.getOutputStream(), "M115");
                if (!drain(in, 2500).isEmpty()) return socket;
                throw new IllegalStateException("no answer to M115");
            } catch (Exception notReady) {
                last = notReady;
                if (socket != null) socket.close();
                System.out.printf("  attempt %d: %s, retrying%n", attempt, notReady.getMessage());
                Thread.sleep(1500);
            }
        }
        throw new IllegalStateException("no printer answering on " + host + ":" + port, last);
    }

    /** {@code gcode/src/test/resources}, found by walking up from the working directory. */
    private static File resourceDir() {
        var dir = new File(".").getAbsoluteFile();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParentFile()) {
            var candidate = new File(dir, "gcode/src/test/resources");
            if (candidate.isDirectory()) return candidate;
        }
        throw new IllegalStateException(
                "gcode/src/test/resources not found above " + new File(".").getAbsolutePath());
    }
}

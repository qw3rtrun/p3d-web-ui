package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.code.core.block.XorCheckSum;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.marlin.command.AutoHome;
import org.qw3rtrun.p3d.g.marlin.command.DeleteSDFile;
import org.qw3rtrun.p3d.g.marlin.command.EndstopStates;
import org.qw3rtrun.p3d.g.marlin.command.FinishMoves;
import org.qw3rtrun.p3d.g.marlin.command.GetCurrentPosition;
import org.qw3rtrun.p3d.g.marlin.command.LinearMoveG1;
import org.qw3rtrun.p3d.g.marlin.command.ListSDCard;
import org.qw3rtrun.p3d.g.marlin.command.ReportPrintTime;
import org.qw3rtrun.p3d.g.marlin.command.ReportSDPrintStatus;
import org.qw3rtrun.p3d.g.marlin.command.ReportSettings;
import org.qw3rtrun.p3d.g.marlin.command.SelectAndStart;
import org.qw3rtrun.p3d.g.marlin.command.SelectSDFile;
import org.qw3rtrun.p3d.g.marlin.command.SerialPrint;
import org.qw3rtrun.p3d.g.marlin.command.SetGetLineNumber;
import org.qw3rtrun.p3d.g.marlin.command.SetLCDMessage;
import org.qw3rtrun.p3d.g.marlin.command.StartSDWrite;
import org.qw3rtrun.p3d.g.marlin.command.StopSDWrite;
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
 * reply-side counterpart of {@code marlin.gcode}, which is what it was <i>told</i>. A hand-run tool,
 * not a test - it needs a listener, so never part of {@code check}. Where {@link GCodeLiveProbe}
 * sweeps the command surface and prints what each answer decoded to, and {@link GCodeCorpusProbe}
 * replays the whole command corpus and histograms what nothing claimed, this one keeps the bytes so
 * the conversation is re-runnable after the module changes.
 *
 * <p>Every received line is written exactly as it arrived, in arrival order, including the ones this
 * module cannot read - those are the point. The only lines not received are the {@code ;}-prefixed
 * header written first; Marlin never begins a reply with {@code ;}, so that prefix is droppable
 * without risking a real line ({@code //} and {@code echo:} are real reply prefixes and are kept).
 *
 * <p>The session is the one <a href="https://github.com/qw3rtrun/p3d-web-ui/issues/10">issue 10</a>
 * asks for: a connect, {@code M115}, an {@code M155}/{@code M154} auto-report run, a homing move, a
 * deliberate checksum failure, and a disconnect - plus the queries whose replies have types here, so
 * the corpus can tell a decoder that stopped claiming a line from one that never did.
 *
 * <h2>The SD write, and why it has to be in here</h2>
 *
 * <p>{@code Writing to file:} and {@code Done saving file.} are the only way {@link
 * org.qw3rtrun.p3d.g.marlin.event.SdWritingToFile} and {@link
 * org.qw3rtrun.p3d.g.marlin.event.SdDoneSaving} can be measured, and until
 * <a href="https://github.com/qw3rtrun/p3d-web-ui/issues/66">issue 66</a> this session never sent
 * an {@code M28}, so neither could legitimately reach the census - the one file in the repo allowed
 * to hold a measurement. {@link SdWriteProbe} had measured the exchange and the transcript lived in
 * {@code INTEGRATION_TESTS_spec.md} instead, which is the wrong resting place for a captured line.
 *
 * <p>Three things make that section unlike the rest of the session, all three measured rather than
 * assumed (MarlinSim bugfix-2.1.x, 2026-11-09, and the same answers {@link SdWriteProbe} got on
 * 2026-11-01):
 *
 * <ul>
 *   <li><strong>it is framed and numbered throughout.</strong> A stored line must carry a line
 *       number and a checksum - an unnumbered one is answered
 *       {@code Error:No Checksum with line number, Last Line: n} and never reaches the file - so
 *       the section opens with its own {@code M110 N0} rather than depending on where the step
 *       before it left the counter;</li>
 *   <li><strong>every stored line is still acknowledged.</strong> {@code N2 G28*17} was answered
 *       {@code ok N2 P15 B3} and went into the file rather than being executed. The wire is a file
 *       sink between the {@code M28} and the {@code M29} and reads exactly like a wire that is
 *       not;</li>
 *   <li><strong>the file is deleted again at the end</strong>, by {@code M30}. The simulator's card
 *       outlives a client disconnect, so without that the {@code M20} earlier in this session would
 *       list a file the <em>previous</em> run wrote, and the capture would differ from itself. That
 *       is also the one step that measures {@code File deleted:…}, which nothing had captured.</li>
 * </ul>
 *
 * <p>The four stored lines are {@link SdWriteProbe}'s, deliberately, so the two probes corroborate:
 * they come back as {@code TEST.GCO 47}, which is their payloads plus a {@code CRLF} each
 * (5 + 14 + 11 + 17) and shows that the stored line keeps neither its {@code N} nor its {@code *}.
 *
 * <h2>The subroutine call, and why it needs two files</h2>
 *
 * <p>{@code echo:SUBROUTINE CALL target:"…" parent:"…" pos<n>}, {@code echo:END SUBROUTINE} and
 * {@code echo:Now doing file: <name>} are the only lines on the wire that announce a print nobody
 * asked for, and until
 * <a href="https://github.com/qw3rtrun/p3d-web-ui/issues/111">issue 111</a> none of them had a
 * type - so none could reach the census either. {@link SdPrintProbe} had measured all three on
 * 2026-11-10; this session reproduces the exchange so they are captured lines like every other.
 *
 * <p>Provoking them takes two files and the right name. {@code M32} is not in Marlin's
 * string-argument special case, so its line is parsed for parameters before what is left is taken
 * as the filename: a {@code p} <em>anywhere</em> in the name is read as a bare {@code P}, which
 * means "call this file as a subroutine". {@code cup.gco} has one and {@code live.gco} has not,
 * which is the whole of the difference - measured, by varying only the name, in
 * {@link SdPrintProbe}'s {@code names} run.
 *
 * <p>So the section sends the same {@code M32 cup.gco} twice. The first time nothing is open, and
 * the firmware says only {@code echo:Now doing file: cup.gco}. The second time an {@code M23} has
 * left {@code live.gco} open and unstarted, and the firmware announces the call in full, prints
 * {@code cup.gco}, writes {@code echo:END SUBROUTINE}, reopens {@code LIVE.GCO} and prints that
 * too - two prints, one {@code Done printing file}. The {@code M118 P0} line in each file is what
 * says on the wire which of them actually ran.
 *
 * <p>Both files are deleted again at the end, for the same reason {@code test.gco} is: this
 * simulator's card outlives a disconnect.
 *
 * <h2>How reproducible a capture is, measured rather than hoped</h2>
 *
 * <p>Three runs back to back on 2026-11-09. The first differs from the two after it, because this
 * simulator is <em>not</em> rebooted by a disconnect and so starts where the last client left it:
 * run 1 found the head parked and cold and homed from there with six {@code busy:} notices; runs 2
 * and 3 found it at {@code X10 Y10 Z5} and homed with three. From run 2 on the capture is a fixed
 * point - same length, same lines, same claimed type for every one of them - with a single drift:
 * the logical {@code E} grows by 10 each run, because the closing {@code G1 E10 F100} advances it
 * even as the firmware answers {@code echo: cold extrusion prevented}. The SD-write block is
 * identical in all three, which is what the closing {@code M30} is for.
 *
 * <p>So a recapture is reviewed by claimed type and not only by line: a reading that moved is the
 * machine, a <em>type</em> that disappeared is a regression in the capture.
 *
 * <p>Usage: {@code ReplyCorpusProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class ReplyCorpusProbe {

    /**
     * The file the SD-write section writes, lists and deletes. {@link SdWriteProbe}'s name, kept:
     * {@code M20} lists only printable extensions and in DOS 8.3 upper case, so this is the one
     * name whose listing line - {@code TEST.GCO 47} - is also the line {@code MockPrinter} and the
     * {@code :backend:core} tests use.
     */
    private static final String SD_FILE = "test.gco";

    /**
     * The file the subroutine section calls. The {@code p} in it is the whole point: {@code M32}
     * parses its line before taking the rest as a name, so this one arrives as {@code M32 P cup.gco}
     * and is run as a subroutine. {@link SdPrintProbe} measured that by varying only the name.
     */
    private static final String SUB_FILE = "cup.gco";

    /** The file left open as the subroutine's parent. No {@code p}, so {@code M32} prints it plainly. */
    private static final String PARENT_FILE = "live.gco";

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

    /** The session, in order. Commands with a class in this module are built and encoded through it,
     * the way {@link GCodeLiveProbe} does, so the corpus records this project talking to a printer
     * rather than hand-typed lines; the rest are typed because their point is a shape no command
     * class would produce. */
    private static List<Step> session() {
        var s = new ArrayList<Step>();

        // Quiet the machine first: an auto-report survives a client disconnect, so without this the
        // capture opens mid-stream with reports belonging to whatever ran last, making the corpus
        // depend on what someone else did. Their own replies are captured further down.
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

        // The SD surface: issue 12 has a format question open on it (RepRap wiki lists
        // `SD init fail`, Marlin 2.1.x is said to send `No SD card`) - captured rather than argued,
        // and answering neither is itself the answer for this firmware.
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

        // The SD write - issue 66. Numbered throughout, and from a counter reset of its own: a
        // stored line without a line number and checksum is refused, and the step above leaves the
        // counter wherever its rejection left it. See the class comment for the rest.
        s.add(new Step("reset the counter for the save", enc(new SetGetLineNumber(0))));
        s.add(new Step("open a file for write", framed(1, new StartSDWrite(SD_FILE))));
        s.add(new Step("a stored line", framed(2, new AutoHome())));
        s.add(new Step("a stored LCD message", framed(3, new SetLCDMessage("Created"))));
        s.add(new Step("a stored host message", framed(4, new SerialPrint(null, "Done"))));
        s.add(new Step("a stored host message, to all ports", framed(5, new SerialPrint(0, "Done-P0"))));
        s.add(new Step("close the file", framed(6, new StopSDWrite())));
        s.add(new Step("list the card, which now holds it", framed(7, new ListSDCard())));
        // An M29 with no file open: nothing but the acknowledgement comes back, which is the answer.
        s.add(new Step("a stray close, with no file open", framed(8, new StopSDWrite())));
        s.add(new Step("delete it, so this session can be run again", framed(9, new DeleteSDFile(SD_FILE))));

        // The subroutine call - issue 111. Continues the save's counter rather than resetting it,
        // since the files below are written the same way. See the class comment for why it takes
        // two files and why the same M32 goes out twice.
        s.add(new Step("write the file a p-bearing name will call", framed(10, new StartSDWrite(SUB_FILE))));
        s.add(new Step("its one line, so the wire says whether it ran", framed(11, new SerialPrint(0, "RAN-cup"))));
        s.add(new Step("close it", framed(12, new StopSDWrite())));
        s.add(new Step("write the file that will be its parent", framed(13, new StartSDWrite(PARENT_FILE))));
        s.add(new Step("its one line", framed(14, new SerialPrint(0, "RAN-live"))));
        s.add(new Step("close it", framed(15, new StopSDWrite())));
        // `p` stays false on purpose: what this measures is the firmware reading the `p` out of
        // the *name*, so the line on the wire has to be the bare `M32 cup.gco` and not `M32 P ...`.
        // Nothing open to return to, so the firmware says only `Now doing file:`.
        s.add(new Step("the p-bearing name through M32, with nothing open",
                framed(16, new SelectAndStart(false, null, SUB_FILE)), 5000));
        // M23 leaves a parent open at byte 0, which is what turns the next M32 into a full call.
        s.add(new Step("open the parent and leave it unstarted", framed(17, new SelectSDFile(PARENT_FILE))));
        s.add(new Step("the same M32 again, now that there is a parent",
                framed(18, new SelectAndStart(false, null, SUB_FILE)), 8000));
        s.add(new Step("delete the called file", framed(19, new DeleteSDFile(SUB_FILE))));
        s.add(new Step("delete the parent, so this session can be run again",
                framed(20, new DeleteSDFile(PARENT_FILE))));

        // Rejections: both of the echo lines a host must not ignore.
        s.add(new Step("unknown command", "M998"));
        s.add(new Step("unknown command with a subcode", "G998.5"));
        s.add(new Step("a tool this machine does not have", "M104 T5 S100"));
        // E is absolute, so a run that found the extruder already at 10 moved it nowhere and was
        // answered a bare ok - the refusal below went missing from the second capture of #111 for
        // exactly that reason. Zeroing the counter first makes the refusal the machine's answer
        // rather than an artefact of where the last run left it.
        s.add(new Step("put the extruder counter back where a fresh machine has it", "G92 E0"));
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
     * Marlin does not frame its replies, so the acknowledgement is the only end marker - a fixed
     * timeout would mis-attribute a slow command's answer ({@code G28} takes seconds) to the next step.
     *
     * <p>The budget is not belt-and-braces: a machine with an auto-report left running from an
     * earlier session talks continuously and the {@code ok} waited for has already gone past, so
     * every individual read succeeds inside the socket timeout and the loop never ends - exactly how
     * the first run of this probe hung. The socket timeout bounds one read; the budget bounds the wait.
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
     *
     * <p>Then it quiets the machine <em>before</em> the capture starts, and none of that is
     * recorded. An auto-report outlives the client that asked for it and queues up while nobody is
     * attached, so a run that simply connected opened its capture with a burst of somebody else's
     * temperature lines. Measured on 2026-11-09, before this capture: a connection to a simulator
     * left auto-reporting read 25 lines in its first four seconds - the queued backlog - and about
     * two a second after that, which is
     * <a href="https://github.com/qw3rtrun/p3d-web-ui/issues/92">issue 92</a>'s drowning in the
     * small. The session's own {@code M155 S0}/{@code M154 S0} steps still run and are still
     * captured; this is what makes them answer for this session rather than mop up the last one.
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
                if (drain(in, 2500).isEmpty()) throw new IllegalStateException("no answer to M115");

                send(socket.getOutputStream(), "M155 S0");
                drain(in, 5000);
                send(socket.getOutputStream(), "M154 S0");
                drain(in, 5000);
                return socket;
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

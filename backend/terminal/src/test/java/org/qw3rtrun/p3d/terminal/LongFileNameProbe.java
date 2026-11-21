package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.code.core.GEncoder;
import org.qw3rtrun.p3d.g.code.core.block.XorCheckSum;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue #148: does this simulator allow an upload under a name longer than DOS 8.3, or does it
 * refuse, truncate, or silently rewrite it? Re-measures {@code Cap:LONG_FILENAME} and
 * {@code Cap:LFN_WRITE} first - {@code ReplyCorpusProbe}'s capture from 2026-11-10 already has both
 * at {@code 0} - then opens five names of increasing hazard and reads back what {@code M20} says is
 * really on the card.
 *
 * <h2>What it found, against MarlinSim (bugfix-2.1.x, compiled Nov 18 2026), 2026-11-21, repeated
 * across two independent connections with identical results</h2>
 *
 * <ul>
 *   <li><strong>Neither capability moved</strong>: {@code Cap:LFN_WRITE:0} and
 *       {@code Cap:LONG_FILENAME:0}, eleven days after the first capture.</li>
 *   <li><strong>It is refused, not truncated or rewritten - and the refusal is invisible to the
 *       ordinary acknowledgement path.</strong> {@code eightchr.gco} (8-char base) opens and lists
 *       back as {@code EIGHTCHR.GCO}; {@code ninechars.gco} (9-char base, one past the boundary) is
 *       echoed {@code Now fresh file: ninechars.gco} and then immediately
 *       {@code open failed, File: ninechars.gco.} - but the {@code M28} line itself still answers
 *       {@code ok}. Nothing is written, {@code M20} shows no trace of the name under any spelling,
 *       and {@code M30} on it answers {@code Deletion failed}.</li>
 *   <li><strong>The rule is a character class, not only a length.</strong> {@code my file.gco} has
 *       a 7-character base - short enough - but the embedded space alone reproduces the identical
 *       failure. A base over eight characters, an extension over three, or any character outside
 *       {@code A-Za-z0-9} is refused.</li>
 *   <li><strong>The lines meant for the file are not held back - they run.</strong> With the file
 *       never open, the content line sent next ({@code G28}) executed for real and answered with a
 *       position report instead of a bare {@code ok} into a file; the closing {@code M29} answered
 *       a bare {@code ok} with no {@code Done saving file.}, confirming nothing was ever open to
 *       close.</li>
 * </ul>
 *
 * <p>This is why {@code SdUploader} generates its own DOS-8.3-safe name before {@code M28} goes out
 * on a machine that has not reported {@code Cap:LFN_WRITE}, rather than ever sending one that would
 * provoke this.
 *
 * <p>Usage: {@code LongFileNameProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class LongFileNameProbe {

    private LongFileNameProbe() {
    }

    private static final String SHORT = "ctrl.gco";
    private static final String LONG = "a_very_long_benchy_name.gco";
    private static final String EIGHT = "eightchr.gco";   // base is exactly 8 chars - the DOS 8.3 edge
    private static final String NINE = "ninechars.gco";    // base is 9 chars - one past the edge
    private static final String SPACED = "my file.gco";    // base is 7 chars but has a space in it

    private static int next = 1;

    private static final List<String> transcript = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        try (var socket = connect(host, port)) {
            var in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            for (var leftover : listen(in, socket, 1500)) {
                System.out.println("  (settling) << " + leftover);
            }
            socket.setSoTimeout(12000);

            var m115 = step(out, in, "=== M115: re-measure the long-filename capabilities ===", "M115");
            for (var line : m115) {
                if (line.contains("LONG_FILENAME") || line.contains("LFN_WRITE")
                        || line.contains("EXTENDED_M20") || line.contains("CUSTOM_FIRMWARE_UPLOAD")) {
                    System.out.println("  ** " + line);
                }
            }

            step(out, in, "=== reset the counter ===", framed("M110 N0"));
            next = 1;

            // control: a name already in 8.3 form, so every other case's listing has a baseline.
            boolean controlOk = tryUpload(out, in, SHORT);

            // the four edge cases: exactly 8 (the DOS boundary), 9 (one past it), a space inside an
            // otherwise-short base, and the original 26-char measurement.
            boolean eightOk = tryUpload(out, in, EIGHT);
            boolean nineOk = tryUpload(out, in, NINE);
            boolean spacedOk = tryUpload(out, in, SPACED);
            boolean longOk = tryUpload(out, in, LONG);

            var listing = step(out, in, "=== M20: what is really on the card? ===", framed("M20"));

            System.out.println();
            System.out.println("=== summary ===");
            System.out.println("  " + SHORT + "  (8.3, control)    open succeeded: " + controlOk);
            System.out.println("  " + EIGHT + " (8-char base)      open succeeded: " + eightOk);
            System.out.println("  " + NINE + " (9-char base)      open succeeded: " + nineOk);
            System.out.println("  " + SPACED + "    (space, 7-char base) open succeeded: " + spacedOk);
            System.out.println("  " + LONG + " (26-char base)    open succeeded: " + longOk);
            System.out.println("  full listing:");
            for (var line : listing) {
                System.out.println("    " + line);
            }

            // cleanup: delete every name that opened under its own spelling, plus the upper-cased
            // 8.3 forms the firmware actually wrote for the ones that did.
            for (var name : List.of(SHORT, EIGHT, NINE, SPACED, LONG)) {
                step(out, in, "=== cleanup: M30 " + name + " ===", framed("M30 " + name));
            }
        }

        report();
    }

    /**
     * Opens [name] for write, writes one line, closes it, and answers whether the open succeeded -
     * {@code open failed, File: ...} is the firmware's own wording for "no", distinct from a
     * protocol-level refusal ({@code Error:}/{@code !!}/{@code Resend:}), both of which still return
     * {@code ok} for the {@code M28} line itself.
     */
    private static boolean tryUpload(OutputStream out, BufferedReader in, String name) throws Exception {
        var opened = step(out, in, "=== open " + name + " ===", framed("M28 " + name));
        var failed = opened.stream().anyMatch(line -> line.contains("open failed"));
        if (complains(opened) || failed) {
            System.out.println("  >> " + name + ": the firmware did not open a file - nothing written");
            return false;
        }
        step(out, in, "=== one content line ===", framed("G28"));
        step(out, in, "=== close ===", framed("M29"));
        return true;
    }

    private static List<String> step(OutputStream out, BufferedReader in, String title, String line)
            throws Exception {
        System.out.println();
        System.out.println(title);
        System.out.println("  >> " + line);
        record(">> " + line);

        send(out, line);
        List<String> replies = drain(in, 20000);
        if (replies.isEmpty()) {
            System.out.println("  !! nothing came back inside the window");
        }
        for (var reply : replies) {
            System.out.println("  << " + reply);
            record("<< " + reply);
        }
        return replies;
    }

    private static boolean complains(List<String> replies) {
        for (var reply : replies) {
            String line = reply.trim();
            if (line.startsWith("Error:") || line.startsWith("!!") || line.startsWith("Resend:")) {
                return true;
            }
            if (line.startsWith("echo:Unknown command")) {
                return true;
            }
        }
        return false;
    }

    private static String framed(String payload) {
        return GEncoder.INSTANCE.frame(next++, payload, new XorCheckSum());
    }

    private static void record(String line) {
        transcript.add(line);
    }

    private static void report() {
        System.out.println();
        System.out.println("=== transcript ===");
        for (var line : transcript) {
            System.out.println("  " + line);
        }
    }

    private static void send(OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

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

    private static List<String> listen(BufferedReader in, Socket socket, long millis) throws Exception {
        var lines = new ArrayList<String>();
        var deadline = System.currentTimeMillis() + millis;
        socket.setSoTimeout(500);
        while (System.currentTimeMillis() < deadline) {
            try {
                var line = in.readLine();
                if (line == null) break;
                lines.add(line);
            } catch (Exception quiet) {
                // nothing in this slice of the window; keep waiting
            }
        }
        return lines;
    }

    private static Socket connect(String host, int port) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            Socket socket = null;
            try {
                socket = new Socket(host, port);
                socket.setSoTimeout(2500);
                var in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                drain(in, 2500);
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
}

package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.protocol.GRs;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays the whole corpus - {@code gcode/src/test/resources/marlin.gcode} - against a running
 * Marlin and reports what this module could not read.
 *
 * <p>A hand-run probe and not a test: it needs a listener on the far end. Where
 * {@link GCodeLiveProbe} sweeps a hand-picked battery, this one sends what a real slicer and a real
 * operator actually sent, and its useful output is the histogram at the end: every reply line no
 * decoder claimed, normalised so the same shape with different numbers in it counts once. That
 * histogram is what says which reply deserves a type.
 *
 * <p><b>Some corpus lines are quarantined</b>, listed in {@link #QUARANTINE}. Not for being
 * dangerous - this is a simulator - but because each one breaks the sweep itself: {@code M0} and
 * {@code M600} wait for a human, {@code M109} and {@code M190} wait for a temperature, {@code M303}
 * runs an autotune for minutes, {@code M28} redirects every following line into an SD file instead
 * of executing it, and {@code M81} powers the machine down and takes the socket with it. A sweep
 * that sent them would report the silence that followed as a hundred commands failing.
 *
 * <p>Usage: {@code GCodeCorpusProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class GCodeCorpusProbe {

    private GCodeCorpusProbe() {
    }

    /**
     * Corpus lines that would stall or hijack the sweep, by the command they start with.
     *
     * <p>Each is a command that does not simply answer and return: it waits for a human or a
     * heater, runs for minutes, swallows the lines behind it, or closes the connection.
     */
    private static final Map<String, String> QUARANTINE = new LinkedHashMap<>();

    static {
        QUARANTINE.put("M0", "waits for the operator to press continue");
        QUARANTINE.put("M109", "blocks until the hotend reaches temperature");
        QUARANTINE.put("M190", "blocks until the bed reaches temperature");
        QUARANTINE.put("M303", "PID autotune, minutes of heater cycling");
        QUARANTINE.put("M28", "redirects every following line into an SD file");
        QUARANTINE.put("M23", "selects an SD file that is not there");
        QUARANTINE.put("M32", "starts an SD print");
        QUARANTINE.put("M928", "starts SD logging and swallows the rest");
        QUARANTINE.put("M81", "powers the machine off, taking the socket with it");
        QUARANTINE.put("M502", "resets settings to factory defaults");
        QUARANTINE.put("M500", "saves whatever M502 just reset");
        QUARANTINE.put("G425", "full backlash calibration, minutes of motion");
        QUARANTINE.put("M16", "halts the printer when the name does not match");
        QUARANTINE.put("M600", "filament change, waits for the operator");
        QUARANTINE.put("M125", "parks and waits to be resumed");
        QUARANTINE.put("M43", "pin watching and probe tests block or flood");
    }

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        var corpus = findCorpus();
        var lines = Files.readAllLines(corpus.toPath(), StandardCharsets.UTF_8);
        System.out.printf("corpus %s (%d lines)%n", corpus, lines.size());

        var undecoded = new LinkedHashMap<String, Integer>();
        var byType = new LinkedHashMap<String, Integer>();
        var examples = new LinkedHashMap<String, String>();
        var senders = new LinkedHashMap<String, String>();
        var quarantined = new ArrayList<String>();
        var silent = new ArrayList<String>();
        int sent = 0;
        int replies = 0;
        int decoded = 0;

        try (var socket = connect(host, port)) {
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();
            socket.setSoTimeout(1500);
            drain(in);

            socket.setSoTimeout(6000);
            for (var raw : lines) {
                var line = raw.trim();
                if (line.isEmpty() || line.startsWith(";")) continue;

                var why = quarantineReason(line);
                if (why != null) {
                    quarantined.add(line + "   (" + why + ")");
                    continue;
                }

                sent++;
                send(out, line);
                var answer = drain(in);
                if (answer.isEmpty()) silent.add(line);

                for (var reply : answer) {
                    replies++;
                    GRs<?> rs = MarlinRsDecoder.INSTANCE.decode(reply);
                    var type = rs == null ? "(none)" : rs.getClass().getSimpleName();
                    byType.merge(type, 1, Integer::sum);
                    if (rs != null) {
                        decoded++;
                        // An EchoMessage is prose, so a structured report that lands in one is
                        // still unread: histogram those alongside what nothing claimed at all.
                        if (!"EchoMessage".equals(type)) continue;
                    }
                    var shape = (rs == null ? "" : "echo: ") + shapeOf(reply);
                    undecoded.merge(shape, 1, Integer::sum);
                    examples.putIfAbsent(shape, reply);
                    senders.putIfAbsent(shape, line);
                }
            }
        }

        System.out.println();
        System.out.printf("sent %d, quarantined %d, read %d replies, decoded %d, undecoded %d%n",
                sent, quarantined.size(), replies, decoded, replies - decoded);

        System.out.println();
        System.out.println("=== quarantined ===");
        for (var q : quarantined) System.out.println("  " + q);

        if (!silent.isEmpty()) {
            System.out.println();
            System.out.println("=== no reply within the window ===");
            for (var s : silent) System.out.println("  " + s);
        }

        System.out.println();
        System.out.println("=== replies by decoded type ===");
        byType.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("  %4d  %s%n", e.getValue(), e.getKey()));

        System.out.println();
        System.out.println("=== unread shapes (nothing claimed it, or it landed in EchoMessage) ===");
        undecoded.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf("  %4d  %-58s  sent: %s%n",
                        e.getValue(), e.getKey(), senders.get(e.getKey())));
    }

    /** Reply text with every run of digits replaced by a hash, so one shape counts once. */
    private static String shapeOf(String line) {
        var out = new StringBuilder();
        boolean inNumber = false;
        for (var ch : line.trim().toCharArray()) {
            if (ch >= '0' && ch <= '9') {
                if (!inNumber) out.append('#');
                inNumber = true;
            } else {
                inNumber = false;
                out.append(ch);
            }
        }
        var shape = out.toString();
        return shape.length() > 52 ? shape.substring(0, 49) + "..." : shape;
    }

    private static String quarantineReason(String line) {
        var head = line.split("[ \t;]", 2)[0].toUpperCase();
        return QUARANTINE.get(head);
    }

    private static void send(OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Everything up to and including the ok that ends the answer, or until the window closes. */
    private static List<String> drain(BufferedReader in) {
        var lines = new ArrayList<String>();
        try {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line);
                var trimmed = line.trim();
                if (trimmed.length() >= 2 && trimmed.regionMatches(true, 0, "ok", 0, 2)) break;
                if (lines.size() > 600) break;
            }
        } catch (Exception quiet) {
            // a read timeout: either there is no answer, or it is slower than the window
        }
        return lines;
    }

    /**
     * A socket with a printer on the other end that is ready to answer.
     *
     * The simulator reboots when a client disconnects, so the connection right after a previous
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
                drain(in);                                  // the boot banner, if there is one
                send(socket.getOutputStream(), "M115");
                if (!drain(in).isEmpty()) return socket;
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

    /** The corpus, found by walking up from the working directory to the repository root. */
    private static File findCorpus() {
        var dir = new File(".").getAbsoluteFile();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParentFile()) {
            var candidate = new File(dir, "gcode/src/test/resources/marlin.gcode");
            if (candidate.isFile()) return candidate;
        }
        throw new IllegalStateException("marlin.gcode not found above " + new File(".").getAbsolutePath());
    }
}

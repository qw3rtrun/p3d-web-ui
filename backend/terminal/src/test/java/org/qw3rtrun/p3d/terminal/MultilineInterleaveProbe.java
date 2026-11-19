package org.qw3rtrun.p3d.terminal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures, against a live simulator, whether anything can land <strong>between</strong> the first
 * line of an M119/M503 multi-line reply and the {@code ok} that ends it - the fact issue #35's
 * redo needs before an accumulator can be scoped to "lines belonging to this reply" rather than
 * "every line until ok, unconditionally".
 *
 * <p>Turns on {@code M155 S1} (temperature auto-report, once a second) first, then sends M119 and
 * M503 with the read window held open past a full second so a tick has time to fire while the
 * reply is still being read, and prints every line verbatim with its arrival offset in
 * milliseconds since the command was written.
 *
 * <p>Usage: {@code MultilineInterleaveProbe [host] [port]}, defaulting to {@code localhost 8099}.
 *
 * <h2>What the reference run measured</h2>
 *
 * <p>2026-11-19 against MarlinSim (Marlin bugfix-2.1.x) on {@code localhost:8099}, offsets in
 * milliseconds since the command was written:
 *
 * <pre>
 * === M119, read window 3000ms (one auto-report tick is due inside it) ===
 *   &lt;&lt; 0ms   T:25.00 /0.00 B:25.00 /0.00 @:0 B@:0     &lt;- leftover tick, ahead of M119's lines
 *   &lt;&lt; 22ms  Reporting endstop status
 *   &lt;&lt; 22ms  x_min: open
 *   &lt;&lt; 22ms  y_min: open
 *   &lt;&lt; 22ms  z_min: open
 *   &lt;&lt; 22ms  ok P15 B3
 *   &lt;&lt; 691ms T:25.00 /0.00 B:25.00 /0.00 @:0 B@:0     &lt;- next tick, behind the ok
 * </pre>
 *
 * <p>{@code M503} measured the same shape: a contiguous ~20-line burst with its {@code ok}
 * immediately behind the last line, both auto-report ticks outside it.
 *
 * <p><strong>Nothing lands between the first line of an M119 or M503 answer and the {@code ok} that
 * ends it</strong> - which is what makes {@code HostTerminal.ReplyFlux.accumulate}'s rule ("fold
 * members of this family until a reply that is not one") safe. The generic rule the issue first
 * proposed ("accumulate everything until {@code ok}") is not safe on this wire and stays rejected:
 * a {@code busy:} notice or a {@code Done printing file} genuinely can land between an unrelated
 * command and <em>its</em> {@code ok} elsewhere.
 */
public final class MultilineInterleaveProbe {

    private MultilineInterleaveProbe() {
    }

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        try (var socket = new Socket(host, port)) {
            socket.setSoTimeout(3000);
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            System.out.println("=== banner ===");
            drainQuiet(in, 1500);

            send(out, "M155 S1");
            System.out.println("=== M155 S1 (enable 1s temperature auto-report) ===");
            for (var line : timed(in, 1500)) System.out.println("  << " + line);

            // Give the auto-report timer a head start so its next tick is due mid-read.
            Thread.sleep(800);

            send(out, "M119");
            System.out.println();
            System.out.println("=== M119, read window 3000ms (one auto-report tick is due inside it) ===");
            for (var line : timed(in, 3000)) System.out.println("  << " + line);

            Thread.sleep(800);

            send(out, "M503");
            System.out.println();
            System.out.println("=== M503, read window 3000ms (one auto-report tick is due inside it) ===");
            for (var line : timed(in, 3000)) System.out.println("  << " + line);

            send(out, "M155 S0");
            System.out.println();
            System.out.println("=== M155 S0 (disable) ===");
            for (var line : timed(in, 1500)) System.out.println("  << " + line);
        }
    }

    private static void send(java.io.OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void drainQuiet(BufferedReader in, long millis) {
        try {
            var deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                if (!in.ready()) {
                    Thread.sleep(50);
                    continue;
                }
                in.readLine();
            }
        } catch (Exception ignored) {
        }
    }

    /** Reads for exactly the given window, printing each line as it arrives - no stop-on-ok, since
     * the whole point is to see what (if anything) lands before and after it. */
    private static List<String> timed(BufferedReader in, long millis) {
        var lines = new ArrayList<String>();
        var start = System.currentTimeMillis();
        var deadline = start + millis;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!in.ready()) {
                    Thread.sleep(20);
                    continue;
                }
                var line = in.readLine();
                if (line == null) break;
                lines.add((System.currentTimeMillis() - start) + "ms  " + line);
            }
        } catch (Exception ignored) {
        }
        return lines;
    }
}

package org.qw3rtrun.p3d.terminal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures, against a live simulator, whether anything can land <strong>between</strong> the first
 * line of an {@code M115} answer and the {@code ok} that ends it - the fact issue #144's fold of
 * every {@code Cap:} line into one {@code CapabilitiesReport} needs before an accumulator can be
 * scoped to "lines belonging to this answer" the way issue #35's {@code MultilineInterleaveProbe}
 * established for {@code M119}/{@code M503}.
 *
 * <p>Turns on {@code M155 S1} (temperature auto-report, once a second) first, then sends
 * {@code M115} with the read window held open past a full second so a tick has time to fire while
 * the answer is still being read, and prints every line verbatim with its arrival offset in
 * milliseconds since the command was written.
 *
 * <p>Usage: {@code CapabilitiesInterleaveProbe [host] [port]}, defaulting to {@code localhost 8099}.
 *
 * <h2>What the reference run measured</h2>
 *
 * <p>2026-11-20 against MarlinSim (Marlin bugfix-2.1.x, Nov 18 2026 11:55:10) on
 * {@code localhost:8099}, offsets in milliseconds since the command was written:
 *
 * <pre>
 * === M115, read window 3000ms (one auto-report tick is due inside it) ===
 *   &lt;&lt; 0ms    T:25.00 /0.00 B:25.00 /0.00 @:0 B@:0            &lt;- leftover tick, ahead of M115's lines
 *   &lt;&lt; 31ms   FIRMWARE_NAME:Marlin bugfix-2.1.x ...
 *   &lt;&lt; 31ms   Cap:QUOTED_STRINGS:1
 *   ...all 38 Cap: lines, same offset...
 *   &lt;&lt; 31ms   Cap:CONFIG_EXPORT:0
 *   &lt;&lt; 31ms   ok P15 B3
 *   &lt;&lt; 651ms  T:25.00 /0.00 B:25.00 /0.00 @:0 B@:0            &lt;- next tick, behind the ok
 * </pre>
 *
 * <p><strong>Nothing lands between the header line of an {@code M115} answer and the {@code ok}
 * that ends it</strong> - the header, all 38 {@code Cap:} lines and the {@code ok} arrive at the
 * same millisecond offset (31ms), with the auto-report ticks strictly outside that burst on both
 * sides (a leftover at 0ms, the next three at 651ms/1671ms/2668ms). That is what makes a reader
 * scoped to "members of this family" (mirroring {@code EndstopReportReader}) safe for {@code M115}
 * too: there is no case measured here where a temperature tick, a busy notice or anything else
 * needs to be let through mid-burst.
 */
public final class CapabilitiesInterleaveProbe {

    private CapabilitiesInterleaveProbe() {
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

            send(out, "M115");
            System.out.println();
            System.out.println("=== M115, read window 3000ms (one auto-report tick is due inside it) ===");
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

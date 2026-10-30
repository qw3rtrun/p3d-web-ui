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
 * Writes a file to the printer's SD card with {@code M28}/{@code M29}, lists it, prints it, and
 * reports what came back.
 *
 * <p>A hand-run probe and not a test: it needs a listener on the far end, so it must never be part
 * of {@code check}. {@code GCodeCorpusProbe} <em>quarantines</em> {@code M28} for exactly the reason
 * this exercises - it redirects every following line into a file instead of executing it, so a
 * sweep that sent it would report the rest of the corpus as a hundred commands doing nothing.
 *
 * <p><strong>What it measures: whether a line written to a file has to be numbered, and from
 * where.</strong> That is not something to assume, so this sends the first content line
 * <em>unnumbered</em>, reads the answer, and only then sends the rest the way that answer says to.
 *
 * <h2>What it found, against MarlinSim (bugfix-2.1.x)</h2>
 *
 * <ul>
 *   <li><strong>Numbering is required while saving</strong>, and it is not while executing. The
 *       same bare {@code G28} is accepted outside a save and refused inside one with
 *       {@code Error:No Checksum with line number, Last Line: 1} - a message that names a line
 *       number the input does not carry, which is the firmware's wording and not a typo here.</li>
 *   <li><strong>{@code M28} does not reset the counter.</strong> Numbering runs straight through
 *       the save. What resets is {@code M110}, which sets {@code last_N} to its argument whatever
 *       {@code N} the block itself rode in on - so after {@code M110 N0} the next line is
 *       {@code N1}, which is exactly {@code GSendWindow.reset(to)}'s {@code next = to + 1}.</li>
 *   <li><strong>Stored lines are stripped of {@code N} and {@code *}.</strong> The file comes back
 *       as {@code Size: 47}, which is the four payloads plus a {@code CRLF} each and nothing
 *       else.</li>
 *   <li><strong>{@code M118} needs {@code P0} to be heard from inside a print</strong> - see
 *       {@link #CONTENT}.</li>
 *   <li><strong>{@code M20} filters by printable extension</strong> - see {@link #FILE}.</li>
 * </ul>
 *
 * <p>Usage: {@code SdWriteProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class SdWriteProbe {

    private SdWriteProbe() {
    }

    /**
     * The file written, and the three lines put in it.
     *
     * <p><strong>The extension is load-bearing.</strong> {@code M20} lists only files it considers
     * printable - {@code .gcode}, {@code .gco}, {@code .g} - in DOS 8.3 form, so a file written as
     * plain {@code test} is on the card and simply never appears in a listing. The first run of
     * this probe used that name and reported the empty listing as a simulator gap, which it is
     * not: {@code M32} opened the same name happily and gave its size. {@code M20} filters,
     * {@code M28} and {@code M32} do not.
     */
    private static final String FILE = "test.gco";

    /**
     * Both spellings of {@code M118} go in, adjacent, so one run tells them apart.
     *
     * <p><strong>{@code P0} is the one that is heard.</strong> Printing this file yields
     * {@code Done-P0} and no {@code Done}: a plain {@code M118} writes to the port the command
     * arrived on, and a command read from the SD reader did not arrive on one, while {@code P0}
     * means "all serial ports". The same {@code M118 Done} sent down the wire directly <em>does</em>
     * answer {@code Done}, which is what rules out "this build has no {@code M118}" - and
     * {@code M117} from the same file reaching the host as {@code //action:notification Created}
     * rules out "commands from a file do not run".
     *
     * <p>Worth knowing before writing a job file: a progress or completion marker put in one with a
     * bare {@code M118} is written to nowhere.
     */
    private static final List<String> CONTENT =
            List.of("G28", "M117 Created", "M118 Done", "M118 P0 Done-P0");

    private static int next = 1;

    private static final List<String> transcript = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        try (var socket = connect(host, port)) {
            var in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            // Flush whatever the handshake left in the buffer before the first step, or every step
            // reports the previous one's replies. `connect` sends an `M115` to prove the machine is
            // answering and reads until the first `ok` - but the capability dump behind it is 40
            // lines, and if any of it is still arriving when the first real command goes out, the
            // whole transcript is one command out of step. That happened: an `M110` appeared to be
            // answered by the capability dump, `N3` by `ok N2`, and an unnumbered line looked
            // accepted when two earlier runs had it rejected. Read to silence instead of to a
            // marker, because what is being waited out is a tail with no end marker of its own.
            for (var leftover : listen(in, socket, 1500)) {
                System.out.println("  (settling) << " + leftover);
            }
            socket.setSoTimeout(12000);

            // A known base, so "N1 is next" is a fact rather than a hope.
            //
            // The reset moves the firmware's counter and therefore this one: `M110 N0` sets
            // last_N to 0, so the next line the firmware will accept is N1 - not N2, even though
            // the M110 block itself went out as N1. Getting that wrong is not a subtle failure:
            // the first run of this probe numbered straight on and every line from M28 onwards
            // came back `Error:Line Number is not Last Line Number+1, Last Line: 0` with a
            // `Resend: 1`. It is also exactly what `GSendWindow.reset(to)` encodes - `next = to + 1`.
            step(out, in, "=== reset the counter ===", framed("M110 N0"));
            next = 1;

            step(out, in, "=== open the file for write ===", framed("M28 " + FILE));

            // The question this probe exists to answer. An unnumbered line while saving: does the
            // firmware take it, or does it complain that a line number is missing?
            List<String> bare = step(out, in, "=== first content line, UNNUMBERED ===", CONTENT.get(0));
            boolean numberingRequired = complains(bare);

            System.out.println();
            System.out.println(numberingRequired
                    ? "  >> the firmware rejected an unnumbered line while saving"
                    : "  >> the firmware accepted an unnumbered line while saving");

            if (numberingRequired) {
                // The unnumbered attempt did not reach the file, so put that line in properly -
                // the point of the exercise is a file with all of CONTENT in it.
                step(out, in, "=== first content line again, numbered ===", framed(CONTENT.get(0)));
            }

            // Either way the rest go numbered - a host that has started numbering does not stop
            // mid-session, and sending them numbered also shows whether the stored file keeps the
            // `N` field or strips it, which is what the listing size and the print will say.
            for (int i = 1; i < CONTENT.size(); i++) {
                step(out, in, "=== content line " + (i + 1) + " ===", framed(CONTENT.get(i)));
            }

            step(out, in, "=== close the file ===", framed("M29"));

            step(out, in, "=== list the card ===", framed("M20"));

            // M32 starts the print. Marlin's own doc example wraps the name in `!...#`; the plain
            // form is what most hosts send, so try that and fall back.
            List<String> started = step(out, in, "=== print it ===", framed("M32 " + FILE));
            if (complains(started)) {
                step(out, in, "=== print it, the doc's !name# form ===", framed("M32 !" + FILE + "#"));
            }

            // The file holds a G28, so the print takes seconds. Listen for what it says while it
            // runs - the two M118 spellings are what this is watching for, and which of them
            // arrives is the answer.
            System.out.println();
            System.out.println("=== while it prints ===");
            for (var line : listen(in, socket, 15000)) {
                record("<< " + line);
                System.out.println("  << " + line);
            }

            // Two controls, each ruling out an explanation that would otherwise still be open.
            //
            // Listing again after an explicit mount separates "the card needed initialising" from
            // "the listing does not show what is on it". Neither turned out to be the answer - the
            // first run of this used a name with no extension, which M20 filters out - and the
            // step is kept because it is what shows the listing is stable rather than incidental.
            socket.setSoTimeout(12000);
            step(out, in, "=== init the card, then list again ===", framed("M21"));
            step(out, in, "=== list the card again ===", framed("M20"));

            // And the same M118 down the wire rather than out of the file. It answers `Done`,
            // which is what makes the missing one above a fact about where the command came from
            // rather than about whether this build implements it at all.
            step(out, in, "=== M118 sent directly, not from the file ===", framed("M118 Done"));
        }

        report();
    }

    /**
     * Sends one line, prints what came back, and keeps it for the report.
     *
     * @return the reply lines, so the caller can decide what to do next from what the printer said
     */
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

    /** Whether any of these lines is the firmware objecting rather than answering. */
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

    /** The next framed block: `N<n> <payload>*<xor>`, through this repo's own encoder. */
    private static String framed(String payload) {
        return GEncoder.INSTANCE.frame(next++, payload, new XorCheckSum());
    }

    private static void record(String line) {
        transcript.add(line);
    }

    /** What the whole exchange showed, so the answer does not have to be read out of the scroll. */
    private static void report() {
        System.out.println();
        System.out.println("=== transcript ===");
        for (var line : transcript) {
            System.out.println("  " + line);
        }

        System.out.println();
        System.out.println("=== what to read out of it ===");
        System.out.println("  * `Error:` after the UNNUMBERED line    -> numbering is required while saving");
        System.out.println("  * every `ok` names the line it answers  -> the transcript is in step; if the");
        System.out.println("                                            numbers lag, a reply from the handshake");
        System.out.println("                                            was still arriving and every step below");
        System.out.println("                                            is reporting the previous one's answer");
        System.out.println("  * `" + FILE.toUpperCase() + " <size>` in the M20 listing -> written, and listed because");
        System.out.println("                                            the name has a printable extension");
        System.out.println("  * size == the payloads + CRLF each      -> the stored lines carry no N or *");
        System.out.println("  * `Done-P0` and no `Done` while printing -> M118 needs P0 to leave an SD print");
    }

    private static void send(OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Everything up to and including the {@code ok} that ends the answer, or until [budget] runs
     * out - the same bounded read {@code ReplyCorpusProbe} uses, and for the same reason: a
     * machine with an auto-report running talks continuously and the `ok` being waited for may
     * already have gone past.
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

    /** Everything that arrives in the next [millis], which is how a running print reports. */
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

    /**
     * A socket with a printer on the other end that is ready to answer.
     *
     * <p>The simulator reboots when a client disconnects, so a connection right after a previous
     * probe lands on a machine that is still starting and drops the first write.
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
}

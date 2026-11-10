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
 * Starts the same print twice - once as {@code M23}+{@code M24}, once as a single
 * {@code M32 <name>} - and reports what each one answered and whether the machine was actually
 * printing afterwards. A hand-run probe, not a test: it needs a listener and it writes to the card,
 * so it is never part of {@code check}.
 *
 * <p>Written for issue #109. {@code SdWriteProbe} had already captured an {@code M32} reply set, but
 * only at the end of a write session - so nothing in evidence showed an idle machine printing after
 * an {@code M32}. This probe makes the two starts comparable: same file, same idle machine, and the
 * control run first.
 *
 * <p>The file it writes is what makes "is it printing?" answerable from outside: two {@code G4}
 * dwells with {@code M118 P0} markers around them, so the print takes seconds rather than
 * microseconds and announces its own progress on the wire ({@code P0} because a bare {@code M118}
 * from inside an SD print writes to the port the command arrived on, and a command read from the
 * card did not arrive on one - {@code SdWriteProbe} measured that). An {@code M27} goes out between
 * the markers, which is the second answer: {@code SD printing byte <at>/<of>} versus
 * {@code Not SD printing}.
 *
 * <h2>What it found, against MarlinSim on 2026-11-10</h2>
 *
 * <p><strong>The answer is in {@code names()}, and the first two runs did not find it.</strong>
 * They compared the two commands on one file called {@code probe.gco} and produced a tidy, wrong
 * conclusion. The `names` run is the one that matters; read it first.
 *
 * <ul>
 *   <li><strong>A plain {@code M32 <name>} finishes one print by starting another, for ordinary
 *       filenames.</strong> {@code M32}'s documented signature is
 *       {@code M32 [P<value>] [S<filepos>] [<filename>]} and, unlike {@code M23}, it is not in
 *       Marlin's string-argument special case - so the firmware parses its line for parameters
 *       before taking what is left as the name. A {@code p} <em>anywhere</em> in the name is
 *       therefore read as {@code P}, which means "call this file as a subroutine". The firmware
 *       announces it and then does it:
 *       <pre>
 *       &gt;&gt; N37 M23 live.gco       &lt;&lt; File opened: live.gco Size: 22   (open, not started)
 *       &gt;&gt; N39 M32 cup.gco        &lt;&lt; echo:SUBROUTINE CALL target:"cup.gco" parent:"/LIVE.GCO" pos0
 *                                 &lt;&lt; echo:END SUBROUTINE
 *                                 &lt;&lt; File opened: LIVE.GCO Size: 22
 *                                 &lt;&lt; RAN-cup.gco
 *                                 &lt;&lt; RAN-live.gco
 *                                 &lt;&lt; Done printing file
 *       </pre>
 *       One request to print {@code cup.gco} printed {@code cup.gco} and then {@code LIVE.GCO}.
 *       This is why issue #109 <em>kept</em> {@code M23}+{@code M24} rather than collapsing to one
 *       command.</li>
 *   <li><strong>Which letters matter was measured, not reasoned.</strong> {@code live.gco} and
 *       {@code set.gco} answer {@code echo:Now fresh file:}; {@code prt.gco} and {@code cup.gco}
 *       answer {@code echo:Now doing file:}, the subroutine announcement - so position is
 *       irrelevant and {@code s} is harmless, because {@code S} needs a value and {@code P} does
 *       not. {@code M23 cup.gco} is unaffected.</li>
 *   <li><strong>Marlin's own {@code !name#} spelling is what suppresses it</strong> -
 *       {@code M32 !cup.gco#} answers {@code echo:Now fresh file:} and opens {@code cup.gco}, the
 *       delimiters stripped. The documented form exists for this reason, which nothing here had
 *       measured before. It is not used: a name containing {@code #} could not then be
 *       delimited, and {@code #} is legal in an 8.3 name.</li>
 *   <li><strong>{@code M32} does start a print</strong>, which was the question the probe was
 *       written for and which no capture had shown: the file's own {@code M118 P0} markers came
 *       back, {@code M27} answered {@code SD printing byte 46/73} mid-run, and
 *       {@code Done printing file} ended it. Its reply set is otherwise {@code M23}'s -
 *       {@code File opened: <name> Size: <n>} and {@code File selected} both arrive - so nothing
 *       the browser reads was the reason for the decision either way.</li>
 *   <li><strong>{@code M24} after an {@code M23} is a <em>resume</em></strong>:
 *       {@code //action:resume} and a {@code //action:prompt_begin Resuming SD} sequence, which is
 *       a host prompt saying "Resuming SD" for a print that has never run. The pair's one real
 *       cost, and the smaller one.</li>
 *   <li><strong>An {@code M23} with no {@code M24} is indistinguishable from a stalled print</strong>
 *       - {@code M27} after the select alone answers {@code SD printing byte 0/73}, not
 *       {@code Not SD printing}. The pair's other cost, and the argument that would favour one
 *       block if the name were not parsed.</li>
 *   <li><strong>Both commands refuse a missing file identically</strong>:
 *       {@code open failed, File: <name>.} then {@code ok}, each behind its own echo. Measured on
 *       a name deleted a step earlier rather than one invented for the occasion.</li>
 * </ul>
 *
 * <p>The methodological point, since this probe got it wrong twice before getting it right: the
 * first two runs used one filename, {@code probe.gco}, which happens to contain a {@code p}. Every
 * {@code M32} in them took the subroutine path, every one of them still completed because the file
 * it returned to was already at EOF, and the difference read as "the echo tracks the command". It
 * took a capture from the running application - which sent {@code M32 LIVE.GCO} and got the
 * <em>other</em> echo - to show that the variable was the name.
 *
 * <p>Usage: {@code SdPrintProbe [host] [port] [pairfirst|m32first|names]}, defaulting to
 * {@code localhost 8099 pairfirst}; or {@code ./gradlew :backend:terminal:probeSdPrint
 * -PprobeOrder=names}.
 */
public final class SdPrintProbe {

    private SdPrintProbe() {
    }

    /** Printable extension, or M20 filters it out of the listing - see {@code SdWriteProbe}. */
    private static final String FILE = "probe.gco";

    /**
     * Long enough to be caught in the act, and loud enough to prove it ran. The dwells are what give
     * the {@code M27} between them something to report; the markers are what tell a transcript
     * reader that the file's lines were executed rather than merely opened.
     */
    private static final List<String> CONTENT = List.of(
            "M118 P0 PROBE-START",
            "G4 S2",
            "M118 P0 PROBE-MID",
            "G4 S2",
            "M118 P0 PROBE-END");

    private static int next = 1;

    private static final List<String> transcript = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;
        // Which run goes first, because one of the answers turned out to depend on it - see the
        // class doc. Run it both ways and compare.
        var m32First = args.length > 2 && args[2].equalsIgnoreCase("m32first");
        // The third run: not which command, but which NAME - see names().
        var names = args.length > 2 && args[2].equalsIgnoreCase("names");
        System.out.println("  mode: " + (names ? "filenames through M32"
                : m32First ? "M32 first, then M23+M24" : "M23+M24 first, then M32"));

        try (var socket = connect(host, port)) {
            var in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            // Read the handshake tail to silence, or every step reports the previous one's replies.
            for (var leftover : listen(in, socket, 1500)) {
                System.out.println("  (settling) << " + leftover);
            }
            socket.setSoTimeout(12000);

            step(out, in, "=== reset the counter ===", framed("M110 N0"));
            next = 1;

            step(out, in, "=== mount the card ===", framed("M21"));

            // The file both runs will print. Numbered, because numbering is required while saving.
            step(out, in, "=== open the file for write ===", framed("M28 " + FILE));
            for (var line : CONTENT) {
                step(out, in, "=== content: " + line + " ===", framed(line));
            }
            step(out, in, "=== close the file ===", framed("M29"));
            step(out, in, "=== list the card ===", framed("M20"));

            step(out, in, "=== baseline: is it printing before either run? ===", framed("M27"));

            if (names) {
                names(out, in, socket);
                return;
            }

            if (m32First) {
                single(out, in, socket, "A");
                pair(out, in, socket, "B");
            } else {
                pair(out, in, socket, "A");
                single(out, in, socket, "B");
            }

            step(out, in, "=== tidy up: delete the probe file ===", framed("M30 " + FILE));

            // The refusal arm, measured on a name that is now provably not on the card rather than
            // on one invented for the occasion: a mock that guesses what "no such file" looks like
            // is a mock that agrees with the guess.
            step(out, in, "=== C1: M23 on a file that is not there ===", framed("M23 " + FILE));
            step(out, in, "=== C2: M32 on a file that is not there ===", framed("M32 " + FILE));
            step(out, in, "=== C3: is it printing after those? ===", framed("M27"));
        }

        report();
    }

    /**
     * Does a letter <em>inside the filename</em> reach {@code M32} as a parameter?
     *
     * <p>The question this mode exists for, and it was found by accident: the application sent
     * {@code M32 LIVE.GCO} and the firmware answered {@code echo:Now fresh file:}, where this probe
     * sending {@code M32 probe.gco} had been answered {@code echo:Now doing file:} - the
     * subroutine-call announcement. The commands are identical; only the name differs, and
     * {@code probe.gco} begins with the letter {@code p}. {@code M32}'s documented signature is
     * {@code M32 [P<value>] [S<filepos>] [<filename>]}, and unlike {@code M23} it is not in Marlin's
     * string-argument special case, so its line goes through the ordinary parameter parser first.
     *
     * <p>Four names, varying only where a letter sits, each written, printed and then asked after:
     * no {@code p} at all, {@code p} first, {@code p} in the middle, and {@code s} first, since
     * {@code S} is {@code M32}'s {@code filepos}. Then the two controls that say what the answer
     * means: Marlin's own {@code !name#} spelling, which puts a non-letter in front of the name, and
     * the same {@code p}-bearing name through {@code M23}, which takes the rest of its line
     * verbatim.
     */
    private static void names(OutputStream out, BufferedReader in, Socket socket) throws Exception {
        var files = List.of("live.gco", "prt.gco", "cup.gco", "set.gco");

        for (var file : files) {
            step(out, in, "=== write " + file + " ===", framed("M28 " + file));
            step(out, in, "=== its one line ===", framed("M118 P0 RAN-" + file));
            step(out, in, "=== close it ===", framed("M29"));
        }
        step(out, in, "=== what is on the card ===", framed("M20"));

        for (var file : files) {
            step(out, in, "=== M32 " + file + " ===", framed("M32 " + file));
            quiet(in, socket, "after M32 " + file);
            step(out, in, "=== M27 after it ===", framed("M27"));
        }

        step(out, in, "=== control: the doc's !name# form ===", framed("M32 !cup.gco#"));
        quiet(in, socket, "after M32 !cup.gco#");
        step(out, in, "=== M27 after it ===", framed("M27"));

        step(out, in, "=== control: the same name through M23 ===", framed("M23 cup.gco"));
        step(out, in, "=== M27 after it ===", framed("M27"));
        step(out, in, "=== and M24 to clear it ===", framed("M24"));
        quiet(in, socket, "after M24");

        // The question the decision turns on. `Now doing file:` is Marlin's announcement for a
        // SUBROUTINE call - it pushes the file that is open as a parent and returns to it at EOF.
        // So: leave a file open and unfinished with M23, start a p-bearing name with M32, and see
        // what the machine is doing once that print ends. If the parent comes back, a plain
        // `M32 <name>` finishes one print by silently starting another.
        step(out, in, "=== P1: leave live.gco open at byte 0, not started ===", framed("M23 live.gco"));
        step(out, in, "=== P2: confirm it is open and idle ===", framed("M27"));
        step(out, in, "=== P3: now print a p-bearing name with M32 ===", framed("M32 cup.gco"));
        quiet(in, socket, "while cup.gco prints");
        step(out, in, "=== P4: what is it doing now? ===", framed("M27"));
        quiet(in, socket, "after that M27");
        step(out, in, "=== P5: and now? ===", framed("M27"));

        for (var file : files) {
            step(out, in, "=== tidy up ===", framed("M30 " + file));
        }
    }

    /** Whatever arrives unasked in the next few seconds - a short print reporting itself. */
    private static void quiet(BufferedReader in, Socket socket, String what) throws Exception {
        System.out.println("  (listening " + what + ")");
        for (var line : listen(in, socket, 5000)) {
            record("<< " + line);
            System.out.println("  << " + line);
        }
        socket.setSoTimeout(12000);
    }

    /** The pair this repository sends today: select, then start, with an {@code M27} either side. */
    private static void pair(OutputStream out, BufferedReader in, Socket socket, String run)
            throws Exception {
        step(out, in, "=== " + run + "1: select it (M23) ===", framed("M23 " + FILE));
        step(out, in, "=== " + run + "2: M27 after the select, before the start ===", framed("M27"));
        step(out, in, "=== " + run + "3: start it (M24) ===", framed("M24"));
        step(out, in, "=== " + run + "4: M27 just after the start ===", framed("M27"));
        watchItRun(out, in, socket, run);
    }

    /** The candidate: one command. */
    private static void single(OutputStream out, BufferedReader in, Socket socket, String run)
            throws Exception {
        step(out, in, "=== " + run + "1: select and start it (M32 <name>) ===", framed("M32 " + FILE));
        step(out, in, "=== " + run + "2: M27 just after the M32 ===", framed("M27"));
        watchItRun(out, in, socket, run);
    }

    /**
     * Listens out the print, then asks once more whether it is over - the window either side of the
     * markers is what separates "the command was accepted" from "the file ran".
     */
    private static void watchItRun(OutputStream out, BufferedReader in, Socket socket, String run)
            throws Exception {
        System.out.println();
        System.out.println("=== " + run + ": while it should be printing ===");
        for (var line : listen(in, socket, 14000)) {
            record("<< " + line);
            System.out.println("  << " + line);
        }
        socket.setSoTimeout(12000);
        step(out, in, "=== " + run + ": M27 after it should have finished ===", framed("M27"));
    }

    /**
     * Sends one line, prints what came back, and keeps it for the report.
     *
     * @return the reply lines, so a caller can decide what to do next from what the printer said
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

    /** The next framed block: N&lt;n&gt; payload*&lt;xor&gt;, through this repo's own encoder. */
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
        System.out.println("  * `SUBROUTINE CALL target:... parent:...` after an M32 -> the name was parsed,");
        System.out.println("                                            a P was found in it, and this print");
        System.out.println("                                            will be followed by the parent's");
        System.out.println("  * `echo:Now doing file:` vs `Now fresh file:` -> the same thing with no parent");
        System.out.println("                                            open to return to");
        System.out.println("  * which NAMES get which echo           -> p anywhere is the trigger; s is not,");
        System.out.println("                                            since S needs a value and P does not");
        System.out.println("  * M32 !name# against M32 name          -> whether the documented delimiters");
        System.out.println("                                            keep the name out of the parser");
        System.out.println("  * M27 after an M23 and before its M24  -> whether a select alone already");
        System.out.println("                                            reports as printing (it does:");
        System.out.println("                                            `SD printing byte 0/<size>`)");
        System.out.println("  * RAN-<name> markers on the wire       -> which files actually executed, which");
        System.out.println("                                            is the only way the second print shows");
    }

    private static void send(OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Everything up to and including the {@code ok} that ends the answer, or until the budget runs out. */
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

    /** Everything that arrives in the next few seconds, which is how a running print reports. */
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

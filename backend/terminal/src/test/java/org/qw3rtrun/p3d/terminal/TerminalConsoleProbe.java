package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.protocol.BaseRsDecoder;
import org.qw3rtrun.p3d.g.protocol.GRs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Measures what a <em>terminal</em> view would have to show: for every line the machine sends, the
 * raw text beside what this module decodes it to, and whether the decoded value can reproduce the
 * text. Issue #135 asks for a chat-like console where "response should be parsed as usual but raw
 * response should be shown", and this probe is the evidence that the second half is not redundant.
 *
 * <p>A hand-run probe and not a test: no assertions, and it must never be part of {@code check}.
 * {@link SettingsProbe} is the model for the shape and the drain plumbing is lifted from it.
 *
 * <p>Usage: {@code TerminalConsoleProbe [host] [port]}, defaulting to {@code localhost 8099}.
 *
 * <h2>The three ways a decoded reply loses the line</h2>
 *
 * Each received line falls into exactly one of these, and the run prints a census of all three:
 *
 * <ol>
 *   <li><b>UNCLAIMED</b> - no decoder answers, so {@code HostTerminal.ReplyFlux.decode} drops the
 *       line to the {@code #traffic} logger and <em>nothing at all</em> reaches a subscriber. A
 *       terminal fed only by {@code messageFlux()} would show a blank where the machine spoke.</li>
 *   <li><b>LOSSY</b> - a decoder claims the line but {@code encode()} does not give the bytes back,
 *       because {@code GRs.encode()} is documented as writing "the canonical one" of several
 *       spellings. The decoded event is not a faithful record of what was said.</li>
 *   <li><b>CONSUMED</b> - claimed, and then deliberately withheld from {@code messageFlux()} by the
 *       transport because it drives the protocol rather than reporting anything ({@code ok},
 *       {@code wait}, {@code Resend:}). Correct for the monitor view, and exactly what an operator
 *       watching a terminal needs to see.</li>
 * </ol>
 *
 * <p>Only a line that is claimed, round-trips, and is published can be rendered from the decoded
 * event alone. The census counts how many of the machine's own lines qualify.
 *
 * <h2>The control</h2>
 *
 * <p>Printing a transcript proves nothing by itself - every line is "shown", trivially. So the run
 * is in two halves over one connection: a battery of commands whose answers are deliberately
 * different in kind (a firmware roster, a settings dump, a temperature, a position, a card listing,
 * a command the firmware does not have, and a line that is not G-code at all), and then the census,
 * which counts the same lines by category. The claim "the raw line carries something the decoded
 * event does not" is false if the census shows every line claimed and round-tripping, and true by
 * however much it does not.
 *
 * <h2>Two things this simulator cannot show - UNMEASURED</h2>
 *
 * <ul>
 *   <li>It always has an SD card, so the no-card answers to {@code M20}/{@code M21} cannot be
 *       captured here.</li>
 *   <li>It reboots when a client disconnects, so a run started straight after another can land
 *       mid-boot and read a partial banner. One connection for the whole run, as in
 *       {@link SettingsProbe}.</li>
 * </ul>
 */
public final class TerminalConsoleProbe {

    private TerminalConsoleProbe() {
    }

    /** One received line, as the three questions a terminal view has to ask about it. */
    private record Seen(String raw, GRs<?> decoded, String who, boolean roundTrips, boolean published) {
    }

    private static final List<Seen> census = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        try (var socket = new Socket(host, port)) {
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var out = socket.getOutputStream();

            System.out.println("=== the banner, and whatever the previous session left queued ===");
            System.out.println("# every one of these is a line a terminal view has to render");
            socket.setSoTimeout(1500);
            for (var line : drainUntilQuiet(in)) report(line);

            socket.setSoTimeout(12000);
            send(out, in, "M155 S0 - stop the auto-reports so the battery is readable", "M155 S0");
            socket.setSoTimeout(1500);
            drainUntilQuiet(in).forEach(TerminalConsoleProbe::report);
            socket.setSoTimeout(12000);

            battery(out, in);
            censusReport();
        }
    }

    /**
     * Commands whose answers differ in kind, so the census has something to count. Sent as raw
     * strings on purpose: that is what the Terminal feature does, and a probe that built them
     * through command classes would not be measuring the same path.
     */
    private static void battery(OutputStream out, BufferedReader in) {
        System.out.println();
        System.out.println("##### battery: answers that differ in kind, each sent as a raw string #####");

        send(out, in, "M115 - the firmware roster", "M115");
        send(out, in, "M105 - one temperature, answered on the ok itself", "M105");
        send(out, in, "M114 - where the toolhead is", "M114");
        send(out, in, "M503 - the settings dump, the longest answer there is", "M503");
        send(out, in, "M20 - list the card", "M20");
        send(out, in, "M27 - print status", "M27");
        send(out, in, "M31 - time since the print started", "M31");
        send(out, in, "M78 - print job statistics", "M78");
        send(out, in, "M119 - endstop states", "M119");
        send(out, in, "M851 - a command this build does not have", "M851");
        send(out, in, "NOTGCODE - not a command at all", "NOTGCODE");
    }

    // ----- the census ------------------------------------------------------------------------

    private static void censusReport() {
        System.out.println();
        System.out.println("##### CENSUS: can the decoded event reproduce the line? #####");

        var byCategory = new LinkedHashMap<String, List<Seen>>();
        for (var seen : census) {
            byCategory.computeIfAbsent(categorise(seen), key -> new ArrayList<>()).add(seen);
        }

        System.out.printf("  %-34s %5s%n", "category", "lines");
        for (var entry : byCategory.entrySet()) {
            System.out.printf("  %-34s %5d%n", entry.getKey(), entry.getValue().size());
        }
        System.out.printf("  %-34s %5d%n", "-- total received", census.size());

        var renderable = byCategory.getOrDefault("RENDERABLE from the decoded event", List.of()).size();
        System.out.println();
        System.out.printf("  %d of %d lines could be rendered from the decoded event alone.%n",
                renderable, census.size());
        System.out.printf("  %d could not, and are the reason the raw line has to be carried.%n",
                census.size() - renderable);

        for (var entry : byCategory.entrySet()) {
            if (entry.getKey().startsWith("RENDERABLE")) {
                continue;
            }
            System.out.println();
            System.out.println("  --- " + entry.getKey() + ", up to 12 examples ---");
            var examples = entry.getValue();
            for (var i = 0; i < Math.min(12, examples.size()); i++) {
                var seen = examples.get(i);
                System.out.printf("    raw      %s%n", seen.raw());
                if (seen.decoded() != null) {
                    System.out.printf("    encode() %s%n", seen.decoded().encode());
                }
            }
        }
    }

    private static String categorise(Seen seen) {
        if (seen.decoded() == null) {
            return "UNCLAIMED - nothing reaches a subscriber";
        }
        if (!seen.published()) {
            return "CONSUMED by the transport as flow control";
        }
        if (!seen.roundTrips()) {
            return "LOSSY - encode() is not the line";
        }
        return "RENDERABLE from the decoded event";
    }

    /**
     * Whether {@code HostTerminal} puts this reply on {@code messageFlux()}. Mirrors
     * {@code ReplaySubscriber.onNext} and {@code publishPayload} by the same family interfaces they
     * match on, read off the simple names so this probe does not have to import the whole reply
     * model - the point is the count, and the names are what the switch there reads.
     */
    private static boolean publishedByTheTransport(GRs<?> reply) {
        var name = reply.getClass().getSimpleName();
        return !(name.equals("SimpleOkRs") || name.equals("AdvancedOkRs")
                || name.equals("WaitRs") || name.equals("ResendRs")
                || name.equals("SdFileListBegin") || name.equals("SdFileListEnd"));
    }

    // ----- plumbing (lifted from SettingsProbe) ----------------------------------------------

    private static List<String> send(OutputStream out, BufferedReader in, String label, String wire) {
        System.out.println();
        System.out.println("=== " + label + " ===");
        System.out.println("  >> " + wire);
        try {
            out.write((wire + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception failed) {
            System.out.println("  !! write failed: " + failed);
            return List.of();
        }
        var replies = drainUntilOk(in);
        if (replies.isEmpty()) {
            System.out.println("  !! no reply within the read window");
        }
        for (var line : replies) {
            report(line);
        }
        return replies;
    }

    /** Prints one received line three ways, and records it for the census. */
    private static void report(String line) {
        GRs<?> marlin = MarlinRsDecoder.INSTANCE.decode(line);
        GRs<?> base = marlin == null ? BaseRsDecoder.INSTANCE.decode(line) : null;
        var decoded = marlin != null ? marlin : base;
        var who = marlin != null ? "marlin" : base != null ? "base" : "NONE";

        var roundTrips = decoded != null && decoded.encode().equals(line.trim());
        var published = decoded != null && publishedByTheTransport(decoded);
        census.add(new Seen(line, decoded, who, roundTrips, published));

        var shown = line.length() > 58 ? line.substring(0, 55) + "..." : line;
        System.out.printf("  << %-58s [%-6s]%s%s %s%n", shown, who,
                roundTrips ? "" : decoded == null ? "" : " [LOSSY]",
                published || decoded == null ? "" : " [CONSUMED]",
                decoded == null ? "" : decoded);
        if (decoded != null && !roundTrips) {
            System.out.printf("     encode() -> %s%n", decoded.encode());
        }
    }

    private static List<String> drainUntilOk(BufferedReader in) {
        return drain(in, 20_000, 1000, TerminalConsoleProbe::endsAnAnswer);
    }

    private static List<String> drainUntilQuiet(BufferedReader in) {
        return drain(in, 6_000, 2000, line -> false);
    }

    /** Reads until {@code isEnd}, until the far end goes quiet, until {@code cap} lines, or until
     * {@code budgetMs} of wall clock - whichever comes first. The clock is the one bound a flooding
     * far end cannot outrun; see {@link SettingsProbe#drain}, which this is lifted from, for the
     * run that hung without it. Nothing is suppressed here: a suppressed line is exactly the kind of
     * line this probe exists to count. */
    private static List<String> drain(BufferedReader in, long budgetMs, int cap, Predicate<String> isEnd) {
        var lines = new ArrayList<String>();
        var deadline = System.nanoTime() + budgetMs * 1_000_000L;
        try {
            String line;
            while (System.nanoTime() < deadline && (line = in.readLine()) != null) {
                lines.add(line);
                if (isEnd.test(line)) {
                    break;
                }
                if (lines.size() > cap) {
                    break;
                }
            }
        } catch (Exception quiet) {
            // the read window elapsed: for a drain-until-quiet that is how it ends, and for a
            // drain-until-ok it means there is no answer, or it is slower than the window
        }
        return lines;
    }

    private static boolean endsAnAnswer(String line) {
        var trimmed = line.trim();
        return trimmed.length() >= 2 && trimmed.regionMatches(true, 0, "ok", 0, 2);
    }

    /** Unused by the run, kept so a reader can see the categories are a partition. */
    static Map<String, String> categories() {
        return Map.of(
                "UNCLAIMED", "no decoder answers; ReplyFlux drops it to the traffic logger",
                "CONSUMED", "claimed, then withheld from messageFlux() as flow control",
                "LOSSY", "claimed, but encode() is not the bytes that arrived",
                "RENDERABLE", "claimed, published, and encode() gives the line back");
    }
}

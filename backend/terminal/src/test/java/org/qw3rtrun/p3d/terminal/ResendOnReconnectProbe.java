package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Issue #60, measured rather than argued: what a <em>second</em> host session does to a firmware
 * that still remembers the first one's line numbers.
 *
 * <p>A hand-run probe and not a test - it needs a listener on the far end, and the one case it
 * exists to produce is the one the suite's {@code MockPrinter} cannot, because a fresh mock always
 * starts at line 0 and so agrees with a fresh host by construction.
 *
 * <p>It runs the same thing twice against <strong>one</strong> simulator process:
 *
 * <ul>
 *   <li><strong>Session A is the control.</strong> A host attaches to a firmware whose counter is
 *       wherever the last client left it, sends a few blocks, and is disposed. It establishes what
 *       the opening exchange looks like and how far the firmware's {@code last_N} is pushed.</li>
 *   <li><strong>Session B is the measurement.</strong> A second host attaches to the same process
 *       and sends the issue's own three blocks - {@code G21}, {@code G90}, {@code G28}. Everything
 *       written and everything received is printed verbatim, and every block is then classified:
 *       acknowledged, replayed, or abandoned by {@code CommandProcessor.resynchronise}.</li>
 * </ul>
 *
 * <p>Two further facts are read off the same capture, because both decide the fix:
 *
 * <ul>
 *   <li><strong>What number the opening {@code M110 N0} carries, and what the block behind it
 *       carries.</strong> {@code GSendWindow.reset(0)} frames the {@code M110} with the number that
 *       was next and then sets {@code next = 1}, so on a fresh window both are {@code N1}. Whether
 *       that is benign depends on which {@code N} the firmware reads out of an {@code M110} line,
 *       and that is on the wire here.</li>
 *   <li><strong>Whether the {@code M110} is acknowledged before the first real block goes out.</strong>
 *       The written transcript is in order, so the interleaving answers it.</li>
 * </ul>
 *
 * <p>Usage: {@code ResendOnReconnectProbe [host] [port]}, defaulting to {@code localhost 8099}.
 * Run it twice in a row if the far end reboots on disconnect: the second run is then the one whose
 * session A found a counter already pushed.
 */
public final class ResendOnReconnectProbe {

    private ResendOnReconnectProbe() {
    }

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        var a = new Session("A (control - pushes the firmware's counter)", host, port);
        a.run(List.of("M105", "M114", "M105", "M114", "M105"), Duration.ofSeconds(5));
        a.close();

        // Long enough that the far end has finished whatever a dropped client makes it do.
        TimeUnit.MILLISECONDS.sleep(2500);

        var b = new Session("B (measurement - the issue's own blocks, on a reconnect)", host, port);
        b.run(List.of("G21", "G90", "G28"), Duration.ofSeconds(12));
        b.close();

        a.report();
        b.report();
    }

    /** One host session: its own connection, its own {@link HostTerminal}, its own transcript. */
    private static final class Session {

        private final String label;
        private final Connection conn;
        private final HostTerminal terminal;
        private final PublisherQueue<String, Priority> queue = new PublisherQueue<>();

        /** Every block as it left this host, in order. */
        private final List<String> out = new CopyOnWriteArrayList<>();
        /** Every line as it arrived, in order. */
        private final List<String> in = new CopyOnWriteArrayList<>();
        /** The two directions interleaved, which is the only thing that shows the ordering. */
        private final List<String> wire = new CopyOnWriteArrayList<>();
        private final List<Object> seen = new CopyOnWriteArrayList<>();
        private final List<String> busEnded = new CopyOnWriteArrayList<>();

        private Disposable replies;
        private Disposable sending;

        Session(String label, String host, int port) {
            this.label = label;
            this.conn = TcpClient.create().host(host).port(port)
                    .connect().block(Duration.ofSeconds(10));
            Objects.requireNonNull(conn, "no connection to " + host + ":" + port);
            this.terminal = new HostTerminal(
                    () -> conn.inbound().receive().asString().doOnNext(this::tapIn),
                    o -> conn.outbound().sendString(Flux.from(o).doOnNext(this::tapOut)).then());
        }

        private void tapOut(String block) {
            var text = block.replace("\n", "");
            out.add(text);
            wire.add("-> " + text);
        }

        private void tapIn(String chunk) {
            for (var line : chunk.split("\n")) {
                var text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                in.add(text);
                wire.add("<- " + text);
            }
        }

        void run(List<String> blocks, Duration settle) throws InterruptedException {
            replies = terminal.messageFlux().subscribe(
                    seen::add,
                    t -> busEnded.add("onError: " + t),
                    () -> busEnded.add("onComplete"));
            sending = terminal.start(Flux.from(queue)).subscribe();
            // A moment for the opening M110 to go out on its own, so the transcript shows whether
            // the host waits for its `ok` before the first real block is queued behind it.
            TimeUnit.MILLISECONDS.sleep(1200);
            blocks.forEach(b -> queue.addPublisher(Priority.REGULAR, Mono.just(b)));
            TimeUnit.MILLISECONDS.sleep(settle.toMillis());
        }

        void close() {
            if (sending != null) {
                sending.dispose();
            }
            if (replies != null) {
                replies.dispose();
            }
            terminal.stop();
            conn.dispose();
        }

        void report() {
            System.out.println();
            System.out.println("================ session " + label + " ================");
            System.out.println("--- the wire, in order ---");
            wire.forEach(l -> System.out.println("  " + l));

            var acked = seen.stream()
                    .filter(BlockAcknowledged.class::isInstance)
                    .map(BlockAcknowledged.class::cast)
                    .map(BlockAcknowledged::lineNumber)
                    .toList();
            var errors = in.stream().filter(l -> l.startsWith("Error:")).toList();
            var resends = in.stream().filter(l -> l.startsWith("Resend:")).toList();
            var m110 = out.stream().filter(b -> b.contains("M110")).toList();

            System.out.println();
            System.out.println("--- what it adds up to ---");
            System.out.printf("  blocks written          %d %s%n", out.size(), out);
            System.out.printf("  M110 blocks written     %d %s%n", m110.size(), m110);
            System.out.printf("  Error: lines            %d %s%n", errors.size(), errors);
            System.out.printf("  Resend: lines           %d %s%n", resends.size(), resends);
            System.out.printf("  BlockAcknowledged       %d %s%n", acked.size(), acked);
            System.out.printf("  bus terminal signal     %s%n",
                    busEnded.isEmpty() ? "none" : busEnded);

            System.out.println();
            System.out.println("--- block by block ---");
            for (var block : distinct(out)) {
                var written = out.stream().filter(block::equals).count();
                var number = numberOf(block);
                var wasAcked = number != null && acked.contains(number);
                System.out.printf("  %-28s written x%d   %s%n", block, written,
                        wasAcked ? "ACKNOWLEDGED" : "NEVER ACKNOWLEDGED");
            }
        }

        private static List<String> distinct(List<String> blocks) {
            return blocks.stream().distinct().toList();
        }

        private static Integer numberOf(String block) {
            if (!block.startsWith("N")) {
                return null;
            }
            int end = 1;
            while (end < block.length() && Character.isDigit(block.charAt(end))) {
                end++;
            }
            return end == 1 ? null : Integer.parseInt(block.substring(1, end));
        }
    }
}

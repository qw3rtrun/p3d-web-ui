package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.g.marlin.event.BusyNotice;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Proves against a real printer what the unit tests can only argue: that the host's numbered,
 * checksummed blocks are what the firmware accepts, that it replays the one the firmware rejects,
 * and that a recoverable error does not end the reply bus.
 *
 * <p>A hand-run probe and not a test - it needs a listener on the far end. The three things it
 * measures are each a fix that was written against a simulator that was down at the time, and each
 * is about what a real firmware actually sends:
 *
 * <ul>
 *   <li><strong>The session stays in sync.</strong> {@link HostTerminal.CommandProcessor} now puts
 *       an {@code N} field and a {@code *} checksum on every block, so Marlin's sequence and
 *       checksum checks apply to this host for the first time. If the numbering or the covered
 *       range were wrong, the firmware would say so on the very first block - and before this
 *       change nothing on the wire was checkable, so nothing said anything.</li>
 *   <li><strong>A rejected block comes back.</strong> One block is corrupted <em>on the wire</em>,
 *       after the window framed it, so the firmware answers {@code Error:checksum mismatch} and
 *       {@code Resend: n} for a block the host still holds. The check is that the same bytes go out
 *       a second time and the session carries on afterwards. That is spec 8.5 step 5 end to end,
 *       and it is the half that cannot be tested without a peer that rejects something.</li>
 *   <li><strong>{@code echo:busy:} reaches the send window.</strong> {@code G28} takes seconds and
 *       Marlin fills the wait with it. Before the fix only the bare {@code busy:} was recognised,
 *       so the window was never told the machine had stalled.</li>
 * </ul>
 *
 * <p>It builds its own connection rather than going through {@link TerminalManager}, because
 * corrupting one block needs a tap between the processor and the socket and the manager owns that
 * seam. Nothing else about the wiring differs.
 *
 * <p>Usage: {@code TerminalRecoveryProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class TerminalRecoveryProbe {

    private TerminalRecoveryProbe() {
    }

    /** Set just before the block that should reach the firmware broken; cleared by the tap. */
    private static final AtomicBoolean corruptNext = new AtomicBoolean();

    /** Every block as the send window framed it, before the tap had a chance to break it. */
    private static final List<String> written = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;
        var settle = Duration.ofSeconds(6);

        Connection conn = TcpClient.create().host(host).port(port)
                .connect().block(Duration.ofSeconds(10));
        Objects.requireNonNull(conn, "no connection to " + host + ":" + port);

        var terminal = new HostTerminal(
                () -> conn.inbound().receive().asString(),
                out -> conn.outbound().sendString(Flux.from(out).map(TerminalRecoveryProbe::tap)).then());

        var queue = new PublisherQueue<String, Priority>();
        Consumer<String> sink = gcode -> queue.addPublisher(Priority.REGULAR, Mono.just(gcode));

        List<GRs<?>> seen = new CopyOnWriteArrayList<>();
        List<String> busEnded = new CopyOnWriteArrayList<>();

        Disposable replies = terminal.messageFlux().subscribe(
                seen::add,
                t -> busEnded.add("onError: " + t),
                () -> busEnded.add("onComplete"));
        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        // 1 - a baseline reply, so we know the bus delivers at all and the numbering is accepted
        sink.accept("M105");
        settle(settle.dividedBy(3));
        int beforeError = countTemperatures(seen);
        int errorsBefore = countErrors(seen);
        int writtenBefore = written.size();

        // 2 - one block broken on the wire. Marlin answers Error: / Resend: n / ok, and the host
        //     has block n, because the window keeps every block until the ok that accepts it.
        corruptNext.set(true);
        sink.accept("M105");
        settle(settle.dividedBy(2));

        // 3 - THE CHECKS: did the rejected block go out again, and does the bus still deliver?
        sink.accept("M105");
        settle(settle);
        int afterError = countTemperatures(seen);

        // 4 - a long move, for the busy notices
        sink.accept("G28");
        settle(settle);

        report(seen, busEnded, beforeError, afterError, countErrors(seen) - errorsBefore, writtenBefore);

        sending.dispose();
        replies.dispose();
        terminal.stop();
        conn.dispose();
        System.exit(busEnded.isEmpty() && afterError > beforeError && replayed(writtenBefore) ? 0 : 1);
    }

    /**
     * The wire tap: records what the window framed, and breaks one block's checksum on request.
     *
     * <p>The body is left exactly as it was so the firmware's <em>sequence</em> check still passes
     * and only the checksum fails - which is what makes the answer a {@code Resend:} for this block
     * rather than a complaint about a gap that no replay can repair.
     */
    private static String tap(String block) {
        written.add(block);
        if (!corruptNext.compareAndSet(true, false)) {
            return block;
        }
        int star = block.lastIndexOf('*');
        if (star < 0) {
            System.out.println("  !! nothing to corrupt in " + block.trim());
            return block;
        }
        int checksum = Integer.parseInt(block.substring(star + 1).trim());
        var broken = block.substring(0, star) + "*" + ((checksum + 1) & 0xFF) + "\n";
        System.out.printf("  !! corrupting %s -> %s", block, broken);
        return broken;
    }

    /** Whether some block framed after {@code from} was written twice, which is the replay. */
    private static boolean replayed(int from) {
        var after = written.subList(from, written.size());
        return after.stream().anyMatch(b -> after.stream().filter(b::equals).count() > 1);
    }

    private static void report(List<GRs<?>> seen, List<String> busEnded,
                               int before, int after, int errors, int writtenBefore) {
        System.out.println();
        System.out.println("=== what reached messageFlux() ===");
        for (var rs : seen) {
            System.out.printf("  %-18s %s%n", rs.getClass().getSimpleName(), rs.encode());
        }

        System.out.println();
        System.out.println("=== what went out ===");
        for (var block : written) {
            System.out.printf("  %s", block);
        }

        var busies = seen.stream().filter(BusyNotice.class::isInstance).count();
        var replayed = replayed(writtenBefore);

        System.out.println();
        System.out.println("=== checks ===");
        System.out.printf("  every block numbered and checksummed %s   (%d written)%n",
                written.stream().allMatch(TerminalRecoveryProbe::isFramed) ? "PASS" : "FAIL", written.size());
        System.out.printf("  the corrupted block was rejected     %s   (%d Error: after it)%n",
                errors > 0 ? "PASS" : "FAIL", errors);
        System.out.printf("  the rejected block was replayed      %s%n", replayed ? "PASS" : "FAIL");
        System.out.printf("  bus still open after the error       %s   (%s)%n",
                busEnded.isEmpty() ? "PASS" : "FAIL", busEnded.isEmpty() ? "no terminal signal" : busEnded);
        System.out.printf("  temperature after the error          %s   (%d before, %d after)%n",
                after > before ? "PASS" : "FAIL", before, after);
        System.out.printf("  echo:busy: decoded as BusyNotice     %s   (%d seen)%n",
                busies > 0 ? "PASS" : "not exercised", busies);
    }

    private static boolean isFramed(String block) {
        return block.startsWith("N") && block.lastIndexOf('*') > 0;
    }

    private static int countTemperatures(List<GRs<?>> seen) {
        return (int) seen.stream().filter(TemperatureRs.class::isInstance).count();
    }

    private static int countErrors(List<GRs<?>> seen) {
        return (int) seen.stream().filter(ErrorRs.class::isInstance).count();
    }

    private static void settle(Duration d) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(d.toMillis());
    }
}

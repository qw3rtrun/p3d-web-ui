package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import org.qw3rtrun.p3d.g.marlin.event.BusyNotice;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import org.qw3rtrun.p3d.g.protocol.ResendRs;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Proves against a real printer the two things the unit tests can only argue: that a recoverable
 * error does not end the reply bus, and that Marlin's {@code echo:busy:} reaches the send window.
 *
 * <p>A hand-run probe and not a test - it needs a listener on the far end. It exists because both
 * fixes were written while the simulator was down, and both are about what a real firmware sends:
 *
 * <ul>
 *   <li>A deliberately corrupt checksum makes Marlin answer {@code Error:checksum mismatch},
 *       {@code Resend:} and then {@code ok}. Before the fix that {@code Error:} went to
 *       {@code tryEmitError} and terminated {@code messageFlux()} for good. The check is that a
 *       temperature report asked for <em>after</em> the error still arrives.</li>
 *   <li>{@code G28} takes seconds and Marlin fills the wait with {@code echo:busy: processing}.
 *       Before the fix only the bare {@code busy:} was recognised, so the window was never told.
 *       The check is that those notices arrive decoded as {@link BusyNotice}.</li>
 * </ul>
 *
 * <p>Usage: {@code TerminalRecoveryProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class TerminalRecoveryProbe {

    private TerminalRecoveryProbe() {
    }

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;
        var settle = Duration.ofSeconds(6);

        var manager = new TerminalManager(new ConnectionDetails(host, port));
        var terminal = manager.connect().block(Duration.ofSeconds(10));
        Objects.requireNonNull(terminal, "no connection to " + host + ":" + port);

        var queue = new PublisherQueue<String, Priority>();
        Consumer<String> sink = gcode -> queue.addPublisher(Priority.REGULAR, Mono.just(gcode));

        List<GRs<?>> seen = new CopyOnWriteArrayList<>();
        List<String> busEnded = new CopyOnWriteArrayList<>();

        Disposable replies = terminal.messageFlux().subscribe(
                seen::add,
                t -> busEnded.add("onError: " + t),
                () -> busEnded.add("onComplete"));
        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        // 1 - a baseline reply, so we know the bus delivers at all
        sink.accept("M105");
        settle(settle.dividedBy(3));
        int beforeError = countTemperatures(seen);

        // 2 - a corrupt checksum. Marlin answers Error: / Resend: / ok and carries on.
        sink.accept("M110 N0");
        sink.accept("N3 M105*1");
        settle(settle.dividedBy(2));

        // 3 - THE CHECK: does the bus still deliver after the printer reported an error?
        sink.accept("M105");
        settle(settle);
        int afterError = countTemperatures(seen);

        // 4 - a long move, for the busy notices
        sink.accept("G28");
        settle(settle);

        report(seen, busEnded, beforeError, afterError);

        sending.dispose();
        replies.dispose();
        terminal.stop();
        System.exit(busEnded.isEmpty() && afterError > beforeError ? 0 : 1);
    }

    private static void report(List<GRs<?>> seen, List<String> busEnded, int before, int after) {
        System.out.println();
        System.out.println("=== what reached messageFlux() ===");
        for (var rs : seen) {
            System.out.printf("  %-18s %s%n", rs.getClass().getSimpleName(), rs.encode());
        }

        var errors = seen.stream().filter(ErrorRs.class::isInstance).count();
        var resends = seen.stream().filter(ResendRs.class::isInstance).count();
        var busies = seen.stream().filter(BusyNotice.class::isInstance).count();

        System.out.println();
        System.out.println("=== checks ===");
        System.out.printf("  bus still open after the error   %s   (%s)%n",
                busEnded.isEmpty() ? "PASS" : "FAIL", busEnded.isEmpty() ? "no terminal signal" : busEnded);
        System.out.printf("  temperature after the error      %s   (%d before, %d after)%n",
                after > before ? "PASS" : "FAIL", before, after);
        System.out.printf("  Error: published as a value      %s   (%d seen)%n",
                errors > 0 ? "PASS" : "not exercised", errors);
        System.out.printf("  Resend: seen                     %s   (%d seen)%n",
                resends > 0 ? "yes" : "no", resends);
        System.out.printf("  echo:busy: decoded as BusyNotice %s   (%d seen)%n",
                busies > 0 ? "PASS" : "not exercised", busies);
    }

    private static int countTemperatures(List<GRs<?>> seen) {
        return (int) seen.stream().filter(TemperatureRs.class::isInstance).count();
    }

    private static void settle(Duration d) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(d.toMillis());
    }
}

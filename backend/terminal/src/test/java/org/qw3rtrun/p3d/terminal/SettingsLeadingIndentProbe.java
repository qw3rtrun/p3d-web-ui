package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.TerminalTraffic;
import org.qw3rtrun.p3d.g.marlin.event.SettingsReport;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends {@code M503 S0} and a bare {@code M92} over the real {@link TerminalManager} stack and
 * checks both still reach {@code messageFlux()} as a {@link SettingsReport} - issue #149. Before the
 * fix, {@code HostTerminal.ReplyFlux}'s framing {@code .trim()}'d the assembled line, which ate the
 * leading two-space indent {@code SettingsValue}'s unprefixed shape decodes on (what both of these
 * answer in), so neither reached {@code MarlinRsDecoder} and both were logged {@code <- (unread)}
 * and dropped - silently, with no report on the bus at all.
 *
 * <p>A hand-run probe and not a test: it needs a listener on the far end, so it must never be part
 * of {@code check}. Run it with {@code gradlew :backend:terminal:probeSettingsLeadingIndent}.
 *
 * <p>Usage: {@code SettingsLeadingIndentProbe [host] [port] [timeoutSeconds]}, defaulting to
 * {@code localhost 8099 10}. The simulator reboots on disconnect, so a run started straight after
 * another can land mid-boot; run it again rather than reading the banner as a failure.
 *
 * <h2>What the reference run measured</h2>
 *
 * <p>2026-11-20 against MarlinSim (Marlin bugfix-2.1.x) on {@code localhost:8099}, after the fix:
 *
 * <pre>
 * === M503 S0 (unprefixed shape) ===
 * -&gt; N1 M503 S0*103
 * &lt;-   G21 ; (mm)                                     [SettingsValue]
 * &lt;-   M104 S210 B250 F0.10                            [SettingsValue]
 * &lt;-   ... 9 more indented lines ...
 * &lt;-   M301 P22.20 I1.08 D114.00                       [SettingsValue]
 * &lt;- ok N1 P15 B3                                      [AdvancedOkRs]
 *    bus: SettingsReport(13 entries)
 *
 * === M92 (bare read-back) ===
 * -&gt; N2 M92*26
 * &lt;-   M92 X80.00 Y80.00 Z400.00 E500.00               [SettingsValue]
 * &lt;- ok N2 P15 B3                                      [AdvancedOkRs]
 *    bus: SettingsReport(1 entries)
 *
 * 2 SettingsReport(s) on the bus for 2 unprefixed-shape reads (0 before the fix)
 * </pre>
 *
 * <p>Both answers are unprefixed and carry no section headers - exactly the shape the leading
 * indent guards - and both now fold into a {@link SettingsReport} the way the {@code echo:}-prefixed
 * {@code M503} dump already did. Before the fix this run printed {@code 0 SettingsReport(s)}: every
 * one of those 14 received lines was logged {@code <- (unread)} and reached nobody.
 */
public final class SettingsLeadingIndentProbe {

    private SettingsLeadingIndentProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;
        var timeout = Duration.ofSeconds(args.length > 2 ? Long.parseLong(args[2]) : 10);

        System.out.printf("connecting to %s:%d%n", host, port);
        var manager = new TerminalManager(new ConnectionDetails(host, port));
        var terminal = manager.connect().block(timeout);
        Objects.requireNonNull(terminal, "no connection to " + host + ":" + port);

        var queue = new PublisherQueue<String, Priority>();

        var reports = new AtomicInteger();
        var latch = new CountDownLatch(2);
        Disposable replies = terminal.messageFlux().subscribe(rs -> {
            if (rs instanceof SettingsReport report) {
                System.out.println("   bus: SettingsReport(" + report.getEntries().size()
                        + " entries): " + report.getEntries());
                reports.incrementAndGet();
                latch.countDown();
            }
        });

        terminal.observeTraffic(line -> System.out.printf("%s %-50s %s%n",
                line.direction() == TerminalTraffic.Direction.SENT ? "->" : "<-", line.raw(),
                line.decodedAs() == null ? "" : "[" + line.decodedAs() + "]"));

        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        System.out.println();
        System.out.println("=== M503 S0 (unprefixed shape) ===");
        queue.addPublisher(Priority.REGULAR, Mono.just("M503 S0"));
        Thread.sleep(500); // let the M503 S0 answer settle before the M92 that follows it

        System.out.println();
        System.out.println("=== M92 (bare read-back) ===");
        queue.addPublisher(Priority.REGULAR, Mono.just("M92"));

        var answered = latch.await(timeout.toSeconds(), TimeUnit.SECONDS);
        System.out.printf("%n%d SettingsReport(s) on the bus for 2 unprefixed-shape reads (0 before the fix)%n",
                reports.get());
        if (!answered) {
            System.out.printf("did not see both within %s%n", timeout);
        }

        sending.dispose();
        replies.dispose();
        terminal.stop();
        System.exit(answered && reports.get() == 2 ? 0 : 1);
    }
}

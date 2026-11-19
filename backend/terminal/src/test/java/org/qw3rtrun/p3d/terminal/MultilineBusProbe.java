package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.TerminalTraffic;
import org.qw3rtrun.p3d.g.marlin.command.EndstopStates;
import org.qw3rtrun.p3d.g.marlin.command.ReportSettings;
import org.qw3rtrun.p3d.g.marlin.event.EndstopReport;
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
 * Counts how many values cross {@code messageFlux()} for one {@code M119} read and one {@code M503}
 * read, over the real {@link TerminalManager} stack - the claim issue #35 was reopened over, and the
 * one thing no unit test can make, because the number of lines a firmware answers with is the
 * firmware's business.
 *
 * <p>It is the probe for {@link HostTerminal.ReplyFlux#accumulate}, the way
 * {@code TerminalRecoveryProbe} is the probe for the send window: it prints the raw lines through
 * {@link HostTerminal#observeTraffic} alongside what reached the bus, so the two counts sit next to
 * each other and "N lines in, one event out" is readable rather than asserted.
 *
 * <p>A hand-run probe and not a test: it needs a listener on the far end, so it must never be part
 * of {@code check}. Run it with {@code gradlew :backend:terminal:probeMultilineBus}.
 *
 * <p>Usage: {@code MultilineBusProbe [host] [port] [timeoutSeconds]}, defaulting to
 * {@code localhost 8099 10}.
 *
 * <h2>What the reference run measured</h2>
 *
 * <p>2026-11-19 against MarlinSim (Marlin bugfix-2.1.x) on {@code localhost:8099}:
 *
 * <pre>
 * -&gt; N1 M110 N0*124
 * &lt;- ok N1 P15 B3                                    [AdvancedOkRs]
 * -&gt; N1 M119*28
 * &lt;- Reporting endstop status                         [EndstopReportHeader]
 * &lt;- x_min: open                                      [EndstopStateRs]
 * &lt;- y_min: open                                      [EndstopStateRs]
 * &lt;- z_min: open                                      [EndstopStateRs]
 * &lt;- ok N1 P15 B3                                     [AdvancedOkRs]
 *    bus: EndstopReport(states=[x_min open, y_min open, z_min open])
 * -&gt; N2 M503*24
 * &lt;- echo:; Steps per unit:                           [SettingsSection]
 *    ... 24 more echo: lines ...
 * &lt;- ok N2 P15 B3                                     [AdvancedOkRs]
 *    bus: SettingsReport(25 entries)
 *
 * M119: 5 lines received, 1 EndstopReport on the bus, 3 states in it
 * M503: 26 lines received, 1 SettingsReport on the bus, 25 entries in it
 * </pre>
 *
 * <p>Both reads produce <strong>one</strong> value, which is what the issue asked for; before the
 * accumulation stage the first produced four and the second twenty-five. The {@code ok} ending each
 * burst is still an acknowledgement - visible above as the next block going out, which it could not
 * do if the {@code ok} had been swallowed into the report.
 *
 * <p>Whether anything can land <em>inside</em> either answer is a different measurement and
 * {@code MultilineInterleaveProbe}'s, which is where the 2026-11-19 interleaving capture lives.
 */
public final class MultilineBusProbe {

    private MultilineBusProbe() {
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
        var g = new GSender(gcode -> queue.addPublisher(Priority.REGULAR, Mono.just(gcode)));

        // Every received line, counted through the traffic seam regardless of what claimed it -
        // read once at the M119/M503 boundary (afterEndstops) to split the running total in two.
        var received = new AtomicInteger();
        var endstopReports = new AtomicInteger();
        var settingsReports = new AtomicInteger();
        var endstopDone = new CountDownLatch(1);
        var settingsDone = new CountDownLatch(1);

        Disposable replies = terminal.messageFlux().subscribe(rs -> {
            if (rs instanceof EndstopReport report) {
                System.out.println("   bus: " + report.getStates().size() + " states - " + report);
                endstopReports.incrementAndGet();
                endstopDone.countDown();
            } else if (rs instanceof SettingsReport report) {
                System.out.println("   bus: " + report.getEntries().size() + " entries");
                settingsReports.incrementAndGet();
                settingsDone.countDown();
            }
        });

        // Counting received lines through the traffic seam, which sees every one of them - including
        // the ones a decoder claims and the accumulator then folds away.
        terminal.observeTraffic(line -> {
            if (line.direction() == TerminalTraffic.Direction.RECEIVED) {
                received.incrementAndGet();
            }
            System.out.printf("%s %-50s %s%n",
                    line.direction() == TerminalTraffic.Direction.SENT ? "->" : "<-", line.raw(),
                    line.decodedAs() == null ? "" : "[" + line.decodedAs() + "]");
        });

        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        g.send(new EndstopStates());
        var sawEndstops = endstopDone.await(timeout.toSeconds(), TimeUnit.SECONDS);
        int afterEndstops = received.get();

        g.send(new ReportSettings(false, false));
        var sawSettings = settingsDone.await(timeout.toSeconds(), TimeUnit.SECONDS);

        System.out.printf("%nM119: %d lines received, %d EndstopReport(s) on the bus%n",
                afterEndstops, endstopReports.get());
        System.out.printf("M503: %d lines received, %d SettingsReport(s) on the bus%n",
                received.get() - afterEndstops, settingsReports.get());
        if (!sawEndstops || !sawSettings) {
            System.out.printf("one of the two reads did not answer within %s%n", timeout);
        }

        sending.dispose();
        replies.dispose();
        terminal.stop();
        System.exit(sawEndstops && sawSettings ? 0 : 1);
    }
}

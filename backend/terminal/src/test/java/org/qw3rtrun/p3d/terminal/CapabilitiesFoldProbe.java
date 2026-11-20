package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.TerminalTraffic;
import org.qw3rtrun.p3d.g.marlin.event.CapabilitiesReport;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Sends one {@code M115} over the real {@link TerminalManager} stack and prints what reaches
 * {@code messageFlux()} beside what crossed the wire - the live check for issue #144's fold, which
 * {@code CapabilitiesInterleaveProbe} cannot make: that one measures the <em>wire</em> (nothing
 * lands inside the burst), this one measures the <em>pipeline</em> (the burst leaves as one value
 * and the console still sees every line).
 *
 * <p>A hand-run probe and not a test: it needs a listener on the far end, so it must never be part
 * of {@code check}. Run it with {@code gradlew :backend:terminal:probeCapabilitiesFold}.
 *
 * <p>Usage: {@code CapabilitiesFoldProbe [host] [port] [timeoutSeconds]}, defaulting to
 * {@code localhost 8099 15}. The simulator reboots when a client disconnects, so a run started
 * straight after another can land mid-boot; run it again rather than reading the banner as an answer.
 *
 * <h2>What the reference run measured</h2>
 *
 * <p>2026-11-20 against MarlinSim (Marlin bugfix-2.1.x) on {@code localhost:8099}:
 *
 * <pre>
 * decoded FirmwareInfoRs
 * decoded CapabilitiesReport  38 capabilities
 *   first: Cap:QUOTED_STRINGS:1   last: Cap:CONFIG_EXPORT:0
 * 38 Cap: lines crossed the wire, 1 CapabilitiesReport and 0 bare CapabilityRs reached the bus
 * </pre>
 *
 * <p>So the 38 lines the firmware really sends arrive as <strong>one</strong> value in the firmware's
 * own order, no individual {@code Cap:} line reaches a subscriber any more, and the per-line
 * annotation a console renders ({@code [CapabilityRs]}, 38 of them) is untouched by the fold above
 * it - {@code decodedAs} is a property of the line, not of what the line produced.
 */
public final class CapabilitiesFoldProbe {

    private CapabilitiesFoldProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;
        var timeout = Duration.ofSeconds(args.length > 2 ? Long.parseLong(args[2]) : 15);

        System.out.printf("connecting to %s:%d%n", host, port);
        var manager = new TerminalManager(new ConnectionDetails(host, port));
        var terminal = manager.connect().block(timeout);
        Objects.requireNonNull(terminal, "no connection to " + host + ":" + port);

        // The sink and the queue PrinterReactor builds, so the probe sends the way production does.
        var queue = new PublisherQueue<String, Priority>();
        Consumer<String> sink = gcode -> queue.addPublisher(Priority.REGULAR, Mono.just(gcode));
        var g = new GSender(sink);

        var reported = new CountDownLatch(1);
        var reports = new AtomicInteger();
        var bareCapabilities = new AtomicInteger();
        Disposable replies = terminal.messageFlux().subscribe(rs -> {
            if (rs instanceof CapabilityRs bare) {
                bareCapabilities.incrementAndGet();
                System.out.println("decoded " + bare.encode() + "   <- the fold let a single line through");
                return;
            }
            if (!(rs instanceof CapabilitiesReport report)) {
                System.out.println("decoded " + rs.getClass().getSimpleName());
                return;
            }
            reports.incrementAndGet();
            var capabilities = report.getCapabilities();
            System.out.printf("decoded CapabilitiesReport  %d capabilities%n", capabilities.size());
            if (!capabilities.isEmpty()) {
                System.out.printf("  first: %s   last: %s%n",
                        capabilities.getFirst().encode(), capabilities.getLast().encode());
            }
            reported.countDown();
        });

        // The console's view, which the fold must leave alone: every Cap: line, annotated as itself.
        var capabilityLines = new AtomicInteger();
        terminal.observeTraffic(line -> {
            if (line.direction() == TerminalTraffic.Direction.RECEIVED
                    && "CapabilityRs".equals(line.decodedAs())) {
                capabilityLines.incrementAndGet();
            }
        });

        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        g.m115();

        var answered = reported.await(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!answered) {
            System.out.printf("no CapabilitiesReport within %s%n", timeout);
        }
        System.out.printf("%d Cap: lines crossed the wire, %d CapabilitiesReport and %d bare"
                        + " CapabilityRs reached the bus%n",
                capabilityLines.get(), reports.get(), bareCapabilities.get());

        sending.dispose();
        replies.dispose();
        terminal.stop();
        System.exit(answered && reports.get() == 1 && bareCapabilities.get() == 0 ? 0 : 1);
    }
}

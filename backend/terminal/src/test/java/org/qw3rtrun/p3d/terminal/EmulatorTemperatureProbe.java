package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.TerminalTraffic;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
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
 * Asks a running printer - in practice the Marlin emulator - for a temperature report over the real
 * {@link TerminalManager} stack, and prints what comes back.
 *
 * <p>A hand-run probe and not a test: it needs a listener on the far end, so it must never be part
 * of {@code check}. It exists to exercise the path a raw socket cannot - the send window in
 * {@link HostTerminal.CommandProcessor}, the line framing in {@link HostTerminal.ReplyFlux}, the
 * priority queue in {@link PublisherQueue} - against a real peer rather than a mock, which is the
 * one thing the unit tests cannot do.
 *
 * <p>Run it with {@code gradlew :backend:terminal:probeTemperature}, or from the
 * "Marlin emulator temperature probe" run configuration.
 *
 * <p>Usage: {@code EmulatorTemperatureProbe [host] [port] [timeoutSeconds]}, defaulting to
 * {@code localhost 8099 10}.
 *
 * <p>It also prints {@link HostTerminal#observeTraffic} as a console renders it - the raw line with
 * the decode as an annotation, which is the only view that shows the outbound direction and the
 * lines {@code messageFlux()} drops. Measured against MarlinSim (Marlin bugfix-2.1.x) on 2026-11-18,
 * a whole session being four lines:
 *
 * <pre>
 * -&gt; N1 M110 N0*124
 * &lt;- ok N1 P15 B3                               [AdvancedOkRs]
 * -&gt; N1 M105*38
 * &lt;- ok T:25.00 /0.00 B:25.00 /0.00 @:0 B@:0     [OkTemperatureRs]
 * 4 lines crossed the wire, 0 of them received and claimed by no decoder
 * </pre>
 *
 * <p>Two of those four reach no subscriber of {@code messageFlux()} at all - the framed
 * {@code M110 N0} has no inbound form, and the {@code ok N1 P15 B3} is window credit the transport
 * consumes - while the bus for the same session carried a {@code BlockAcknowledged} and the
 * temperature. That is the gap the seam exists to close; {@code TerminalConsoleProbe} measures its
 * full size (25 of 106 lines) over a battery of commands.
 */
public final class EmulatorTemperatureProbe {

    private EmulatorTemperatureProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;
        var timeout = Duration.ofSeconds(args.length > 2 ? Long.parseLong(args[2]) : 10);

        System.out.printf("connecting to %s:%d%n", host, port);
        var manager = new TerminalManager(new ConnectionDetails(host, port));
        var terminal = manager.connect().block(timeout);
        Objects.requireNonNull(terminal, "no connection to " + host + ":" + port);

        // The sink and the queue PrinterReactor builds, so the probe sends the way production does:
        // GSender renders, the queue orders by priority, the send window decides when it goes out.
        var queue = new PublisherQueue<String, Priority>();
        Consumer<String> sink = gcode -> queue.addPublisher(Priority.REGULAR, Mono.just(gcode));
        var g = new GSender(sink);

        var reported = new CountDownLatch(1);
        // Already decoded: HostTerminal reads the line once, through MarlinRsDecoder, and hands
        // over the typed reply. This used to take raw text and decode it a second time.
        Disposable replies = terminal.messageFlux()
                .doOnNext(rs -> System.out.println("decoded " + rs))
                .subscribe(rs -> {
                    if (rs instanceof TemperatureRs<?> temp) {
                        print(temp);
                        reported.countDown();
                    }
                });
        // Issue #135's seam, printed the way a console renders it: the raw line, with the decode as
        // an annotation. It is the only view that shows what messageFlux() drops - a line no decoder
        // claims reaches no subscriber at all - and the only one that shows the outbound direction.
        var crossed = new AtomicInteger();
        var unclaimed = new AtomicInteger();
        terminal.observeTraffic(line -> {
            crossed.incrementAndGet();
            if (line.direction() == TerminalTraffic.Direction.RECEIVED && line.decodedAs() == null) {
                unclaimed.incrementAndGet();
            }
            System.out.printf("%s %-58s %s%n",
                    line.direction() == TerminalTraffic.Direction.SENT ? "->" : "<-", line.raw(),
                    line.decodedAs() == null ? "" : "[" + line.decodedAs() + "]");
        });

        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        g.m105();

        var answered = reported.await(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!answered) {
            System.out.printf("no temperature report within %s%n", timeout);
        }
        System.out.printf("%d lines crossed the wire, %d of them received and claimed by no decoder%n",
                crossed.get(), unclaimed.get());

        sending.dispose();
        replies.dispose();
        terminal.stop();
        System.exit(answered ? 0 : 1);
    }

    private static void print(TemperatureRs<?> temp) {
        System.out.println("temperature report" + (temp.getOk() ? " (on an ok)" : " (bare)"));
        print("hotend", temp.getHotend());
        print("bed", temp.getBed());
        print("chamber", temp.getChamber());
        print("probe", temp.getProbe());
        print("cooler", temp.getCooler());
        print("board", temp.getBoard());
        temp.getHotends().forEach((index, reading) -> print("hotend " + index, reading));
    }

    private static void print(String name, HeaterReading reading) {
        if (reading == null) {
            return;
        }
        System.out.printf("  %-10s %s", name, reading.getCurrent().toPlainString());
        if (reading.getTarget() != null) {
            System.out.printf(" -> %s", reading.getTarget().toPlainString());
        }
        if (reading.getPower() != null) {
            System.out.printf(" @ %d/127", reading.getPower());
        }
        System.out.println();
    }
}

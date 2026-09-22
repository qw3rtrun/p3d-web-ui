package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.MarlinRsDecoder;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import org.qw3rtrun.p3d.terminal.msg.Replay;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        Disposable replies = terminal.messageFlux()
                .map(Replay.Message::raw)
                .map(MarlinRsDecoder.INSTANCE::decode)
                .filter(Objects::nonNull)
                .doOnNext(rs -> System.out.println("decoded " + rs))
                .subscribe(rs -> {
                    if (rs instanceof TemperatureRs<?> temp) {
                        print(temp);
                        reported.countDown();
                    }
                });
        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        g.m105();

        var answered = reported.await(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!answered) {
            System.out.printf("no temperature report within %s%n", timeout);
        }

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

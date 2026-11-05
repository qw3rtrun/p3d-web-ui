package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.SdFileOpened;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Uploads a file to the card through the whole transport stack while a slow source competes for the
 * wire, and measures whether anything leaked into it. A hand-run probe, not a test - it needs a
 * listener and writes to the card, so never part of {@code check}. {@code SdWriteProbe} established
 * what the firmware does over a raw socket; this one goes through {@link TerminalManager},
 * {@link HostTerminal} and {@link PublisherQueue}, the only way to exercise the thing measured.
 *
 * <p><b>Why a byte count settles it.</b> {@code M28 <name>} turns the wire into a file sink until
 * {@code M29}, so a command from another source reaching the wire in between is silently stored in
 * the file. The firmware strips the {@code N} word and checksum before writing ({@code SdWriteProbe}
 * established this), so a finished file is exactly its payloads plus a {@code CRLF} each - making
 * the size an exact measure: one byte over what was uploaded is a file something got into. The size
 * is read with {@code M23}'s decoded {@code File opened: <name> Size: <n>}; {@code M20}'s listing is
 * reported alongside it as the only live exercise of the transport's assembly of that reply.
 *
 * <p><b>Why the competitor is a slow source, not one-shot commands.</b> A stream of
 * {@code Mono.just} spends its demand instantly and completes, so nothing is ever left holding
 * demand and priority alone keeps the upload at the head - the first version of this probe tried
 * that and came back clean, proving nothing. The real hazard is demand already handed out to a
 * source that has not spent it yet (a print reading blocks off a file); {@link SlowSource} emits one
 * line per tick and only while it has demand, well-behaved and never overrunning. The shared run is
 * the control and is meant to come back too long - without it this cannot tell "the mechanism works"
 * from "the race did not fire today".
 *
 * <p>Usage: {@code SdUploadProbe [host] [port]}, defaulting to {@code localhost 8099}.
 */
public final class SdUploadProbe {

    private SdUploadProbe() {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    /** How often the competing source produces a line, once it has been asked for one. */
    private static final Duration TICK = Duration.ofMillis(15);

    /**
     * The file's content - long enough that the competitor has many chances to cut in.
     *
     * <p>Every line is inert on purpose. {@code M118} prints a message and moves nothing, and
     * {@code G90} is idempotent, so a file printed by accident afterwards cannot damage a machine.
     */
    private static List<String> content() {
        var lines = new ArrayList<String>();
        lines.add("G90");
        for (int i = 0; i < 40; i++) {
            lines.add("M118 P0 line " + i);
        }
        return lines;
    }

    /** What the card should report if nothing leaked: the payloads plus a CRLF each. */
    private static long expectedSize(List<String> lines) {
        long total = 0;
        for (var line : lines) {
            total += line.getBytes(StandardCharsets.UTF_8).length + 2;
        }
        return total;
    }

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;

        Result exclusive = run(host, port, "excl.gco", true);
        Result shared = run(host, port, "shared.gco", false);
        boolean closedOnStop = stopRun(host, port, "stopped.gco");

        System.out.println();
        System.out.println("=== what to read out of it ===");
        System.out.printf("  exclusive : %s%n", exclusive);
        System.out.printf("  shared    : %s%n", shared);
        System.out.printf("  stopped   : %s%n", closedOnStop
                ? "M29 closed the file - the next command was not written into it"
                : "NOT CLOSED, or not measured");
        System.out.println();
        if (shared.intact()) {
            System.out.println("  !! the control came back clean, so the competing source never");
            System.out.println("     actually got in and the exclusive run above proves nothing.");
            System.out.println("     Raise the tick rate or lengthen the file and run it again.");
        } else {
            System.out.println("  The control came back too long, which is the competing source");
            System.out.println("  reaching the wire mid-transfer. That is what makes the exclusive");
            System.out.println("  run a measurement of the mechanism rather than of a quiet moment.");
        }

        System.exit(exclusive.intact() && !shared.intact() && closedOnStop ? 0 : 1);
    }

    /**
     * Stops a transfer part-way and proves the firmware really closed the file - the claim no unit
     * test can make. A stop must end the <em>content</em> and let {@code M29} follow
     * ({@code SdUploader.transferOf} builds that shape, {@code SdUploaderTest} pins it), but only the
     * firmware can say whether that {@code M29} actually closed the file. So this measures the file
     * twice, once right after the stop and once after an ordinary command: unchanged means closed; a
     * size that grew means every command from now on is being written into an unread G-code file.
     * The shape is rebuilt here rather than imported since {@code :backend:terminal} cannot see
     * {@code :backend:core} - this probe owns the firmware half of the claim.
     */
    private static boolean stopRun(String host, int port, String file) throws Exception {
        System.out.println();
        System.out.printf("=== uploading %s and stopping half way ===%n", file);

        var manager = new TerminalManager(new ConnectionDetails(host, port));
        var terminal = manager.connect().block(Duration.ofSeconds(10));
        Objects.requireNonNull(terminal, "no connection to " + host + ":" + port);

        var queue = new PublisherQueue<String, Priority>();
        var sizes = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var measured = new java.util.concurrent.atomic.AtomicInteger();

        Disposable replies = terminal.messageFlux().subscribe(message -> {
            if (message instanceof SdFileOpened reply && reply.getFile().equalsIgnoreCase(file)) {
                sizes.add(reply.getSize());
                measured.incrementAndGet();
            }
        });
        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        queue.addPublisher(Priority.REGULAR, Flux.just("M21", "M30 " + file)).block(Duration.ofSeconds(20));

        var lines = content();
        int stopAfter = 10;
        var stop = Sinks.<Void>empty();
        var sent = new java.util.concurrent.atomic.AtomicInteger();

        // The same arrangement SdUploader builds: takeUntilOther ends the content, M29 is
        // concatenated behind it, so M29 goes out either way.
        Flux<String> content = Flux.fromIterable(lines)
                .doOnNext(block -> {
                    if (sent.incrementAndGet() == stopAfter) {
                        stop.tryEmitEmpty();
                    }
                })
                .takeUntilOther(stop.asMono());

        Flux<String> transfer = Flux.concat(
                Mono.just("M28 " + file),
                content,
                Mono.just("M29"));

        queue.addExclusivePublisher(Priority.REALTIME, transfer).block(TIMEOUT);
        System.out.printf("  stopped after %d of %d block(s)%n", sent.get(), lines.size());

        queue.addPublisher(Priority.REGULAR, Mono.just("M23 " + file)).block(Duration.ofSeconds(20));
        Thread.sleep(500);

        // An ordinary command. If the file is still open this is written into it and the size grows.
        var marker = "M118 P0 WOULD-BE-WRITTEN-IN";
        queue.addPublisher(Priority.REGULAR, Mono.just(marker)).block(Duration.ofSeconds(20));
        queue.addPublisher(Priority.REGULAR, Mono.just("M23 " + file)).block(Duration.ofSeconds(20));
        Thread.sleep(500);

        sending.dispose();
        replies.dispose();
        terminal.stop();

        var reported = new java.util.ArrayList<>(sizes);
        if (reported.size() < 2) {
            System.out.printf("  !! only %d size(s) came back, so nothing was measured%n", reported.size());
            return false;
        }
        long before = reported.get(reported.size() - 2);
        long after = reported.get(reported.size() - 1);
        System.out.printf("  the card reports %d bytes, then %d after an unrelated command%n", before, after);

        if (before == after) {
            System.out.println("  -> unchanged: M29 closed the file, so the command was executed");
            return true;
        }
        System.out.printf("  -> grew by %d bytes: the file was left OPEN and '%s' went into it%n",
                after - before, marker);
        return false;
    }

    /** What one run found: the size the card reported against the size that was sent. */
    private record Result(boolean measured, long expected, long actual) {

        boolean intact() {
            return measured && expected == actual;
        }

        @Override
        public String toString() {
            if (!measured) {
                return "nothing was measured";
            }
            long over = actual - expected;
            return over == 0
                    ? "exact at " + actual + " bytes - nothing else reached the wire"
                    : String.format("%d bytes, %+d over - about %d stray line(s) were written in",
                    actual, over, Math.round(over / 6.0));
        }
    }

    private static Result run(String host, int port, String file, boolean exclusive) throws Exception {
        System.out.println();
        System.out.printf("=== uploading %s, %s ===%n", file, exclusive ? "exclusively" : "shared (the control)");

        var manager = new TerminalManager(new ConnectionDetails(host, port));
        var terminal = manager.connect().block(Duration.ofSeconds(10));
        Objects.requireNonNull(terminal, "no connection to " + host + ":" + port);

        var queue = new PublisherQueue<String, Priority>();
        var opened = new AtomicReference<SdFileOpened>();
        var sawSize = new CountDownLatch(1);
        var listing = new AtomicReference<SdFileList>();
        var sawListing = new CountDownLatch(1);

        Disposable replies = terminal.messageFlux().subscribe(message -> {
            if (message instanceof SdFileOpened reply && reply.getFile().equalsIgnoreCase(file)) {
                opened.set(reply);
                sawSize.countDown();
            }
            // The listing is HostTerminal.ReplyFlux's own assembly of several lines; nothing
            // downstream could see one before that was built.
            if (message instanceof SdFileList files) {
                listing.set(files);
                sawListing.countDown();
            }
        });
        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        // Mount and clear any leftover of this name, and WAIT for it - the first version of this
        // probe queued the cleanup and went straight to the upload, so REALTIME outranked REGULAR
        // and M30 ran after the M28 that created the file, deleting it the moment it was written.
        queue.addPublisher(Priority.REGULAR, Flux.just("M21", "M30 " + file)).block(Duration.ofSeconds(20));

        var lines = content();
        var transfer = new ArrayList<String>();
        transfer.add("M28 " + file);
        transfer.addAll(lines);
        transfer.add("M29");

        // The competitor runs before the upload is queued, so it already holds demand when the
        // transfer starts - priority decides who gets the *next* unit, not who already holds one.
        var pest = new SlowSource("M105");
        queue.addPublisher(Priority.BACKGROUND, pest);
        Thread.sleep(200);

        Mono<Void> done = exclusive
                ? queue.addExclusivePublisher(Priority.REALTIME, Flux.fromIterable(transfer))
                : queue.addPublisher(Priority.REALTIME, Flux.fromIterable(transfer));

        boolean finished = true;
        try {
            done.block(TIMEOUT);
            System.out.println("  the transfer finished");
        } catch (Exception e) {
            finished = false;
            System.out.println("  !! the transfer did not finish: " + e);
        }
        System.out.printf("  the competitor emitted %d line(s) over the run%n", pest.emitted());

        // M23 selects the file without printing it, and answers with its size.
        queue.addPublisher(Priority.EMERGENCY, Mono.just("M23 " + file));
        boolean answered = sawSize.await(30, TimeUnit.SECONDS);

        // M20's answer is several lines the transport assembles into one reply - reported rather
        // than asserted, since what this run measures is the upload.
        queue.addPublisher(Priority.EMERGENCY, Mono.just("M20"));
        if (sawListing.await(30, TimeUnit.SECONDS)) {
            System.out.printf("  M20 assembled into one reply: %d file(s) %s%n",
                    listing.get().getFiles().size(), listing.get().getFiles());
        } else {
            System.out.println("  !! M20 produced no listing event");
        }

        pest.stop();
        sending.dispose();
        replies.dispose();
        terminal.stop();

        long expected = expectedSize(lines);
        System.out.printf("  expected %d bytes (%d payload lines plus a CRLF each)%n", expected, lines.size());

        if (!finished || !answered) {
            System.out.println("  !! no size came back, so nothing was measured");
            return new Result(false, expected, 0);
        }

        long actual = opened.get().getSize();
        System.out.printf("  the card reports %d bytes for %s%n", actual, opened.get().getFile());
        var result = new Result(true, expected, actual);
        System.out.println("  -> " + result);
        return result;
    }

    /**
     * A source that produces one line per tick, only while it has demand - a stand-in for a print
     * feeding blocks off a file. Deliberately well-behaved (never emits without demand, so never
     * trips {@link PublisherQueue}'s overrun guard). {@code Flux.interval} would not do: it produces
     * on a clock regardless of demand, so withheld demand during an exclusive transfer would overflow
     * it and take the whole queue down - a fixture failure reading as a failure of the thing tested.
     */
    private static final class SlowSource implements Publisher<String> {

        private final String line;
        private final Requested demand = new Requested();
        private final java.util.concurrent.atomic.AtomicLong emitted =
                new java.util.concurrent.atomic.AtomicLong();
        private volatile Disposable ticker;
        private volatile boolean stopped;

        SlowSource(String line) {
            this.line = line;
        }

        long emitted() {
            return emitted.get();
        }

        void stop() {
            stopped = true;
            var t = ticker;
            if (t != null) {
                t.dispose();
            }
        }

        @Override
        public void subscribe(Subscriber<? super String> s) {
            s.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    demand.inc(n);
                }

                @Override
                public void cancel() {
                    stop();
                }
            });
            ticker = Schedulers.parallel().schedulePeriodically(() -> {
                if (stopped || demand.get() <= 0) {
                    return;
                }
                demand.dec();
                emitted.incrementAndGet();
                s.onNext(line);
            }, TICK.toMillis(), TICK.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}

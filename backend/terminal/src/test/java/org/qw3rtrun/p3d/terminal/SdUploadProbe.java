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
 * wire, and measures whether anything leaked into it.
 *
 * <p>A hand-run probe and not a test: it needs a listener on the far end and it writes to the card,
 * so it must never be part of {@code check}. {@code SdWriteProbe} does the same exchange over a raw
 * socket to establish what the firmware does; this one goes through {@link TerminalManager},
 * {@link HostTerminal} and {@link PublisherQueue}, which is the only way to exercise the thing being
 * measured.
 *
 * <h2>Why a byte count settles it</h2>
 *
 * <p>{@code M28 <name>} turns the wire into a file sink: every line sent after it is <em>written
 * into the file</em> rather than executed, until {@code M29}. A command from another source that
 * reaches the wire in between is stored in the G-code, and nothing on either end reports it - the
 * printer takes it happily and the fault surfaces much later as a line of nonsense mid-print.
 *
 * <p>The firmware strips the {@code N} word and the checksum before writing, which
 * {@code SdWriteProbe} established, so a finished file is exactly its payloads plus a {@code CRLF}
 * each. That makes the size an <strong>exact</strong> measure: one byte over what was uploaded is a
 * file something got into.
 *
 * <p>The size is read with {@code M23}, whose {@code File opened: <name> Size: <n>} is a decoded
 * reply. {@code M20}'s listing is reported alongside it, which is the only live exercise of the
 * transport's assembly of that reply - the entry lines are deliberately not claimable replies, so
 * before {@code ReplyFlux} learned to assemble them nothing downstream could see a listing at all.
 *
 * <h2>Why the competitor is a slow source and not a stream of one-shot commands</h2>
 *
 * <p>Because a stream of one-shot commands <em>cannot</em> demonstrate the failure, which the first
 * version of this probe found out the hard way: each {@code Mono.just} spends its unit of demand the
 * instant it is given one and completes, so nothing is ever left holding demand and the upload's
 * priority alone keeps it at the head. The transcript came back clean and proved nothing.
 *
 * <p>The hazard is <strong>demand already handed out to a source that has not spent it yet</strong>
 * - a print reading blocks off a file, which is given a few units and produces them a moment later.
 * {@link SlowSource} is that, and nothing more: it emits one line per tick and only while it has
 * demand, so it is well-behaved and never overruns.
 *
 * <p>The shared run is the control and is meant to come back too long. Without it this could not
 * tell "the mechanism works" from "the race did not fire today", and the failure mode is a race.
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
     * Stops a transfer part-way and proves the firmware really closed the file.
     *
     * <p><strong>The claim no unit test can make.</strong> A stop must not cancel the transfer - it
     * ends the <em>content</em> and lets {@code M29} follow, which is what
     * {@code SdUploader.transferOf} builds and {@code SdUploaderTest} pins. What that test cannot
     * show is the half that lives in the firmware: that the {@code M29} actually closed the file,
     * so the next command is executed rather than appended to it.
     *
     * <p>So this measures the file twice. Once right after the stop, and once after sending an
     * ordinary command. <strong>If the size does not move, the file was closed.</strong> If it grew
     * by the length of that command, it was not, and every command the machine is given from now on
     * is being written into a G-code file nobody is reading.
     *
     * <p>The shape is built here rather than imported because {@code :backend:terminal} cannot see
     * {@code :backend:core}. That split is deliberate: this probe owns the firmware half of the
     * claim and {@code SdUploaderTest} owns the "production builds this shape" half.
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

        // The same arrangement SdUploader builds: the content is ended by takeUntilOther and the
        // close is concatenated behind it, so M29 goes out either way.
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
            // The listing is the transport's own assembly of several lines - see HostTerminal's
            // ReplyFlux. Nothing downstream could see one at all before that was built, so this is
            // the live check on it.
            if (message instanceof SdFileList files) {
                listing.set(files);
                sawListing.countDown();
            }
        });
        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        // Mount, and clear any leftover of this name - and WAIT for it. The first version of this
        // probe queued the cleanup and went straight on to the upload, which put an `M30` for the
        // file *after* the `M28` that created it, because REALTIME outranks REGULAR and the queue
        // did exactly what it was told. The file was deleted the moment it was written.
        queue.addPublisher(Priority.REGULAR, Flux.just("M21", "M30 " + file)).block(Duration.ofSeconds(20));

        var lines = content();
        var transfer = new ArrayList<String>();
        transfer.add("M28 " + file);
        transfer.addAll(lines);
        transfer.add("M29");

        // The competitor, running before the upload is queued so that it is already holding demand
        // when the transfer starts. That is the hazard: priority decides who gets the *next* unit,
        // not who is holding one already.
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

        // And M20, whose answer is several lines the transport assembles into one reply. Reported
        // rather than asserted: what this run is measuring is the upload, and the listing is here
        // because it is the only live exercise of that assembly.
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
     * A source that produces one line per tick, and only while it has demand.
     *
     * <p>A stand-in for a print feeding blocks off a file: it is asked for several and answers a
     * moment later, which is the whole shape of the problem. Deliberately well-behaved - it never
     * emits without demand, so it never trips {@link PublisherQueue}'s overrun guard and never
     * clouds the measurement with a cancelled source.
     *
     * <p>{@code Flux.interval} would not do: it produces on a clock whatever the demand, so with
     * the queue withholding demand during an exclusive transfer it would overflow, error, and take
     * the whole queue down with it - a failure of the test fixture that would read as a failure of
     * the thing under test.
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

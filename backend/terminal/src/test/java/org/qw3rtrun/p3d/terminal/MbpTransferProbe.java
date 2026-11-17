package org.qw3rtrun.p3d.terminal;

import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.SdFile;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Writes one file to the card over Marlin's Binary Protocol through the whole transport stack, and
 * measures whether it landed at the exact byte count. A hand-run probe, not a test - it needs a
 * printer and it writes to the card, so it is never part of {@code check}.
 *
 * <p>It is the only thing that exercises the arrangement {@code :backend:core} has to build, which
 * no unit test can: the <strong>ASCII {@code M28 B1} and the packet stream are two different
 * writers</strong> on one wire, and the first must still hold the queue's exclusivity while the
 * second runs. So the exclusive publisher emits {@code M28 B1} and then <em>nothing</em> until the
 * transfer's flux ends - a {@code Mono.just("M28 B1")} would complete, release the exclusivity and
 * let another source write an ASCII line into the middle of a packet.
 *
 * <p>Usage: {@code MbpTransferProbe [host] [port] [cancelAfterMs]}, defaulting to
 * {@code localhost 8099} and no cancel. The card is left as it was found - the file is deleted with
 * {@code M30} on the way out (commit {@code 643d900} set that rule).
 *
 * <p><strong>A stopped transfer releases the wire only once it has left binary mode</strong>
 * (#132). The transfer is stopped through its {@code cancel} {@code Mono} - after
 * {@code cancelAfterMs}, or after 60s without one - and the exclusive source is released from the
 * flux's {@code doFinally}, never from a wait that gave up. Disposing the flux instead, as this
 * probe once did through {@code block(TIMEOUT)}, shared the wire while the driver was still between
 * its ABORT and its CONTROL CLOSE: measured 2026-11-17 behind ESP3D, the {@code M21}/{@code M20}
 * that followed completed a stuck FILE OPEN after the ABORT had been handled, and the CLOSE left
 * Marlin in M28 save mode - every later line written into the file and answered with a bare
 * {@code ok}, until a manual {@code M29}. If the transfer does not end within 20s of being
 * cancelled the probe sends nothing more and exits 2.
 *
 * <p><strong>What it captured.</strong> 2026-11-16, MarlinSim {@code Marlin bugfix-2.1.x} compiled
 * Oct 29 2026, on {@code localhost:8099}. {@code M115} answered
 * {@code Cap:BINARY_FILE_TRANSFER:1}; a 250-byte file landed on the card at 250 bytes exactly, and
 * {@code M30} left it as it was found:
 *
 * <pre>
 * -&gt; N1 M110 N0*124                   &lt;- ok N1 P15 B3
 * -&gt; N4 M28 B1*78                     &lt;- echo:Switching to Binary Protocol
 * -&gt; AD B5 00 01 00 00 01 03          &lt;- ok N4 P15 B3         (the ASCII ok, AFTER the echo)
 *                                     &lt;- ss22,96,0.1.0        (no ok for a SYNC, ever)
 * -&gt; AD B5 16 10 00 00 26 88          &lt;- ok22
 *                                     &lt;- PFT:version:0.1.0:compression:heatshrink,8,4
 * -&gt; AD B5 17 11 0A 00 32 A3 00 00    &lt;- ok23
 *    6D 62 70 2E 67 63 6F 00 B0 AC    &lt;- echo:SD card ok
 *                                     &lt;- echo:Now fresh file: mbp.gco
 *                                     &lt;- Writing to file: mbp.gco
 *                                     &lt;- //action:notification mbp.gco
 *                                     &lt;- PFT:success
 * -&gt; AD B5 18 13 60 00 8B 5A ..96..   &lt;- ok24    (96 of 250 bytes acknowledged)
 * -&gt; AD B5 19 13 60 00 8C 5E ..96..   &lt;- ok25    (192)
 * -&gt; AD B5 1A 13 3A 00 67 16 ..58..   &lt;- ok26    (250 - the last block jumps to the file size)
 * -&gt; AD B5 1B 12 00 00 2D A2          &lt;- ok27
 *                                     &lt;- echo:SD card released
 *                                     &lt;- PFT:success
 * -&gt; AD B5 1C 02 00 00 1E 76          &lt;- ok28
 * -&gt; N5 M21*21 / N6 M20*23            &lt;- MBP.GCO 250     in the listing, byte-exact
 * -&gt; N7 M30 mbp.gco*13                &lt;- File deleted:mbp.gco
 * </pre>
 *
 * <p>Four facts worth not re-deriving. The firmware's payload buffer is <strong>96 bytes</strong>,
 * not the stock 512, which is why {@code MbpTransfer} must be built after the {@code ss}. The sync
 * id is <strong>not</strong> reset by connecting or by {@code M28 B1} - consecutive runs of this
 * probe answered {@code ss15}, {@code ss22} and {@code ss29} - so it has to be adopted from the
 * {@code ss}.
 * The firmware <strong>releases the card</strong> on close ({@code echo:SD card released}), which is
 * why the {@code M20} below is preceded by an {@code M21}; {@code :backend:core} needs the same.
 *
 * <p>And the fourth is the one that justifies setting the mode flag eagerly: the {@code ok} for
 * {@code M28 B1} is <strong>{@code ok N4 P15 B3}</strong>, it arrives <em>after</em> the echo and
 * after the host has already written its {@code SYNC} packet, and it still decoded as an
 * {@code AdvancedOkRs} and still credited the ASCII send window - because {@code MbpRsDecoder}
 * claims {@code ok<digits>} only. Marlin never writes {@code ok4} for an ASCII line, so there is no
 * boundary case to guard.
 *
 * <p>The simulator reboots when a client disconnects, so a run straight after another one can land
 * mid-boot; and it always has a card, so no-card paths cannot be measured here.
 */
public final class MbpTransferProbe {

    private MbpTransferProbe() {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final Duration ASCII_TIMEOUT = Duration.ofSeconds(20);

    /** How long a cancelled transfer gets to leave binary mode: above the driver's own give-up of
     * 5 x 2s, so a CLOSE that is never answered still ends in the driver's failure, not here. */
    private static final Duration CANCEL_BUDGET = Duration.ofSeconds(20);

    /** The name as it is sent; the card answers with the 8.3 upper case of it. */
    private static final String NAME = "mbp.gco";

    /** The file's size: two whole 96-byte blocks and a short third, so the block arithmetic is
     * exercised rather than landing on a boundary. */
    private static final int SIZE = 250;

    /**
     * Exactly {@link #SIZE} bytes - whole lines while another still fits, then a comment to the
     * byte, so the size the card reports can be compared against a number and not an estimate.
     *
     * <p>Every line is inert. {@code M118} prints a message and moves nothing and {@code G90} is
     * idempotent, so a file printed by accident cannot damage a machine.
     */
    private static byte[] content() {
        var text = new StringBuilder("G90\n");
        for (int i = 0; text.length() < SIZE - 25; i++) {
            text.append("M118 P0 mbp line ").append(i).append('\n');
        }
        while (text.length() < SIZE - 1) {
            text.append(';');
        }
        text.append('\n');
        return text.toString().getBytes(US_ASCII);
    }

    public static void main(String[] args) throws Exception {
        var host = args.length > 0 ? args[0] : "localhost";
        var port = args.length > 1 ? Integer.parseInt(args[1]) : 8099;
        Duration cancelAfter = args.length > 2 && !args[2].isBlank()
                ? Duration.ofMillis(Long.parseLong(args[2])) : null;
        byte[] file = content();

        var manager = new TerminalManager(new ConnectionDetails(host, port));
        var terminal = manager.connect().block(Duration.ofSeconds(10));
        Objects.requireNonNull(terminal, "no connection to " + host + ":" + port);

        var queue = new PublisherQueue<String, Priority>();
        var listings = new ConcurrentLinkedQueue<SdFileList>();
        var sawListing = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
        var binaryCapable = new AtomicReference<String>("not reported");

        Disposable replies = terminal.messageFlux().subscribe(message -> {
            if (message instanceof SdFileList files) {
                listings.add(files);
                sawListing.get().countDown();
            }
            if (message instanceof CapabilityRs cap && cap.getCapability().equals("BINARY_FILE_TRANSFER")) {
                binaryCapable.set(cap.getEnabled() ? "yes" : "no");
            }
        });
        Disposable sending = terminal.start(Flux.from(queue)).subscribe();

        System.out.println();
        System.out.println("=== what the printer says it can do ===");
        queue.addPublisher(Priority.REGULAR, Flux.just("M115", "M21", "M30 " + NAME)).block(ASCII_TIMEOUT);
        System.out.printf("  Cap:BINARY_FILE_TRANSFER -> %s%n", binaryCapable.get());

        System.out.println();
        System.out.printf("=== transferring %s, %d bytes ===%n", NAME, file.length);

        // The arrangement :backend:core has to build. M28 B1 goes out as an ordinary numbered
        // block, and the source it came from then holds the queue's exclusivity, emitting nothing,
        // until the transfer's flux ends - so no other source can put an ASCII line inside a packet.
        var transferred = Sinks.<Void>empty();
        Mono<Void> exclusive = queue.addExclusivePublisher(Priority.REALTIME,
                Flux.concat(Mono.just("M28 B1"), Flux.<String>never())
                        .takeUntilOther(transferred.asMono()));

        // The exclusivity is released when the transfer flux has *terminated* - never when a wait
        // on it gives up. A transfer stopped from here aborts through further packets and replies
        // (ABORT, then CONTROL CLOSE), so until it terminates the printer is still reading packets,
        // and an ASCII line let in early lands in its packet reader (#132).
        var stop = Sinks.<Void>empty();
        var ended = new CountDownLatch(1);
        var progress = new CopyOnWriteArrayList<Long>();
        var outcome = new AtomicReference<>("completed");
        terminal.binaryTransfer(NAME, file, stop.asMono())
                .doFinally(signal -> {
                    transferred.tryEmitEmpty();
                    ended.countDown();
                })
                .subscribe(bytes -> {
                    progress.add(bytes);
                    System.out.printf("  %d of %d bytes acknowledged%n", bytes, file.length);
                }, failed -> outcome.set("FAILED: " + failed.getMessage()));

        Duration budget = cancelAfter == null ? TIMEOUT : cancelAfter;
        if (!ended.await(budget.toMillis(), TimeUnit.MILLISECONDS)) {
            // A cancellation completes rather than errors, so the outcome is set before it fires.
            outcome.set("CANCELLED after " + budget.toMillis() + "ms");
            stop.tryEmitEmpty();
            if (!ended.await(CANCEL_BUDGET.toMillis(), TimeUnit.MILLISECONDS)) {
                // Still in binary mode as far as anything here knows, so no ASCII may follow - not
                // even the M30 that would tidy the card.
                System.out.printf("  !! the transfer did not end within %dms of being cancelled; "
                        + "sending nothing more, the printer may still be in binary mode%n",
                        CANCEL_BUDGET.toMillis());
                sending.dispose();
                replies.dispose();
                terminal.stop();
                System.exit(2);
            }
        }
        exclusive.block(ASCII_TIMEOUT);
        System.out.printf("  the transfer %s, reporting %s%n", outcome.get(), progress);

        System.out.println();
        System.out.println("=== what landed on the card ===");
        // The firmware releases the card on close - echo:SD card released - so M21 before M20.
        sawListing.set(new CountDownLatch(1));
        queue.addPublisher(Priority.REGULAR, Flux.just("M21", "M20")).block(ASCII_TIMEOUT);
        boolean listed = sawListing.get().await(30, TimeUnit.SECONDS);

        SdFile landed = null;
        if (listed) {
            var files = listings.peek() == null ? List.<SdFile>of() : lastListing(listings).getFiles();
            System.out.printf("  the card holds %s%n", files);
            landed = files.stream()
                    .filter(f -> f.getName().equalsIgnoreCase(NAME))
                    .findFirst()
                    .orElse(null);
        } else {
            System.out.println("  !! M20 produced no listing, so nothing was measured");
        }

        System.out.println();
        System.out.println("=== leaving the card as it was found ===");
        queue.addPublisher(Priority.REGULAR, Mono.just("M30 " + NAME)).block(ASCII_TIMEOUT);
        sawListing.set(new CountDownLatch(1));
        queue.addPublisher(Priority.REGULAR, Mono.just("M20")).block(ASCII_TIMEOUT);
        if (sawListing.get().await(30, TimeUnit.SECONDS)) {
            System.out.printf("  the card now holds %s%n", lastListing(listings).getFiles());
        }

        sending.dispose();
        replies.dispose();
        terminal.stop();

        System.out.println();
        System.out.println("=== what to read out of it ===");
        boolean exact = landed != null && landed.getSize() == file.length;
        if (landed == null) {
            System.out.printf("  %s is NOT on the card - the transfer did not land%n", NAME);
        } else {
            System.out.printf("  %s is %d bytes against %d sent -> %s%n", landed.getName(),
                    landed.getSize(), file.length, exact ? "byte-exact" : "WRONG SIZE");
        }
        boolean progressExact = !progress.isEmpty() && progress.getLast() == file.length;
        System.out.printf("  the last progress reading is %s, which is %s the file size%n",
                progress.isEmpty() ? "absent" : progress.getLast(), progressExact ? "exactly" : "NOT");

        System.exit(exact && progressExact ? 0 : 1);
    }

    /** The most recent listing, since every {@code M20} of the run adds one. */
    private static SdFileList lastListing(ConcurrentLinkedQueue<SdFileList> listings) {
        SdFileList last = null;
        for (SdFileList listing : listings) {
            last = listing;
        }
        return last;
    }
}

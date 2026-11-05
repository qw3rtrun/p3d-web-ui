package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.msg.SdUploadProgressed;
import org.qw3rtrun.p3d.api.msg.SdUploadState;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes a stored G-code file onto a machine's SD card - shaped like {@link JobFeeder}, except the
 * blocks go into a file instead of being executed and nothing else may reach the wire meanwhile. No
 * bytes pass through here from a browser: the file already lives in the content-addressed store
 * behind {@code /api-files}, which is what makes {@link GcodeFile#sizeBytes()} an exact denominator
 * before a single line goes out and lets a second machine reuse the same upload.
 *
 * <p>Three things this has to get right:
 * <ol>
 *   <li><strong>Exclusivity.</strong> {@code M28} turns the wire into a file sink until
 *       {@code M29}; any other command landing in between is written into the G-code rather than
 *       executed, silently, surfacing later as nonsense mid-print.
 *       {@link PrinterReactor#uploadToCard} queues this as an exclusive source for that reason.</li>
 *   <li><strong>{@code M29} goes out even when stopped.</strong> A cancel completes the content with
 *       {@code takeUntilOther} rather than tearing the source out, and {@code concat} moves on to the
 *       close - cancelling outright would leave the file open, and the very next command given to
 *       the machine would be written into it: the corruption this exists to prevent, caused by the
 *       button meant to stop it.</li>
 *   <li><strong>The name has to be listable.</strong> {@code M20} reports only what Marlin considers
 *       printable, so a file written as {@code benchy} is on the card, printable by exact name, and
 *       invisible to every listing - measured, not read: a probe once wrote an extensionless file,
 *       got an empty listing, and the first conclusion drawn was that the simulator was broken.
 *       {@link #cardNameFor} gives it an extension instead.</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class SdUploader {

    /** What Marlin's {@code M20} considers printable, lower-cased - anything else exists, prints, and is invisible to every listing. */
    private static final List<String> PRINTABLE = List.of(".gcode", ".gco", ".g");

    /** What a name without a printable extension gets. The short one, because the block is 96 bytes. */
    static final String DEFAULT_EXTENSION = ".gco";

    private final PrinterReactorManager reactors;
    private final GcodeFileService files;

    private final Map<UUID, Transfer> running = new ConcurrentHashMap<>();

    /** One transfer in flight. */
    private static final class Transfer {

        private final String cardName;
        private final long bytesTotal;

        private final Sinks.Empty<Void> stop = Sinks.empty();

        private final AtomicLong bytesSent = new AtomicLong();
        private final AtomicLong blocksSent = new AtomicLong();

        /** Set when the stop was asked for, so a stopped transfer is not reported as a finished one. */
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** Set once, so a terminal signal that arrives twice reports once. */
        private final AtomicBoolean reported = new AtomicBoolean();

        private volatile Disposable subscription;

        Transfer(String cardName, long bytesTotal) {
            this.cardName = cardName;
            this.bytesTotal = bytesTotal;
        }

        SdUploadProgressed at(SdUploadState state, String reason) {
            return new SdUploadProgressed(cardName, state, bytesSent.get(), bytesTotal,
                    blocksSent.get(), reason);
        }
    }

    /**
     * Starts writing {@code fileId} to {@code machineId}'s card and answers whether it was taken on
     * - it does not wait for the transfer, since a real file takes minutes of round trips and an
     * HTTP request held open for it would time out; {@link SdUploadProgressed} on the machine's
     * event stream says how it is going. Every way this can decline is a {@link ControlOutcome}, not
     * an exception - a stale file id and an unplugged printer are ordinary, and as exceptions both
     * used to answer 500. Checks run cheapest and most certain first (known machine, then link, then
     * file, then "one at a time"), so a blocking file-store lookup never runs for a printer that
     * could not have taken the file anyway - the common case on a farm that is mostly idle.
     *
     * @param reactor   the machine's runtime, which the caller has already opened
     * @param requested what the operator asked to call it, which may lack an extension
     */
    public Mono<ControlOutcome> start(PrinterReactor reactor, UUID machineId, UUID fileId, String requested) {
        return Mono.defer(() -> reactor.linked()
                ? Mono.fromCallable(() -> files.fetch(fileId))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(found -> found
                                .map(file -> begin(reactor, machineId, fileId, file, requested))
                                .orElseGet(() -> ControlOutcome.absent(machineId,
                                        "there is no file " + fileId + " to upload")))
                : Mono.just(ControlOutcome.wrongState(machineId, "printer " + machineId
                        + " is not connected, so nothing can be written to its card")));
    }

    /**
     * Queues the transfer, or says why it could not be. Returns a value rather than a {@code Mono}
     * because nothing in it is asynchronous; it still runs on {@code boundedElastic} because it is
     * called from inside the {@code map} the file lookup was scheduled on.
     */
    private ControlOutcome begin(PrinterReactor reactor, UUID machineId, UUID fileId,
                                 GcodeFile file, String requested) {
        var cardName = cardNameFor(requested);
        var transfer = new Transfer(cardName, file.sizeBytes());

        // one at a time per machine - a second would have its M28 written into the first one's file.
        if (running.putIfAbsent(machineId, transfer) != null) {
            return ControlOutcome.wrongState(machineId,
                    "printer " + machineId + " is already writing to its card");
        }

        Flux<String> content = GcodeLines.blocksOf(
                        Flux.from(files.read(fileId)).doOnNext(chunk -> read(transfer, chunk)))
                .doOnNext(block -> transfer.blocksSent.incrementAndGet());

        Flux<String> whole = transferOf(cardName, content, transfer.stop.asMono());

        log.info("writing {} ({} bytes) to {} as {}", file.name(), file.sizeBytes(), machineId, cardName);

        // announced before the first block, so the page shows the panel from the moment the request
        // is answered rather than a second later.
        reactor.announce(transfer.at(SdUploadState.UPLOADING, null));

        transfer.subscription = reactor.uploadToCard(whole).subscribe(
                nothing -> {
                },
                error -> finish(machineId, SdUploadState.FAILED, error.toString()),
                () -> finish(machineId,
                        transfer.cancelled.get() ? SdUploadState.CANCELLED : SdUploadState.DONE,
                        null));

        return ControlOutcome.accepted(machineId);
    }

    /**
     * Stops the transfer this machine is running, if any - a no-op rather than an error when there
     * is none, since the page's button and the machine's state are different clocks. {@code M29}
     * still follows, so what is left on the card is short but closed.
     */
    public Mono<Void> cancel(UUID machineId) {
        return Mono.fromRunnable(() -> {
            var transfer = running.get(machineId);
            if (transfer == null) {
                log.debug("{} was asked to stop writing to its card and was not writing", machineId);
                return;
            }
            transfer.cancelled.set(true);
            transfer.stop.tryEmitEmpty();
            log.info("{} was asked to stop writing {} after {} block(s)",
                    machineId, transfer.cardName, transfer.blocksSent.get());
        });
    }

    /** On a timer rather than per block - the same rate decision {@code JobFeeder} makes, and for the same reason. */
    @Scheduled(fixedDelayString = "${p3d.sd.upload-progress-interval:1000}")
    public void reportProgress() {
        running.forEach((machineId, transfer) -> {
            var reactor = reactors.runtimeOf(machineId);
            if (reactor != null) {
                reactor.announce(transfer.at(SdUploadState.UPLOADING, null));
            }
        });
    }

    /** The transfer is over, one way or another - published here rather than left to the timer, which is up to a second stale, since this is what re-enables the controls the transfer was holding. */
    private void finish(UUID machineId, SdUploadState state, String reason) {
        var transfer = running.remove(machineId);
        if (transfer == null || !transfer.reported.compareAndSet(false, true)) {
            return;
        }
        if (state == SdUploadState.FAILED) {
            log.warn("writing {} to {} failed after {} block(s): {}",
                    transfer.cardName, machineId, transfer.blocksSent.get(), reason);
        } else {
            log.info("{} finished writing {} after {} block(s) ({})",
                    machineId, transfer.cardName, transfer.blocksSent.get(), state);
        }
        var reactor = reactors.runtimeOf(machineId);
        if (reactor != null) {
            reactor.announce(transfer.at(state, reason));
        }
        if (transfer.subscription != null) {
            transfer.subscription.dispose();
        }
    }

    /**
     * The blocks of one transfer: open, write, close. The close is concatenated rather than appended
     * to the content - {@code takeUntilOther} completes the content when {@code stop} fires and
     * {@code concat} moves on to it - so {@code M29} goes out either way and the card keeps a closed
     * file; cancelling the transfer outright instead would leave it open for the next command to be
     * written into. Package-private and taking its pieces rather than finding them, so this shape
     * can be asserted without a printer on the end of it.
     */
    static Flux<String> transferOf(String cardName, Flux<String> content, Mono<Void> stop) {
        return Flux.concat(
                Mono.just("M28 " + cardName),
                content.takeUntilOther(stop),
                Mono.just("M29"));
    }

    /**
     * Counts a chunk without consuming it ({@code GcodeLines} reads the same buffer, so this
     * duplicates first) - the count therefore runs slightly ahead of the blocks, which is the right
     * way round for a progress bar, and the same thing {@code JobFeeder} does.
     */
    private static void read(Transfer transfer, ByteBuffer chunk) {
        transfer.bytesSent.addAndGet(chunk.duplicate().remaining());
    }

    /**
     * The name the file will have on the card. {@code M20} lists only {@code .gcode}, {@code .gco}
     * and {@code .g}; anything else opens under {@code M23} but is invisible to every listing, so an
     * operator typing {@code benchy} would make a file they cannot find or delete. The comparison is
     * a plain lower-cased tail check, since the card's own names are ASCII; the result is what
     * travels in {@code SdUploadProgressed}, so a page shows what is really on the card.
     */
    static String cardNameFor(String requested) {
        var trimmed = requested == null ? "" : requested.trim();
        var lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        for (var extension : PRINTABLE) {
            if (lower.endsWith(extension)) {
                return trimmed;
            }
        }
        return trimmed + DEFAULT_EXTENSION;
    }
}

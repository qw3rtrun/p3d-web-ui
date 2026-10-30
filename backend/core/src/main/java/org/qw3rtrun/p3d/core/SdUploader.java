package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Writes a stored G-code file onto a machine's SD card.
 *
 * <p>Shaped like {@link JobFeeder}, because it is the same job with two differences: the blocks go
 * into a file on the card instead of being executed, and <strong>nothing else may reach the wire
 * while they do</strong>.
 *
 * <p><strong>No bytes pass through here from a browser.</strong> The file is one the server already
 * holds, uploaded to {@code /api-files} and streamed into a content-addressed store. That is what
 * makes {@link GcodeFile#sizeBytes()} available as an exact denominator before a single line goes
 * out, and it means sending the same file to a second machine costs no second upload.
 *
 * <h2>The three things this has to get right</h2>
 *
 * <p><strong>1. Exclusivity.</strong> {@code M28} turns the wire into a file sink until
 * {@code M29}, so a command from any other source that lands in between is written into the G-code
 * rather than executed - silently, surfacing much later as a line of nonsense mid-print.
 * {@link PrinterReactor#uploadToCard} queues this as an exclusive source for that reason.
 *
 * <p><strong>2. {@code M29} goes out even when the transfer is stopped.</strong> This is the
 * subtle one. A cancel does <em>not</em> tear the source out - it completes the content with
 * {@code takeUntilOther}, and {@code concat} then moves on to the close. Cancelling outright would
 * leave the file <strong>open</strong> on the card, and the very next command the machine was given
 * would be written into it: the corruption this whole arrangement exists to prevent, caused by the
 * button meant to stop it.
 *
 * <p><strong>3. The name has to be listable.</strong> {@code M20} reports only what Marlin
 * considers printable, so a file written as {@code benchy} is on the card, is printable by exact
 * name, and never appears in a listing. {@link #cardNameFor} gives it an extension rather than
 * letting an operator create a file they cannot see. That was measured rather than read - a probe
 * wrote a file with no extension, got an empty listing, and the first conclusion drawn was that the
 * simulator was broken.
 */
@Slf4j
@RequiredArgsConstructor
public class SdUploader {

    /**
     * What Marlin's {@code M20} considers printable, lower-cased.
     *
     * <p>Not a style preference - a name outside this set produces a file that exists, prints, and
     * is invisible to every listing, which is the worst of the three possible outcomes.
     */
    private static final List<String> PRINTABLE = List.of(".gcode", ".gco", ".g");

    /** What a name without a printable extension gets. The short one, because the block is 96 bytes. */
    static final String DEFAULT_EXTENSION = ".gco";

    private final PrinterReactorManager reactors;
    private final GcodeFileService files;

    /** What is being written right now, per machine, so a cancel has something to stop. */
    private final Map<UUID, Transfer> running = new ConcurrentHashMap<>();

    /** One transfer in flight. */
    private static final class Transfer {

        private final String cardName;
        private final long bytesTotal;

        /** Emits when the transfer should stop producing content - see the class note on {@code M29}. */
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
     * Starts writing {@code fileId} to {@code machineId}'s card, and completes once it is queued.
     *
     * <p>It deliberately does not wait for the transfer. Every line is a round trip inside the send
     * window, so a real file takes minutes and an HTTP request held open for it would time out.
     * What the caller gets back is "this started"; what says it finished is
     * {@link SdUploadProgressed} on the machine's event stream.
     *
     * @param requested what the operator asked to call it, which may lack an extension
     */
    public Mono<Void> start(UUID machineId, UUID fileId, String requested) {
        return Mono.fromCallable(() -> files.fetch(fileId).orElseThrow(() ->
                        new IllegalArgumentException("there is no file " + fileId + " to upload")))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(file -> begin(machineId, fileId, file, requested));
    }

    private Mono<Void> begin(UUID machineId, UUID fileId, GcodeFile file, String requested) {
        var reactor = reactors.runtimeOf(machineId);
        if (reactor == null) {
            return Mono.error(new IllegalStateException(
                    "printer " + machineId + " is not connected, so nothing can be written to its card"));
        }

        var cardName = cardNameFor(requested);
        var transfer = new Transfer(cardName, file.sizeBytes());

        // One at a time, per machine. A second would have its M28 written into the first one's
        // file, which is the same corruption from a different direction.
        if (running.putIfAbsent(machineId, transfer) != null) {
            return Mono.error(new IllegalStateException(
                    "printer " + machineId + " is already writing to its card"));
        }

        Flux<String> content = GcodeLines.blocksOf(
                        Flux.from(files.read(fileId)).doOnNext(chunk -> read(transfer, chunk)))
                .doOnNext(block -> transfer.blocksSent.incrementAndGet());

        Flux<String> whole = transferOf(cardName, content, transfer.stop.asMono());

        log.info("writing {} ({} bytes) to {} as {}", file.name(), file.sizeBytes(), machineId, cardName);

        // Announced before the first block, so the page shows the panel - and the machine as busy -
        // from the moment the request is answered rather than a second later.
        reactor.announce(transfer.at(SdUploadState.UPLOADING, null));

        transfer.subscription = reactor.uploadToCard(whole).subscribe(
                nothing -> {
                },
                error -> finish(machineId, SdUploadState.FAILED, error.toString()),
                () -> finish(machineId,
                        transfer.cancelled.get() ? SdUploadState.CANCELLED : SdUploadState.DONE,
                        null));

        return Mono.empty();
    }

    /**
     * Stops the transfer this machine is running, if it is running one.
     *
     * <p>Asks the content to end; {@code M29} still follows it, so what is left on the card is a
     * short but <em>closed</em> file. Doing nothing when there is no transfer is the right answer
     * rather than an error: the page's button and the machine's state are different clocks.
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

    /**
     * Publishes where every running transfer has got to.
     *
     * <p>On a timer rather than per block, which is the same rate decision {@code JobFeeder} makes
     * and for the same reason: a hundred-thousand-block file would otherwise be a hundred thousand
     * events to answer a question a page asks about once a second.
     */
    @Scheduled(fixedDelayString = "${p3d.sd.upload-progress-interval:1000}")
    public void reportProgress() {
        running.forEach((machineId, transfer) -> {
            var reactor = reactors.runtimeOf(machineId);
            if (reactor != null) {
                reactor.announce(transfer.at(SdUploadState.UPLOADING, null));
            }
        });
    }

    /**
     * The transfer is over, one way or another: say so exactly, once.
     *
     * <p>The final reading is published here rather than left to the timer, because the timer's is
     * up to a second stale and this is the one a page acts on - it is what re-enables the controls
     * the transfer was holding.
     */
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
     * The blocks of one transfer: open the file, write it, close it.
     *
     * <p><strong>The close is concatenated rather than appended to the content, and that is the
     * whole of how a stop stays safe.</strong> {@code takeUntilOther} <em>completes</em> the
     * content when {@code stop} fires, and {@code concat} then moves on to its next source - so
     * {@code M29} goes out either way, and the card is left with a closed file.
     *
     * <p>The alternative, cancelling the transfer outright, leaves the file <strong>open</strong>:
     * the exclusive hold on the connection ends, the next command the machine is given is written
     * into the file, and the corruption this whole arrangement exists to prevent is caused by the
     * button meant to stop it.
     *
     * <p>Package-private and taking its pieces rather than finding them, so that the shape can be
     * asserted without a printer on the end of it - it is the one part of this class worth a test
     * of its own.
     */
    static Flux<String> transferOf(String cardName, Flux<String> content, Mono<Void> stop) {
        return Flux.concat(
                Mono.just("M28 " + cardName),
                content.takeUntilOther(stop),
                Mono.just("M29"));
    }

    /**
     * Counts a chunk as it goes past, without consuming it.
     *
     * <p>{@code GcodeLines} reads the same buffer, so the position may not move: it duplicates
     * before decoding and so does this. The count therefore runs slightly <em>ahead</em> of the
     * blocks - a chunk is measured when it is read and turned into blocks after - which is the
     * right way round for a progress bar and the same thing {@code JobFeeder} does.
     */
    private static void read(Transfer transfer, ByteBuffer chunk) {
        transfer.bytesSent.addAndGet(chunk.duplicate().remaining());
    }

    /**
     * The name the file will have on the card - the operator's, with an extension if it had none.
     *
     * <p>{@code M20} lists only {@code .gcode}, {@code .gco} and {@code .g}. Anything else is on
     * the card, opens under {@code M23}, and is invisible to every listing - so an operator who
     * typed {@code benchy} would have made a file they cannot find, let alone delete. The
     * comparison is a plain lower-cased tail check, because the card's own names are ASCII.
     *
     * <p>The name that comes out is what travels in {@code SdUploadProgressed}, so a page that
     * shows it is showing what is really on the card rather than what was typed.
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

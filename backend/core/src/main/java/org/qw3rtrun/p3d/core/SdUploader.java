package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.msg.SdUploadProgressed;
import org.qw3rtrun.p3d.api.msg.SdUploadState;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
 *
 * <h3>Two modes</h3>
 * The ASCII one above, and Marlin's Binary Protocol - {@code M28 B1}, packets, then {@code M21}.
 * Same request boundary, same exclusive queue, same one-at-a-time guard, same 1 Hz progress pump,
 * same {@link SdUploadProgressed} and the same terminal states; only the middle of the transfer
 * differs, and {@link SdUploadProgressed#binary()} says which ran. Measured against MarlinSim
 * ({@code Marlin bugfix-2.1.x}, compiled Oct 29 2026) on 2026-11-16:
 * <ul>
 *   <li><strong>{@code M21} after a binary transfer is not optional.</strong> The firmware
 *       releases the card when the transfer closes - {@code >> PFT CLOSE} answered {@code ok6},
 *       then {@code echo:SD card released} and {@code PFT:success}, after which {@code M20}
 *       answers {@code echo:No media} until an {@code M21} is sent. Without it the Storage tab's
 *       listing is broken for the rest of the session. The ASCII path has no equivalent.</li>
 *   <li><strong>A binary transfer is byte-exact and the ASCII one is not.</strong> 250 bytes sent,
 *       {@code MBP.GCO 250} listed; the ASCII path re-assembles lines through
 *       {@link GcodeLines#blocksOf} and the stored lines come back stripped of their {@code N} and
 *       {@code *} fields.</li>
 *   <li><strong>The capability is {@code Cap:BINARY_FILE_TRANSFER}</strong>, reported {@code 1} on
 *       that simulator beside {@code Cap:SDCARD:1} and {@code Cap:SD_WRITE:1}. A machine that
 *       never reported it is refused rather than downgraded: the server is the authority on what
 *       an operator may do, and reporting success for the mode they did not ask for would hide a
 *       real client bug.</li>
 * </ul>
 *
 * <p><strong>A binary transfer is held in memory, which is why it has a ceiling.</strong>
 * {@code MbpTransfer} in {@code :gcode} does no I/O and so needs the whole file as one array; the
 * bytes are collected on {@code boundedElastic} and bounded by
 * {@code p3d.sd.binary-max-bytes} (64 MiB by default), above which the request is refused. The
 * honest fix is a block-at-a-time entry point in {@code :gcode} - named as future work in
 * {@code MbpTransfer}'s own javadoc and out of scope for issue #122. The ASCII path streams and
 * needs no such limit.
 */
@Slf4j
public class SdUploader {

    /** What Marlin's {@code M20} considers printable, lower-cased - anything else exists, prints, and is invisible to every listing. */
    private static final List<String> PRINTABLE = List.of(".gcode", ".gco", ".g");

    /** What a name without a printable extension gets. The short one, because the block is 96 bytes. */
    static final String DEFAULT_EXTENSION = ".gco";

    /** 64 MiB. Not a protocol limit - see the class javadoc: the state machine holds the file. */
    static final long DEFAULT_BINARY_MAX_BYTES = 64L * 1024 * 1024;

    private final PrinterReactorManager reactors;
    private final GcodeFileService files;

    /** The ceiling on a binary transfer, {@code p3d.sd.binary-max-bytes}. */
    private final long binaryMaxBytes;

    private final Map<UUID, Transfer> running = new ConcurrentHashMap<>();

    public SdUploader(PrinterReactorManager reactors, GcodeFileService files) {
        this(reactors, files, DEFAULT_BINARY_MAX_BYTES);
    }

    public SdUploader(PrinterReactorManager reactors, GcodeFileService files, long binaryMaxBytes) {
        this.reactors = reactors;
        this.files = files;
        this.binaryMaxBytes = binaryMaxBytes;
    }

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

        /**
         * Why a binary transfer gave up, recorded rather than propagated. The packets and the
         * exclusive hold of the wire are two concurrent subscriptions (see
         * {@link SdUploader#startedBinary}) and the
         * {@code M21} has to go out after a failure as much as after a success, so the packet
         * stream's error is kept here and raised when both have finished. Null on the ASCII path,
         * whose one stream carries its own error.
         */
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private volatile Disposable subscription;

        /** Which mode this transfer is, carried on every event it produces - the timer's and the terminal one alike. */
        private final boolean binary;

        Transfer(String cardName, long bytesTotal, boolean binary) {
            this.cardName = cardName;
            this.bytesTotal = bytesTotal;
            this.binary = binary;
        }

        SdUploadProgressed at(SdUploadState state, String reason) {
            return new SdUploadProgressed(cardName, state, bytesSent.get(), bytesTotal,
                    blocksSent.get(), reason, binary);
        }
    }

    /**
     * Starts writing {@code fileId} to {@code machineId}'s card and answers whether it was taken on
     * - it does not wait for the transfer, since a real file takes minutes of round trips and an
     * HTTP request held open for it would time out; {@link SdUploadProgressed} on the machine's
     * event stream says how it is going. Every way this can decline is a {@link ControlOutcome}, not
     * an exception - a stale file id and an unplugged printer are ordinary, and as exceptions both
     * used to answer 500. Checks run cheapest and most certain first (known machine, then link, then
     * the capability if {@code binary} was asked for, then file, then "one at a time"), so a
     * blocking file-store lookup never runs for a printer that could not have taken the file anyway
     * - the common case on a farm that is mostly idle. The capability joins the link in that first
     * tier because it is the same kind of question: one volatile read of what the machine already
     * said, with no store behind it.
     *
     * @param reactor   the machine's runtime, which the caller has already opened
     * @param requested what the operator asked to call it, which may lack an extension
     * @param binary    whether to use Marlin's Binary Protocol rather than the ASCII write loop -
     *                  refused rather than downgraded on a machine that never reported the
     *                  capability, since reporting success for a mode nobody asked for would hide a
     *                  client that ignored the UI's own check
     */
    public Mono<ControlOutcome> start(PrinterReactor reactor, UUID machineId, UUID fileId,
                                      String requested, boolean binary) {
        return Mono.defer(() -> {
            if (!reactor.linked()) {
                return Mono.just(ControlOutcome.wrongState(machineId, "printer " + machineId
                        + " is not connected, so nothing can be written to its card"));
            }
            if (binary && !reactor.profile().hasBinaryFileTransfer()) {
                return Mono.just(ControlOutcome.wrongState(machineId, "printer " + machineId
                        + " did not report Cap:BINARY_FILE_TRANSFER, so it cannot take a binary transfer"));
            }
            return Mono.fromCallable(() -> files.fetch(fileId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(found -> found
                            .map(file -> begin(reactor, machineId, fileId, file, requested, binary))
                            .orElseGet(() -> Mono.just(ControlOutcome.absent(machineId,
                                    "there is no file " + fileId + " to upload"))));
        });
    }

    /**
     * Queues the transfer, or says why it could not be. A {@code Mono} because the binary branch has
     * one asynchronous step - reading the whole file - and the ASCII branch has none; both run on
     * {@code boundedElastic}, having been reached from inside the {@code flatMap} the file lookup was
     * scheduled on.
     */
    private Mono<ControlOutcome> begin(PrinterReactor reactor, UUID machineId, UUID fileId,
                                       GcodeFile file, String requested, boolean binary) {
        var cardName = cardNameFor(requested);

        // before the map is touched, so a refusal cannot leave the machine marked busy.
        if (binary && file.sizeBytes() > binaryMaxBytes) {
            return Mono.just(ControlOutcome.wrongState(machineId, "file " + fileId + " is "
                    + file.sizeBytes() + " bytes and a binary transfer is held in memory, so it is"
                    + " capped at " + binaryMaxBytes + " bytes (p3d.sd.binary-max-bytes)"));
        }

        var transfer = new Transfer(cardName, file.sizeBytes(), binary);

        // one at a time per machine - a second would have its M28 written into the first one's file.
        if (running.putIfAbsent(machineId, transfer) != null) {
            return Mono.just(ControlOutcome.wrongState(machineId,
                    "printer " + machineId + " is already writing to its card"));
        }

        log.info("writing {} ({} bytes) to {} as {}{}", file.name(), file.sizeBytes(), machineId,
                cardName, binary ? " over the binary protocol" : "");

        return binary
                ? wholeOf(fileId, file)
                        .map(bytes -> startedBinary(reactor, machineId, transfer, bytes))
                        // the slot is claimed before the read, so a read that fails has to give it
                        // back or this machine never takes another transfer.
                        .onErrorResume(unreadable -> {
                            running.remove(machineId);
                            return Mono.just(ControlOutcome.absent(machineId, "file " + fileId
                                    + " could not be read: " + unreadable));
                        })
                : Mono.just(started(reactor, machineId, transfer,
                        reactor.uploadToCard(transferOf(transfer.cardName,
                                asciiContentOf(fileId, transfer), transfer.stop.asMono()))));
    }

    /**
     * The binary transfer: <strong>two subscriptions, and the statement order is the wire order.</strong>
     *
     * <p>The driver goes first because it starts waiting for
     * {@code echo:Switching to Binary Protocol} at subscribe, while
     * {@link PrinterReactor#uploadToCard} is <em>not</em> lazy - it is
     * {@code PublisherQueue.addExclusivePublisher}, which takes the connection and starts draining
     * when it is <em>called</em>, the returned {@code Mono} being only the completion. So anything
     * that subscribes the driver after that call has already missed the echo. Measured on the
     * trace: {@code -> N3 M28 B1*73} at {@code .546}, {@code BinaryModeSwitched} delivered at
     * {@code .548}, the driver's {@code reading replies as binary-protocol replies first} at
     * {@code .551}, then {@code gave up: the printer did not switch to binary mode within 10000ms}
     * - every run against {@code MockPrinter}, which answers instantly. Composing the driver as the
     * middle of {@link #binaryTransferOf}'s {@code concat} reads as the obvious shape and is this
     * bug; so is {@code Mono.when}, which was tried. <strong>Neither unit test in this module can
     * see it</strong> - a {@code TestPublisher} stands in for the very thing whose subscribe time is
     * the bug - so {@code SdCardIntegrationTest} in {@code :app} is the only guard, and
     * {@code HostTerminal.binaryTransfer}'s javadoc is the authority for the pairing.
     *
     * <p>The wire's source is {@code M28 B1}, then nothing until the packets are over, then
     * {@code M21}; a middle that completed would release the exclusivity and let a temperature poll
     * write an ASCII line into a packet. The gate fires from {@code doFinally}, so a failure and a
     * cancellation reach it too - without that the never-completing middle holds the wire for ever
     * and the machine takes no further command of any kind. Verified on the same trace: the driver
     * gave up and {@code -> N4 M21*20} still went out.
     */
    private ControlOutcome startedBinary(PrinterReactor reactor, UUID machineId, Transfer transfer,
                                         byte[] bytes) {
        var transferred = Sinks.<Void>empty();

        reactor.announce(transfer.at(SdUploadState.UPLOADING, null));

        var packets = reactor.binaryTransfer(transfer.cardName, bytes, transfer.stop.asMono())
                .doOnNext(sent -> acknowledged(transfer, sent))
                .doOnError(transfer.failure::set)
                .onErrorComplete()
                .doFinally(signal -> transferred.tryEmitEmpty())
                .subscribe();

        var wire = reactor.uploadToCard(binaryTransferOf(transferred.asMono())).subscribe(
                nothing -> {
                },
                error -> finish(machineId, SdUploadState.FAILED, error.toString()),
                () -> ended(machineId, transfer));

        transfer.subscription = Disposables.composite(packets, wire);

        return ControlOutcome.accepted(machineId);
    }

    /** The ASCII content: the stored bytes re-assembled into blocks, counted as they pass. */
    private Flux<String> asciiContentOf(UUID fileId, Transfer transfer) {
        return GcodeLines.blocksOf(
                        Flux.from(files.read(fileId)).doOnNext(chunk -> read(transfer, chunk)))
                .doOnNext(block -> transfer.blocksSent.incrementAndGet());
    }

    /**
     * The whole file as one array, because {@code MbpTransfer} in {@code :gcode} does no I/O and
     * cannot be handed the file a block at a time (its javadoc names a streaming entry point as
     * future work). Demand is unbounded here, unlike every other bulk path in this application -
     * deliberately, since the destination <em>is</em> the heap; what bounds it is
     * {@code p3d.sd.binary-max-bytes}, checked against the stored size before this runs. On
     * {@code boundedElastic} because the store reads a file channel.
     */
    private Mono<byte[]> wholeOf(UUID fileId, GcodeFile file) {
        return Flux.from(files.read(fileId))
                .reduce(new ByteArrayOutputStream(Math.max(32, (int) Math.min(file.sizeBytes(), binaryMaxBytes))),
                        (collected, chunk) -> {
                            var slice = chunk.duplicate();
                            var bytes = new byte[slice.remaining()];
                            slice.get(bytes);
                            collected.write(bytes, 0, bytes.length);
                            return collected;
                        })
                .map(ByteArrayOutputStream::toByteArray)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Announces the transfer, subscribes it, and says it was taken on - the ASCII path, whose one
     * stream is both the wire and the outcome ({@link #startedBinary} needs two). Announced before
     * the first block so the page shows the panel from the moment the request is answered rather
     * than a second later. {@code lifecycle} completes when the machine is <em>free</em> - after the
     * {@code M29}, not when the content ran out - because that is what the terminal event re-enables
     * the page's controls on. A cancellation is a completion rather than an error on both paths
     * ({@code takeUntilOther} completes the content here, the transport completes on an abort
     * there), so the state comes from the flag the cancel set and not from the signal.
     */
    private ControlOutcome started(PrinterReactor reactor, UUID machineId, Transfer transfer,
                                   Mono<Void> lifecycle) {
        reactor.announce(transfer.at(SdUploadState.UPLOADING, null));

        transfer.subscription = lifecycle.subscribe(
                nothing -> {
                },
                error -> finish(machineId, SdUploadState.FAILED, error.toString()),
                () -> ended(machineId, transfer));

        return ControlOutcome.accepted(machineId);
    }

    /** Both halves are over: a recorded packet failure outranks the clean completion that followed it. */
    private void ended(UUID machineId, Transfer transfer) {
        var failed = transfer.failure.get();
        if (failed != null) {
            finish(machineId, SdUploadState.FAILED, failed.toString());
            return;
        }
        finish(machineId, transfer.cancelled.get() ? SdUploadState.CANCELLED : SdUploadState.DONE, null);
    }

    /**
     * One acknowledgement from the binary transfer. {@code set}, not {@code addAndGet}: the
     * transport reports <em>cumulative</em> acknowledged payload bytes, so accumulating them would
     * read 538 for a 250-byte file.
     */
    private static void acknowledged(Transfer transfer, long sent) {
        transfer.bytesSent.set(sent);
        transfer.blocksSent.incrementAndGet();
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
     * The blocks of one <em>binary</em> transfer: switch into the protocol, write nothing at all
     * while the packets go, then remount the card. Takes a gate that fires when the packets are
     * over rather than the packet stream itself - see {@link #startedBinary} for why those cannot
     * be one subscription - so this shape can be asserted without a printer on the end of it, the
     * same reason {@link #transferOf} takes its pieces.
     *
     * <p>{@code Flux.never()} is the middle, and it has to be something that stays alive: while the
     * packets go, the exclusive publisher is the queue's head and emits nothing, so nothing else is
     * drained and nothing else is written - which is what keeps an {@code M105} from the
     * temperature poller out of the middle of a packet, reusing the exclusivity the queue already
     * provides rather than adding a second mechanism. A middle that <em>completed</em> would
     * release that hold. The ASCII cancel path relies on a stalled exclusive head in just the same
     * way.
     *
     * <p>The {@code M21} is not optional - see the class javadoc's measured capture: the firmware
     * releases the card the moment the binary transfer closes, so without it every later
     * {@code M20} answers {@code echo:No media}.
     */
    static Flux<String> binaryTransferOf(Mono<Void> transferred) {
        return Flux.concat(
                Mono.just("M28 B1"),
                Flux.<String>never().takeUntilOther(transferred),
                Mono.just("M21"));
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

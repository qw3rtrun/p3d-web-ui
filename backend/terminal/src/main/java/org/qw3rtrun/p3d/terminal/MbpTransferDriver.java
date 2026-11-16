package org.qw3rtrun.p3d.terminal;

import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.g.marlin.binary.MbpAccepted;
import org.qw3rtrun.p3d.g.marlin.binary.MbpAck;
import org.qw3rtrun.p3d.g.marlin.binary.MbpFinished;
import org.qw3rtrun.p3d.g.marlin.binary.MbpGaveUp;
import org.qw3rtrun.p3d.g.marlin.binary.MbpIdle;
import org.qw3rtrun.p3d.g.marlin.binary.MbpRs;
import org.qw3rtrun.p3d.g.marlin.binary.MbpSendWindow;
import org.qw3rtrun.p3d.g.marlin.binary.MbpStep;
import org.qw3rtrun.p3d.g.marlin.binary.MbpSynced;
import org.qw3rtrun.p3d.g.marlin.binary.MbpTransfer;
import org.qw3rtrun.p3d.g.marlin.binary.MbpTransmit;
import org.qw3rtrun.p3d.g.marlin.event.BinaryModeSwitched;
import org.qw3rtrun.p3d.g.protocol.GRs;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.lang.String.format;

/**
 * Drives one Marlin Binary Protocol file transfer: the transport half that {@code :gcode}'s
 * {@link MbpSendWindow} and {@link MbpTransfer} deliberately do not have - a clock, a thread
 * discipline, and somewhere for the bytes to go.
 *
 * <p><strong>Demand contract.</strong> The progress flux is one {@code Long} per acknowledged write
 * block: the cumulative count of payload bytes the firmware has acknowledged
 * ({@link MbpTransfer#getBytesWritten()}), not a percentage and not a delta. It jumps to the file's
 * full size on the final block, which is what makes 100% exact. The readings are
 * <em>buffered</em>, and the transfer does not wait on the subscriber's demand for them: the
 * firmware times a silent stream out after ten seconds, so a packet held back for a slow progress
 * subscriber would fail the transfer. An empty file reports no progress at all - there is no block
 * to acknowledge.
 *
 * <p><strong>One thread touches the state machines, and it is whichever one got into the drain
 * loop.</strong> {@link MbpSendWindow} and {@link MbpTransfer} are portable Kotlin with no atomics
 * and are explicitly not thread-safe, the same contract as {@code GSendWindow} and for the same
 * reason - they port to C. Three threads arrive here: a decoded reply on the netty inbound loop, the
 * retry timeout on a {@link Scheduler} thread, and a cancellation on a caller's thread. All three
 * park an intent and run {@link #drain()}; only the thread inside the loop touches the window, the
 * transfer, the timer or {@link #write}. The {@link AtomicInteger} guard carries the happens-before
 * their plain fields need. That is the module's one mechanism - see {@code CommandProcessor} and
 * {@code PublisherQueue} - and not a lock.
 *
 * <p><strong>The handshake order is load-bearing.</strong> {@link MbpTransfer} reads
 * {@link MbpSendWindow#getBlockSize()} <em>at construction</em>, and it is
 * {@code MbpSendWindow.on(MbpStreamSync)} that clamps it to whatever the firmware really has. So:
 * {@code SYNC} -> {@code ss} -> build the transfer -> {@code start()}. Measured, the simulator's
 * buffer is <strong>96 bytes, not the stock 512</strong> ({@code << ss1,96,0.1.0}); build the
 * transfer first and it slices 512-byte blocks and {@code window.send} throws
 * {@code payload of 512 bytes exceeds the negotiated block size of 96} on the first write. The sync
 * id is adopted the same way and for a measured reason: it is <em>not</em> reset by connecting or by
 * {@code M28 B1} - three consecutive sessions answered {@code ss1}, {@code ss8}, {@code ss0}.
 *
 * <p><strong>The two numbers here are chosen, not measured:</strong> {@link #REPLY_TIMEOUT} of
 * 2000ms and {@link #ATTEMPTS} of 5, both constructor arguments. Every reply in the capture came
 * back inside a 1.2s read window, so the timeout is not a measurement of anything; what fixes the
 * product is the firmware, which times a stuck transfer out by itself after ten seconds
 * ({@code MbpTransferBusy}'s javadoc). 5 x 2s lines the host's budget up with the firmware's instead
 * of fighting it.
 *
 * <p><strong>Giving up is a value until the boundary.</strong> {@link MbpTransfer#on} already turns
 * an {@code MbpFatal} and an {@code MbpOutOfSync} into {@link MbpGaveUp}; that policy is propagated
 * rather than second-guessed - no resynchronise-and-retry is added here, because deciding not to is
 * {@code MbpTransfer}'s documented decision. Only at the public boundary does an {@code MbpGaveUp}
 * become an {@code onError}, carrying {@link MbpTransfer#getReason()} verbatim.
 *
 * <p><strong>A terminal signal means the mode has been left, or the attempt to leave it ran out.</strong>
 * {@code onComplete} is the file closed on the card (or a cancellation, which also completes - see
 * {@link HostTerminal#binaryTransfer}); {@code onError} is a give-up that was not a cancellation.
 * Either way a {@code CONTROL CLOSE} packet goes out first and the mode flag is cleared when its
 * {@code ok<n>} arrives or when the one deadline for it expires - whichever happens, the caller is
 * told, because a transfer that has already decided its outcome must not be delayed by a lost
 * acknowledgement of the mode exit.
 *
 * <p>The capture this was built against is in {@code HostTerminalBinaryTest}'s javadoc: MarlinSim,
 * {@code Marlin bugfix-2.1.x} compiled Oct 29 2026, measured 2026-11-16 on {@code localhost:8099},
 * and {@code MbpTransferProbe} re-measures it against a live printer.
 */
@Slf4j
public final class MbpTransferDriver {

    /** How long one unanswered packet is waited for. Chosen, not measured; see the class javadoc. */
    public static final Duration REPLY_TIMEOUT = Duration.ofSeconds(2);

    /** How many times one packet is transmitted before the transfer gives up, the first transmission
     * included. Chosen, not measured; 5 x {@link #REPLY_TIMEOUT} is the firmware's own ten seconds. */
    public static final int ATTEMPTS = 5;

    /** What an {@code MbpGaveUp} becomes at the public boundary. Carries {@code :gcode}'s reason
     * string unchanged - those words are the diagnosis and paraphrasing them loses it. */
    public static final class Failed extends RuntimeException {

        Failed(String reason) {
            super(reason);
        }
    }

    /** Where the driver is. Not {@code MbpTransfer}'s own state, which is finer and about the file;
     * this is about the mode and the stream around it. */
    private enum Phase {

        /** {@code M28 B1} is on the wire and the firmware has not echoed the switch yet. */
        AWAITING_MODE,

        /** A {@code SYNC} packet is out; waiting for the {@code ss} that carries the real buffer size. */
        SYNCING,

        /** {@link #transfer} is driving the file. */
        TRANSFERRING,

        /** A {@code CONTROL CLOSE} is out; the outcome is already decided. */
        LEAVING,

        /** Terminated. Nothing follows. */
        ENDED,
    }

    /** One thing that happened, parked for the drain loop by whichever thread saw it. */
    private sealed interface Intent {
    }

    private record Arrived(GRs<?> reply) implements Intent {
    }

    /** A deadline expired. Carries the sequence number it was armed with, so a timer disposed a
     * moment too late cannot retransmit a packet a reply has already resolved. */
    private record Expired(long seq) implements Intent {
    }

    private record Stopped() implements Intent {
    }

    private record Begun() implements Intent {
    }

    private final HostTerminal terminal;

    /** Where a packet goes: {@code CommandProcessor.writeRaw}, which parks it for the <em>other</em>
     * drain loop. This one may not write to a socket any more than it may block. */
    private final Consumer<byte[]> write;

    private final String name;

    private final byte[] data;

    private final Scheduler scheduler;

    private final Duration timeout;

    private final int attempts;

    /**
     * The progress readings. Unicast and buffering: there is exactly one subscriber (the caller of
     * {@link HostTerminal#binaryTransfer}) and the readings must survive until it asks for them,
     * since the transfer cannot wait. Only the drain loop emits, so a {@code FAIL_NON_SERIALIZED} is
     * unreachable here.
     */
    private final Sinks.Many<Long> progress = Sinks.many().unicast().onBackpressureBuffer();

    private final Queue<Intent> intents = new ConcurrentLinkedQueue<>();

    /** The drain guard: zero when no thread is in {@link #drain()}, otherwise the number of calls
     * still to be accounted for. */
    private final AtomicInteger wip = new AtomicInteger();

    private final MbpSendWindow window = new MbpSendWindow();

    // Everything below is touched from the drain loop only, which is why none of it is volatile.

    private MbpTransfer transfer;

    private Phase phase = Phase.AWAITING_MODE;

    /** How many transmissions of the packet currently being waited on have gone out. */
    private int sent;

    private long timerSeq;

    private Disposable timer;

    private Disposable watchingCancel;

    private boolean cancelled;

    /** What to fail with, or null to complete. Decided when the transfer ends, applied when the mode
     * has been left. */
    private Throwable failure;

    MbpTransferDriver(HostTerminal terminal, Consumer<byte[]> write, String name, byte[] data) {
        this(terminal, write, name, data, Schedulers.parallel(), REPLY_TIMEOUT, ATTEMPTS);
    }

    MbpTransferDriver(HostTerminal terminal, Consumer<byte[]> write, String name, byte[] data,
                      Scheduler scheduler, Duration timeout, int attempts) {
        this.terminal = terminal;
        this.write = write;
        this.name = name;
        this.data = data;
        this.scheduler = scheduler;
        this.timeout = timeout;
        this.attempts = attempts;
    }

    /**
     * Arms the session and hands back the progress readings. The caller subscribes the result; this
     * is called from {@code Flux.defer}, so nothing here has run until then.
     *
     * <p>The mode flag is set <em>before</em> the first reply can be read, which is the whole reason
     * it is set eagerly - see {@link HostTerminal#binaryMode}.
     */
    Flux<Long> run(Mono<Void> cancel) {
        terminal.enterBinaryMode(this);
        watchingCancel = cancel.subscribe(
                ignored -> {
                },
                error -> {
                    log.warn("MBP: the cancel signal failed, which is a stop either way", error);
                    park(new Stopped());
                },
                () -> park(new Stopped()));
        park(new Begun());
        return progress.asFlux().doOnCancel(() -> park(new Stopped()));
    }

    /**
     * One decoded reply, from the netty inbound loop. Every reply, not only the {@link MbpRs} ones:
     * the single signal that binary mode really changed is {@link BinaryModeSwitched}, an echo line,
     * and the lines the firmware emits mid-transfer ({@code echo:SD card ok},
     * {@code Writing to file:}) are neither ours nor a reason to stop reading.
     */
    void onReply(GRs<?> reply) {
        park(new Arrived(reply));
    }

    private void park(Intent intent) {
        intents.add(intent);
        drain();
    }

    /** The one place the state machines are touched. First caller in runs the loop; a thread that
     * finds it running leaves its intent and returns, and the thread inside goes round again. */
    private void drain() {
        if (wip.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        for (; ; ) {
            for (Intent intent; (intent = intents.poll()) != null; ) {
                try {
                    apply(intent);
                } catch (RuntimeException broken) {
                    collapse(broken);
                }
            }
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }

    private void apply(Intent intent) {
        if (phase == Phase.ENDED) {
            return;
        }
        switch (intent) {
            // The wait for the mode echo is timed like a packet, so an incapable printer fails
            // rather than hanging. Guarded, because the echo can be parked ahead of this: the mode
            // flag is set before the intent, so a reply may already have driven the stream on.
            case Begun _ -> {
                if (phase == Phase.AWAITING_MODE) {
                    sent = 1;
                    arm();
                }
            }
            case Arrived(GRs<?> reply) -> accept(reply);
            case Expired(long seq) -> expired(seq);
            case Stopped _ -> stop();
        }
    }

    private void accept(GRs<?> reply) {
        if (phase == Phase.AWAITING_MODE) {
            if (reply != BinaryModeSwitched.INSTANCE) {
                return;
            }
            log.info("MBP: the firmware echoed the mode switch; synchronising the stream");
            phase = Phase.SYNCING;
            transmit(window.resync());
            return;
        }
        if (!(reply instanceof MbpRs<?> mbp)) {
            return;
        }
        switch (phase) {
            case SYNCING -> synchronise(mbp);
            case TRANSFERRING -> advance(mbp);
            // The CONTROL CLOSE is answered by a plain ok<n> - unlike SYNC, it is dispatched through
            // the normal path. Measured: ok7.
            case LEAVING -> {
                if (window.on(mbp) instanceof MbpAccepted) {
                    finish();
                }
            }
            default -> {
            }
        }
    }

    private void synchronise(MbpRs<?> mbp) {
        MbpAck ack = window.on(mbp);
        if (!(ack instanceof MbpSynced synced)) {
            log.debug("MBP: still waiting for the handshake, not {}", ack);
            return;
        }
        log.info("MBP: stream synchronised at {} with a {}-byte buffer, protocol {}",
                synced.getSync(), synced.getBuffer(), synced.getVersion());
        // After the ss and not before: the transfer reads the block size now, and the firmware's
        // 96 is what it has to read.
        transfer = new MbpTransfer(window, name, data, false);
        phase = Phase.TRANSFERRING;
        step(transfer.start());
    }

    private void advance(MbpRs<?> mbp) {
        int before = transfer.getBlocksWritten();
        MbpStep next = transfer.on(mbp);
        if (transfer.getBlocksWritten() > before) {
            publish(transfer.getBytesWritten());
        }
        step(next);
    }

    private void step(MbpStep next) {
        switch (next) {
            case MbpTransmit transmit -> transmit(transmit.getBytes());
            // The packet is in and a PFT: line is owed instead; a fresh deadline, because what is
            // being waited for has changed.
            case MbpIdle _ -> arm();
            case MbpFinished _ -> leave(null);
            case MbpGaveUp gaveUp -> leave(cancelled ? null : new Failed(gaveUp.getReason()));
            default -> log.warn("MBP: {} is a step this driver does not know", next);
        }
    }

    private void transmit(byte[] packet) {
        if (packet == null) {
            // Unreachable while one packet is in flight at a time, which is the window's whole
            // shape; a dropped packet would be the failure this module exists to prevent.
            leave(new Failed("a packet was already in flight"));
            return;
        }
        sent = 1;
        write.accept(packet);
        arm();
    }

    private void expired(long seq) {
        if (seq != timerSeq) {
            // A reply resolved this packet while the timeout was already on its way here.
            return;
        }
        if (phase == Phase.LEAVING) {
            log.warn("MBP: the CONTROL CLOSE went unacknowledged; reporting the outcome anyway");
            finish();
            return;
        }
        // Every wait gets the same budget - attempts x timeout, the firmware's own ten seconds -
        // but says which of the three it was, because they need different answers from an operator.
        if (sent >= attempts) {
            long budget = timeout.toMillis() * attempts;
            leave(new Failed(switch (phase) {
                case AWAITING_MODE -> format("the printer did not switch to binary mode within %dms",
                        budget);
                default -> window.getInFlight()
                        ? format("no reply to the packet in flight after %d attempt(s) %dms apart",
                        attempts, timeout.toMillis())
                        : format("the packet was acknowledged but never answered, after %dms", budget);
            }));
            return;
        }
        sent++;
        byte[] again = window.retransmit();
        if (again == null) {
            // Nothing is outstanding, so there is nothing to retransmit: either the mode echo or a
            // PFT: line is owed, and waiting is the only thing a host can do about it.
            arm();
            return;
        }
        log.warn("MBP: no reply in {}ms, transmitting the same packet again (attempt {} of {})",
                timeout.toMillis(), sent, attempts);
        write.accept(again);
        arm();
    }

    private void stop() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        log.info("MBP: the transfer was cancelled in phase {}", phase);
        if (phase == Phase.TRANSFERRING) {
            // Unconditional, reusing the abandoned packet's sync id - MbpTransfer.abort's
            // documented behaviour, and the PFT:success it earns reports MbpGaveUp.
            step(transfer.abort("the operator cancelled the transfer"));
            return;
        }
        leave(null);
    }

    /**
     * Leaves binary mode and then terminates. {@code failure} is kept rather than signalled now,
     * because a printer left in binary mode would swallow every ASCII command the host sends next.
     */
    private void leave(Throwable error) {
        failure = error;
        if (phase == Phase.AWAITING_MODE) {
            // The firmware never said it switched, so it is still reading ASCII and a CONTROL CLOSE
            // packet would reach it as eight bytes of garbage command.
            finish();
            return;
        }
        phase = Phase.LEAVING;
        // A packet is in flight for almost all of a give-up; abandoning it keeps the sync id, which
        // the CLOSE then reuses, so the stream stays synchronised on the way out.
        window.abandon();
        byte[] closing = window.close();
        if (closing == null) {
            finish();
            return;
        }
        write.accept(closing);
        arm();
    }

    /**
     * A throw out of the state machines, contained. {@code :gcode} answers a bad <em>reply</em> with
     * a value and throws only on a call this driver got wrong - a payload wider than the negotiated
     * block, which is what building the transfer before the handshake produces. Letting it out of
     * the loop would unwind through the netty inbound subscriber and take the whole session's reply
     * stream with it, so the transfer fails and the session lives.
     *
     * <p>No {@code CONTROL CLOSE} is attempted: the stream state is exactly what is no longer
     * trustworthy. The printer is left in binary mode and the caller has to reconnect, which is the
     * honest outcome of a bug here.
     */
    private void collapse(RuntimeException broken) {
        log.error("MBP: the transfer state machine threw, which is a defect in this driver", broken);
        failure = broken;
        phase = Phase.LEAVING;
        finish();
    }

    private void arm() {
        dispose();
        long seq = ++timerSeq;
        timer = scheduler.schedule(() -> park(new Expired(seq)), timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void dispose() {
        if (timer != null) {
            timer.dispose();
            timer = null;
        }
    }

    private void finish() {
        if (phase == Phase.ENDED) {
            return;
        }
        phase = Phase.ENDED;
        dispose();
        // Lazily, and only here: an ASCII ok crossing the boundary either way still falls through to
        // MarlinRsDecoder, so being a moment late costs nothing - see HostTerminal.binaryMode.
        terminal.leaveBinaryMode(this);
        if (watchingCancel != null) {
            watchingCancel.dispose();
            watchingCancel = null;
        }
        Sinks.EmitResult result = failure == null
                ? progress.tryEmitComplete()
                : progress.tryEmitError(failure);
        if (result.isFailure()) {
            // FAIL_CANCELLED is the normal way here: the caller cancelled the progress flux, which
            // is what parked the Stopped intent in the first place.
            log.debug("MBP: the transfer ended with nobody listening ({}): {}", result, failure);
        }
        if (failure == null) {
            log.info("MBP: {} is on the card and the printer is back in ASCII mode", name);
        } else {
            log.warn("MBP: {} gave up: {}", name, failure.getMessage());
        }
    }

    private void publish(long bytesAcknowledged) {
        Sinks.EmitResult result = progress.tryEmitNext(bytesAcknowledged);
        if (result.isFailure()) {
            log.warn("MBP: progress reading dropped ({}): {} bytes", result, bytesAcknowledged);
        }
    }
}

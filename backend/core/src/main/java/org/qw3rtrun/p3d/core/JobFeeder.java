package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.JobFeedCompleted;
import org.qw3rtrun.p3d.api.jobs.JobFeedFailed;
import org.qw3rtrun.p3d.api.jobs.JobProgress;
import org.qw3rtrun.p3d.api.jobs.JobProgressed;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Feeds a running job's file to the machine it was assigned to - plan item C3.
 *
 * <p>This is the item everything below the application layer was built for. The priority queue, the
 * send window, the block framing and the resend loop all existed and nothing used them for the one
 * job they were written for.
 *
 * <p><strong>It is driven by events in and events out, and calls into no other module.</strong>
 * {@code jobs} announces that a job started; this reads the file through {@code GcodeFileService}
 * and hands it to the machine; when the file runs out or the feed breaks, it announces
 * {@link JobFeedCompleted} or {@link JobFeedFailed} and {@code jobs} decides what that does to the
 * job's state. Only {@code core} can know whether the lines reached the wire - knowing it means
 * holding the socket - and only {@code jobs} may move a job, which is the same division the
 * firmware report crosses in plan item B2.
 *
 * <p><strong>The file is fed at {@code BACKGROUND} and nothing else is.</strong> That is what lets
 * an interactive command preempt a print: {@code GSender} queues at {@code REGULAR}, which the
 * queue drains first, and the job resumes where it left off because the queue hands its demand back
 * to the next source rather than discarding it.
 */
@Slf4j
@RequiredArgsConstructor
public class JobFeeder {

    private final PrinterReactorManager reactors;
    private final GcodeFileService files;
    private final JobService jobs;
    private final ApplicationEventPublisher events;

    /** What is being fed right now, so that a cancel has something to stop. */
    private final Map<UUID, Feed> feeding = new ConcurrentHashMap<>();

    /**
     * One job's feed in flight.
     *
     * @param stop      emits when the job should stop being fed, which ends the source cleanly
     * @param sent      how many blocks have gone out, for the event at the end
     * @param cancelled set when the stop was deliberate, so a stopped feed is not reported as a
     *                  finished one
     */
    private record Feed(UUID jobId, UUID machineId, Sinks.Empty<Void> stop, AtomicLong sent,
                        AtomicBoolean cancelled, Disposable subscription, Disposable watchingTheLink,
                        Disposable watchingForAbandonment, Progress progress) {

        void release() {
            subscription.dispose();
            watchingTheLink.dispose();
            watchingForAbandonment.dispose();
        }
    }

    /**
     * Fails the job if the machine's link goes away while it is printing - plan item C5.
     *
     * <p><strong>Nothing else would notice.</strong> When a connection drops, {@code PrinterReactor}
     * builds a fresh {@code PublisherQueue} for the next attempt and abandons the old one - so the
     * feed's {@code Mono} is left subscribed to a queue nobody drains, and never completes or
     * errors. The job would read {@code RUNNING} for ever, on a printer that has been cold for
     * hours. C3 left a method for this and never called it; this is the call.
     *
     * <p>The machine's own event stream is the signal, because that is where the lifecycle
     * announces itself. What is recorded with the failure is how far the feed had got, which is
     * the plan's "a killed connection leaves a job in FAILED with its last acknowledged line" -
     * and the number a better recovery than this one would start from.
     */
    private Disposable watchTheLink(JobStateChanged event, PrinterReactor reactor,
                                    AtomicLong sent, AtomicBoolean cancelled) {
        return reactor.updates()
                .map(EventMessage::event)
                .filter(ConnectionStateChanged.class::isInstance)
                .cast(ConnectionStateChanged.class)
                .filter(change -> !change.to().isLinked())
                .next()
                .subscribe(lost -> {
                    if (!feeding.containsKey(event.jobId())) {
                        return;
                    }
                    stop(event.jobId(), JobState.FAILED);
                    cancelled.set(true);
                    fail(event, sent.get(), "the connection to the machine went away while it was"
                            + " printing (" + lost.to()
                            + (lost.reason() == null ? "" : ": " + lost.reason()) + ")");
                });
    }

    /**
     * Fails the job when the transport throws away blocks it had already framed - issue #60.
     *
     * <p><strong>Nothing else would notice, and that is the whole bug.</strong> The firmware's line
     * counter and the host's disagree, so the firmware answers with a {@code Resend:} for a number
     * this host has never framed; the send window cannot answer it, restarts the numbering with
     * {@code M110 N0} and drops what it was holding. The feed carries on perfectly from there, and
     * this class counts the dropped blocks as sent - because they <em>were</em> sent - so the job
     * reaches the end of its file and reports {@code COMPLETED}. A print that silently skipped its
     * {@code G28} homed nowhere.
     *
     * <p><strong>What was lost cannot be replayed from the checkpoint</strong>, which is why this
     * fails the job rather than retrying it: a checkpoint records what was <em>sent</em>, and what
     * went missing is the difference between what was sent and what was accepted. Replaying from it
     * would re-run blocks the machine already executed - a relative move sent twice is a move made
     * twice - and skip the ones it did not. See {@link BlocksAbandoned}, whose count is a lower
     * bound rather than an exact one.
     *
     * <p><strong>{@link #stop} goes before {@link #fail}, and the order is load-bearing.</strong>
     * {@code stop} removes the feed <em>and</em> publishes one last exact {@link JobProgressed}
     * reading; {@code fail} publishes {@link JobFeedFailed}, which {@code jobs} turns into a
     * {@code FAILED} job, which comes back here as a {@link JobStateChanged} whose {@code stop} is
     * then a no-op and whose {@code coolDown} turns the heaters off. Reversed, the exact reading is
     * lost - the timer's checkpoints are up to a second stale - and a failed job's recorded
     * position is the one thing plan item C5 has to work from.
     *
     * <p><strong>One thing about {@code updates()} that is folklore until it is written down.</strong>
     * It is a {@code Sinks.many().replay().latestOrDefault(...)}, so a new subscriber is handed the
     * stream's <em>latest</em> message before any new one - which means a watcher started by a new
     * feed can see the previous feed's last event. {@link #watchTheLink} tolerates that because the
     * replayed default is a linked connection state and its filter drops it. This one is displaced
     * in every path that is actually reachable, because a failed print is followed by
     * {@link #coolDown}'s five commands and their acknowledgements, but it is not <em>guaranteed</em>.
     * The clean fix is a timestamp on {@code EventMessage}, which is a change to {@code :backend:api}
     * and its own piece of work; it is on the skill's known-debt list.
     */
    private Disposable watchForAbandonedBlocks(JobStateChanged event, PrinterReactor reactor,
                                               AtomicLong sent, AtomicBoolean cancelled) {
        return reactor.updates()
                .map(EventMessage::event)
                .filter(BlocksAbandoned.class::isInstance)
                .cast(BlocksAbandoned.class)
                .next()
                .subscribe(abandoned -> {
                    if (!feeding.containsKey(event.jobId())) {
                        return;
                    }
                    stop(event.jobId(), JobState.FAILED);
                    cancelled.set(true);
                    fail(event, sent.get(), abandoned.blocks() + " block(s) this host had already"
                            + " framed were abandoned and never reached the machine: the printer"
                            + " asked to resend line " + abandoned.lineNumber() + ", which is not a"
                            + " block the send window still held, so the session had to be"
                            + " renumbered. What was skipped cannot be replayed from the"
                            + " checkpoint, because the checkpoint records what was sent and not"
                            + " what was accepted");
                });
    }

    /**
     * A job moved, and some moves mean starting or stopping a feed.
     *
     * <p>A plain {@code @EventListener}, so this runs inside the transaction that moved the job and
     * the feed is under way before the request that started it returns. That matters for the
     * failure case above all: a start against a machine that is not connected is answered
     * immediately rather than leaving a job that reads as {@code RUNNING} with nothing behind it.
     */
    @EventListener
    public void onJobStateChanged(JobStateChanged event) {
        if (event.to() == JobState.RUNNING && event.from() == JobState.ASSIGNED) {
            start(event);
            return;
        }
        if (event.to() == JobState.RUNNING && event.from() == JobState.PAUSED) {
            resume(event);
            return;
        }
        if (event.from() == JobState.RUNNING) {
            stop(event.jobId(), event.to());
            if (event.to() == JobState.CANCELLED || event.to() == JobState.FAILED) {
                coolDown(event.machineId());
            }
        }
    }

    private void start(JobStateChanged event) {
        start(event, 0);
    }

    private void start(JobStateChanged event, long skip) {
        var reactor = reactors.runtimeOf(event.machineId());
        if (reactor == null) {
            fail(event, 0, "there is no runtime for machine " + event.machineId()
                    + ", so nothing could be sent to it");
            return;
        }
        var file = files.fetch(event.fileId()).orElse(null);
        if (file == null) {
            fail(event, 0, "there is no file " + event.fileId() + " to print");
            return;
        }

        var stop = Sinks.<Void>empty();
        var sent = new AtomicLong();
        var cancelled = new AtomicBoolean();
        var progress = new Progress(file.sizeBytes(), LocalDateTime.now());

        // The skipped blocks are read and counted but not sent: the machine already has them, and
        // the layer they left the file on is still the layer it is printing. Counting them is what
        // keeps a resumed job's progress continuous rather than restarting at zero.
        var lines = GcodeLines.feedOf(
                        Flux.from(files.read(event.fileId())).doOnNext(progress::read))
                .doOnNext(fed -> progress.block(sent.incrementAndGet(), fed.layer()))
                .skip(skip)
                .map(GcodeLines.Fed::block)
                .takeUntilOther(stop.asMono());

        log.info("job {} is feeding {} ({} bytes) from block {} to {}", event.jobId(), file.name(),
                file.sizeBytes(), skip, event.machineId());
        var subscription = reactor.feed(lines).subscribe(
                nothing -> {
                },
                error -> finished(event, sent.get(), cancelled.get(), error),
                () -> finished(event, sent.get(), cancelled.get(), null));

        var watchingTheLink = watchTheLink(event, reactor, sent, cancelled);
        var watchingForAbandonment = watchForAbandonedBlocks(event, reactor, sent, cancelled);

        feeding.put(event.jobId(), new Feed(event.jobId(), event.machineId(), stop, sent,
                cancelled, subscription, watchingTheLink, watchingForAbandonment, progress));
    }

    /**
     * What has gone out, and how much of the file it came from.
     *
     * <p><strong>Counted from what the job's source emitted, which is monotone and immune to a
     * resend.</strong> That is worth stating because plan decision 3 says the opposite - that
     * counting what the feeder handed over "is wrong in exactly the case that matters: after a
     * {@code Resend:} the transport replays blocks the job already counted". It does not: a replay
     * is served from {@code GSendWindow} into {@code CommandProcessor}'s own {@code replaying}
     * deque and written from there, and the source is never pulled a second time. The decision's
     * conclusion - that the acknowledgement is the trustworthy signal - is right; its reason for
     * distrusting this count is not.
     *
     * <p>What this number <em>does</em> run ahead of the firmware by is the send window: at most
     * four blocks, because the window will not pull another until one is acknowledged. That bound
     * is reported rather than hidden, as {@code JobProgress.inFlight}.
     */
    private static final class Progress {
        private final long bytesTotal;
        private final LocalDateTime startedAt;
        private final AtomicLong bytesSent = new AtomicLong();
        private final AtomicLong blocks = new AtomicLong();
        private final AtomicReference<Integer> layer =
                new AtomicReference<>();
        private volatile int inFlight;

        Progress(long bytesTotal, LocalDateTime startedAt) {
            this.bytesTotal = bytesTotal;
            this.startedAt = startedAt;
        }

        void read(ByteBuffer chunk) {
            bytesSent.addAndGet(chunk.remaining());
        }

        void block(long count, Integer onLayer) {
            blocks.set(count);
            if (onLayer != null) {
                layer.set(onLayer);
            }
        }

        void acknowledged(int outstanding) {
            inFlight = outstanding;
        }

        JobProgress snapshot() {
            return new JobProgress(blocks.get(), inFlight, Math.min(bytesSent.get(), bytesTotal),
                    bytesTotal, layer.get(), startedAt, LocalDateTime.now());
        }
    }

    /**
     * Publishes where every running job has got to.
     *
     * <p><strong>On a timer rather than per block, and that is the whole of the rate decision.</strong>
     * A hundred-thousand-block job would otherwise be a hundred thousand events and, behind them, a
     * hundred thousand writes - to answer a question a page asks about once a second and a restart
     * asks once. A second is fast enough for a human and slow enough for a database.
     */
    @Scheduled(fixedDelayString = "${p3d.jobs.progress-interval:1000}")
    public void reportProgress() {
        feeding.values().forEach(feed -> {
            var reactor = reactors.runtimeOf(feed.machineId());
            if (reactor != null) {
                feed.progress().acknowledged(reactor.blocksInFlight());
            }
            events.publishEvent(new JobProgressed(feed.jobId(), feed.machineId(),
                    feed.progress().snapshot()));
        });
    }

    /**
     * Stops feeding, because the job was cancelled, paused or failed from elsewhere.
     *
     * <p>{@code stop} rather than cancelling the subscription: completing the source lets the send
     * window finish the blocks it has already framed and the queue hand its demand on, where a
     * cancel would tear the stream out from under the drain loop. "Cancelling mid-stream stops the
     * source and drains cleanly" is C3's own wording, and this is the half that makes it clean.
     */
    private void stop(UUID jobId, JobState to) {
        var feed = feeding.remove(jobId);
        if (feed == null) {
            return;
        }
        log.info("job {} stopped after {} blocks ({})", jobId, feed.sent().get(), to);
        feed.cancelled().set(true);
        feed.stop().tryEmitEmpty();
        feed.watchingTheLink().dispose();
        feed.watchingForAbandonment().dispose();

        // One last, exact reading. The timer's checkpoints are up to a second stale, and a resume
        // starts from whatever was written down - so without this a paused job restarts thousands
        // of blocks early and extrudes over what it already printed.
        events.publishEvent(new JobProgressed(jobId, feed.machineId(), feed.progress().snapshot()));
    }

    /**
     * Resumes a paused job from the block it stopped at - plan item C5.
     *
     * <p><strong>This is a seek, and it is safe here for one specific reason: the machine never
     * lost its state.</strong> A pause stops the feed and nothing else - the printer stays
     * connected, stays hot, and stays exactly where block N-1 left it, with the positioning mode,
     * extruder mode, feedrate and fan speed the file had already set. So continuing at block N is
     * continuing, not restarting.
     *
     * <p><strong>That is emphatically not true after a disconnect</strong>, which is the case the
     * plan warns about: a machine that was power-cycled, or sat cooling for an hour, has none of
     * that state, and resuming it would need the file's state-setting commands replayed up to the
     * resume point rather than skipped. The honest answer there is to fail the job with its
     * position recorded - see {@link #onConnectionLost} - and that is what happens.
     *
     * <p>The position is read from the job rather than remembered here, which is the one query this
     * class makes of {@code jobs}. It has to be: a pause that outlives a restart has no in-memory
     * anything, and the checkpoint is where the number survives. Everything else reaches this class
     * as a fact on an event, because everything else is wanted on every transition rather than one.
     */
    private void resume(JobStateChanged event) {
        long from = jobs.fetch(event.jobId())
                .map(job -> job.progress().blocksSent())
                .orElse(0L);
        log.info("job {} resumes at block {}", event.jobId(), from);
        start(event, from);
    }

    /**
     * Stops, cools and retracts - what a cancelled or failed print leaves behind.
     *
     * <p>Queued at {@code REGULAR} so it preempts whatever is left of the file rather than waiting
     * for it. A cancellation that queued behind the rest of a six-hour print would cool the machine
     * down six hours after it was asked for.
     *
     * <p><strong>It does not home, park or disable the steppers.</strong> Homing drags the nozzle
     * across whatever was printed; disabling the steppers drops the Z axis on a machine without a
     * brake. Both need to know something about the machine that the farm does not, and getting
     * either wrong breaks a part or a printer. Recorded as debt rather than guessed at.
     */
    private void coolDown(UUID machineId) {
        var reactor = machineId == null ? null : reactors.runtimeOf(machineId);
        if (reactor == null) {
            return;
        }
        log.info("cooling {} down after a stopped print", machineId);
        reactor.interject(List.of(
                "M107",
                "M104 S0",
                "M140 S0",
                "M83",
                "G1 E-2 F2400"));
    }

    /** What the end of a feed means, reported as the fact {@code jobs} acts on. */
    private void finished(JobStateChanged event, long sent, boolean cancelled, Throwable error) {
        var feed = feeding.remove(event.jobId());
        if (feed != null) {
            feed.watchingTheLink().dispose();
            feed.watchingForAbandonment().dispose();
        }
        if (cancelled) {
            log.info("job {} stopped after {} blocks, which was asked for", event.jobId(), sent);
            return;
        }
        if (error != null) {
            fail(event, sent, error.toString());
            return;
        }
        log.info("job {} sent its last block ({} in all)", event.jobId(), sent);
        events.publishEvent(new JobFeedCompleted(event.jobId(), event.machineId(), sent,
                LocalDateTime.now()));
    }

    private void fail(JobStateChanged event, long sent, String reason) {
        log.warn("job {} failed after {} blocks: {}", event.jobId(), sent, reason);
        events.publishEvent(new JobFeedFailed(event.jobId(), event.machineId(), sent, reason,
                LocalDateTime.now()));
    }

}

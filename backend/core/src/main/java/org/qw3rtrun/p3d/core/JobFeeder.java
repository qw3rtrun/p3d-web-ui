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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
 * Feeds a running job's file to the machine it is assigned to - plan item C3. Driven by events in
 * and out, and calls into no other module: {@code jobs} announces a job started, this reads the
 * file through {@code GcodeFileService} and hands it to the machine at {@code BACKGROUND} (so an
 * interactive {@code GSender} command, queued at {@code REGULAR}, preempts it and the job resumes
 * where it left off), and on completion or failure this announces {@link JobFeedCompleted} or
 * {@link JobFeedFailed} for {@code jobs} to act on - only {@code core} can know whether the lines
 * reached the wire, only {@code jobs} may move a job.
 *
 * <h3>Nobody else would notice a feed dying, which is why two watchers exist (plan item C5, #60)</h3>
 * A dropped link leaves {@code PrinterReactor} holding a fresh, undrained {@code PublisherQueue} and
 * the feed's {@code Mono} subscribed to the abandoned one forever - so {@link #watchTheLink} fails
 * the job itself rather than leaving it {@code RUNNING} on a cold printer. A desynchronised send
 * window - the firmware asks to resend a line the host never framed, so the window renumbers with
 * {@code M110 N0} and drops what it held - would otherwise let the feed finish {@code COMPLETED}
 * having silently skipped real blocks (a dropped {@code G28} homes nowhere); {@link #watchForAbandonedBlocks}
 * fails it instead, because what was lost cannot be replayed from a checkpoint that only records
 * what was <em>sent</em>. Both watchers take their reading from {@link #stop} and put it on the
 * failure event, so the reading and the state change it causes are one write rather than two.
 * Since #85 {@link PrinterReactor#updates()} opens with a snapshot rather than a replay, so a new
 * feed's {@link #watchTheLink} can no longer be handed a previous feed's stale
 * {@link BlocksAbandoned} as if it had just happened.
 *
 * <h3>Every way a feed ends takes one exact reading, and who announces it differs (#62, #102)</h3>
 * {@link #reading} is the only place {@code blocksSent}/{@code inFlight} are read, by the progress
 * timer, by {@link #stop} and by {@link #finished} - and only the timer's is approximate. Before
 * this, a job's checkpoint on every terminal path was whatever the once-a-second timer last held,
 * because {@link #finished} removed the feed before {@link #stop} got a chance to record one: a
 * measured twelve-block job completed reading {@code blocksSent 4}, and a shorter one read
 * {@code 0}. A completed job's {@code blocksSent} is now the file's block count exactly, and
 * {@code inFlight} routinely stays non-zero on a {@code COMPLETED} job (measured {@code 12, inFlight 2}) -
 * a feed ends when the file runs out, not when the firmware has caught up, and the field says so
 * rather than hiding it.
 *
 * <p><strong>Only two things publish a {@link JobProgressed}: the {@code @Scheduled} timer and
 * {@link #onJobStopped}</strong>, neither of which can be on a reactor-netty thread. The end-of-feed
 * reading goes on {@link JobFeedCompleted}/{@link JobFeedFailed} instead, so {@code jobs} writes it
 * in the terminal transition's own transaction and the two cannot be observed apart - an ordering
 * that used to be bought by publishing the reading inline, on the event loop the feed ended on,
 * which is a blocking JDBC transaction there once per feed (#102; measured as
 * {@code JobProgressed reactor-tcp-nio-6 txn=false} against the timer's {@code scheduling-1}).
 *
 * <h3>A start waits for the commit; a stop must not (#93)</h3>
 * One listener split in two, not rewritten - the three branches were already mutually exclusive on
 * {@code (from, to)}, since nothing moves from {@code RUNNING} to {@code RUNNING}.
 * {@link #onJobStarted} is {@code AFTER_COMMIT} because <strong>this feed's own outcome moves the
 * job again, and a message that moves a job must not be produced from inside the transaction that
 * produced its own precondition</strong>. It was: a twelve-block file against a mock printer ran
 * out five milliseconds after {@code ASSIGNED -> RUNNING} and before that transition committed, so
 * {@code FeedOutcomes}' separate transaction read the row as {@code ASSIGNED} and logged
 * {@code refused to move job e0417935... from ASSIGNED to COMPLETED} - whole file on the wire, job
 * {@code RUNNING} for ever, because a feed outcome is at-most-once and nothing retries (4 of 10
 * loaded runs of {@code PrintJobIntegrationTest}, 0 of 10 idle). <strong>No responsiveness is
 * traded away</strong>: {@code AFTER_COMMIT} runs synchronously on the publishing thread, inside
 * the {@code TransactionTemplate} the service call is still in, so the feed is still under way
 * before the request that started it returns and a start against a disconnected machine is still
 * answered immediately. {@code fallbackExecution} because some callers publish
 * {@link JobStateChanged} with no transaction, and without it those starts would be dropped in
 * silence.
 *
 * <p>{@link #onJobStopped} stays inline for the opposite reason: it publishes the one exact
 * checkpoint a resume reads from inside the transition's own transaction, so
 * {@code JobServiceImpl.onProgress} shares that persistence context rather than racing it (#62).
 * After the commit it would be a second transaction an {@code OptimisticLockingFailureException}
 * may legitimately drop, and a resume would seek to the timer's stale number instead.
 */
@Slf4j
@RequiredArgsConstructor
public class JobFeeder {

    private final PrinterReactorManager reactors;
    private final GcodeFileService files;
    private final JobService jobs;
    private final ApplicationEventPublisher events;

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

    private Disposable watchTheLink(JobStateChanged event, PrinterReactor reactor,
                                    AtomicBoolean cancelled) {
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
                    var reading = stop(event.jobId(), JobState.FAILED);
                    cancelled.set(true);
                    fail(event, reading, "the connection to the machine went away while it was"
                            + " printing (" + lost.to()
                            + (lost.reason() == null ? "" : ": " + lost.reason()) + ")");
                });
    }

    private Disposable watchForAbandonedBlocks(JobStateChanged event, PrinterReactor reactor,
                                               AtomicBoolean cancelled) {
        return reactor.updates()
                .map(EventMessage::event)
                .filter(BlocksAbandoned.class::isInstance)
                .cast(BlocksAbandoned.class)
                .next()
                .subscribe(abandoned -> {
                    if (!feeding.containsKey(event.jobId())) {
                        return;
                    }
                    var reading = stop(event.jobId(), JobState.FAILED);
                    cancelled.set(true);
                    fail(event, reading, abandoned.blocks() + " block(s) this host had already"
                            + " framed were abandoned and never reached the machine: the printer"
                            + " asked to resend line " + abandoned.lineNumber() + ", which is not a"
                            + " block the send window still held, so the session had to be"
                            + " renumbered. What was skipped cannot be replayed from the"
                            + " checkpoint, because the checkpoint records what was sent and not"
                            + " what was accepted");
                });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onJobStarted(JobStateChanged event) {
        if (event.to() != JobState.RUNNING) {
            return;
        }
        if (event.from() == JobState.ASSIGNED) {
            start(event);
        } else if (event.from() == JobState.PAUSED) {
            resume(event);
        }
    }

    /**
     * The one place a {@link JobProgressed} is published from anything but the timer, and it is
     * published <em>here</em> rather than inside {@link #stop} on purpose (#102): this runs inside
     * the transition's own transaction, so {@code JobServiceImpl.onProgress} joins it rather than
     * racing it, and a resume reads the exact block the feed stopped at instead of the timer's
     * up-to-a-second-stale one (#62). The two watchers and {@link #finished} also call
     * {@link #stop}, from a reactor-netty thread and with no transaction - they put the same
     * reading on their outcome event instead.
     */
    @EventListener
    public void onJobStopped(JobStateChanged event) {
        if (event.from() != JobState.RUNNING) {
            return;
        }
        var reading = stop(event.jobId(), event.to());
        if (reading != null) {
            events.publishEvent(new JobProgressed(event.jobId(), event.machineId(), reading));
        }
        if (event.to() == JobState.CANCELLED || event.to() == JobState.FAILED) {
            coolDown(event.machineId());
        }
    }

    private void start(JobStateChanged event) {
        start(event, 0);
    }

    private void start(JobStateChanged event, long skip) {
        var reactor = reactors.runtimeOf(event.machineId());
        if (reactor == null) {
            fail(event, JobProgress.none(), "there is no runtime for machine "
                    + event.machineId() + ", so nothing could be sent to it");
            return;
        }
        var file = files.fetch(event.fileId()).orElse(null);
        if (file == null) {
            fail(event, JobProgress.none(), "there is no file " + event.fileId() + " to print");
            return;
        }

        var stop = Sinks.<Void>empty();
        var sent = new AtomicLong();
        var cancelled = new AtomicBoolean();
        var progress = new Progress(file.sizeBytes(), LocalDateTime.now());

        // skipped blocks are read and counted but not sent - the machine already has them - so a
        // resumed job's progress stays continuous instead of restarting at zero.
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

        var watchingTheLink = watchTheLink(event, reactor, cancelled);
        var watchingForAbandonment = watchForAbandonedBlocks(event, reactor, cancelled);

        feeding.put(event.jobId(), new Feed(event.jobId(), event.machineId(), stop, sent,
                cancelled, subscription, watchingTheLink, watchingForAbandonment, progress));
    }

    /**
     * What has gone out, and how much of the file it came from. Counted from what the job's own
     * source emitted - monotone and immune to a resend, because a resend is served from
     * {@code GSendWindow} into {@code CommandProcessor}'s own replay deque and never pulls this
     * source a second time. {@code inFlight} is the one figure that can run ahead of the firmware,
     * bounded by the send window (at most four), and is reported as {@code JobProgress.inFlight}
     * rather than hidden.
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

    /** On a timer rather than per block: a page asks about once a second, so that is fast enough and nothing finer is worth an event and a write per block. */
    @Scheduled(fixedDelayString = "${p3d.jobs.progress-interval:1000}")
    public void reportProgress() {
        feeding.values().forEach(feed -> events.publishEvent(
                new JobProgressed(feed.jobId(), feed.machineId(), reading(feed))));
    }

    /**
     * Where a feed has got to, right now - see the class javadoc's checkpoint section. Takes the
     * reading and publishes nothing: who announces it differs by caller, which is the whole of
     * #102. {@code inFlight} is re-read from the aggregate here, not left at the timer's value,
     * because {@link JobProgress#blocksAccepted()} is {@code blocksSent - inFlight} and a fresh
     * count paired with a stale gap would be a third number that was never true. Every caller that
     * ends a feed removes it from {@link #feeding} first, so a feed is read exactly once at the end.
     */
    private JobProgress reading(Feed feed) {
        var reactor = reactors.runtimeOf(feed.machineId());
        if (reactor != null) {
            feed.progress().acknowledged(reactor.blocksInFlight());
        }
        return feed.progress().snapshot();
    }

    /**
     * Stops feeding because the job was cancelled, paused or failed elsewhere. Completes the source
     * rather than cancelling the subscription, so the send window finishes blocks already framed and
     * the queue hands its demand on instead of being torn out from under the drain loop - then takes
     * one exact reading, since the timer's own checkpoints are up to a second stale and a resumed
     * job starts from whatever was written down here.
     *
     * @return that exact reading, for the caller to put wherever it belongs, or null when there was
     *         no feed to stop
     */
    private JobProgress stop(UUID jobId, JobState to) {
        var feed = feeding.remove(jobId);
        if (feed == null) {
            return null;
        }
        log.info("job {} stopped after {} blocks ({})", jobId, feed.sent().get(), to);
        feed.cancelled().set(true);
        feed.stop().tryEmitEmpty();
        feed.watchingTheLink().dispose();
        feed.watchingForAbandonment().dispose();
        return reading(feed);
    }

    /**
     * Resumes a paused job by seeking to the block it stopped at (plan item C5) - safe only because
     * a pause leaves the machine connected, hot and positioned exactly where block N-1 left it, so
     * continuing at block N is continuing rather than restarting. Not true after a disconnect, which
     * has none of that state and instead fails with its position recorded (see
     * {@link #watchTheLink}). The resume point is read from the job rather than remembered here,
     * since a pause can outlive a restart and the checkpoint is where the number survives.
     */
    private void resume(JobStateChanged event) {
        long from = jobs.fetch(event.jobId())
                .map(job -> job.progress().blocksSent())
                .orElse(0L);
        log.info("job {} resumes at block {}", event.jobId(), from);
        start(event, from);
    }

    /**
     * Stops, cools and retracts - what a cancelled or failed print leaves behind. Queued at
     * {@code REGULAR} so it preempts whatever is left of the file, rather than cooling the machine
     * down hours after it was asked for. Does not home, park or disable the steppers - each needs a
     * machine fact the farm does not have, and getting either wrong breaks a part or a printer; left
     * as debt rather than guessed at.
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

    /**
     * What the end of a feed means, reported as the fact {@code jobs} acts on. This is the one
     * caller that runs on a reactor-netty thread - the file running out arrives through
     * {@code PublisherQueue} on the connection's own loop - so the exact reading rides on
     * {@link JobFeedCompleted}/{@link JobFeedFailed} rather than being published ahead of it as a
     * {@link JobProgressed} (#102): {@code jobs} writes it in the same transaction as the terminal
     * transition, which is a structural guarantee of the ordering #62 bought with a JDBC
     * transaction on an event loop. No timer ever reads this feed again - it has just been removed
     * from {@link #feeding} - so that number is what the job keeps.
     */
    private void finished(JobStateChanged event, long sent, boolean cancelled, Throwable error) {
        var feed = feeding.remove(event.jobId());
        var reading = JobProgress.none();
        if (feed != null) {
            feed.watchingTheLink().dispose();
            feed.watchingForAbandonment().dispose();
            reading = reading(feed);
        }
        if (cancelled) {
            log.info("job {} stopped after {} blocks, which was asked for", event.jobId(), sent);
            return;
        }
        if (error != null) {
            fail(event, reading, error.toString());
            return;
        }
        log.info("job {} sent its last block ({} in all)", event.jobId(), sent);
        events.publishEvent(new JobFeedCompleted(event.jobId(), event.machineId(), reading,
                LocalDateTime.now()));
    }

    /** @param reading where the feed got to, or null when there was no feed to take one from */
    private void fail(JobStateChanged event, JobProgress reading, String reason) {
        var got = reading == null ? JobProgress.none() : reading;
        log.warn("job {} failed after {} blocks: {}", event.jobId(), got.blocksSent(), reason);
        events.publishEvent(new JobFeedFailed(event.jobId(), event.machineId(), got, reason,
                LocalDateTime.now()));
    }

}

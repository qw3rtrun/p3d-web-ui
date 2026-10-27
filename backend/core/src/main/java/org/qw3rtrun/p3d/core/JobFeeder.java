package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.JobFeedCompleted;
import org.qw3rtrun.p3d.api.jobs.JobFeedFailed;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
                        AtomicBoolean cancelled, Disposable subscription) {
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
            refuseToResume(event);
            return;
        }
        if (event.from() == JobState.RUNNING) {
            stop(event.jobId(), event.to());
        }
    }

    private void start(JobStateChanged event) {
        var reactor = reactors.runtimeOf(event.machineId());
        if (reactor == null) {
            fail(event, 0, "there is no runtime for machine " + event.machineId()
                    + ", so nothing could be sent to it");
            return;
        }

        var stop = Sinks.<Void>empty();
        var sent = new AtomicLong();
        var cancelled = new AtomicBoolean();

        var lines = GcodeLines.blocksOf(files.read(event.fileId()))
                .doOnNext(block -> sent.incrementAndGet())
                .takeUntilOther(stop.asMono());

        log.info("job {} is feeding {} to {}", event.jobId(), event.fileId(), event.machineId());
        var subscription = reactor.feed(lines).subscribe(
                nothing -> {
                },
                error -> finished(event, sent.get(), cancelled.get(), error),
                () -> finished(event, sent.get(), cancelled.get(), null));

        feeding.put(event.jobId(), new Feed(event.jobId(), event.machineId(), stop, sent,
                cancelled, subscription));
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
    }

    /**
     * Resuming a paused job is refused, loudly, rather than silently reprinting from the top.
     *
     * <p><strong>There is no safe naive resume and this is the honest way to say so.</strong> The
     * file is a stream and the feed holds no position in it, so starting again means starting at
     * line one - on a machine that is half way through a part, with the bed hot and the nozzle
     * somewhere above it. Plan item C5 owns recovery, and it has to restore temperatures, position,
     * extrusion mode and feedrate as of the last acknowledged line before anything is sent.
     *
     * <p>Failing the job is not a pleasant answer. It is better than the two alternatives: printing
     * the part twice, or leaving a job that reads as {@code RUNNING} with nothing feeding it.
     */
    private void refuseToResume(JobStateChanged event) {
        log.warn("job {} was resumed, which plan item C5 owns and this cannot do safely", event.jobId());
        fail(event, 0, "resuming a paused job is not implemented yet: the feed holds no position in"
                + " the file, so it would restart from the first line. See plan item C5.");
    }

    /** What the end of a feed means, reported as the fact {@code jobs} acts on. */
    private void finished(JobStateChanged event, long sent, boolean cancelled, Throwable error) {
        feeding.remove(event.jobId());
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

    /** The connection went away, so every job on that machine stops being fed. */
    void onMachineOffline(UUID machineId, String why) {
        feeding.values().stream()
                .filter(feed -> machineId.equals(feed.machineId()))
                .toList()
                .forEach(feed -> {
                    feeding.remove(feed.jobId());
                    feed.cancelled().set(true);
                    feed.stop().tryEmitEmpty();
                    fail(new JobStateChanged(feed.jobId(), null, JobState.RUNNING, JobState.RUNNING,
                            machineId, null, LocalDateTime.now()), feed.sent().get(), why);
                });
    }
}

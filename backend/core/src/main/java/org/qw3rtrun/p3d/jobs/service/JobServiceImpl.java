package org.qw3rtrun.p3d.jobs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.qw3rtrun.p3d.api.jobs.JobProgress;
import org.qw3rtrun.p3d.api.jobs.JobProgressed;
import org.qw3rtrun.p3d.api.jobs.JobRequirements;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.jobs.model.JobEntity;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The print queue: one verb per thing that can happen to a job, all going through
 * {@link JobEntity#moveTo} - find the job, ask it to move, publish what moved, deliberately
 * repetitive rather than letting each verb reimplement "is this legal". A refusal never reaches the
 * database: the entity answers null for an illegal move and {@link #apply} turns that into
 * {@link JobOutcome.Refused} with where the job actually is (plan item C2's "a value rather than a
 * constraint violation or an exception from somewhere deep" - the common cause is an ordinary race,
 * a user clicking Pause while the last line is being acknowledged).
 *
 * <p>A listener is {@code @ApplicationModuleListener} only when its event is published inside a
 * transaction, which rules it out for anything {@code core} reports from a reactive thread -
 * {@link JobProgressed} here, and the two feed outcomes in {@link FeedOutcomes}. Those are a plain
 * {@code @EventListener}, which makes them at-most-once rather than guaranteed, and the
 * {@code @Transactional} goes on the listener rather than only on the verb it calls, since that call
 * is a self-invocation and would otherwise skip the proxy.
 *
 * <p>{@link #onProgress} has two callers and neither is an event loop (#102): {@code JobFeeder}'s
 * once-a-second timer, on a scheduler thread with no transaction, and its {@code onJobStopped},
 * inside the transition's own transaction, which the checkpoint therefore joins rather than races
 * (#62). The exact reading taken when a feed <em>ends</em> no longer arrives as an event at all -
 * it rides on {@code JobFeedCompleted}/{@code JobFeedFailed} and is written by
 * {@link #complete(UUID, JobProgress)}/{@link #fail(UUID, String, JobProgress)} in the same
 * transaction as the terminal transition, so the two cannot be observed apart. <strong>The trade is
 * that such a reading is lost exactly when its transaction is rolled back</strong>: an overtaken
 * {@link #move} takes it with it, where before it landed in one of its own. A merely <em>refused</em>
 * one still records it - a reading is a fact about what was sent rather than part of the move. The
 * window is the few milliseconds between a feed ending and {@code FeedOutcomes}' hopped task
 * running, and losing a transition there is already at-most-once by design.
 *
 * <p><strong>Every write path that can be overtaken owns its transaction explicitly</strong>
 * (#100), with a {@link TransactionTemplate} rather than {@code @Transactional}. Since
 * {@code JobEntity} carries a {@code @Version}, a writer whose read has been overtaken is refused
 * at flush - and the flush is on commit, which is <em>after</em> an annotated method has returned.
 * What to tell the caller therefore cannot be decided inside the method that failed; it has to be
 * decided outside the transaction boundary, which is what makes that boundary something this class
 * states rather than something the proxy supplies. The verbs below are consequently not annotated:
 * the template in {@link #move} is their transaction.
 *
 * <p>Being overtaken is answered the way an illegal transition is - {@link JobOutcome.Refused}
 * carrying the state the job actually went to, which {@code Outcomes} turns into a 409 - so there
 * is one failure shape for "the job was not in the state you thought". It is deliberately
 * <em>not</em> retried: the caller asked to move a job that was somewhere else, and doing it anyway
 * from wherever it has since got to decides on their behalf something they can see for themselves.
 * {@link #onProgress} and {@link #blocked} are the exceptions, and only because neither has a
 * caller to tell - both merely annotate the row, so losing drops the write: the checkpoint timer
 * writes again a second later, and a reason attached to a job that has moved is stale by
 * definition.
 *
 * <p>The two listeners that call {@link #apply} directly - {@link #onMachineDeleted} and
 * {@link #failWhatTheRestartInterrupted} - keep {@code @Transactional} and let the failure out.
 * That is right for both: the first is durable, so the registry redelivers it, and the second runs
 * at startup where nothing else is moving jobs.
 */
@Slf4j
@RequiredArgsConstructor
public class JobServiceImpl implements JobService {

    private final JobRepository repo;
    private final GcodeFileService files;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /** Queues a file. Checked here rather than by a foreign key: the two live in different modules, and a finished job's record must survive deleting the file it printed. */
    @Override
    @Transactional
    public JobOutcome queue(UUID fileId, JobRequirements requires) {
        Optional<GcodeFile> found = files.fetch(fileId);
        if (found.isEmpty()) {
            return new JobOutcome.Refused(null, null, JobState.QUEUED,
                    "there is no file " + fileId + " to print");
        }
        var file = found.get();
        var job = repo.save(new JobEntity(UUID.randomUUID(), file.id(), file.name(), now(), requires));
        log.info("queued {} to print {}", job.getId(), file.name());
        events.publishEvent(new JobStateChanged(
                job.getId(), file.id(), null, JobState.QUEUED, null, null, job.getCreatedAt()));
        return new JobOutcome.Done(job.describe());
    }

    @Override
    public JobOutcome assign(UUID jobId, UUID machineId) {
        return move(jobId, JobState.ASSIGNED, machineId, null);
    }

    @Override
    public JobOutcome unassign(UUID jobId, String reason) {
        return move(jobId, JobState.QUEUED, null, reason);
    }

    @Override
    public JobOutcome start(UUID jobId) {
        return move(jobId, JobState.RUNNING, null, null);
    }

    @Override
    public JobOutcome pause(UUID jobId) {
        return move(jobId, JobState.PAUSED, null, null);
    }

    @Override
    public JobOutcome resume(UUID jobId) {
        return move(jobId, JobState.RUNNING, null, null);
    }

    @Override
    public JobOutcome complete(UUID jobId, JobProgress finalReading) {
        return move(jobId, JobState.COMPLETED, null, null, finalReading);
    }

    @Override
    public JobOutcome fail(UUID jobId, String reason, JobProgress finalReading) {
        return move(jobId, JobState.FAILED, null, reason, finalReading);
    }

    @Override
    public JobOutcome cancel(UUID jobId, String reason) {
        return move(jobId, JobState.CANCELLED, null, reason);
    }

    /**
     * A machine left the farm, so everything it was holding is dealt with: an unstarted job goes
     * back to the queue (nothing lost); a started one fails, since the file is partly consumed and a
     * second machine starting from line one would print the top half onto an empty bed - plan item
     * C5's recovery is what happens to those next. {@code @ApplicationModuleListener}, so the
     * registry writes the publication inside the delete's own transaction and redelivers on failure -
     * otherwise a printer deleted mid-shutdown could leave its jobs assigned to a machine that no
     * longer exists, unnoticed.
     */
    @ApplicationModuleListener
    public void onMachineDeleted(MDeletedEvent event) {
        var held = repo.findByMachineIdAndStateIn(event.getMachineId(),
                List.of(JobState.ASSIGNED, JobState.RUNNING, JobState.PAUSED));
        for (var job : held) {
            if (job.getState() == JobState.ASSIGNED) {
                apply(job, JobState.QUEUED, null, "the machine it was assigned to was removed");
            } else {
                apply(job, JobState.FAILED, null, "the machine it was printing on was removed");
            }
        }
    }

    /**
     * A job that was printing when the process died cannot still be printing - plan item C5. The
     * feed lived in memory and the process took it with it, so a {@code RUNNING} job would hold its
     * printer against the scheduler forever and claim a cold machine is halfway through a part. They
     * fail, with the checkpoint intact - "the honest first version", since resuming would mean
     * replaying the file's state-setting commands up to the last block rather than the seek
     * {@code JobFeeder.resume} does for a pause, and the recorded position is deliberately left for a
     * better version to use later. {@code PAUSED} jobs are left alone: that machine is still
     * connected and hot (the farm redials it at startup), and the checkpoint lets it resume exactly
     * as it would have before the restart.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failWhatTheRestartInterrupted() {
        var interrupted = repo.findByStateOrderByCreatedAtAsc(JobState.RUNNING);
        if (interrupted.isEmpty()) {
            return;
        }
        log.warn("{} job(s) were printing when the process stopped and cannot be resumed from here",
                interrupted.size());
        interrupted.forEach(job -> apply(job, JobState.FAILED, null,
                "the process restarted while this was printing; it had sent "
                        + job.describe().progress().blocksSent() + " blocks"));
    }

    /**
     * A running job got further, so the checkpoint moves. Arrives about once a second, not once a
     * block - {@code core} publishes on a timer - so this is one write per second per running
     * printer, a checkpoint rather than a ledger. A reading overtaken by a transition is dropped
     * rather than refused to anybody: the timer writes again a second later, and the one reading
     * that has to land - the exact one taken when a feed stops - is published from inside the
     * transition's own transaction and therefore shares its persistence context rather than racing
     * it. A feed that <em>ends</em> does not come through here at all; see the class javadoc.
     */
    @EventListener
    public void onProgress(JobProgressed event) {
        try {
            transactions.executeWithoutResult(status ->
                    repo.findById(event.jobId()).ifPresent(job -> {
                        job.progressed(event.progress());
                        repo.save(job);
                    }));
        } catch (OptimisticLockingFailureException lost) {
            log.debug("the checkpoint for job {} was overtaken by a transition and dropped",
                    event.jobId());
        }
    }

    private JobOutcome move(UUID jobId, JobState next, UUID machineId, String reason) {
        return move(jobId, next, machineId, reason, JobProgress.none());
    }

    /**
     * The same, writing the reading the caller ended a feed on before the transition (#102). One
     * transaction, so the order within it decides nothing about visibility; it is written first
     * because that is the order the facts happened in.
     */
    private JobOutcome move(UUID jobId, JobState next, UUID machineId, String reason,
                            JobProgress reading) {
        try {
            return transactions.execute(status -> {
                Optional<JobEntity> found = repo.findById(jobId);
                if (found.isEmpty()) {
                    return new JobOutcome.Refused(jobId, null, next, "there is no job " + jobId);
                }
                var job = found.get();
                // lands even when apply refuses: job is managed, and a reading is a fact about the feed rather than part of the transition
                job.progressed(reading);
                return apply(job, next, machineId, reason);
            });
        } catch (OptimisticLockingFailureException lost) {
            return overtaken(jobId, next);
        }
    }

    /** Where the job actually went while this move was being decided, as the refusal its caller gets. */
    private JobOutcome overtaken(UUID jobId, JobState attempted) {
        var where = transactions.execute(
                status -> repo.findById(jobId).map(JobEntity::getState).orElse(null));
        if (where == null) {
            return new JobOutcome.Refused(jobId, null, attempted, "there is no job " + jobId);
        }
        log.info("job {} could not become {}: something else moved it to {} first",
                jobId, attempted, where);
        return new JobOutcome.Refused(jobId, where, attempted,
                "this job was moved to " + where + " by something else before it could become "
                        + attempted);
    }

    private JobOutcome apply(JobEntity job, JobState next, UUID machineId, String reason) {
        var was = job.getState();
        var change = job.moveTo(next, machineId, reason, now());
        if (change == null) {
            log.info("refused to move job {} from {} to {}", job.getId(), was, next);
            return new JobOutcome.Refused(job.getId(), was, next,
                    "a job that is " + was + " cannot become " + next);
        }
        repo.save(job);
        log.info("job {}: {} -> {}{}", job.getId(), change.from(), change.to(),
                reason == null ? "" : " (" + reason + ")");
        events.publishEvent(change);
        return new JobOutcome.Done(job.describe());
    }

    /** Records why a queued job is stuck, without moving it - only when the reason has changed, since a scheduler runs on every transition and on a timer and would otherwise rewrite the same row forever. A reason overtaken by a transition is dropped rather than written: a job that has moved is not waiting for anything. */
    @Override
    public JobOutcome blocked(UUID jobId, String why) {
        try {
            return transactions.execute(status -> {
                var found = repo.findById(jobId);
                if (found.isEmpty()) {
                    return new JobOutcome.Refused(jobId, null, null, "there is no job " + jobId);
                }
                var job = found.get();
                if (job.getState() != JobState.QUEUED) {
                    return new JobOutcome.Refused(jobId, job.getState(), null,
                            "only a queued job can be waiting for a machine");
                }
                if (job.blockedReasonIs(why)) {
                    return new JobOutcome.Done(job.describe());
                }
                log.info("job {} is waiting: {}", jobId, why);
                job.blocked(why);
                return new JobOutcome.Done(repo.save(job).describe());
            });
        } catch (OptimisticLockingFailureException lost) {
            return overtaken(jobId, null);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Job> queued() {
        return repo.findByStateOrderByCreatedAtAsc(JobState.QUEUED).stream()
                .map(JobEntity::describe).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Job> fetch(UUID jobId) {
        return repo.findById(jobId).map(JobEntity::describe);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Job> fetchAll() {
        return repo.findAllByOrderByCreatedAtDesc().stream().map(JobEntity::describe).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Job> forMachine(UUID machineId) {
        return repo.findByMachineIdOrderByCreatedAtDesc(machineId).stream()
                .map(JobEntity::describe).toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}

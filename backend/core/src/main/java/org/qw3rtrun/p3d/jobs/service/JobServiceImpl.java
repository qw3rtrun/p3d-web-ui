package org.qw3rtrun.p3d.jobs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobFeedCompleted;
import org.qw3rtrun.p3d.api.jobs.JobFeedFailed;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
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
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The print queue: one verb per thing that can happen to a job, all of them going through
 * {@link JobEntity#moveTo}.
 *
 * <p><strong>Every verb has the same shape</strong> - find the job, ask it to move, publish what
 * moved - and that is deliberate rather than repetitive. The alternative is the shape where each
 * verb reimplements "is this legal", which is how a queue acquires a state nobody designed.
 *
 * <p><strong>A refusal never reaches the database.</strong> The entity answers null for an illegal
 * move and {@link #apply} turns that into {@link JobOutcome.Refused} with where the job actually
 * is. Plan item C2's first done-when is that this is a value rather than a constraint violation or
 * an exception from somewhere deep, because the common cause is an ordinary race - a user clicking
 * Pause while the last line is being acknowledged - and that deserves an answer.
 */
@Slf4j
@RequiredArgsConstructor
public class JobServiceImpl implements JobService {

    private final JobRepository repo;
    private final GcodeFileService files;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * Queues a file.
     *
     * <p>The file is checked here rather than by a foreign key. The two live in different modules,
     * so a database constraint between them would be a dependency the boundary says does not
     * exist - and it would be the wrong behaviour anyway, since deleting a file that a finished job
     * printed must not delete the record that it printed.
     */
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
    @Transactional
    public JobOutcome assign(UUID jobId, UUID machineId) {
        return move(jobId, JobState.ASSIGNED, machineId, null);
    }

    @Override
    @Transactional
    public JobOutcome unassign(UUID jobId, String reason) {
        return move(jobId, JobState.QUEUED, null, reason);
    }

    @Override
    @Transactional
    public JobOutcome start(UUID jobId) {
        return move(jobId, JobState.RUNNING, null, null);
    }

    @Override
    @Transactional
    public JobOutcome pause(UUID jobId) {
        return move(jobId, JobState.PAUSED, null, null);
    }

    @Override
    @Transactional
    public JobOutcome resume(UUID jobId) {
        return move(jobId, JobState.RUNNING, null, null);
    }

    @Override
    @Transactional
    public JobOutcome complete(UUID jobId) {
        return move(jobId, JobState.COMPLETED, null, null);
    }

    @Override
    @Transactional
    public JobOutcome fail(UUID jobId, String reason) {
        return move(jobId, JobState.FAILED, null, reason);
    }

    @Override
    @Transactional
    public JobOutcome cancel(UUID jobId, String reason) {
        return move(jobId, JobState.CANCELLED, null, reason);
    }

    /**
     * A machine left the farm, so everything it was holding is dealt with.
     *
     * <p><strong>An unstarted job goes back to the queue; a started one fails.</strong> That is the
     * one distinction worth making here: a job that has not begun can be given to another printer
     * with nothing lost, and a job that was half printed cannot - the file has been partly consumed
     * and a second machine starting it from line one would print the top half of the part onto an
     * empty bed. What happens to those is plan item C5's recovery, and in the meantime they are
     * {@code FAILED} with a reason that says so.
     *
     * <p><strong>{@code @ApplicationModuleListener} and not a plain {@code @EventListener}</strong>,
     * so the registry writes the publication down inside the delete's transaction and redelivers if
     * this fails. A printer deleted while the application was shutting down would otherwise leave
     * its jobs assigned to a machine that no longer exists, which nothing would ever notice.
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
     * The machine has been sent every line of the file, so the job is done.
     *
     * <p>Only {@code core} can know this - knowing it means holding the socket - and only this
     * module may move a job, which is why it crosses as an event rather than a call. The same
     * division a firmware report crosses in plan item B2, running the other way.
     *
     * <p><strong>A plain listener, and deliberately not an {@code @ApplicationModuleListener}.</strong>
     * That annotation is a {@code @TransactionalEventListener}, which <em>drops</em> an event
     * published outside a transaction - and this one is published by {@code core} from a
     * reactor-netty thread, which has no transaction and no database to tie one to. Using it here
     * silently swallowed the completion: a 96,152-block print reached the end of its file, the
     * transport logged the last block, and the job stayed {@code RUNNING} forever.
     *
     * <p>So a feed outcome is <strong>at-most-once</strong>, and that is a real limitation rather
     * than a detail: if the process dies between the last block and this listener, the job is left
     * {@code RUNNING} with nothing feeding it. Reconciling that at startup belongs to plan item C5,
     * which already has to deal with a job whose machine went away mid-print.
     *
     * <p>{@code @Transactional} is on the listener rather than only on {@link #complete}, because
     * the call below is a self-invocation and would not go through the proxy.
     */
    @EventListener
    @Transactional
    public void onFeedCompleted(JobFeedCompleted event) {
        log.info("job {} was fed {} blocks and is done", event.jobId(), event.lines());
        complete(event.jobId());
    }

    /**
     * A job that was printing when the process died cannot still be printing - plan item C5.
     *
     * <p><strong>The feed lives in memory and the process took it with it.</strong> Nothing is
     * sending blocks to that machine any more, so a job left {@code RUNNING} is a job that reads
     * as alive and is not: it would hold its printer against the scheduler for ever and tell an
     * operator that a cold machine is halfway through a part.
     *
     * <p><strong>They fail, with the checkpoint intact.</strong> That is the plan's own "the honest
     * first version": resuming would mean restoring temperatures, position, extrusion mode and
     * feedrate as of the last block, which is a replay of the file's state-setting commands and not
     * the seek that {@code JobFeeder.resume} does for a pause. The recorded position is what makes
     * a better version possible later, and it is deliberately not overwritten here.
     *
     * <p><strong>{@code PAUSED} jobs are left alone, and that is the useful half.</strong> A paused
     * job's machine is still connected and still hot - the farm redials it at startup - and the
     * block it stopped at is in the checkpoint, so resuming it after a restart works exactly as
     * resuming it before one does.
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
     * A running job got further, so the checkpoint moves.
     *
     * <p>Arrives about once a second rather than once a block - {@code core} publishes on a timer -
     * so this is one write per second per running printer, which is what "persisted often enough to
     * survive a restart" costs. The plan item asks for a checkpoint and not a ledger.
     *
     * <p>Plain and transactional for the same reason as the two below: it is published from a
     * reactive thread with no transaction to tie a durable publication to.
     */
    @EventListener
    @Transactional
    public void onProgress(JobProgressed event) {
        repo.findById(event.jobId()).ifPresent(job -> {
            job.progressed(event.progress());
            repo.save(job);
        });
    }

    /** The feed stopped before the file did - see {@link #onFeedCompleted} for why this is plain. */
    @EventListener
    @Transactional
    public void onFeedFailed(JobFeedFailed event) {
        fail(event.jobId(), event.reason() + " (after " + event.lines() + " blocks)");
    }

    private JobOutcome move(UUID jobId, JobState next, UUID machineId, String reason) {
        Optional<JobEntity> found = repo.findById(jobId);
        if (found.isEmpty()) {
            return new JobOutcome.Refused(jobId, null, next, "there is no job " + jobId);
        }
        return apply(found.get(), next, machineId, reason);
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

    /**
     * Records why a queued job is stuck, without moving it.
     *
     * <p>Written only when the reason has changed. A scheduler runs on every job transition and on
     * a timer, so a farm with one unassignable job and nothing else happening would otherwise
     * write the same sentence to the same row for ever.
     */
    @Override
    @Transactional
    public JobOutcome blocked(UUID jobId, String why) {
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

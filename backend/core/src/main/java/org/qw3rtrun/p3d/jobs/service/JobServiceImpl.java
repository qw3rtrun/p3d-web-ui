package org.qw3rtrun.p3d.jobs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.Job;
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
 * <p>{@link #onProgress} is therefore the one listener left here that can open a transaction on a
 * reactor-netty thread: a checkpoint usually arrives from {@code JobFeeder}'s own timer, but the
 * exact reading taken when a feed ends is published inline from wherever the feed ended. That is
 * deliberate for now - the reading must be written before the event that moves the job, and running
 * inline is what buys that order - so moving it needs a design for keeping the order rather than a
 * scheduler hop.
 */
@Slf4j
@RequiredArgsConstructor
public class JobServiceImpl implements JobService {

    private final JobRepository repo;
    private final GcodeFileService files;
    private final ApplicationEventPublisher events;
    private final Clock clock;

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

    /** A running job got further, so the checkpoint moves. Arrives about once a second, not once a block - {@code core} publishes on a timer - so this is one write per second per running printer, a checkpoint rather than a ledger. */
    @EventListener
    @Transactional
    public void onProgress(JobProgressed event) {
        repo.findById(event.jobId()).ifPresent(job -> {
            job.progressed(event.progress());
            repo.save(job);
        });
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

    /** Records why a queued job is stuck, without moving it - only when the reason has changed, since a scheduler runs on every transition and on a timer and would otherwise rewrite the same row forever. */
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

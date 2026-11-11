package org.qw3rtrun.p3d.api.jobs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The farm's print queue.
 *
 * <p>Every method that moves a job answers with a {@link JobOutcome}, so a refusal is a value a
 * caller can act on rather than an exception to catch and classify.
 *
 * <p>The verbs are split by who calls them, since each has different authorisation: {@link #queue}
 * and {@link #cancel} are a person; {@link #assign} is a person or D1's scheduler; {@link #start},
 * {@link #complete(UUID, JobProgress)} and {@link #fail(UUID, String, JobProgress)} belong to
 * whatever is actually feeding the machine (C3), and carry the exact reading that feed ended on.
 *
 * <p>Blocking, like the rest of the persistence side; the web layer schedules it off the event loop.
 */
public interface JobService {

    /**
     * Queues a file to be printed, with no machine yet - choosing one is {@link #assign}. Refused
     * when the file is unknown.
     */
    default JobOutcome queue(UUID fileId) {
        return queue(fileId, JobRequirements.any());
    }

    /**
     * The same, stating what the job needs of a machine - plan item D1. A job that names a machine
     * waits for it however free the rest of the farm is, the manual override the plan asks for.
     */
    JobOutcome queue(UUID fileId, JobRequirements requires);

    /** Gives a queued job to a machine. Refused unless the job is {@code QUEUED}. */
    JobOutcome assign(UUID jobId, UUID machineId);

    /**
     * Takes a job back off a machine and returns it to the queue - for a machine that went away
     * before the job started. A job already <em>running</em> cannot come back this way; that is
     * {@link #fail} and C5's recovery.
     */
    JobOutcome unassign(UUID jobId, String reason);

    /** The machine has begun. Refused unless the job is {@code ASSIGNED}. */
    JobOutcome start(UUID jobId);

    JobOutcome pause(UUID jobId);

    JobOutcome resume(UUID jobId);

    /**
     * It printed to the end, and this is exactly where it got to - the reading
     * {@link JobFeedCompleted} carried. Written in the same transaction as the transition (#62,
     * #102), so a job is never visible as over while its checkpoint still says something else: a
     * feed is removed the moment it ends, so nothing checkpoints that job again and this number is
     * what it keeps.
     */
    JobOutcome complete(UUID jobId, JobProgress finalReading);

    /** Something broke, and the reason is kept on the job for whoever asks next. The reading is {@link #complete(UUID, JobProgress)}'s, for the same reason. */
    JobOutcome fail(UUID jobId, String reason, JobProgress finalReading);

    /** For a caller with no reading of its own to record - a state-machine test, or a verb reached from somewhere that never fed anything. */
    default JobOutcome complete(UUID jobId) {
        return complete(jobId, JobProgress.none());
    }

    /** The same, for {@link #fail(UUID, String, JobProgress)}. */
    default JobOutcome fail(UUID jobId, String reason) {
        return fail(jobId, reason, JobProgress.none());
    }

    /** An operator stopped it. Refused only when the job has already ended. */
    JobOutcome cancel(UUID jobId, String reason);

    /**
     * Records why a queued job has not been given a machine - plan item D1's "a stated reason".
     * Does not move the job: being unassignable is a fact about the farm right now, not a state,
     * so the reason lands on the job and it stays {@code QUEUED}. Refused for a job that is not
     * queued.
     */
    JobOutcome blocked(UUID jobId, String why);

    /** Everything waiting for a machine, oldest first - the order a scheduler assigns in. */
    List<Job> queued();

    Optional<Job> fetch(UUID jobId);

    /** The whole queue, newest first. */
    List<Job> fetchAll();

    /** Everything a machine is holding - what a printer's page shows and what D1 will ask. */
    List<Job> forMachine(UUID machineId);
}

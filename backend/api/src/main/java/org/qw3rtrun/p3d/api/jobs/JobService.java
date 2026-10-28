package org.qw3rtrun.p3d.api.jobs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The farm's print queue.
 *
 * <p><strong>Every method that moves a job answers with a {@link JobOutcome}</strong>, so a refusal
 * is a value a caller can act on rather than an exception it has to catch and classify. See that
 * type for why.
 *
 * <p><strong>The verbs are split by who does them</strong>, because they genuinely have different
 * callers and will get different authorisation: {@link #queue} and {@link #cancel} are a person;
 * {@link #assign} is a person or plan item D1's scheduler; {@link #start}, {@link #complete} and
 * {@link #fail} belong to whatever is actually feeding the machine, which is C3's streaming engine.
 * A single {@code moveTo(state)} would be fewer methods and would lose all of that.
 *
 * <p>Blocking, like the rest of the persistence side. The web layer schedules it off the event loop.
 */
public interface JobService {

    /**
     * Queues a file to be printed.
     *
     * <p>No machine yet: choosing one is {@link #assign}, and keeping the two apart is what lets a
     * farm queue work before it knows which printer is free. Refused when the file is unknown,
     * because a job pointing at nothing is a job that fails the moment anything tries to run it.
     */
    default JobOutcome queue(UUID fileId) {
        return queue(fileId, JobRequirements.any());
    }

    /**
     * The same, stating what the job needs of a machine - plan item D1.
     *
     * <p>A job that states nothing runs on the first idle printer. A job that names a machine waits
     * for that machine however free the rest of the farm is, which is the manual override the plan
     * asks for.
     */
    JobOutcome queue(UUID fileId, JobRequirements requires);

    /** Gives a queued job to a machine. Refused unless the job is {@code QUEUED}. */
    JobOutcome assign(UUID jobId, UUID machineId);

    /**
     * Takes a job back off a machine and returns it to the queue.
     *
     * <p>For a machine that went away before the job started. A job that was actually
     * <em>running</em> on it cannot come back this way - the machine has already consumed part of
     * the file, so what happens to it is {@link #fail} and plan item C5's recovery.
     */
    JobOutcome unassign(UUID jobId, String reason);

    /** The machine has begun. Refused unless the job is {@code ASSIGNED}. */
    JobOutcome start(UUID jobId);

    JobOutcome pause(UUID jobId);

    JobOutcome resume(UUID jobId);

    /** It printed to the end. */
    JobOutcome complete(UUID jobId);

    /** Something broke, and the reason is kept on the job for whoever asks next. */
    JobOutcome fail(UUID jobId, String reason);

    /** An operator stopped it. Refused only when the job has already ended. */
    JobOutcome cancel(UUID jobId, String reason);

    /**
     * Records why a queued job has not been given a machine - plan item D1's "a stated reason".
     *
     * <p>It does not move the job: being unassignable is not a state, it is a fact about the farm
     * at this moment, and a farm that gains a capable printer a minute later should start the job
     * without anything having to undo a transition. So the reason lands on the job and the job
     * stays {@code QUEUED}.
     *
     * <p>Refused for a job that is not queued, because there is nothing to explain about one that
     * already has a machine.
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

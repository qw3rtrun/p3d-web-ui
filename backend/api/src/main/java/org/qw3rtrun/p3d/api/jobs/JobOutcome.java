package org.qw3rtrun.p3d.api.jobs;

import java.util.UUID;

/**
 * What a caller gets back from asking a job to move: the job, or a stated refusal.
 *
 * <p>A typed failure rather than an exception (plan item C2's first done-when): asking to pause a
 * job that already finished is an ordinary race, not a fault, and deserves an answer rather than
 * a stack trace or a web layer guessing a status from an exception's class.
 *
 * <p>{@link Refused} carries where the job actually is, what was asked, and why not - the REST
 * surface turns that into a 409 with the reason in the body.
 */
public sealed interface JobOutcome {

    /** The job moved, and here it is. */
    record Done(Job job) implements JobOutcome {
    }

    /**
     * The job did not move, and here is why.
     *
     * @param attempted what was asked of it, or null when the request made no sense at all
     * @param reason    a sentence a user could be shown
     */
    record Refused(UUID jobId, JobState state, JobState attempted, String reason) implements JobOutcome {
    }

    /** The job, or null when the move was refused. For a caller that only wants the happy path. */
    default Job jobOrNull() {
        return this instanceof Done done ? done.job() : null;
    }
}

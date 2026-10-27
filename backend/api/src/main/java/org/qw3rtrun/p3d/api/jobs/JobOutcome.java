package org.qw3rtrun.p3d.api.jobs;

import java.util.UUID;

/**
 * What a caller gets back from asking a job to move: the job, or a stated refusal.
 *
 * <p><strong>A typed failure and not an exception, which is plan item C2's first done-when.</strong>
 * Two kinds of thing go wrong here and they are not the same kind: asking to pause a job that has
 * already finished is an ordinary race - a user clicked while the last line was being
 * acknowledged - and it deserves an answer rather than a stack trace. Letting it become a database
 * constraint violation, or an {@code IllegalStateException} from somewhere deep, would mean the
 * web layer translating an exception back into "409, you cannot do that" by guessing at its type.
 *
 * <p>A {@link Refused} carries what the caller needs to say something useful: where the job
 * actually is, what was asked, and why not. The REST surface turns that into a 409 with the reason
 * in the body, and a page can re-read the job and show the truth.
 */
public sealed interface JobOutcome {

    /** The job moved, and here it is. */
    record Done(Job job) implements JobOutcome {
    }

    /**
     * The job did not move, and here is why.
     *
     * @param jobId     the job asked about
     * @param state     where it actually is
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

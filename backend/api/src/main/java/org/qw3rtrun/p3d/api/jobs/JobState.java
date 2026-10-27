package org.qw3rtrun.p3d.api.jobs;

import java.util.Set;

/**
 * Where a print job is, and the only place the legal moves are written down.
 *
 * <pre>
 *   QUEUED   --assigned to a machine--&gt;  ASSIGNED  --started--&gt;  RUNNING
 *   ASSIGNED --its machine went away--&gt;  QUEUED
 *   RUNNING  --paused--&gt;  PAUSED  --resumed--&gt;  RUNNING
 *   RUNNING  --finished--&gt;  COMPLETED
 *   RUNNING / PAUSED  --something broke--&gt;  FAILED
 *   anything not yet terminal  --operator cancelled--&gt;  CANCELLED
 * </pre>
 *
 * <p><strong>The table is on the enum rather than in the service, so there is one copy.</strong>
 * A web page deciding which buttons to show and a scheduler deciding what it may assign are asking
 * the same question as the service that enforces it, and the three answering it separately is how
 * a UI comes to offer an action the backend refuses.
 *
 * <p><strong>A terminal state is terminal.</strong> {@code COMPLETED}, {@code FAILED} and
 * {@code CANCELLED} have no outgoing edges at all - not even to each other, and not back to
 * {@code QUEUED}. Re-running a file means a new job, which is what keeps a job's history a fact
 * rather than a thing that can be overwritten: plan item C5's recovery records the last
 * acknowledged line on a failed job, and that record is worthless if the job can be revived in
 * place.
 */
public enum JobState {

    /** Waiting for a machine. Where every job starts. */
    QUEUED,

    /** A machine has been chosen, and has not started it yet. */
    ASSIGNED,

    /** Being printed right now. */
    RUNNING,

    /** Started and deliberately stopped, with the machine still holding it. */
    PAUSED,

    /** It printed to the end. */
    COMPLETED,

    /** Something went wrong - see the job's reason. */
    FAILED,

    /** Somebody stopped it on purpose. */
    CANCELLED;

    /** Whether this job is over. Nothing moves out of a terminal state, ever. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** Whether a machine is working on it, which is what makes it uninterruptible by a scheduler. */
    public boolean isActive() {
        return this == RUNNING || this == PAUSED;
    }

    /** Whether a machine is spoken for, which is what plan item D1 will ask before assigning. */
    public boolean holdsAMachine() {
        return this == ASSIGNED || isActive();
    }

    /** Whether {@code next} is a legal move from here. The whole of the state machine. */
    public boolean canMoveTo(JobState next) {
        return switch (this) {
            case QUEUED -> Set.of(ASSIGNED, CANCELLED, FAILED).contains(next);
            case ASSIGNED -> Set.of(RUNNING, QUEUED, CANCELLED, FAILED).contains(next);
            case RUNNING -> Set.of(PAUSED, COMPLETED, FAILED, CANCELLED).contains(next);
            case PAUSED -> Set.of(RUNNING, FAILED, CANCELLED).contains(next);
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}

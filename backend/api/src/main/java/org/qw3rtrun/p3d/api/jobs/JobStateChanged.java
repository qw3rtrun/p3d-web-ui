package org.qw3rtrun.p3d.api.jobs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A job moved - the past-tense fact every transition publishes.
 *
 * <p><strong>One event rather than seven.</strong> {@code JobQueuedEvent},
 * {@code JobStartedEvent} and the rest would each carry the same fields and force every listener to
 * subscribe to all of them to follow one job; a scheduler wants "this machine is free again",
 * which is {@code from.holdsAMachine() && !to.holdsAMachine()} and is unanswerable from any single
 * one of them. The same argument that made {@code ConnectionStateChanged} one event out of four.
 *
 * <p><strong>It goes through Modulith's event publication registry</strong>, which means it is
 * written down inside the transaction that moved the job and delivered at least once afterwards.
 * That matters because the listeners are going to be things like "start feeding this file to that
 * printer": an event lost between the commit and the listener is a job that is {@code RUNNING} in
 * the database and running nowhere else.
 *
 * @param jobId     which job
 * @param from      where it was, or null for the event that announces a job into existence -
 *                  a creation is not a transition, but a scheduler still wants to hear about it
 * @param to        where it is now
 * @param machineId the machine involved, or null when there is none
 * @param reason    why, when the move had a reason worth keeping
 * @param timestamp when it moved
 */
public record JobStateChanged(UUID jobId,
                              JobState from,
                              JobState to,
                              UUID machineId,
                              String reason,
                              LocalDateTime timestamp) {

    /** Whether this move handed a machine back, which is what a scheduler waits for. */
    public boolean releasedAMachine() {
        return from != null && from.holdsAMachine() && !to.holdsAMachine();
    }

    /** Whether this is a job appearing rather than moving. */
    public boolean isNew() {
        return from == null;
    }

    /** Whether the job is over, whichever way it ended. */
    public boolean finished() {
        return to.isTerminal();
    }
}

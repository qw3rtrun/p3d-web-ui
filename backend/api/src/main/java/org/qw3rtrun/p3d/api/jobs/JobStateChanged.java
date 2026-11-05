package org.qw3rtrun.p3d.api.jobs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A job moved - the past-tense fact every transition publishes.
 *
 * <p>One event rather than seven: per-transition events would force every listener to subscribe
 * to all of them to follow one job - a scheduler wanting "this machine is free again" needs
 * {@code from.holdsAMachine() && !to.holdsAMachine()}, unanswerable from any single one. Same
 * argument that made {@code ConnectionStateChanged} one event out of four.
 *
 * <p>Goes through Modulith's event publication registry: written inside the transaction that
 * moved the job, delivered at least once afterwards - needed because a listener like "start
 * feeding this file" losing the event would leave a job {@code RUNNING} in the database and
 * running nowhere else.
 *
 * @param fileId carried so a listener acting on the move does not have to query the queue back
 *               for it, which would turn an announcement into a round trip
 * @param from   null for the event announcing a job into existence - not a transition, but a
 *               scheduler still wants to hear about it
 */
public record JobStateChanged(UUID jobId,
                              UUID fileId,
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

package org.qw3rtrun.p3d.api.jobs;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qw3rtrun.p3d.api.jobs.JobState.ASSIGNED;
import static org.qw3rtrun.p3d.api.jobs.JobState.CANCELLED;
import static org.qw3rtrun.p3d.api.jobs.JobState.COMPLETED;
import static org.qw3rtrun.p3d.api.jobs.JobState.FAILED;
import static org.qw3rtrun.p3d.api.jobs.JobState.PAUSED;
import static org.qw3rtrun.p3d.api.jobs.JobState.QUEUED;
import static org.qw3rtrun.p3d.api.jobs.JobState.RUNNING;

/**
 * The state machine, enumerated rather than sampled.
 *
 * <p>Plan item C2 says getting the states and their legal transitions right before anything streams
 * is cheaper than discovering them afterwards, and the way to be sure is to assert the whole table
 * - every state against every state - rather than the handful of edges that happened to come to
 * mind. Forty-nine pairs, and the ones that must be refused are the half that matters.
 */
class JobStateTest {

    /** The entire transition table. Anything not listed here is refused. */
    private static Set<JobState> legalFrom(JobState state) {
        return switch (state) {
            case QUEUED -> Set.of(ASSIGNED, CANCELLED, FAILED);
            case ASSIGNED -> Set.of(RUNNING, QUEUED, CANCELLED, FAILED);
            case RUNNING -> Set.of(PAUSED, COMPLETED, FAILED, CANCELLED);
            case PAUSED -> Set.of(RUNNING, FAILED, CANCELLED);
            case COMPLETED, FAILED, CANCELLED -> Set.of();
        };
    }

    @Test
    void everyPairOfStatesIsEitherLegalOrRefused() {
        for (JobState from : JobState.values()) {
            for (JobState to : JobState.values()) {
                assertEquals(legalFrom(from).contains(to), from.canMoveTo(to),
                        from + " -> " + to);
            }
        }
    }

    /**
     * A terminal state has no way out, including to another terminal state.
     *
     * <p>Re-running a file is a new job. That is what keeps a finished job a record of what
     * happened: plan item C5 writes the last acknowledged line onto a failed job so that a better
     * recovery can be built later, and a job that could be revived in place would overwrite it.
     */
    @Test
    void nothingLeavesATerminalState() {
        for (JobState terminal : EnumSet.of(COMPLETED, FAILED, CANCELLED)) {
            assertTrue(terminal.isTerminal(), terminal + " should be terminal");
            for (JobState to : JobState.values()) {
                assertFalse(terminal.canMoveTo(to), terminal + " must not move to " + to);
            }
        }
    }

    @Test
    void noStateMovesToItself() {
        for (JobState state : JobState.values()) {
            assertFalse(state.canMoveTo(state), state + " -> " + state);
        }
    }

    /** A job on a machine is one the scheduler may not hand that machine out from under. */
    @Test
    void aJobHoldsAMachineFromTheMomentItIsAssignedUntilItEnds() {
        assertFalse(QUEUED.holdsAMachine());
        assertTrue(ASSIGNED.holdsAMachine());
        assertTrue(RUNNING.holdsAMachine());
        assertTrue(PAUSED.holdsAMachine(), "a paused job still owns the printer it is paused on");
        assertFalse(COMPLETED.holdsAMachine());
        assertFalse(CANCELLED.holdsAMachine());
        assertFalse(FAILED.holdsAMachine());
    }

    /** Active means a machine is working on it, which is narrower than holding one. */
    @Test
    void activeIsNarrowerThanHoldingAMachine() {
        assertFalse(ASSIGNED.isActive(), "chosen is not started");
        assertTrue(RUNNING.isActive());
        assertTrue(PAUSED.isActive());
    }

    /**
     * A job can only go back to the queue before it has started.
     *
     * <p>Once a machine has consumed part of the file, putting the job back in the queue would mean
     * a second machine starting it from line one while the first is still hot and half printed.
     * What happens to a running job whose machine went away is {@code FAILED} and C5's recovery.
     */
    @Test
    void onlyAnUnstartedJobCanGoBackToTheQueue() {
        assertTrue(ASSIGNED.canMoveTo(QUEUED));
        assertFalse(RUNNING.canMoveTo(QUEUED));
        assertFalse(PAUSED.canMoveTo(QUEUED));
    }

    /** A move that hands a machine back is what a scheduler is waiting for. */
    @Test
    void releasingAMachineIsVisibleOnTheEvent() {
        assertTrue(changed(RUNNING, COMPLETED).releasedAMachine());
        assertTrue(changed(ASSIGNED, QUEUED).releasedAMachine());
        assertFalse(changed(QUEUED, ASSIGNED).releasedAMachine());
        assertFalse(changed(RUNNING, PAUSED).releasedAMachine(),
                "a paused job has not given the printer back");
    }

    private static JobStateChanged changed(JobState from, JobState to) {
        return new JobStateChanged(null, null, from, to, null, null, null);
    }
}

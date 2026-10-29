package org.qw3rtrun.p3d.api.jobs;

import java.util.UUID;

/**
 * A running job got further - published often, persisted rarely.
 *
 * <p><strong>Two consumers with opposite appetites, which is why the rate is split.</strong> A page
 * watching a print wants this as often as it changes; the database wants it as seldom as possible,
 * because a write per block on a hundred-thousand-block job is a hundred thousand writes to answer
 * a question nobody asks between restarts. So {@code core} publishes on a timer and {@code jobs}
 * persists a checkpoint - the plan's "persisted often enough to survive a restart", with "often
 * enough" being the thing that is chosen rather than assumed.
 *
 * @param jobId     the job that moved
 * @param machineId the machine printing it
 * @param progress  where it has got to
 */
public record JobProgressed(UUID jobId, UUID machineId, JobProgress progress) {
}

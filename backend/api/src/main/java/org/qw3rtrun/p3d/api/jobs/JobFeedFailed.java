package org.qw3rtrun.p3d.api.jobs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A job's feed stopped before the file did, and here is as far as it got - the connection
 * dropped, the file could not be read, or the machine was never reachable. {@code jobs} turns
 * this into a {@code FAILED} job with the reason on it.
 *
 * <p>The reading rides on the event rather than being published separately just before it (#102) -
 * see {@link JobFeedCompleted} for why that order is load-bearing and what it used to cost.
 *
 * @param progress how far the feed got when it stopped. {@code progress.blocksSent()} is what C5
 *                 builds recovery on, recorded now because it cannot be recovered later: it is an
 *                 upper bound on what the printer executed, not the exact point - the honest
 *                 resume point is the last <em>acknowledged</em> line, which C5 reconciles against
 *                 this. Never null; {@link JobProgress#none()} where no feed ever ran.
 */
public record JobFeedFailed(UUID jobId, UUID machineId, JobProgress progress, String reason,
                            LocalDateTime timestamp) {
}

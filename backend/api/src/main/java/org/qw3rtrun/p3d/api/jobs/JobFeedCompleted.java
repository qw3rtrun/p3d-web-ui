package org.qw3rtrun.p3d.api.jobs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Every line of a job's file reached the wire - a fact only {@code core} can know, since knowing
 * it means holding the socket; only {@code jobs} may decide what it does to a job's state. Same
 * shape as {@code MFirmwareReportedEvent}: the module that learns something announces it.
 *
 * <p>"Reached the wire" is not "printed": the last blocks are still in the printer's planner when
 * this is published, which is why plan item C4 measures progress in acknowledgements rather than
 * in this.
 *
 * <p>The reading rides on the event rather than being published separately just before it (#102).
 * A feed is removed from the feeder the moment it ends, so no timer ever checkpoints that job
 * again - whatever is on the row when this is acted on is what the job keeps for ever, which is
 * why {@code jobs} writes it in the same transaction as the terminal transition. It was published
 * as its own {@code JobProgressed} immediately beforehand, inline on the reactor-netty thread the
 * feed ended on, which bought the order with a JDBC transaction on an event loop.
 *
 * @param progress how far the feed got, exactly - {@code progress.blocksSent()} is the number of
 *                 blocks sent after comments and blank lines were dropped, and is what C5 recovery
 *                 is built on. Never null; {@link JobProgress#none()} where no feed ever ran.
 */
public record JobFeedCompleted(UUID jobId, UUID machineId, JobProgress progress,
                               LocalDateTime timestamp) {
}

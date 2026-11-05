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
 * @param lines how many blocks were sent, after comments and blank lines were dropped
 */
public record JobFeedCompleted(UUID jobId, UUID machineId, long lines, LocalDateTime timestamp) {
}

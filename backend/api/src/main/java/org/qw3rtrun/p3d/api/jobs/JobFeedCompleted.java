package org.qw3rtrun.p3d.api.jobs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Every line of a job's file reached the wire - the fact {@code core} alone can know.
 *
 * <p><strong>It travels as an event rather than {@code core} calling {@code JobService.complete},
 * for the reason every other crossing in this application does.</strong> Only {@code core} can know
 * this, because knowing it means holding the socket; only {@code jobs} may decide what it does to a
 * job's state. The same shape as {@code MFirmwareReportedEvent}: the module that learns something
 * announces it, and the module that keeps it listens.
 *
 * <p><strong>"Reached the wire" is not "printed".</strong> The last blocks are in the printer's
 * planner when this is published, and on a long move that is seconds of motion still to come. A job
 * that is {@code COMPLETED} means the host has nothing left to send, which is the only thing the
 * host can honestly claim - and is why plan item C4 measures progress in acknowledgements rather
 * than in this.
 *
 * @param jobId     the job whose file is exhausted
 * @param machineId where it was printing
 * @param lines     how many blocks were sent, after comments and blank lines were dropped
 * @param timestamp when the last one went out
 */
public record JobFeedCompleted(UUID jobId, UUID machineId, long lines, LocalDateTime timestamp) {
}

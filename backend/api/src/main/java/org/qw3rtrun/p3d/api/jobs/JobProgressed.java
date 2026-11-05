package org.qw3rtrun.p3d.api.jobs;

import java.util.UUID;

/**
 * A running job got further - published often, persisted rarely.
 *
 * <p>Two consumers with opposite appetites: a watching page wants this as often as it changes,
 * while a write per block on a hundred-thousand-block job is more writes than the database needs
 * to survive a restart. {@code core} publishes on a timer; {@code jobs} persists a checkpoint.
 */
public record JobProgressed(UUID jobId, UUID machineId, JobProgress progress) {
}

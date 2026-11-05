package org.qw3rtrun.p3d.api.jobs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One print job: a file, a machine that may be printing it, and where it has got to.
 *
 * <p>{@code fileName} is denormalised on purpose, and safe because a stored file is immutable -
 * its name is fixed when first stored, so the copy can never go stale. That buys a job listing
 * that does not query the file store once per row.
 *
 * @param machineId  the machine working on it, or null while still queued
 * @param reason     why it failed or was cancelled, or null
 * @param startedAt  when a machine first began printing it, or null
 * @param finishedAt when it reached a terminal state, or null
 * @param progress   the last checkpoint, never null, approximate by design - see {@link JobProgress}
 * @param requires   never null - see {@link JobRequirements}
 */
public record Job(UUID id,
                  UUID fileId,
                  String fileName,
                  UUID machineId,
                  JobState state,
                  String reason,
                  LocalDateTime createdAt,
                  LocalDateTime startedAt,
                  LocalDateTime finishedAt,
                  JobProgress progress,
                  JobRequirements requires) {

    public Job {
        progress = progress == null ? JobProgress.none() : progress;
        requires = requires == null ? JobRequirements.any() : requires;
    }
}

package org.qw3rtrun.p3d.api.jobs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One print job: a file, a machine that may be printing it, and where it has got to.
 *
 * <p><strong>{@code fileName} is denormalised on purpose, and it is safe because a stored file is
 * immutable.</strong> A file's name is fixed when it is first stored - a re-upload of the same
 * bytes returns the existing record rather than renaming it - so the copy can never go stale. What
 * it buys is a job listing that does not query the file store once per row, which is the shape a
 * farm page actually asks for.
 *
 * @param id         the job's identity
 * @param fileId     what it prints, in the file store
 * @param fileName   what that file is called, as of when the job was created
 * @param machineId  the machine working on it, or null while it is still queued
 * @param state      where it is - see {@link JobState}
 * @param reason     why it failed or was cancelled, in whatever words the cause gave. Null otherwise
 * @param createdAt  when it was queued
 * @param startedAt  when a machine first began printing it, or null
 * @param finishedAt when it reached a terminal state, or null
 * @param progress   the last checkpoint, never null. Approximate by design - see {@link JobProgress}
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
                  JobProgress progress) {

    public Job {
        progress = progress == null ? JobProgress.none() : progress;
    }
}

package org.qw3rtrun.p3d.api.jobs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A job's feed stopped before the file did, and here is as far as it got.
 *
 * <p>The connection dropped, the file could not be read, or the machine was never reachable in the
 * first place. {@code jobs} turns this into a {@code FAILED} job with the reason on it.
 *
 * <p><strong>{@code lines} is what plan item C5 will build recovery on</strong>, and it is recorded
 * now because it costs nothing now and cannot be recovered later. It is how many blocks this host
 * put on the wire, which is an upper bound on what the printer executed rather than the exact
 * point - the honest resume point is the last <em>acknowledged</em> line, and C5 is where the two
 * get reconciled.
 *
 * @param jobId     the job that stopped
 * @param machineId where it was printing
 * @param lines     how many blocks had been sent when it stopped
 * @param reason    why, in whatever words the cause gave
 * @param timestamp when it stopped
 */
public record JobFeedFailed(UUID jobId, UUID machineId, long lines, String reason,
                            LocalDateTime timestamp) {
}

package org.qw3rtrun.p3d.api.jobs;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A job's feed stopped before the file did, and here is as far as it got - the connection
 * dropped, the file could not be read, or the machine was never reachable. {@code jobs} turns
 * this into a {@code FAILED} job with the reason on it.
 *
 * <p>{@code lines} is what C5 builds recovery on, recorded now because it cannot be recovered
 * later: it is an upper bound on what the printer executed, not the exact point - the honest
 * resume point is the last <em>acknowledged</em> line, which C5 reconciles against this.
 *
 * @param lines how many blocks had been sent when it stopped
 */
public record JobFeedFailed(UUID jobId, UUID machineId, long lines, String reason,
                            LocalDateTime timestamp) {
}

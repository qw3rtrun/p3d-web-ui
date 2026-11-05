package org.qw3rtrun.p3d.api.jobs;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * How far a print has got - plan item C4.
 *
 * <p>The fraction is bytes and the count is blocks, deliberately paired: bytes are exact and
 * free (the file's size is known up front), while blocks are what actually went to the machine
 * and cannot be counted in advance without tokenizing the whole file - a sliced file is up to a
 * tenth comments and blank lines, dropped rather than sent, so line count is an upper bound and
 * not a denominator.
 *
 * @param blocksSent blocks handed to the machine, monotone - see {@link #blocksSent()}
 * @param inFlight   of those, how many the firmware has not acknowledged yet
 * @param layer      the layer the file last said it was on, or null when it never says
 */
public record JobProgress(long blocksSent,
                          int inFlight,
                          long bytesSent,
                          long bytesTotal,
                          Integer layer,
                          LocalDateTime startedAt,
                          LocalDateTime at) {

    /** Nothing has happened yet. Not null, so a caller never has to check. */
    public static JobProgress none() {
        return new JobProgress(0, 0, 0, 0, null, null, null);
    }

    /**
     * How many blocks the firmware has taken - the honest number, not the headline one.
     *
     * <p>{@link #blocksSent} runs ahead by whatever is in the send window (at most four blocks,
     * since it will not pull another until one is acknowledged); on a failed job that gap is the
     * difference between what was sent and what was printed, which is why both are kept.
     */
    public long blocksAccepted() {
        return Math.max(0, blocksSent - inFlight);
    }

    /** Zero to one, by bytes. Zero when the size is not known yet. */
    public double fraction() {
        return bytesTotal <= 0 ? 0 : Math.min(1.0, (double) bytesSent / bytesTotal);
    }

    public Duration elapsed() {
        return startedAt == null || at == null ? Duration.ZERO : Duration.between(startedAt, at);
    }

    /**
     * How much longer, extrapolated from how long it has taken so far. Null before anything has
     * been sent and null at the end - a straight extrapolation, since a print does not proceed at
     * a constant rate (infill is faster than perimeters, the first layer slower than everything).
     * The slicer's own estimate is the better number for a total; this is the better one for "how
     * much is left".
     */
    public Duration remaining() {
        double done = fraction();
        if (done <= 0 || done >= 1 || startedAt == null || at == null) {
            return null;
        }
        long elapsedMillis = elapsed().toMillis();
        return Duration.ofMillis((long) (elapsedMillis / done - elapsedMillis));
    }
}

package org.qw3rtrun.p3d.api.jobs;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * How far a print has got - plan item C4.
 *
 * <p><strong>The fraction is bytes and the count is blocks, and that pairing is deliberate.</strong>
 * Bytes are exact and free: the file's size is known before anything starts, and the feed knows how
 * much of it it has consumed. Blocks are what actually went to the machine, and there is no cheap
 * way to know in advance how many a file holds - a tenth of a sliced file is comments and blank
 * lines, which are dropped rather than sent, so the file's line count is an upper bound and not a
 * denominator. Counting them up front would mean tokenizing the whole file at upload.
 *
 * @param blocksSent  blocks handed to the machine, which is monotone - see {@link #blocksSent()}
 * @param inFlight    of those, how many the firmware has not acknowledged yet
 * @param bytesSent   how much of the file has been read
 * @param bytesTotal  how large the file is
 * @param layer       the layer the file last said it was on, or null when it never says
 * @param startedAt   when the feed began
 * @param at          when this measurement was taken
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
     * How many blocks the firmware has taken, which is the honest number and not the headline one.
     *
     * <p>{@link #blocksSent} runs ahead of it by whatever is in the send window - at most four
     * blocks, because the window will not pull another until one is acknowledged. On a job of a
     * hundred thousand blocks that gap is invisible; on a job that <em>failed</em> it is the
     * difference between what was sent and what was printed, which is why both numbers are kept.
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
     * How much longer, extrapolated from how long it has taken so far.
     *
     * <p>Null before anything has been sent, and null at the end. <strong>It is a straight
     * extrapolation and says so</strong>: a print does not proceed at a constant rate - infill is
     * faster than perimeters and the first layer is slower than everything - so this is worth
     * showing and not worth believing to the minute. The slicer's own estimate, which is on the
     * file, is the better number for a total and a worse one for "how much is left".
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

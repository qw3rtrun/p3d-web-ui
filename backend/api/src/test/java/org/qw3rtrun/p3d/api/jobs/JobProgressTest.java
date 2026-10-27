package org.qw3rtrun.p3d.api.jobs;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a progress reading means - plan item C4.
 *
 * <p>The arithmetic is simple and the distinctions are not, which is what these pin: sent against
 * accepted, a fraction by bytes rather than by blocks, and an estimate that is honest about being
 * one.
 */
class JobProgressTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    private static JobProgress at(long blocks, int inFlight, long bytes, long total, long secondsIn) {
        return new JobProgress(blocks, inFlight, bytes, total, null, START, START.plusSeconds(secondsIn));
    }

    @Test
    void aJobThatHasNotStartedReportsNothingRatherThanNull() {
        var none = JobProgress.none();

        assertEquals(0, none.blocksSent());
        assertEquals(0, none.fraction());
        assertEquals(Duration.ZERO, none.elapsed());
        assertNull(none.remaining());
    }

    /**
     * Accepted is sent minus what is still in the send window.
     *
     * <p>The gap is at most four blocks, because the window will not pull another until one comes
     * back - but it is the gap that matters on a job that failed, where it is the difference
     * between what was sent and what was printed.
     */
    @Test
    void acceptedIsSentMinusWhatIsStillInFlight() {
        assertEquals(96148, at(96152, 4, 0, 0, 0).blocksAccepted());
        assertEquals(0, at(0, 0, 0, 0, 0).blocksAccepted());
    }

    /** And it never goes negative, however the two counts raced. */
    @Test
    void acceptedIsNeverNegative() {
        assertEquals(0, at(2, 4, 0, 0, 0).blocksAccepted());
    }

    /**
     * The fraction is bytes, not blocks.
     *
     * <p>Because the file's size is known before anything starts and its block count is not: a
     * tenth of a sliced file is comments and blank lines, which are dropped rather than sent, so
     * the line count is an upper bound and would leave a progress bar permanently short.
     */
    @Test
    void theFractionIsByBytes() {
        assertEquals(0.5, at(10, 0, 500, 1000, 0).fraction());
        assertEquals(1.0, at(10, 0, 1000, 1000, 0).fraction());
    }

    @Test
    void anUnknownSizeIsNoProgressRatherThanDivisionByZero() {
        assertEquals(0, at(10, 0, 100, 0, 0).fraction());
    }

    /** Reading past the end is not more than finished. */
    @Test
    void theFractionIsCappedAtOne() {
        assertEquals(1.0, at(10, 0, 1200, 1000, 0).fraction());
    }

    @Test
    void elapsedIsFromTheStartOfTheFeed() {
        assertEquals(Duration.ofSeconds(90), at(10, 0, 500, 1000, 90).elapsed());
    }

    /** Half done in ninety seconds is another ninety to go. */
    @Test
    void remainingIsExtrapolatedFromWhatIsDone() {
        assertEquals(Duration.ofSeconds(90), at(10, 0, 500, 1000, 90).remaining());
        assertEquals(Duration.ofSeconds(30), at(10, 0, 750, 1000, 90).remaining());
    }

    /** There is nothing to extrapolate from before the first byte, or after the last. */
    @Test
    void remainingIsUnknownAtBothEnds() {
        assertNull(at(0, 0, 0, 1000, 5).remaining());
        assertNull(at(10, 0, 1000, 1000, 90).remaining());
    }

    /**
     * Progress only moves forward, which is plan item C4's first done-when.
     *
     * <p>It holds because of where the numbers come from rather than because anything clamps them:
     * bytes are counted as the file is read and a file is read once, and blocks are counted as the
     * job's source emits them, which a resend does not re-enter.
     */
    @Test
    void progressIsMonotoneAcrossASequenceOfReadings() {
        long lastBytes = -1;
        long lastBlocks = -1;
        for (int i = 0; i <= 100; i++) {
            var reading = at(i * 10L, 4, i * 100L, 10_000, i);
            assertTrue(reading.bytesSent() >= lastBytes, "bytes went backwards at " + i);
            assertTrue(reading.blocksSent() >= lastBlocks, "blocks went backwards at " + i);
            lastBytes = reading.bytesSent();
            lastBlocks = reading.blocksSent();
        }
    }
}

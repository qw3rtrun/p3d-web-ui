package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The counter the send window is built out of.
 *
 * <p>The arithmetic tests pin the two rules {@code canProcess()} depends on - unbounded is
 * absorbing, and the count never goes negative. {@link #everyUpdateSurvivesConcurrentIncAndDec()}
 * is the one that is about the fix: it is a stress test and not a forced interleaving, because the
 * defect it covers is a lost read-modify-write on a plain {@code long} and there is no way to
 * schedule two threads onto the same {@code n += i} on demand. What it does instead is make the
 * race enormously likely - two threads, a hundred thousand updates each, started together on a
 * barrier - and assert a total that only holds if no update was lost.
 */
class RequestedTest {

    private static final int THREADS = 4;
    private static final int UPDATES = 100_000;

    /** Well clear of zero, so {@code dec}'s clamp never fires and the total is exact. */
    private static final long FLOOR = 10_000_000L;

    private final Requested requested = new Requested();

    @Test
    void startsAtZero() {
        assertEquals(0, requested.get());
    }

    @Test
    void decStopsAtZeroRatherThanGoingNegative() {
        requested.inc(2);

        requested.dec(5);

        assertEquals(0, requested.get());
    }

    /**
     * Reactive Streams rule 3.17: {@link Long#MAX_VALUE} is "unbounded", and once a counter is
     * there nothing moves it again. An overflowing {@code inc} lands on it rather than wrapping
     * negative - a negative demand count would read as "no room" and stall the window for good.
     */
    @Test
    void unboundedIsAbsorbing() {
        requested.inc(Long.MAX_VALUE);

        requested.inc(5);
        requested.dec(5);

        assertEquals(Long.MAX_VALUE, requested.get());
    }

    @Test
    void anOverflowingIncSaturatesAtUnbounded() {
        requested.inc(Long.MAX_VALUE - 1);

        requested.inc(100);

        assertEquals(Long.MAX_VALUE, requested.get());
    }

    @Test
    void resetTakesTheWholeCountInOneStep() {
        requested.inc(7);

        assertEquals(7, requested.reset());
        assertEquals(0, requested.get());
    }

    /**
     * Equal numbers of increments and decrements from eight threads leave the count where it
     * started - which is only true if every single one of them was applied.
     *
     * <p>On the {@code long n; n += i;} version this fails essentially every run: each lost update
     * is a permanent skew in {@code blockQueue - sentWithoutAck - requestedFromGCode}, too low and
     * the window stalls forever, too high and it overruns the printer's buffer.
     */
    @Test
    void everyUpdateSurvivesConcurrentIncAndDec() throws Exception {
        requested.inc(FLOOR);
        var barrier = new CyclicBarrier(THREADS * 2);
        List<Thread> threads = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            threads.add(Thread.ofPlatform().unstarted(hammer(barrier, requested::inc)));
            threads.add(Thread.ofPlatform().unstarted(hammer(barrier, requested::dec)));
        }
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(FLOOR, requested.get());
    }

    private static Runnable hammer(CyclicBarrier barrier, Runnable update) {
        return () -> {
            try {
                barrier.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            for (int i = 0; i < UPDATES; i++) {
                update.run();
            }
        };
    }
}

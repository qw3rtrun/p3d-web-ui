package org.qw3rtrun.p3d.terminal;

import reactor.core.publisher.Operators;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A non-negative, saturating counter for outstanding work shared across this module: demand
 * requested, demand passed upstream, commands in flight.
 *
 * <p>Every operation is atomic ({@link AtomicLong} + CAS), not a plain read-modify-written
 * {@code long}: {@code request}, {@code onNext}, {@code handleBackpressure} and {@code cancel} run
 * on at least two threads (the producer and the netty inbound loop), and a lost update here would
 * silently narrow or widen the send window ({@code blockQueue - sentWithoutAck -
 * requestedFromGCode}) - Marlin drops an overrun with no exception to catch.
 *
 * <p>Two invariants the send window depends on:
 * <ul>
 *   <li>{@link Long#MAX_VALUE} is unbounded and absorbing, per Reactive Streams rule 3.17;
 *       {@link Operators#addCap} saturates an overflowing {@code inc} there instead of wrapping
 *       to negative.
 *   <li>It never goes below zero - {@code dec} clamps, because more acknowledgements than sends is
 *       a real race (a {@code busy:} zeroes in-flight while an earlier {@code ok} is still on the
 *       wire), and a negative count would widen the window past the printer's buffer.
 * </ul>
 */
public class Requested {

    private final AtomicLong n = new AtomicLong();

    /**
     * Adds {@code i}, saturating at {@link Long#MAX_VALUE} - a CAS loop, not {@code addAndGet},
     * because the saturating add is a conditional update on the old value.
     */
    public void inc(long i) {
        for (; ; ) {
            long current = n.get();
            if (current == Long.MAX_VALUE) {
                return;
            }
            if (n.compareAndSet(current, Operators.addCap(current, i))) {
                return;
            }
        }
    }

    public void inc() {
        inc(1);
    }

    /** Subtracts {@code i}, clamping at zero and leaving an unbounded counter alone. */
    public void dec(long i) {
        for (; ; ) {
            long current = n.get();
            if (current == Long.MAX_VALUE) {
                return;
            }
            if (n.compareAndSet(current, Math.max(0, current - i))) {
                return;
            }
        }
    }

    public void dec() {
        dec(1);
    }

    public long get() {
        return n.get();
    }

    /** Takes the whole count and leaves zero behind, in one step. */
    public long reset() {
        return n.getAndSet(0);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Requested other && get() == other.get();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(get());
    }

    @Override
    public String toString() {
        long current = get();
        return current == Long.MAX_VALUE ? "unbounded" : String.valueOf(current);
    }
}

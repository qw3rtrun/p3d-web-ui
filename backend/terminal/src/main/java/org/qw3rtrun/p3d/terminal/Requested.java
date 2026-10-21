package org.qw3rtrun.p3d.terminal;

import reactor.core.publisher.Operators;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A non-negative, saturating counter shared by everything in this module that keeps a count of
 * outstanding work: demand asked for, demand passed upstream, and commands in flight.
 *
 * <p><strong>Every operation is atomic.</strong> This used to be a plain {@code long} with no
 * synchronisation, read-modify-written from {@code request}, {@code onNext},
 * {@code handleBackpressure} and {@code cancel} - four methods that in a live terminal run on at
 * least two threads, the producer and the netty inbound loop. A lost update there is not a
 * cosmetic slip: the send window is {@code blockQueue - sentWithoutAck - requestedFromGCode}, so a
 * dropped decrement closes the window by one for the rest of the session and a dropped increment
 * opens it by one past what the printer can hold. Marlin answers an overrun by silently dropping
 * commands, and there is no exception to catch.
 *
 * <p>Two rules it keeps from the version it replaces, both of which the send window depends on:
 *
 * <ul>
 *   <li><strong>{@link Long#MAX_VALUE} is unbounded and is absorbing.</strong> Reactive Streams
 *       rule 3.17 lets a subscriber ask for an effectively infinite amount; once a counter reaches
 *       it, no further {@code inc} or {@code dec} moves it. {@link Operators#addCap} is the
 *       saturating add Reactor uses for the same purpose, so an overflowing {@code inc} now lands
 *       on "unbounded" rather than wrapping to a negative count.
 *   <li><strong>It never goes below zero.</strong> {@code dec} clamps. More acknowledgements than
 *       sends is the shape of a race the transport cannot rule out - a {@code busy:} zeroes the
 *       in-flight count while an {@code ok} for a command sent before the stall is still on the
 *       wire - and a negative count would widen the window past the printer's buffer, which is the
 *       failure that has no symptom until a command goes missing.
 * </ul>
 */
public class Requested {

    private final AtomicLong n = new AtomicLong();

    /**
     * Adds {@code i}, saturating at {@link Long#MAX_VALUE}.
     *
     * <p>A compare-and-set loop and not {@code addAndGet}, because the "unbounded is absorbing"
     * rule makes this a conditional update: the new value depends on the old one in a way an
     * atomic add cannot express.
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

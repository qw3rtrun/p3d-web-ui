package org.qw3rtrun.p3d.core;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Hands items to one consumer, one thread at a time, without blocking any thread that offers one.
 *
 * <p>Exists because a safe {@code Sinks.Many} does not serialise for its caller, it refuses: two
 * threads emitting at once leaves one holding {@code FAIL_NON_SERIALIZED} and an event nobody will
 * see - measured at 26,640 of 40,000 emissions refused with two threads on one safe sink, a question
 * of load rather than correctness, reproducing on a loaded CI runner and (#55) on a laptop running
 * one simulator for two minutes. A thread that finds another already draining parks its item and
 * leaves; the thread inside picks it up on its next pass, so the sink never sees two threads and
 * {@code FAIL_NON_SERIALIZED} becomes unreachable - a failure that still surfaces means a terminated
 * or cancelled stream, which says something real.
 *
 * <p>Not {@code EmitFailureHandler.busyLooping}, which spins on whichever thread emitted (as readily
 * a netty event loop) and throws once the spin runs out, skipping every statement after the
 * emission; a {@link ConcurrentLinkedQueue} add throws nothing, blocks nothing, spins nothing.
 *
 * <p>The consumer is called by exactly one thread at a time, so it may keep plain fields -
 * {@code PrinterReactor} relies on this for its {@code streamEnded} flag, the {@link AtomicInteger}
 * hand-off supplying the happens-before a volatile would otherwise need. It must not assume it runs
 * on the offering thread. Ordering holds only between offers that do not overlap - two racing
 * {@link #emit} calls order by which reaches the queue first - which is why the durable half of a
 * caller like {@code PrinterReactor.onEvent} must run before the volatile half: the drain loop can
 * hand a later event to a subscriber before the earlier one's own durable half has finished.
 *
 * <p>A third copy of this loop, {@code HostTerminal.drainBus} in {@code :backend:terminal}, is left
 * where it is: that module may not depend on this one, and sharing a home for twenty lines is a
 * module-boundary decision for whoever owns the transport, not a side effect of fixing a farm stream.
 *
 * @param <T> what is parked - a message, or a caller's marker for the end of its stream
 */
final class Outbox<T> {

    /** Items decided on but not yet handed to {@link #publish}, oldest first. */
    private final Queue<T> parked = new ConcurrentLinkedQueue<>();

    /**
     * The emission guard: zero when no thread is in {@link #drain()}, otherwise the number of calls
     * still to be accounted for.
     */
    private final AtomicInteger emitting = new AtomicInteger();

    private final Consumer<T> publish;

    Outbox(Consumer<T> publish) {
        this.publish = publish;
    }

    /** Parks one item, and publishes what is parked if no other thread is already doing it. */
    void emit(T item) {
        parked.add(item);
        drain();
    }

    /**
     * Publishes what is parked, one thread at a time.
     *
     * <p>The first caller in runs the loop; a caller that arrives while it is running leaves a mark
     * and returns with its item safely queued. The same shape the transport's send window and
     * {@code PublisherQueue} use.
     */
    private void drain() {
        if (emitting.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        for (; ; ) {
            for (T item; (item = parked.poll()) != null; ) {
                publish.accept(item);
            }
            missed = emitting.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }
}

package org.qw3rtrun.p3d.core;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Hands items to one consumer, one thread at a time, without blocking any of the threads that
 * offer them.
 *
 * <p><strong>What it exists for: a safe {@code Sinks.Many} does not serialise for its caller, it
 * refuses.</strong> Two threads emitting at once leaves one of them holding a
 * {@code FAIL_NON_SERIALIZED} and an event nobody will ever see - measured at <em>26,640 of 40,000
 * emissions refused</em> with two threads on one safe sink, which makes a collision a question of
 * load rather than of correctness: it reproduces on a loaded CI runner and, as plan item #55 found,
 * on a laptop running one simulator for two minutes.
 *
 * <p>A thread that finds another one already draining parks its item here and leaves; the thread
 * inside goes round again and picks it up. So the sink never sees two threads at all and
 * {@code FAIL_NON_SERIALIZED} becomes unreachable - a failure that still reaches a caller's log
 * means a terminated or cancelled stream, which says something real.
 *
 * <p><strong>Not {@code EmitFailureHandler.busyLooping}, which is the wrong remedy twice over.</strong>
 * It spins on whichever thread emitted - as readily a netty event loop, which is this layer's
 * cardinal rule broken outright - and when the spin runs out it <em>throws</em>, skipping every
 * statement after the emission. Adding to a {@link ConcurrentLinkedQueue} throws nothing, blocks
 * nothing and spins nothing.
 *
 * <p><strong>The consumer is called by exactly one thread at a time, so it may keep plain
 * fields.</strong> {@code PrinterReactor} relies on that for its {@code streamEnded} flag: the
 * {@link AtomicInteger} hand-off from one draining thread to the next carries the happens-before a
 * volatile would otherwise be needed for. What the consumer must <em>not</em> do is assume it runs
 * on the thread that offered the item - a message parked by one thread is published by another.
 *
 * <p><strong>Ordering is preserved between two offers that do not overlap, and only then.</strong>
 * Two threads racing into {@link #emit} order themselves by which reaches the queue first, which is
 * the most any lock-free structure can say. It is also why the volatile work goes last in a caller
 * like {@code PrinterReactor.onEvent}: the drain loop can hand a later event to a subscriber before
 * the thread carrying the earlier one has finished its own durable half.
 *
 * <p>The third copy of this loop is {@code HostTerminal.drainBus} in {@code :backend:terminal},
 * which is deliberately left where it is: that module may not depend on this one, the only home
 * both could see is the transport itself, and a shared home for twenty lines is a module-boundary
 * decision for whoever owns the transport to make rather than a side effect of fixing a farm
 * stream.
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

package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Scheduler;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Hands items to one consumer, on a thread that is not the one which produced them.
 *
 * <p>Exists because the consumer blocks: {@code PrinterReactor.onEvent} used to call
 * {@code ApplicationEventPublisher.publishEvent} straight, whose listeners are
 * {@code @Transactional} JPA writes - so a database round trip happened on the thread carrying a
 * printer's decoded replies, stalling that machine's own reads for as long as the write took.
 * Parking here costs an {@code offer} and an atomic increment; the publish runs on somebody else's
 * thread.
 *
 * <p>The same loop as {@link Outbox}, with the drain moved onto a scheduled worker instead of run
 * inline: {@link Outbox}'s consumer is a {@code Sinks.Many} that cannot block, so the offering
 * thread is already the right one to emit on; here the consumer is exactly the thing being got off
 * the caller's thread, so the drain has to be scheduled elsewhere. Two small classes rather than one
 * with a flag, because the flag would cost the {@code Sinks} caller a thread hop for nothing.
 *
 * <p>Items offered by one thread reach {@code publish} in that thread's order, and {@code publish}
 * runs on one thread at a time, so a consumer may keep plain fields the way
 * {@code PrinterReactor.publish} keeps {@code streamEnded}. There is no ordering between two
 * {@code Handoff} instances - two printers are two queues and two drains - which is deliberate:
 * enforcing one would need one queue and one thread for the whole farm, the arrangement #54 exists
 * to take apart. A consumer must not assume it runs on the offering thread; it never does except
 * under {@code Schedulers.immediate()}.
 *
 * <p>Two flavours, because the two streams this feeds fail differently: {@link #lossless} is for
 * facts the roster persists (a firmware report, a capability, a connection transition) - each one a
 * state <em>change</em>, rare enough that an unbounded queue cannot realistically grow, and a lost
 * one is a column wrong for good. {@link #droppable} is for the farm's live stream at a message or
 * two a second per machine, where a missed one is just a refresh: bounded, and a full queue costs
 * only the overflowing item.
 *
 * @param <T> what is parked - whatever the consumer was built to publish
 * @see Outbox
 */
@Slf4j
final class Handoff<T> {

    /** How many drops go by between two WARN lines, after the first one. */
    private static final long DROPS_PER_WARNING = 256;

    private final String what;

    private final Queue<T> parked;

    private final Scheduler on;

    private final Consumer<T> publish;

    /** Zero when no thread is in or scheduled for {@link #drain()}, otherwise the number of {@link #emit} calls still to be accounted for. */
    private final AtomicInteger wip = new AtomicInteger();

    private final AtomicLong dropped = new AtomicLong();

    private volatile boolean closed;

    private Handoff(String what, Queue<T> parked, Scheduler on, Consumer<T> publish) {
        this.what = what;
        this.parked = parked;
        this.on = on;
        this.publish = publish;
    }

    static <T> Handoff<T> lossless(String what, Scheduler on, Consumer<T> publish) {
        return new Handoff<>(what, new ConcurrentLinkedQueue<>(), on, publish);
    }

    /**
     * @param capacity how many items may wait; beyond it an item is dropped and counted. Uses
     *                 {@code offer}, never {@code put} - blocking the offering thread until there is
     *                 room would reinstate the exact stall this class removes.
     */
    static <T> Handoff<T> droppable(String what, int capacity, Scheduler on, Consumer<T> publish) {
        return new Handoff<>(what, new ArrayBlockingQueue<>(capacity), on, publish);
    }

    /** Safe from any thread; never blocks, spins or throws - those three are the contract, since this runs on a printer's reply thread. */
    void emit(T item) {
        if (item == null) {
            // not defensive habit: ArrayBlockingQueue.offer(null) throws, and that would be the
            // propagating exception this class exists to stop.
            log.warn("{} was offered nothing at all, which is a caller's bug", what);
            return;
        }
        if (closed) {
            log.debug("{} is closed, so {} goes nowhere", what, item);
            return;
        }
        if (!parked.offer(item)) {
            drop(item);
            return;
        }
        if (wip.getAndIncrement() == 0) {
            schedule();
        }
    }

    long dropped() {
        return dropped.get();
    }

    /**
     * Stops accepting items. Idempotent, safe from any thread, and deliberately does not clear what
     * is already parked: the caller ends a printer by scheduling the scheduler's own disposal as a
     * task on that same scheduler, so everything parked ahead of it - the final connection
     * transition above all - drains first.
     */
    void close() {
        closed = true;
    }

    /**
     * Schedules a drain. {@code schedule} throws {@link RejectedExecutionException} once the
     * scheduler is disposed - ordinary when a reply is still in flight as a printer is deleted - and
     * the caller here is the only thread that will act on the 0-to-1 transition of {@link #wip}
     * (every other emitter sees it non-zero and trusts someone is draining). So a rejection must
     * reset the guard to 0, or the queue wedges for good: every later {@link #emit} parks silently
     * with no drain ever scheduled again, which looks like a hang with no error anywhere.
     */
    private void schedule() {
        try {
            on.schedule(this::drain);
        } catch (RejectedExecutionException gone) {
            closed = true;
            wip.set(0);
            log.debug("{} has no worker left, so what is parked stays parked ({})", what, gone.toString());
        }
    }

    /**
     * Publishes what is parked, one thread at a time - {@link Outbox}'s loop, shape for shape. A
     * consumer that throws costs one item and nothing else: before this it propagated out of
     * {@code PrinterReactor.onEvent} and terminated that session's whole reply stream over one bad
     * row. Letting it escape here instead would abandon the rest of the queue and leave {@link #wip}
     * non-zero - the same wedge {@link #schedule()} guards against.
     */
    private void drain() {
        int missed = 1;
        for (; ; ) {
            for (T item; (item = parked.poll()) != null; ) {
                try {
                    publish.accept(item);
                } catch (Throwable failure) {
                    log.warn("{} could not publish {}", what, item, failure);
                }
            }
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                return;
            }
        }
    }

    /**
     * WARN on the first drop, then one in {@value #DROPS_PER_WARNING}, each with the running total -
     * a silent drop would hide a backlog and a line per drop would write it into the log at the rate
     * it is arriving. {@link #dropped()} is the exact count for anyone asking.
     */
    private void drop(T item) {
        long total = dropped.incrementAndGet();
        if (total == 1 || total % DROPS_PER_WARNING == 0) {
            log.warn("{} is full - {} dropped, {} in total so far", what, item, total);
        }
    }
}

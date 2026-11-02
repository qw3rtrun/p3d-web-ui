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
 * Hands items to one consumer on a thread that is not the one which produced them.
 *
 * <p><strong>Why it exists: the consumer blocks.</strong> {@code PrinterReactor.onEvent} used to
 * call {@code ApplicationEventPublisher.publishEvent} straight, and {@code publishEvent} is
 * synchronous - the listeners on the other end of it are {@code @Transactional} JPA writes. So a
 * database round trip, or a lock wait behind one, happened on the thread that carries a printer's
 * decoded replies: the machine stopped being read from while a row was being written. One
 * printer's slow write was every printer's stall for as long as the reply thread was shared, and
 * after #54 it would still be that printer's own stall, which is just as wrong and only smaller.
 * Parking the item here costs an {@code offer} and an atomic increment, and the publish happens on
 * somebody else's thread.
 *
 * <p><strong>Not a new mechanism: this is {@link Outbox}'s loop with the drain moved onto a
 * worker.</strong> Park into a queue, and let the thread that observes the work-in-progress guard
 * at zero own the draining; everyone else has already handed its item over and leaves. The one
 * difference is where that draining happens - {@link Outbox} runs it inline on the offering thread,
 * which is correct where it is used, because its consumer is a {@code Sinks.Many} that cannot block
 * and the offering thread is already the right thread to be emitting on. Here the consumer is the
 * thing being got off the caller's thread, so the drain is scheduled instead of run. That is the
 * whole of the difference, and it is why there are two small classes rather than one with a flag:
 * the flag would offer "drain somewhere else" to the {@code Sinks} caller, for whom it is a thread
 * hop bought for nothing, and "drain here" to this one, which is the bug.
 *
 * <p><strong>What a caller may rely on.</strong> Items offered by one thread reach {@code publish}
 * in the order that thread offered them, and {@code publish} is called by one thread at a time, so
 * a consumer may keep plain fields the way {@code PrinterReactor.publish} keeps its
 * {@code streamEnded} - the atomic hand-off from one draining thread to the next carries the
 * happens-before. Two threads racing into {@link #emit} order themselves by which reaches the queue
 * first, which is the most any lock-free structure can say; {@link Outbox}'s javadoc says exactly
 * that and this one does not claim more.
 *
 * <p><strong>What it is not.</strong> There is no ordering between two {@code Handoff} instances,
 * which is to say between two printers: machine A's event and machine B's event are two queues and
 * two drains, and nothing relates them. That is deliberate. Enforcing an order across printers
 * means one queue drained by one thread, which is the JVM-wide {@code Schedulers.single()} that
 * #54 exists to take apart. Something that needs a total order needs a timestamp, not a stronger
 * hand-off. The consumer must also not assume it runs on the thread that offered the item - it
 * never does, except under {@code Schedulers.immediate()}.
 *
 * <p><strong>Two flavours, because the two streams fail differently.</strong> {@link #lossless} is
 * for facts the roster persists - a firmware report, a capability line, a connection transition.
 * Each one is a state <em>change</em>, so a lost one is a column that disagrees with the machine
 * for good, and they arrive rarely enough that an unbounded queue cannot realistically grow.
 * {@link #droppable} is for the farm's live stream at a message or two a second per machine, where
 * a browser that missed one is a refresh: it is bounded, and a full queue costs the overflowing
 * item and nothing else.
 *
 * @param <T> what is parked - whatever the consumer was built to publish
 * @see Outbox
 */
@Slf4j
final class Handoff<T> {

    /** How many drops go by between two WARN lines, after the first one. */
    private static final long DROPS_PER_WARNING = 256;

    /** A human phrase naming this queue in the log, e.g. "the farm stream of 7f3a...". */
    private final String what;

    /** Items decided on but not yet handed to {@link #publish}, oldest first. */
    private final Queue<T> parked;

    /** Where {@link #drain()} runs. One thread per printer, and never the offering one. */
    private final Scheduler on;

    private final Consumer<T> publish;

    /**
     * The drain guard: zero when no thread is in or scheduled for {@link #drain()}, otherwise the
     * number of {@link #emit} calls still to be accounted for.
     */
    private final AtomicInteger wip = new AtomicInteger();

    /** Items a full queue refused, for the lifetime of this hand-off. */
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Set once this hand-off is finished with, and read by every {@link #emit}.
     *
     * <p>Plain {@code volatile} and not an atomic: nothing here decides anything on the strength of
     * having been the thread that set it.
     */
    private volatile boolean closed;

    private Handoff(String what, Queue<T> parked, Scheduler on, Consumer<T> publish) {
        this.what = what;
        this.parked = parked;
        this.on = on;
        this.publish = publish;
    }

    /**
     * A hand-off that never drops, parking into an unbounded queue.
     *
     * <p>For what the roster writes down. A dropped connection transition is a database row saying
     * the machine is online after it went offline, and nothing later comes along to correct it; the
     * events are rare - a reconnect, an {@code M115}, a capability line - so the queue has nothing
     * to grow on.
     */
    static <T> Handoff<T> lossless(String what, Scheduler on, Consumer<T> publish) {
        return new Handoff<>(what, new ConcurrentLinkedQueue<>(), on, publish);
    }

    /**
     * A hand-off that drops what it has no room for, and counts it.
     *
     * <p>For the live stream a browser is watching. {@link ArrayBlockingQueue} and {@code offer} -
     * never {@code put}, which <em>blocks the offering thread until there is room</em> and so would
     * reinstate on a slow consumer the very stall this class removes, in the worst possible place:
     * the queue is only ever full when the consumer is already behind.
     *
     * @param capacity how many items may wait; beyond it an item is dropped and counted
     */
    static <T> Handoff<T> droppable(String what, int capacity, Scheduler on, Consumer<T> publish) {
        return new Handoff<>(what, new ArrayBlockingQueue<>(capacity), on, publish);
    }

    /**
     * Parks one item, and schedules the drain if nobody else already has.
     *
     * <p>Safe from any thread, and it neither blocks, spins nor throws - those three are the
     * contract, because this is called from {@code PrinterReactor.onEvent}, which runs on the
     * thread carrying a printer's replies, and anything that escapes from there terminates that
     * session's reply stream.
     */
    void emit(T item) {
        if (item == null) {
            // Not a defensive habit: ArrayBlockingQueue.offer(null) throws, and a
            // NullPointerException out of this method is the propagating throw the class exists to
            // stop. Saying so in the log is the lesser evil over killing a reply stream for it.
            log.warn("{} was offered nothing at all, which is a caller's bug", what);
            return;
        }
        if (closed) {
            log.debug("{} is closed, so {} goes nowhere", what, item);
            return;
        }
        if (!parked.offer(item)) {
            drop(item);
            // Nothing was parked, so nothing new needs draining - and a full queue means a drain is
            // already in flight by construction: a parked item is always in front of either a
            // running loop or a scheduled one.
            return;
        }
        if (wip.getAndIncrement() == 0) {
            schedule();
        }
    }

    /** How many items a full queue has refused since this hand-off was made. */
    long dropped() {
        return dropped.get();
    }

    /**
     * Stops accepting items. Idempotent, and safe from any thread.
     *
     * <p><strong>It deliberately does not clear what is already parked, and nobody should "fix"
     * that.</strong> The caller ends a printer by scheduling the scheduler's own disposal <em>as a
     * task on that same single-threaded scheduler</em>, so everything queued ahead of it drains
     * first and the disposal is the last thing that thread does. Clearing here would throw away
     * exactly the tail of a machine's life - the final connection transition above all - which is
     * the half this hand-off is lossless for.
     */
    void close() {
        closed = true;
    }

    /**
     * Asks the worker to drain, and copes with the worker having gone.
     *
     * <p><strong>The trap, and the one non-obvious thing about moving a work-in-progress guard
     * behind a scheduler.</strong> {@code schedule} throws {@link RejectedExecutionException} once
     * the scheduler is disposed, and a reply still in flight when a printer was deleted is the
     * ordinary way to get there. The caller of this method has already taken {@code wip} from 0 to
     * 1 and is the only thread that will ever act on that transition - every other emitter sees a
     * non-zero guard and leaves, trusting that somebody is draining. So an exception that leaves
     * {@code wip} at 1 wedges the queue for good: from then on every {@link #emit} parks in silence
     * and no drain is ever scheduled again, which looks exactly like a hang with no error anywhere.
     * Resetting the guard is what makes a rejection cost the items in the queue rather than the
     * hand-off itself.
     */
    private void schedule() {
        try {
            on.schedule(this::drain);
        } catch (RejectedExecutionException gone) {
            // Closed first, so a thread arriving behind this one stops parking into a queue nothing
            // will drain; the guard second, so the invariant above is restored either way.
            closed = true;
            wip.set(0);
            log.debug("{} has no worker left, so what is parked stays parked ({})", what, gone.toString());
        }
    }

    /**
     * Publishes what is parked, one thread at a time. {@link Outbox}'s loop, shape for shape.
     *
     * <p><strong>A consumer that throws costs one item and nothing else.</strong> That is what the
     * catch is for, and it is a behaviour change worth naming: a {@code @Transactional} listener
     * that failed used to propagate out of {@code publishEvent}, out of
     * {@code PrinterReactor.onEvent}, out of {@code gEvent}'s {@code doOnNext} and <em>terminate
     * that session's reply stream</em> - one bad row and the printer stopped being read. Now that
     * the publish happens on a drain there is no caller left to carry a failure back to, and the
     * only tolerable answer is to log it and take the next item. Letting it out of here instead
     * would abandon the rest of the queue and leave {@code wip} non-zero, which is the wedge
     * {@link #schedule()} describes.
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
     * Counts a dropped item, and says so without becoming the problem itself.
     *
     * <p>A silent drop is worse than the stall it replaces - the stream would simply be missing
     * things and nothing would say so. A line per drop is no better: an overflow is sustained by
     * definition, so logging each one writes the slow consumer's backlog into the log file at the
     * rate it is arriving. So: WARN on the first, then one in {@value #DROPS_PER_WARNING}, each
     * carrying the running total, which is the number that says whether this was a blip or a
     * consumer that has stopped keeping up. {@link #dropped()} is the exact count for anyone
     * asking.
     */
    private void drop(T item) {
        long total = dropped.incrementAndGet();
        if (total == 1 || total % DROPS_PER_WARNING == 0) {
            log.warn("{} is full - {} dropped, {} in total so far", what, item, total);
        }
    }
}

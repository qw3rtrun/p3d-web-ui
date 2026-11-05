package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link Handoff} promises a caller, measured rather than read off the code - the per-printer
 * hand-off #54 puts between a decoded reply and the {@code @Transactional} listeners that persist
 * what it meant. Everything here is about a thread: which one runs the consumer, which one is
 * charged, and what survives a consumer slower than the machine. Every test holds the consumer on a
 * latch rather than hoping for a schedule, with a bound on every wait, since a property of this kind
 * fails by hanging. Blocking inside the consumer, which production may never do, is the only way to
 * make "the drain has not advanced" a fact rather than a coincidence; where two threads must meet,
 * the meeting is forced on a gate, since a race merely hoped for is one not tested. No property is
 * bought with a sleep; the one place that parks at all explains why.
 */
@Timeout(30)
class HandoffTest {

    /** Long enough that a broken property fails rather than times out, short enough to notice. */
    private static final long PATIENCE_SECONDS = 10;

    // the worker a production hand-off would be given: one thread, this printer's own.
    private Scheduler worker;

    // items the consumer saw, in the order it saw them.
    private final List<Integer> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startTheWorker() {
        worker = Schedulers.newSingle("handoff-test");
    }

    @AfterEach
    void stopTheWorker() {
        worker.dispose();
    }

    /**
     * One offering thread, a slow consumer, and the order comes out the way it went in. Breaks the
     * moment two threads are ever inside the drain at once, since then two items publish
     * concurrently and the recorded list interleaves; a slow consumer gives a second thread the
     * window to do that, where an instant one would empty the queue before the test could catch it.
     * Parks on every tenth item rather than every one, since a sub-millisecond
     * {@code LockSupport.parkNanos} does not exist on Windows (rounded up to the system timer
     * granularity, historically 15.6 ms) - a park per item would be 7.8 seconds for 500 items against
     * the {@value #PATIENCE_SECONDS}-second bound, failing on a loaded runner for a coarse clock
     * rather than a broken property. Fifty parks hold the worst case under a second and buy the same
     * thing, since what widens the window is a preemption point between two published items, not how
     * many there are.
     */
    @Test
    void whatOneThreadOffersArrivesInTheOrderItOfferedIt() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(500);
        Handoff<Integer> handoff = Handoff.lossless("the ordered queue", worker, item -> {
            if (item % 10 == 0) {
                LockSupport.parkNanos(100_000);
            }
            seen.add(item);
            delivered.countDown();
        });

        for (int item = 1; item <= 500; item++) {
            handoff.emit(item);
        }

        assertTrue(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never saw 500 items");
        assertEquals(IntStream.rangeClosed(1, 500).boxed().toList(), seen);
        assertEquals(0, handoff.dropped());
    }

    /**
     * Two threads offering at the same moment lose nothing, and neither one's own order is
     * disturbed - the shape production has: {@code PrinterReactor.onEvent} is reached by three
     * different threads and every one lands in {@code Handoff.emit}, made safe by the
     * work-in-progress transition where exactly one emitter schedules the drain and every other
     * parks and trusts it. The collision is forced, not hoped for, the way
     * {@code FarmEventsEmissionTest}/{@code PrinterReactorEmissionTest} force theirs: both emitters
     * start, meet on one gate, and are joined before it releases, so all 400 offers demonstrably
     * happen before a single item comes back. What breaks it: a lost guard transition leaves one
     * thread's items parked undrained and the wait times out short of 400; two drains running at once
     * take items off the queue concurrently and a thread's own subsequence comes back out of order.
     * How the two threads interleave with each other is deliberately not asserted - {@link Handoff}
     * only promises each thread's own subsequence, so the recorded list is filtered to each thread's
     * range before comparing.
     */
    @Test
    void twoThreadsOfferingAtOnceLoseNothingAndKeepTheirOwnOrder() throws InterruptedException {
        List<Integer> oneRun = IntStream.rangeClosed(1, 200).boxed().toList();
        List<Integer> theOther = IntStream.rangeClosed(1001, 1200).boxed().toList();

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(oneRun.size() + theOther.size());
        AtomicBoolean first = new AtomicBoolean();

        Handoff<Integer> handoff = Handoff.lossless("the contended queue", worker, item -> {
            if (first.compareAndSet(false, true)) {
                holding.countDown();
                awaitQuietly(release);
            }
            seen.add(item);
            delivered.countDown();
        });

        Thread one = offering(oneRun, go, handoff);
        Thread two = offering(theOther, go, handoff);
        go.countDown();
        one.join();
        two.join();

        assertTrue(holding.await(PATIENCE_SECONDS, TimeUnit.SECONDS),
                "nothing was ever scheduled to drain, so the work-in-progress transition was lost");
        release.countDown();

        assertTrue(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS),
                "a lossless hand-off did not deliver all 400 items two threads offered it");
        assertEquals(400, seen.size(), "a lossless hand-off must publish every item exactly once");
        assertEquals(0, handoff.dropped());
        assertEquals(oneRun, seen.stream().filter(item -> item <= 200).toList(),
                "one thread's items came back out of the order that thread offered them");
        assertEquals(theOther, seen.stream().filter(item -> item > 200).toList(),
                "the other thread's items came back out of the order that thread offered them");
    }

    /**
     * The offering thread is not charged for what the consumer does with the item - asserted twice:
     * every {@code emit} returns while the consumer is still inside the first item (nothing recorded
     * yet), and the thread that eventually publishes is not the thread that offered. Breaks if the
     * drain is ever run inline on the offering thread, which is what {@link Outbox} does and is the
     * reason this is a separate class - an inline drain would mean the first {@code emit} never
     * returns until the latch below releases, timing the test out before any assertion.
     */
    @Test
    void theOfferingThreadDoesNotWaitForTheConsumer() throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(8);
        AtomicReference<Thread> drainedOn = new AtomicReference<>();

        Handoff<Integer> handoff = Handoff.lossless("the held queue", worker, item -> {
            drainedOn.compareAndSet(null, Thread.currentThread());
            if (item == 1) {
                holding.countDown();
                awaitQuietly(release);
            }
            seen.add(item);
            delivered.countDown();
        });

        handoff.emit(1);
        assertTrue(holding.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never started");

        for (int item = 2; item <= 8; item++) {
            handoff.emit(item);
        }
        // every call above has returned, and the consumer is demonstrably still inside the first
        // item - it records nothing until let go.
        assertEquals(List.of(), seen, "the consumer advanced, so the hold proves nothing");

        release.countDown();
        assertTrue(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never caught up");
        assertEquals(IntStream.rangeClosed(1, 8).boxed().toList(), seen);
        assertNotNull(drainedOn.get());
        assertNotSame(Thread.currentThread(), drainedOn.get(), "the drain ran on the offering thread");
    }

    /**
     * A full droppable hand-off costs the overflowing items, and nothing else. The consumer is held
     * on the first item, so what the queue holds is exact: item 1 has been polled by the time the
     * hold is observed, leaving the whole capacity free for four more to park, with the next five
     * finding no room. Pins that an overflow is local - before #54 the equivalent pressure reached a
     * {@code Sinks.Many} as an overflow result and ended the whole stream for everybody; here it
     * costs five items, and the hand-off keeps working afterwards.
     */
    @Test
    void aFullQueueDropsTheOverflowAndKeepsTheRest() throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch whatWasParked = new CountDownLatch(5);
        CountDownLatch andOneMore = new CountDownLatch(6);

        Handoff<Integer> handoff = Handoff.droppable("the bounded queue", 4, worker, item -> {
            if (item == 1) {
                holding.countDown();
                awaitQuietly(release);
            }
            seen.add(item);
            whatWasParked.countDown();
            andOneMore.countDown();
        });

        handoff.emit(1);
        assertTrue(holding.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never started");

        for (int item = 2; item <= 10; item++) {
            handoff.emit(item);
        }
        assertEquals(5, handoff.dropped(), "four of the nine fit, so five and only five are dropped");

        release.countDown();
        // wait for the backlog to drain first: emitting into a still-full queue would be a sixth
        // drop and would say nothing about recovery.
        assertTrue(whatWasParked.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never caught up");
        handoff.emit(11);
        assertTrue(andOneMore.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the hand-off stopped working after an overflow");
        assertEquals(List.of(1, 2, 3, 4, 5, 11), seen);
        assertEquals(5, handoff.dropped(), "nothing was dropped after there was room again");
    }

    // thousands of items held on the first one - a backlog no bounded queue would survive. Breaks
    // if lossless ever acquires a bound, which would quietly turn a persisted connection transition
    // into a column that disagrees with the machine for good.
    @Test
    void aLosslessQueueDropsNothingUnderTheSamePressure() throws InterruptedException {
        int count = 3_000;
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(count);

        Handoff<Integer> handoff = Handoff.lossless("the unbounded queue", worker, item -> {
            if (item == 1) {
                holding.countDown();
                awaitQuietly(release);
            }
            seen.add(item);
            delivered.countDown();
        });

        handoff.emit(1);
        assertTrue(holding.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never started");

        for (int item = 2; item <= count; item++) {
            handoff.emit(item);
        }
        assertEquals(0, handoff.dropped());

        release.countDown();
        assertTrue(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never caught up");
        assertEquals(IntStream.rangeClosed(1, count).boxed().toList(), seen);
        assertEquals(0, handoff.dropped());
    }

    /**
     * A consumer that throws costs the item it threw on, and no more - the listeners at the other end
     * are {@code @Transactional} JPA writes, so a failure is a matter of when, not whether. Before the
     * publish moved onto a drain, a throw propagated out of {@code PrinterReactor.onEvent} and
     * terminated that session's reply stream entirely. Breaks if the {@code try/catch} leaves the
     * drain loop, wedging items 4 and 5 forever with {@code wip} stuck above zero. Run on
     * {@code Schedulers.immediate()} so the drain is on the calling thread, making "nothing escaped
     * {@code emit}" an assertion about this stack.
     */
    @Test
    void aConsumerThatThrowsCostsOneItem() {
        Handoff<Integer> handoff = Handoff.lossless("the failing queue", Schedulers.immediate(), item -> {
            if (item == 3) {
                throw new IllegalStateException("could not write the row");
            }
            seen.add(item);
        });

        assertDoesNotThrow(() -> {
            for (int item = 1; item <= 5; item++) {
                handoff.emit(item);
            }
        });

        assertEquals(List.of(1, 2, 4, 5), seen);
        assertEquals(0, handoff.dropped(), "a failed publish is not a drop - the item was delivered");
    }

    /**
     * After {@code close} nothing new is parked, and what was already parked still arrives - both
     * halves pull in opposite directions. Closing must stop a reply still in flight when a printer
     * is released from parking into a queue nobody will drain, but must not clear what is already
     * there, since the caller disposes the worker by scheduling the disposal onto that same thread,
     * with the tail of a machine's life (its last connection transition) sitting ahead of it in the
     * queue.
     */
    @Test
    void closingStopsNewItemsAndKeepsTheOnesAlreadyParked() throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(3);
        CountDownLatch aFourth = new CountDownLatch(4);

        Handoff<Integer> handoff = Handoff.lossless("the closing queue", worker, item -> {
            if (item == 1) {
                holding.countDown();
                awaitQuietly(release);
            }
            seen.add(item);
            delivered.countDown();
            aFourth.countDown();
        });

        handoff.emit(1);
        assertTrue(holding.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never started");
        handoff.emit(2);
        handoff.emit(3);

        handoff.close();
        assertDoesNotThrow(() -> handoff.emit(4));

        release.countDown();
        assertTrue(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "what was parked before the close was lost");
        assertFalse(aFourth.await(200, TimeUnit.MILLISECONDS), "a closed hand-off accepted an item");
        assertEquals(List.of(1, 2, 3), seen);
    }

    /**
     * A disposed worker is refused politely, not thrown back at the reply thread - production
     * shape: a printer is deleted, its scheduler disposed, and a decoded reply reaches
     * {@code onEvent} afterwards, where {@code Scheduler.schedule} answers with a
     * {@link java.util.concurrent.RejectedExecutionException} that must not escape {@code emit}.
     * The item is lost, correctly - there is no printer left to tell. The second {@code emit} is the
     * guard half: the refused thread already took {@code wip} from 0 to 1, so a rejection that does
     * not reset it would leave every later emit parking in silence forever.
     */
    @Test
    void aDisposedWorkerDoesNotWedgeTheQueue() {
        Scheduler gone = Schedulers.newSingle("handoff-gone");
        gone.dispose();

        Handoff<Integer> handoff = Handoff.lossless("the orphaned queue", gone, seen::add);

        assertDoesNotThrow(() -> handoff.emit(1));
        assertDoesNotThrow(() -> handoff.emit(2));
        assertEquals(List.of(), seen, "a disposed worker cannot have published anything");
    }

    // starts a thread that offers one ordered run of items, once the gate opens - starting two
    // threads is not the same as making them collide, so the gate makes the overlap a fact: both
    // are already running and blocked on it when it opens.
    private static Thread offering(List<Integer> items, CountDownLatch go, Handoff<Integer> handoff) {
        Thread emitter = new Thread(() -> {
            awaitQuietly(go);
            items.forEach(handoff::emit);
        }, "handoff-offering-" + items.getFirst());
        emitter.start();
        return emitter;
    }

    // waits on a latch off the test's own thread without letting an interrupt escape - an exception
    // here would be swallowed by the drain's own try/catch and fail the test somewhere else
    // entirely.
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("a thread this test parked was never let go");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

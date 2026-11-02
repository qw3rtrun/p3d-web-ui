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
 * What {@link Handoff} promises a caller, measured rather than read off the code.
 *
 * <p>The class is the per-printer hand-off #54 puts between a decoded reply and the
 * {@code @Transactional} listeners that persist what it meant. Everything asserted here is about a
 * <em>thread</em> - which one runs the consumer, which one is charged for it, and what survives a
 * consumer that is slower than the machine talking to it. So every test holds the consumer on a
 * latch rather than hoping for a schedule, and every wait has a bound: a property of this kind
 * fails by hanging, and a hanging test says nothing.
 *
 * <p>Blocking inside the consumer is exactly what production code may never do and exactly what
 * these tests need - it is the only way to make "the drain has not advanced" a fact instead of a
 * coincidence. Where two threads have to meet, the meeting is forced on a gate for the same
 * reason: a race one hopes for is a race one has not tested, and it passes against the bug on
 * most runs.
 *
 * <p><strong>No property here is bought with a sleep, and the one place that parks at all says
 * why.</strong> A timed wait on this platform is not the number it is given - see
 * {@link #whatOneThreadOffersArrivesInTheOrderItOfferedIt} - so a test whose pass depends on how
 * long something took is a test that fails on a loaded runner for a reason that is not a bug.
 */
@Timeout(30)
class HandoffTest {

    /** Long enough that a broken property fails rather than times out, short enough to notice. */
    private static final long PATIENCE_SECONDS = 10;

    /** The worker a production hand-off would be given: one thread, this printer's own. */
    private Scheduler worker;

    /** Items the consumer saw, in the order it saw them. */
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
     * One offering thread, a slow consumer, and the order comes out the way it went in.
     *
     * <p>Breaks the moment two threads are ever inside the drain at once - the guard dropped, or a
     * second {@code schedule} issued for a loop that is already running - because then two items
     * are published concurrently and the recorded list interleaves. The slow consumer is what gives
     * a second thread the window to do it in: with an instant consumer the drain empties the queue
     * long before the offering thread catches up, and the test passes over the bug.
     *
     * <p><strong>The park is on every tenth item on purpose, and tidying it back to one per item
     * would turn this test into a measurement of the platform's clock.</strong> A sub-millisecond
     * {@code LockSupport.parkNanos} does not exist on Windows: the park is served by a waitable
     * timer rounded up to the system timer granularity, which is 1 ms at best and has historically
     * been 15.6 ms when nothing on the box has asked for a finer one. At 15.6 ms, a park per item
     * is 7.8 seconds of parking for 500 items against a {@value #PATIENCE_SECONDS}-second bound -
     * so on a loaded runner this fails as "the consumer never saw 500 items", which reads as the
     * ordering property being broken when all that happened was a coarse clock. Fifty parks hold
     * the worst case under a second and buy the same thing, because what widens the window for a
     * second drain is <em>a preemption point between two published items</em>, not the number of
     * them: a second drain that exists at all has the whole backlog to race for, and fifty gaps to
     * be caught in.
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
     * disturbed.
     *
     * <p><strong>This is the shape production has.</strong> {@code PrinterReactor.onEvent} is
     * reached by three different threads - the aggregate's own executor, the backoff scheduler on
     * a failed connection attempt, and netty's on a dropped link - and every one of them lands in
     * {@code Handoff.emit}. What makes that safe is the work-in-progress transition: exactly one
     * emitter must see the guard go from zero and schedule the drain, and every other one must
     * park its item and leave, trusting that somebody is draining. Until this test nothing in the
     * file loaded that transition from more than one thread.
     *
     * <p><strong>The collision is forced rather than hoped for</strong>, the way
     * {@code FarmEventsEmissionTest} and {@code PrinterReactorEmissionTest} force theirs - a race
     * one merely hopes for is a race one has not tested. Both emitters are started and then held
     * on one gate, so they are let go at the same instant and meet inside {@code emit} rather than
     * one finishing before the other is scheduled; the consumer is held inside the first item it
     * is handed, so nothing is published while anything is still being offered; and both threads
     * are joined before that hold is released, so every one of the 400 offers has demonstrably
     * happened before a single item comes back out.
     *
     * <p>What breaks it, in the two ways it can break. A lost guard transition leaves one thread's
     * items parked with nothing scheduled to drain them, and the wait below times out short of
     * 400 - which is why nothing-was-lost is asserted as an entitlement and not as a hope: this is
     * the lossless flavour, and dropping is the other one's job. Two drains running at once take
     * items off the one queue concurrently, and a thread's own subsequence comes back out of the
     * order that thread offered it.
     *
     * <p><strong>How the two threads interleave with each other is deliberately not
     * asserted.</strong> {@link Handoff} does not promise it: its javadoc says two threads racing
     * into {@code emit} order themselves by which reaches the queue first, which is the most any
     * lock-free structure can say. An assertion on the merged order would be pinning an accident
     * of scheduling - it would fail for a reason that is not a bug, and it would advertise a
     * guarantee the class refuses to make, which is worse. Each thread's own subsequence is the
     * whole of the promise, so the recorded list is filtered down to each thread's range and only
     * that is compared.
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
     * The offering thread is not charged for what the consumer does with the item.
     *
     * <p>This is the whole point of the class, so it is asserted twice over: every {@code emit}
     * returns while the consumer is still inside the <em>first</em> item - proved by the consumer
     * having recorded nothing at the moment the last of them returned - and the thread that
     * eventually publishes is not the thread that offered.
     *
     * <p>Breaks if the drain is ever run inline on the offering thread, which is what {@link Outbox}
     * does and is the reason this is a separate class: with an inline drain the first
     * {@code emit} would not return until the latch below was released, and the test would time out
     * before it ever got to an assertion.
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
        // Every one of those calls has returned, and the consumer is demonstrably still inside the
        // first item: it records nothing until it is let go.
        assertEquals(List.of(), seen, "the consumer advanced, so the hold proves nothing");

        release.countDown();
        assertTrue(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never caught up");
        assertEquals(IntStream.rangeClosed(1, 8).boxed().toList(), seen);
        assertNotNull(drainedOn.get());
        assertNotSame(Thread.currentThread(), drainedOn.get(), "the drain ran on the offering thread");
    }

    /**
     * A full droppable hand-off costs the overflowing items, and nothing else.
     *
     * <p>The consumer is held on the first item, so what the queue holds is exact rather than a
     * race: item 1 has been polled by the time the hold is observed, leaving the whole capacity
     * free. Four more park, the next five find no room.
     *
     * <p>What it pins is that an overflow is local. Before #54 the equivalent pressure reached a
     * {@code Sinks.Many} as an overflow result, and the stream that carried it was over - one slow
     * browser ended the machine's event stream for everybody. Here it costs five items; the five
     * that were parked still arrive, in order, and the hand-off still works afterwards.
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
        // Wait for the backlog to drain before offering again: emitting into a queue that is still
        // full would be a sixth drop, and would say nothing about whether the hand-off recovered.
        assertTrue(whatWasParked.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the consumer never caught up");
        handoff.emit(11);
        assertTrue(andOneMore.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the hand-off stopped working after an overflow");
        assertEquals(List.of(1, 2, 3, 4, 5, 11), seen);
        assertEquals(5, handoff.dropped(), "nothing was dropped after there was room again");
    }

    /**
     * A lossless hand-off under the same pressure drops nothing at all.
     *
     * <p>Thousands of items while the consumer is held on the first one - a backlog no bounded
     * queue would survive. Breaks if {@code lossless} ever acquires a bound, which would quietly
     * turn a connection transition the roster persists into a column that disagrees with the
     * machine for good.
     */
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
     * A consumer that throws costs the item it threw on, and no more.
     *
     * <p>The listeners on the other end of this are {@code @Transactional} JPA writes, so a failure
     * is a matter of when rather than whether. Before the publish moved onto a drain, one that threw
     * propagated out of {@code PrinterReactor.onEvent}, out of {@code gEvent}'s {@code doOnNext}
     * and terminated that session's reply stream - the printer stopped being read because a row
     * would not write.
     *
     * <p>Breaks if the {@code try/catch} leaves the drain loop: items 4 and 5 would stay parked for
     * ever, {@code wip} would stay above zero, and the hand-off would be wedged. Run on
     * {@code Schedulers.immediate()} so the drain happens on the calling thread, which is what makes
     * "nothing escaped {@code emit}" an assertion about this stack rather than about another one.
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
     * After {@code close} nothing new is parked, and what was already parked still arrives.
     *
     * <p>Both halves matter and they pull in opposite directions. Closing must stop a reply that is
     * still in flight when a printer is released from parking into a queue nobody will drain; it
     * must <em>not</em> clear what is already there, because the caller disposes the worker by
     * scheduling the disposal onto that same single thread, so the tail of a machine's life - its
     * last connection transition above all - is sitting in this queue in front of it.
     *
     * <p>Breaks if {@code close} ever clears the queue, and breaks the other way if a closed
     * hand-off still accepts: the negative is bounded, so the test fails rather than hangs.
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
     * A disposed worker is refused politely, not thrown back at the reply thread.
     *
     * <p>How it happens in production: a printer is deleted, its scheduler is disposed, and a reply
     * already decoded reaches {@code onEvent} afterwards. {@code Scheduler.schedule} answers that
     * with a {@link java.util.concurrent.RejectedExecutionException}, and letting it out of
     * {@code emit} would kill the very stream that is shutting down anyway - noisily and for no
     * gain. The item is lost, which is right: there is no printer to tell anybody about.
     *
     * <p>The second {@code emit} is the guard half. The thread that is refused has already taken
     * {@code wip} from 0 to 1, so a rejection that returns without resetting it leaves a hand-off
     * where every later emit parks in silence and nothing is ever scheduled again.
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

    /**
     * Starts a thread that offers one ordered run of items, once the gate opens.
     *
     * <p>Starting two threads is not the same as making them collide: the first can be through its
     * whole run before the second is scheduled, and then nothing has been tested. The gate is what
     * makes the overlap a fact - both threads are already running and already blocked on it when
     * it opens.
     */
    private static Thread offering(List<Integer> items, CountDownLatch go, Handoff<Integer> handoff) {
        Thread emitter = new Thread(() -> {
            awaitQuietly(go);
            items.forEach(handoff::emit);
        }, "handoff-offering-" + items.getFirst());
        emitter.start();
        return emitter;
    }

    /**
     * Waits on a latch off the test's own thread - inside the consumer, or on an offering thread -
     * without letting an interrupt out of it.
     *
     * <p>An exception here would be swallowed by the drain's own {@code try/catch} and the test
     * would fail somewhere else entirely, which is a bad afternoon. The bound is the same
     * {@value #PATIENCE_SECONDS} seconds as everywhere else: a held thread that is never let go
     * must end the test rather than outlive it.
     */
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

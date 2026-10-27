package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qw3rtrun.p3d.terminal.msg.Priority.BACKGROUND;
import static org.qw3rtrun.p3d.terminal.msg.Priority.EMERGENCY;
import static org.qw3rtrun.p3d.terminal.msg.Priority.REGULAR;

/**
 * The rules {@link PublisherQueue} hand-implements, each asserted once.
 *
 * <p>{@code PublisherQueueTest} covers what it does - priority ordering and preemption. This covers
 * what it must not do, which is the half that was broken: signals after a terminal event, values
 * nobody asked for, a demand of zero, and a source whose {@code onSubscribe} arrives late.
 */
class PublisherQueueContractTest {

    private final PublisherQueue<String, Priority> queue = new PublisherQueue<>();

    /**
     * A source whose {@code onSubscribe} arrives on another thread.
     *
     * <p>The old code read {@code subscription.request(n)} on the line after {@code subscribe()},
     * which works only while every source calls back synchronously from inside {@code subscribe}.
     * <strong>Every Reactor operator tried here does</strong> - {@code publishOn},
     * {@code delaySubscription} and {@code Mono.fromFuture} all hand over a subscription
     * immediately and defer only the values - so this needs a publisher written to defer it, which
     * Reactive Streams explicitly permits and a hand-written one may well do. Against the previous
     * implementation it NullPointers.
     */
    @Test
    void aSourceThatSubscribesAsynchronouslyIsNotLost() {
        var future = new CompletableFuture<String>();
        queue.addPublisher(REGULAR, new LateSubscription(future));

        StepVerifier.create(queue)
                .thenRequest(1)
                .then(() -> future.complete("G28"))
                .expectNext("G28")
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    /**
     * A publisher that calls {@code onSubscribe} from another thread, some time later.
     *
     * <p>Written out because no Reactor operator does it: the specification allows
     * {@code onSubscribe} to arrive whenever the publisher likes, and a subscriber that assumes
     * otherwise is correct only by the grace of its sources.
     */
    private static final class LateSubscription implements org.reactivestreams.Publisher<String> {
        private final CompletableFuture<String> value;

        LateSubscription(CompletableFuture<String> value) {
            this.value = value;
        }

        @Override
        public void subscribe(Subscriber<? super String> s) {
            CompletableFuture.runAsync(() -> s.onSubscribe(new Subscription() {
                @Override
                public void request(long n) {
                    value.thenAccept(v -> {
                        s.onNext(v);
                        s.onComplete();
                    });
                }

                @Override
                public void cancel() {
                }
            }));
        }
    }

    /** And demand asked for before it subscribes still reaches it. */
    @Test
    void demandAskedForBeforeTheSubscriptionArrivesIsReplayed() {
        var latch = new CountDownLatch(1);
        var slow = Flux.just("G1 X1", "G1 X2")
                .delaySubscription(Mono.fromRunnable(latch::countDown)
                        .subscribeOn(Schedulers.boundedElastic()));
        queue.addPublisher(REGULAR, slow);

        StepVerifier.create(queue)
                .thenRequest(2)
                .then(() -> {
                    try {
                        assertTrue(latch.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .expectNext("G1 X1", "G1 X2")
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    /**
     * Two sources at one priority come out in the order they were added.
     *
     * <p>A heap is not stable and two interactive commands at {@code REGULAR} are the normal case,
     * so without a tie-break a user who pressed Home and then Set Temperature could get them the
     * other way round - which on a printer is a different thing happening.
     */
    @Test
    void sourcesOfEqualPriorityKeepTheOrderTheyWereAddedIn() {
        for (int i = 0; i < 16; i++) {
            queue.addPublisher(REGULAR, Mono.just("G1 X" + i));
        }

        var expected = new ArrayList<String>();
        for (int i = 0; i < 16; i++) {
            expected.add("G1 X" + i);
        }

        StepVerifier.create(queue)
                .thenRequest(16)
                .expectNextSequence(expected)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    /** Rule 1.10: one subscriber, and the second is told rather than silently replacing it. */
    @Test
    void aSecondSubscriberIsDeclinedRatherThanReplacingTheFirst() {
        var first = new Recording();
        queue.subscribe(first);

        var second = new Recording();
        queue.subscribe(second);

        assertNotNull(second.error, "the second subscriber should have been told");
        assertInstanceOf(IllegalStateException.class, second.error);
        assertNotNull(first.subscription, "and the first should still be attached");
        assertEquals(null, first.error);
    }

    /** Rule 3.9: a non-positive request is an error, not something to ignore. */
    @Test
    void aRequestOfZeroIsAnError() {
        var sub = new Recording();
        queue.subscribe(sub);
        queue.addPublisher(REGULAR, Mono.just("G28"));

        sub.subscription.request(0);

        assertInstanceOf(IllegalArgumentException.class, sub.error);
        assertEquals(List.of(), sub.values, "and nothing was emitted");
    }

    /**
     * Rule 2.4: nothing is signalled after a cancel.
     *
     * <p>A guard rather than a regression test: the previous implementation happened not to emit
     * here either, because its {@code cancel} zeroed the demand and the path it then took read
     * that zero and returned. It got the right answer for a reason that had nothing to do with
     * the rule, which is exactly the kind of thing that stops being true after an unrelated edit.
     */
    @Test
    void nothingIsSignalledAfterACancel() {
        var sub = new Recording();
        queue.subscribe(sub);
        var source = TestPublisher.<String>create();
        queue.addPublisher(REGULAR, source);
        sub.subscription.request(10);

        sub.subscription.cancel();
        source.next("G28");
        source.complete();

        assertEquals(List.of(), sub.values);
        assertEquals(0, sub.completions);
    }

    /**
     * Rule 1.7: one terminal signal, and nothing after it.
     *
     * <p>The shape that worried the skill was real - {@code onError} signalled the subscriber and
     * <em>then</em> handed demand on - but it did not fire, because the cancel one line above had
     * already zeroed the count it passed. A guard, then, and not a fixed bug: the ordering is now
     * impossible rather than harmless by accident.
     */
    @Test
    void nothingIsSignalledAfterAnError() {
        var sub = new Recording();
        queue.subscribe(sub);
        var failing = TestPublisher.<String>create();
        queue.addPublisher(EMERGENCY, failing);
        queue.addPublisher(BACKGROUND, Flux.just("G1 X1", "G1 X2"));
        sub.subscription.request(10);

        failing.error(new IllegalStateException("the file went away"));

        assertNotNull(sub.error);
        assertEquals(1, sub.errors, "exactly one terminal signal");
        assertEquals(List.of(), sub.values, "and the next source was not drained into a dead stream");
    }

    /**
     * A source that over-produces is cancelled, and its excess never reaches the wire.
     *
     * <p>Forwarding it would be an {@code onNext} the send window never asked for, and the send
     * window is the thing that stops the printer's buffer being overrun - which Marlin answers by
     * dropping commands in silence. A loud drop is the better of two bad outcomes.
     */
    @Test
    void aSourceThatEmitsWithoutDemandIsCancelledRatherThanForwarded() {
        var sub = new Recording();
        queue.subscribe(sub);
        var rogue = TestPublisher.<String>createNoncompliant(TestPublisher.Violation.REQUEST_OVERFLOW);
        queue.addPublisher(REGULAR, rogue);
        sub.subscription.request(1);

        rogue.next("G1 X1");
        rogue.next("G1 X2");
        rogue.next("G1 X3");

        assertEquals(List.of("G1 X1"), sub.values, "only what was asked for reached the subscriber");
        assertEquals(0, sub.errors, "and the session survived it");
    }

    /**
     * A source announces when it is finished, which is what plan item C3 needs.
     *
     * <p>The queue removed an exhausted source and told nobody, so a print that reached the end of
     * its file was invisible to the layer that queued it - and {@code JobService.complete} would
     * never have been called by anything.
     */
    @Test
    void anExhaustedSourceTellsWhoeverQueuedIt() {
        var done = queue.addPublisher(REGULAR, Flux.just("G1 X1", "G1 X2"));
        var sub = new Recording();
        queue.subscribe(sub);

        StepVerifier.create(done)
                .then(() -> sub.subscription.request(10))
                .verifyComplete();
    }

    /** And a source that fails says so the same way, rather than completing. */
    @Test
    void aFailedSourceReportsItsFailureToWhoeverQueuedIt() {
        var failing = TestPublisher.<String>create();
        var done = queue.addPublisher(REGULAR, failing);
        var sub = new Recording();
        queue.subscribe(sub);
        sub.subscription.request(10);

        StepVerifier.create(done)
                .then(() -> failing.error(new IllegalStateException("the file went away")))
                .verifyError(IllegalStateException.class);
    }

    /** What a finished source did not spend goes back to whatever is next in line. */
    @Test
    void unspentDemandReturnsToTheQueueWhenASourceFinishes() {
        queue.addPublisher(EMERGENCY, Mono.just("M112"));
        queue.addPublisher(BACKGROUND, Flux.just("G1 X1", "G1 X2"));

        StepVerifier.create(queue)
                .thenRequest(3)
                .expectNext("M112", "G1 X1", "G1 X2")
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    /** A subscriber that records what it was told, so the rules above can be asserted directly. */
    private static final class Recording implements Subscriber<String> {
        final List<String> values = new ArrayList<>();
        final AtomicReference<Subscription> held = new AtomicReference<>();
        Subscription subscription;
        Throwable error;
        int errors;
        int completions;

        @Override
        public void onSubscribe(Subscription s) {
            this.subscription = s;
            held.set(s);
        }

        @Override
        public void onNext(String s) {
            values.add(s);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            errors++;
        }

        @Override
        public void onComplete() {
            completions++;
        }
    }
}

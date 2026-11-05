package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PrinterReactor#awaitSubscribersGone(Duration)} - the exact witness issue #87's second pass
 * needed, and the thing {@code PrinterReactorManager.releaseAll()} is built on now.
 *
 * <p><strong>Why none of this module's existing tests could ever have caught the bug this is for.</strong>
 * Every other test that builds a {@code PrinterReactor} directly - {@link PrinterReactorSnapshotTest},
 * {@link PrinterReactorEmissionTest}, {@link PrinterReactorRetryTest} - wires
 * {@code Schedulers.immediate()} as the executor, which is also what {@link PrinterThreads#shared}
 * hands out when a test passes it {@code Schedulers.immediate()}. With that scheduler
 * {@code tryEmitComplete()} delivers its {@code onComplete} to a subscriber's {@code publishOn}
 * worker <em>on the calling thread, inline</em>, so {@code doFinally} runs before {@code stop()}
 * itself returns and {@link #awaitSubscribersGoneReturnsTruePromptlyWhenNobodyWasSubscribed()}'s
 * sibling tests would pass trivially whether or not the method actually waited for anything. This
 * file wires real schedulers - {@code Schedulers.boundedElastic()}, the JVM-wide shared one, whose
 * {@code dispose()} is a documented no-op so {@code stop()} disposing it is harmless - so that a
 * terminal signal genuinely crosses a scheduler boundary before a subscriber sees it, which is the
 * boundary the real bug lived in.
 */
class PrinterReactorAwaitSubscribersGoneTest {

    private final UUID id = UUID.randomUUID();

    private PrinterState printer;
    private PrinterReactor reactor;
    private Disposable subscription;

    @BeforeEach
    void build() {
        printer = new PrinterState(id);
        reactor = new PrinterReactor(new TerminalManager(new ConnectionDetails("localhost", 1)),
                printer, event -> {
        }, ConnectionPolicy.standard(), Schedulers.boundedElastic(), Schedulers.boundedElastic(),
                Schedulers.parallel());
    }

    @AfterEach
    void letGo() {
        if (subscription != null) {
            subscription.dispose();
        }
        reactor.stop();
    }

    /**
     * A machine nobody is watching must not make a caller wait out any part of its budget - the
     * case {@link PrinterReactor#stop()}'s own completion of {@code subscribersGone} exists for.
     */
    @Test
    void awaitSubscribersGoneReturnsTruePromptlyWhenNobodyWasSubscribed() {
        reactor.stop();

        var started = System.nanoTime();
        assertTrue(reactor.awaitSubscribersGone(Duration.ofSeconds(5)),
                "a machine with no subscribers has nothing to wait for");
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0,
                "took " + elapsed.toMillis() + "ms - a machine nobody was watching should not spend "
                        + "any part of the caller's budget");
    }

    /**
     * Once a subscriber's stream has actually terminated after a {@link PrinterReactor#stop()},
     * {@code awaitSubscribersGone} says so - and not a moment before the subscriber itself saw it.
     */
    @Test
    void awaitSubscribersGoneReturnsTrueOnceASubscribersStreamTerminatesAfterStop() throws InterruptedException {
        var completed = new CountDownLatch(1);
        var firstMessage = new CountDownLatch(1);
        subscription = reactor.updates().subscribe(
                message -> firstMessage.countDown(),
                failure -> completed.countDown(),
                completed::countDown);
        assertTrue(firstMessage.await(10, TimeUnit.SECONDS),
                "the snapshot never arrived, so nothing is actually watching this machine");

        reactor.stop();

        assertTrue(reactor.awaitSubscribersGone(Duration.ofSeconds(10)),
                "the subscriber's stream should have completed well within ten seconds");
        assertTrue(completed.await(1, TimeUnit.SECONDS),
                "awaitSubscribersGone answered true before the subscriber had actually completed - "
                        + "that is the exact gap this method exists to close");
    }

    /**
     * A subscriber that has not caught up with its own backlog within the limit is reported as
     * still there, not thrown at the caller - the same shape as the real failure this method fixes.
     *
     * <p><strong>The backlog is real, not a sleep standing in for one.</strong> Five firmware
     * reports are queued on the live sink before {@code stop()} runs, and the subscriber consumes
     * them one at a time with a pause - {@code request(1)}, sleep, {@code request(1)} - which is
     * exactly {@code GracefulShutdownIntegrationTest}'s {@code subscribeSlowly} at a smaller scale.
     * A {@code publishOn} worker does not deliver a queued {@code onComplete} ahead of items still
     * sitting in its queue, so the terminal signal genuinely has not reached this subscriber by the
     * time the short limit below expires.
     */
    @Test
    void awaitSubscribersGoneReturnsFalseRatherThanThrowingWhenASubscriberHasNotCaughtUp()
            throws InterruptedException {
        var firstMessage = new CountDownLatch(1);
        var perItemPause = Duration.ofMillis(150);
        var slow = new BaseSubscriber<EventMessage>() {
            @Override
            protected void hookOnSubscribe(Subscription s) {
                request(1);
            }

            @Override
            protected void hookOnNext(EventMessage message) {
                firstMessage.countDown();
                park(perItemPause);
                request(1);
            }
        };
        reactor.updates().subscribe(slow);
        subscription = slow;
        assertTrue(firstMessage.await(10, TimeUnit.SECONDS),
                "the snapshot never arrived, so nothing is actually watching this machine");

        for (var i = 0; i < 5; i++) {
            printer.onEvent(firmware("Marlin 2.1." + i));
        }
        reactor.stop();

        var limit = Duration.ofMillis(200);
        var started = System.nanoTime();
        var gone = reactor.awaitSubscribersGone(limit);
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertFalse(gone, "the slow subscriber had not caught up with its five-item backlog yet");
        assertTrue(elapsed.compareTo(limit.plusSeconds(2)) < 0,
                "took " + elapsed.toMillis() + "ms to time out against a " + limit.toMillis()
                        + "ms limit - a stuck subscriber must not be allowed to hold the caller up "
                        + "past its own budget");
    }

    private static FirmwareInfoRs firmware(String which) {
        return new FirmwareInfoRs(Map.of("FIRMWARE_NAME", which));
    }

    private static void park(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

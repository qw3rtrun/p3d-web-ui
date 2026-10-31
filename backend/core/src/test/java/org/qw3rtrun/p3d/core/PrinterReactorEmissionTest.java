package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MFirmwareReportedEvent;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Two threads put an event on this reactor at the same moment, and neither one is lost.
 *
 * <p><strong>The same bug class the transport was just fixed for, on the aggregate's own sink.</strong>
 * {@code PrinterReactor.updates} is a <em>safe</em> {@code Sinks.Many}, which does not serialise for
 * its caller - it <em>refuses</em> a concurrent emission with {@code FAIL_NON_SERIALIZED}. The
 * emitters really are several threads, and the class javadoc on the reactor says which: the
 * aggregate's executor, the backoff scheduler on a failed attempt, and netty's on a dropped link.
 *
 * <p>What that cost is not a late UI update. The refusal was handed to
 * {@code EmitFailureHandler.busyLooping(Duration.ofSeconds(1))} held in a {@code static final}
 * field - which Reactor's own javadoc rules out in as many words, because the handler's deadline is
 * computed <em>at construction</em>: "don't cache the returning handler for later usage". A handler
 * built when the class loaded is expired one second later, so from then on every collision throws
 * {@code Sinks.EmissionException} immediately, and the throw happens <em>before</em> the roster is
 * told. The machine's firmware never reaches the database, and the exception propagates out of
 * {@code PrinterReactor.gEvent}'s {@code doOnNext} and kills that session's reply stream.
 *
 * <p>The loss is permanent rather than late: {@code PrinterState.on(FirmwareInfoRs)} sets its own
 * {@code profile} field before emitting and guards on the firmware having changed, so after a throw
 * the aggregate believes it has the firmware and the identical {@code M115} of any later redial is
 * suppressed.
 *
 * <p><strong>The collision is forced rather than hoped for.</strong> Reactor's serialisation guard
 * is re-entrant for the thread that holds it, so the only way one emission can be refused is for a
 * <em>different</em> thread to be inside the sink at that moment. So the subscriber here parks the
 * first thread inside {@code tryEmitNext} and the second thread emits while it is held. That makes
 * the race a certainty instead of a 2-core-CI-runner coincidence - which is how the transport's
 * version of this bug had to be found.
 */
class PrinterReactorEmissionTest {

    private static final FirmwareInfoRs FIRST =
            new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1.2"));

    /**
     * A different firmware from {@link #FIRST}, because the aggregate suppresses a repeat.
     *
     * <p>Not padding: it is what makes the second emission a real one. {@code PrinterState} only
     * announces a firmware report that changed something.
     */
    private static final FirmwareInfoRs SECOND =
            new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1.3"));

    private final UUID id = UUID.randomUUID();

    /** Everything the roster was told, in order - both halves of {@code PrinterReactor.onEvent}. */
    private final List<Object> roster = new CopyOnWriteArrayList<>();

    /** Counted down from inside the subscriber, once the first report is being delivered. */
    private final CountDownLatch delivering = new CountDownLatch(1);

    /** Counted down by the second thread once it has emitted; it is what frees the first. */
    private final CountDownLatch collided = new CountDownLatch(1);

    private final AtomicReference<Throwable> secondEmitterFailed = new AtomicReference<>();

    private Thread held;
    private Thread colliding;
    private Disposable watching;

    private PrinterState printer;
    private PrinterReactor reactor;

    /**
     * A reactor over a manager that is never asked to dial.
     *
     * <p>{@code TerminalManager}'s constructor only builds a {@code TcpClient}, so nothing here
     * opens a socket. {@code Schedulers.immediate()} as the executor is what makes the subscriber
     * run on the emitting thread, which is what lets the test hold that thread inside the sink.
     */
    @BeforeEach
    void build() {
        printer = new PrinterState(id);
        reactor = new PrinterReactor(new TerminalManager(new ConnectionDetails("localhost", 1)),
                printer, roster::add,
                ConnectionPolicy.standard(), Schedulers.immediate(), Schedulers.parallel());
    }

    @AfterEach
    void letGo() throws InterruptedException {
        collided.countDown();
        delivering.countDown();
        joinEmitters();
        if (watching != null) {
            watching.dispose();
        }
        reactor.stop();
    }

    /**
     * Every event reaches a subscriber, even when the two emissions overlap.
     *
     * <p>The second report arrives while the first is still being delivered, so the sink refuses it.
     * That is the whole of the observable: a browser watching this printer either sees both firmware
     * reports or it does not.
     */
    @Test
    void everyEventReachesASubscriberWhenTwoThreadsEmitAtOnce() {
        StepVerifier.create(reactor.updates().map(EventMessage::event))
                .assertNext(seed -> assertInstanceOf(ConnectionStateChanged.class, seed,
                        "the replay sink is seeded with the current connection state"))
                .then(this::twoThreadsEmitAtOnce)
                .assertNext(report -> {
                    assertEquals(FIRST, report);
                    holdThisThreadInsideTheSink();
                })
                .expectNext(SECOND)
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    /**
     * A collision on the UI sink does not stop the farm recording what the machine said.
     *
     * <p>The sink is a live stream and the roster is the database. A refused emission on the first
     * must never cost the second - and it did, because the emission came first and threw.
     */
    @Test
    void aCollisionOnTheUpdateSinkDoesNotStopTheRosterBeingTold() throws InterruptedException {
        watching = reactor.updates().subscribe(message -> {
            if (message.event() instanceof FirmwareInfoRs) {
                holdThisThreadInsideTheSink();
            }
        });

        twoThreadsEmitAtOnce();
        joinEmitters();

        assertEquals(List.of(FIRST.getKeyValues(), SECOND.getKeyValues()), firmwareToldToTheRoster(),
                "both firmware reports have to reach the roster, which is what persists them");
        assertEquals(List.of(FIRST, SECOND), machineEventsToldToTheRoster(),
                "and both have to reach the farm-wide stream, which listens on the same bus");
        assertNull(secondEmitterFailed.get(),
                "putting an event on the sink must not throw out at the caller: this one propagates "
                        + "out of PrinterReactor.gEvent's doOnNext and kills the reply stream");
    }

    /**
     * Starts the two emitters: one that will be held inside the sink, one that collides with it.
     *
     * <p>Both go through {@code PrinterState.onEvent}, which is the production path - a reply
     * reaches the aggregate and the aggregate emits - rather than poking the reactor directly.
     */
    private void twoThreadsEmitAtOnce() {
        colliding = new Thread(() -> {
            try {
                if (!delivering.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("the first report was never delivered");
                }
                printer.onEvent(SECOND);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                secondEmitterFailed.set(failure);
            } finally {
                collided.countDown();
            }
        }, "colliding-emitter");
        held = new Thread(() -> printer.onEvent(FIRST), "held-emitter");

        colliding.start();
        held.start();
    }

    private void joinEmitters() throws InterruptedException {
        if (colliding != null) {
            colliding.join(TimeUnit.SECONDS.toMillis(15));
        }
        if (held != null) {
            held.join(TimeUnit.SECONDS.toMillis(15));
        }
    }

    /**
     * Parks the delivering thread inside {@code tryEmitNext} until the other one has emitted.
     *
     * <p>Blocking in a subscriber is exactly what this test needs and nothing else does: it is the
     * only way to make "two threads are in the sink at once" a fact rather than a probability.
     */
    private void holdThisThreadInsideTheSink() {
        delivering.countDown();
        try {
            if (!collided.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("the second thread never emitted");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Map<String, String>> firmwareToldToTheRoster() {
        return roster.stream()
                .filter(MFirmwareReportedEvent.class::isInstance)
                .map(event -> ((MFirmwareReportedEvent) event).firmware())
                .toList();
    }

    private List<Object> machineEventsToldToTheRoster() {
        return roster.stream()
                .filter(EventMessage.class::isInstance)
                .map(event -> ((EventMessage) event).event())
                .toList();
    }
}

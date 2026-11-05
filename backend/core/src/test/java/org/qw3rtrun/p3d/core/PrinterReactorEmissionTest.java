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
 * Two threads put an event on this reactor at the same moment, and neither one is lost - the same
 * bug class the transport was just fixed for, on the aggregate's own sink. {@code PrinterReactor.updates}
 * is a safe {@code Sinks.Many}, which refuses a concurrent emission with
 * {@code FAIL_NON_SERIALIZED} rather than serialising it; the emitters really are several threads
 * (the aggregate's executor, the backoff scheduler, netty's on a dropped link). Before the outbox,
 * the refusal was handed to {@code EmitFailureHandler.busyLooping(...)} held in a
 * {@code static final} field - which Reactor's own javadoc rules out, since the handler's deadline
 * is computed at construction and expires one second after class load, so every collision threw
 * {@code Sinks.EmissionException} immediately, before the roster was told. The loss was permanent,
 * not late: {@code PrinterState.on(FirmwareInfoRs)} sets its profile before emitting and suppresses
 * an identical later {@code M115}. The collision is forced, not hoped for: Reactor's serialisation
 * guard is re-entrant for the thread holding it, so refusal needs a different thread inside the
 * sink, made certain here by parking the first thread inside {@code tryEmitNext} while the second
 * emits.
 */
class PrinterReactorEmissionTest {

    private static final FirmwareInfoRs FIRST =
            new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1.2"));

    // different from FIRST, not padding: it is what makes the second emission real, since
    // PrinterState only announces a firmware report that changed something.
    private static final FirmwareInfoRs SECOND =
            new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1.3"));

    private final UUID id = UUID.randomUUID();

    // everything the roster was told, in order - both halves of PrinterReactor.onEvent.
    private final List<Object> roster = new CopyOnWriteArrayList<>();

    // counted down from inside the subscriber, once the first report is being delivered.
    private final CountDownLatch delivering = new CountDownLatch(1);

    // counted down by the second thread once it has emitted; frees the first.
    private final CountDownLatch collided = new CountDownLatch(1);

    private final AtomicReference<Throwable> secondEmitterFailed = new AtomicReference<>();

    private Thread held;
    private Thread colliding;
    private Disposable watching;

    private PrinterState printer;
    private PrinterReactor reactor;

    // TerminalManager's constructor only builds a TcpClient, so nothing opens a socket.
    // Schedulers.immediate() as the executor makes the subscriber run on the emitting thread, which
    // is what lets the test hold it inside the sink.
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

    // the second report arrives while the first is still being delivered, so the sink refuses it -
    // a browser watching this printer either sees both firmware reports or it does not.
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

    // the sink is a live stream and the roster is the database - a refused emission on the first
    // must never cost the second, which it did when the emission came first and threw.
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

    // starts the two emitters: one held inside the sink, one that collides with it - both through
    // PrinterState.onEvent, the production path, rather than poking the reactor directly.
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

    // parks the delivering thread inside tryEmitNext until the other has emitted - blocking in a
    // subscriber is the only way to make "two threads in the sink at once" a fact, not a probability.
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

package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Two machines put an event on the farm stream at the same moment, and neither one is lost - the
 * third instance of the bug {@code HostTerminal} and {@code PrinterReactor} were fixed for, and the
 * only one seen on a laptop: {@code FarmEvents.emit} called {@code tryEmitNext} on a safe
 * {@code Sinks.Many} from whatever thread published the Spring event, which refuses a concurrent
 * emission with {@code FAIL_NON_SERIALIZED} rather than serialising it - two minutes of simulator
 * traffic against one printer dropped a real {@code IDLE -> CONNECTING} transition. Nastier than the
 * other two because of the log line: the sink is meant to drop for backpressure (costing one
 * subscriber one event), but a collision costs every dashboard the same event and wore identical
 * words in the log. The collision is forced, not hoped for: a refusal needs a different thread
 * inside the sink while Reactor's re-entrant guard is held, made certain here by parking the first
 * thread inside {@code tryEmitNext} while the second emits.
 */
class FarmEventsEmissionTest {

    // alpha's reading, delivered to a subscriber that will not let go of the thread carrying it.
    private static final BareTemperatureRs FIRST = temperature(210);

    // beta's, emitted from a second thread while the first is still inside the sink.
    private static final BareTemperatureRs SECOND = temperature(60);

    private final UUID alpha = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();

    private final List<EventMessage> seen = new CopyOnWriteArrayList<>();

    // counted down from inside the subscriber, once alpha's reading is being delivered.
    private final CountDownLatch delivering = new CountDownLatch(1);

    // counted down by the second thread once it has emitted; frees the first.
    private final CountDownLatch collided = new CountDownLatch(1);

    private final AtomicReference<Throwable> secondEmitterFailed = new AtomicReference<>();

    private Thread held;
    private Thread colliding;
    private Disposable watching;

    private FarmEvents farm;

    // an empty directory, so updates() is the live sink and nothing else - an empty snapshot
    // completes during subscribe(), so the sink has a subscriber by the time that call returns.
    @BeforeEach
    void build() {
        var directory = new PrinterDirectory();
        farm = new FarmEvents(directory, new PrinterReactorManager(directory, PrinterState::new, event -> {
        }));
    }

    @AfterEach
    void letGo() throws InterruptedException {
        collided.countDown();
        delivering.countDown();
        joinEmitters();
        if (watching != null) {
            watching.dispose();
        }
    }

    // a page holding /api-farm/stream either sees both readings or it does not; the ids are
    // asserted too, since demultiplexing by EventMessage.id is the thing one stream exists to do.
    @Test
    void bothMachinesReachADashboardWhenTheyEmitAtOnce() throws InterruptedException {
        watching = Flux.from(farm.updates()).subscribe(message -> {
            seen.add(message);
            if (message.event() == FIRST) {
                holdThisThreadInsideTheSink();
            }
        });

        twoMachinesEmitAtOnce();
        joinEmitters();

        assertEquals(List.of(FIRST, SECOND), seen.stream().map(EventMessage::event).toList(),
                "a collision on the farm sink costs every dashboard the event, not one slow one");
        assertEquals(List.of(alpha, beta), seen.stream().map(EventMessage::id).toList(),
                "and each one has to arrive under the machine it came from");
        assertNull(secondEmitterFailed.get(),
                "putting an event on the farm stream must not throw out at the caller: it is "
                        + "reached from a Spring listener on the reactor's own thread");
    }

    // starts the two emitters: both arrive via onMachineEvent, the @EventListener every reactor's
    // events reach.
    private void twoMachinesEmitAtOnce() {
        colliding = new Thread(() -> {
            try {
                if (!delivering.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("alpha's reading was never delivered");
                }
                farm.onMachineEvent(new EventMessage(beta, SECOND));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                secondEmitterFailed.set(failure);
            } finally {
                collided.countDown();
            }
        }, "colliding-emitter");
        held = new Thread(() -> farm.onMachineEvent(new EventMessage(alpha, FIRST)), "held-emitter");

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

    // parks the delivering thread inside tryEmitNext until the other has emitted - the only way to
    // make "two threads in the sink at once" a fact rather than a probability.
    private void holdThisThreadInsideTheSink() {
        delivering.countDown();
        try {
            if (!collided.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("the second machine never emitted");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static BareTemperatureRs temperature(double hotend) {
        return new BareTemperatureRs(
                reading(hotend), reading(60), null, null, null, null, null, Map.of());
    }

    private static HeaterReading reading(double current) {
        return new HeaterReading(BigDecimal.valueOf(current), BigDecimal.valueOf(current), 100);
    }
}

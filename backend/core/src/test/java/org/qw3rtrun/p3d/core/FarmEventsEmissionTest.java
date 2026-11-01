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
 * Two machines put an event on the farm stream at the same moment, and neither one is lost.
 *
 * <p><strong>The third instance of the bug {@code HostTerminal} and {@code PrinterReactor} were
 * fixed for, and the only one that was seen on a laptop.</strong> {@code FarmEvents.emit} called
 * {@code tryEmitNext} on a <em>safe</em> {@code Sinks.Many} from whatever thread published the
 * Spring event - a reactor's {@code single}, a netty loop, or {@code boundedElastic}. A safe sink
 * does not serialise for its caller, it <em>refuses</em>: the second emitter gets
 * {@code FAIL_NON_SERIALIZED}, and checking the result is not the same as handling it. Two minutes
 * of simulator traffic against one printer dropped a real {@code IDLE -> CONNECTING} transition.
 *
 * <p><strong>What makes this instance nastier than the other two is the log line.</strong> The sink
 * is {@code multicast().directBestEffort()} and is <em>meant</em> to drop - for backpressure, when
 * one slow browser cannot keep up, so that a stalled dashboard never stalls the reactor feeding it.
 * That drop costs one subscriber one event. A collision costs <em>every</em> dashboard the same
 * event and has nothing to do with how fast anyone is reading, and it wore the same words in the
 * log.
 *
 * <p><strong>The collision is forced rather than hoped for.</strong> Reactor's serialisation guard
 * is re-entrant for the thread that holds it, so a refusal needs a <em>different</em> thread inside
 * the sink at that moment. The subscriber here parks the first thread inside {@code tryEmitNext}
 * and the second emits while it is held. A test that merely emits from two threads and hopes passes
 * against the bug on most runs - which is exactly why this survived two rounds of fixing it
 * elsewhere.
 */
class FarmEventsEmissionTest {

    /** Alpha's reading, delivered to a subscriber that will not let go of the thread carrying it. */
    private static final BareTemperatureRs FIRST = temperature(210);

    /** Beta's, emitted from a second thread while the first is still inside the sink. */
    private static final BareTemperatureRs SECOND = temperature(60);

    private final UUID alpha = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();

    /** Everything a dashboard saw, in order. */
    private final List<EventMessage> seen = new CopyOnWriteArrayList<>();

    /** Counted down from inside the subscriber, once alpha's reading is being delivered. */
    private final CountDownLatch delivering = new CountDownLatch(1);

    /** Counted down by the second thread once it has emitted; it is what frees the first. */
    private final CountDownLatch collided = new CountDownLatch(1);

    private final AtomicReference<Throwable> secondEmitterFailed = new AtomicReference<>();

    private Thread held;
    private Thread colliding;
    private Disposable watching;

    private FarmEvents farm;

    /**
     * A farm view over an empty directory, so that {@code updates()} is the live sink and nothing
     * else.
     *
     * <p>An empty snapshot completes during {@code subscribe()}, so the sink has a subscriber by the
     * time that call returns - which is what lets the test emit into it from another thread
     * immediately afterwards.
     */
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

    /**
     * Both machines' events reach the dashboard, even though the two emissions overlap.
     *
     * <p>That is the whole of the observable: a page holding {@code /api-farm/stream} either sees
     * both readings or it does not. The two ids are asserted as well, because demultiplexing by
     * {@code EventMessage.id} is the thing one stream exists to do - the <em>end to end</em> version
     * of that, two machines through the HTTP endpoint, belongs to the {@code :app} suite.
     */
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

    /**
     * Starts the two emitters: one that will be held inside the sink, one that collides with it.
     *
     * <p>Both arrive the way the application delivers them - {@code onMachineEvent}, the
     * {@code @EventListener} every reactor's events reach.
     */
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

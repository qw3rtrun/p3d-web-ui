package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.msg.MachineJoinedFarm;
import org.qw3rtrun.p3d.api.msg.MachineLeftFarm;
import org.qw3rtrun.p3d.g.marlin.event.BareTemperatureRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One stream for the whole farm - plan item D2.
 *
 * <p>What is asserted is the three things the done-when names: a subscriber learns where every
 * machine is without asking, a printer appearing or disappearing shows up on the open stream, and
 * every machine's events arrive on the one subscription.
 */
class FarmEventsTest {

    private PrinterDirectory directory;
    private PrinterReactorManager reactors;
    private FarmEvents farm;

    @BeforeEach
    void setUp() {
        directory = new PrinterDirectory();
        reactors = new PrinterReactorManager(directory, PrinterState::new, event -> {
        });
        farm = new FarmEvents(directory, reactors);
    }

    private UUID theFarmHas(String name) {
        var id = UUID.randomUUID();
        var event = new MRegisteredEvent(id, name, "localhost", 8099, MachineProfile.unknown(),
                MachineSpec.undeclared(), MachineConnectionState.OFFLINE, LocalDateTime.now());
        directory.onRegistered(event);
        farm.onRegistered(event);
        return id;
    }

    // makes one subscription enough to draw a page: the alternative is a row of blanks until each
    // printer next says something, which for an idle one could be never.
    @Test
    void aSubscriberLearnsWhereEveryMachineIsBeforeAnythingHappens() {
        var first = theFarmHas("alpha");
        var second = theFarmHas("beta");

        StepVerifier.create(Flux.from(farm.updates()).take(2))
                .assertNext(message -> {
                    assertKnown(message, first, second);
                    assertState(message);
                })
                .assertNext(message -> {
                    assertKnown(message, first, second);
                    assertState(message);
                })
                .verifyComplete();
    }

    private static void assertKnown(EventMessage message, UUID first, UUID second) {
        assertTrue(message.id().equals(first) || message.id().equals(second),
                "the snapshot should only carry machines the farm has: " + message.id());
    }

    private static void assertState(EventMessage message) {
        assertInstanceOf(ConnectionStateChanged.class, message.event());
        assertEquals(MachineConnectionState.OFFLINE,
                ((ConnectionStateChanged) message.event()).to(),
                "nothing has dialled it, so there is nothing it could be doing");
    }

    /** An empty farm is an empty snapshot, not a failure. */
    @Test
    void anEmptyFarmSnapshotsToNothing() {
        StepVerifier.create(Flux.from(farm.updates()).take(Duration.ofMillis(100)))
                .verifyComplete();
    }

    /** Every machine's events arrive on the one subscription. */
    @Test
    void oneSubscriptionCarriesEveryMachine() {
        var alpha = theFarmHas("alpha");
        var beta = theFarmHas("beta");

        StepVerifier.create(Flux.from(farm.updates()).skip(2).take(2))
                .then(() -> farm.onMachineEvent(new EventMessage(alpha, temperature(210))))
                .assertNext(message -> {
                    assertEquals(alpha, message.id());
                    assertEquals("BareTemperatureRs", message.type());
                })
                .then(() -> farm.onMachineEvent(new EventMessage(beta, temperature(60))))
                .assertNext(message -> assertEquals(beta, message.id()))
                .verifyComplete();
    }

    @Test
    void aPrinterAddedWhileWatchingAppears() {
        StepVerifier.create(Flux.from(farm.updates()).take(1))
                .then(() -> theFarmHas("latecomer"))
                .assertNext(message -> {
                    var joined = assertInstanceOf(
                            MachineJoinedFarm.class, message.event());
                    assertEquals("latecomer", joined.name());
                })
                .verifyComplete();
    }

    /** And one removed disappears. */
    @Test
    void aPrinterRemovedWhileWatchingDisappears() {
        var doomed = theFarmHas("doomed");

        StepVerifier.create(Flux.from(farm.updates()).skip(1).take(1))
                .then(() -> farm.onDeleted(new MDeletedEvent(doomed, LocalDateTime.now())))
                .assertNext(message -> {
                    assertEquals(doomed, message.id());
                    assertInstanceOf(MachineLeftFarm.class, message.event());
                })
                .verifyComplete();
    }

    // MRegisteredEvent re-publishes at startup and whenever the roster learns something - a
    // dashboard would otherwise see a printer "appear" on every firmware report.
    @Test
    void aReAnnouncedMachineDoesNotJoinTwice() {
        var id = UUID.randomUUID();
        var announcement = new MRegisteredEvent(id, "alpha", "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now());
        directory.onRegistered(announcement);
        farm.onRegistered(announcement);

        StepVerifier.create(Flux.from(farm.updates()).skip(1).take(1))
                .then(() -> farm.onRegistered(announcement))
                .then(() -> farm.onMachineEvent(new EventMessage(id, temperature(210))))
                .assertNext(message -> assertEquals(
                        "BareTemperatureRs", message.type(),
                        "the re-announcement should not have produced a join"))
                .verifyComplete();
    }

    /** And a machine deleted and registered again is a genuine new arrival. */
    @Test
    void aMachineRegisteredAgainAfterDeletionJoinsAgain() {
        var id = UUID.randomUUID();
        var announcement = new MRegisteredEvent(id, "alpha", "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now());
        farm.onRegistered(announcement);
        farm.onDeleted(new MDeletedEvent(id, LocalDateTime.now()));

        StepVerifier.create(Flux.from(farm.updates()).take(1))
                .then(() -> farm.onRegistered(announcement))
                .assertNext(message -> assertInstanceOf(
                        MachineJoinedFarm.class, message.event()))
                .verifyComplete();
    }

    /** Two subscribers each get the whole farm - it is a multicast, not a hand-off. */
    @Test
    void twoSubscribersBothSeeEverything() {
        var alpha = theFarmHas("alpha");

        var first = Flux.from(farm.updates()).skip(1).take(1);
        var second = Flux.from(farm.updates()).skip(1).take(1);

        StepVerifier.create(Flux.merge(first, second))
                .then(() -> farm.onMachineEvent(new EventMessage(alpha, temperature(210))))
                .expectNextCount(2)
                .verifyComplete();
    }

    /**
     * The snapshot is built without blocking, asserted on a thread that will not permit it - exists
     * because every other test here missed a live 500: the first snapshot called
     * {@code reactor.connection().block()}, and a snapshot is built on whichever thread subscribed
     * (a netty event loop for an SSE endpoint), but every test above subscribes on an ordinary
     * thread where blocking is merely rude. {@code Schedulers.parallel()} pins that cheaply, since
     * its workers carry Reactor's {@code NonBlocking} marker, the same one a netty event loop
     * carries and the same check that produced the 500.
     */
    @Test
    void theSnapshotDoesNotBlockTheThreadItIsBuiltOn() {
        var id = theFarmHas("alpha");
        reactors.openReactor(id);

        StepVerifier.create(Flux.from(farm.updates())
                        .subscribeOn(Schedulers.parallel())
                        .take(1))
                .assertNext(FarmEventsTest::assertState)
                .verifyComplete();

        // the only reactor this file opens, and a reactor owns two threads now - see #54.
        reactors.release(id);
    }

    // the row's two figures - connection and temperature - arrive before anything live; the
    // firmware the machine also reported is deliberately not in the farm's snapshot, since forty
    // machines' capabilities on every page load is a few thousand messages no row draws.
    @Test
    void aMachineThatHasReportedATemperatureIsDrawnWithIt() {
        var id = theFarmHas("alpha");
        var aggregates = new HashMap<UUID, PrinterState>();
        reactors = new PrinterReactorManager(directory,
                machine -> aggregates.computeIfAbsent(machine, PrinterState::new), event -> {
        });
        farm = new FarmEvents(directory, reactors);
        reactors.openReactor(id);
        aggregates.get(id).onEvent(new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1.2")));
        aggregates.get(id).onEvent(temperature(210));

        StepVerifier.create(Flux.from(farm.updates()).take(Duration.ofMillis(200)))
                .assertNext(FarmEventsTest::assertState)
                .assertNext(message -> {
                    assertEquals(id, message.id());
                    assertEquals(temperature(210), message.event());
                })
                .verifyComplete();

        reactors.release(id);
    }

    private static BareTemperatureRs temperature(double hotend) {
        return new BareTemperatureRs(
                reading(hotend), reading(60), null, null, null, null, null, Map.of());
    }

    private static HeaterReading reading(double current) {
        return new HeaterReading(BigDecimal.valueOf(current), BigDecimal.valueOf(current), 100);
    }

    // ---------------------------------------------------------------- close() - issue #87

    // lets GET /api-farm/stream stop being an in-flight request when the farm is told to stop - an
    // already-attached subscriber must be told the stream is over, not merely find it quiet.
    @Test
    void closeCompletesASubscribersStream() {
        StepVerifier.create(Flux.from(farm.updates()))
                .then(farm::close)
                .verifyComplete();
    }

    // gets the snapshot (OFFLINE, since no runtime exists) and completes at once, since the live
    // half it subscribes to first (CatchUp.snapshotThenLive) has already finished.
    @Test
    void aSubscriberAfterCloseGetsTheSnapshotThenCompletes() {
        var alpha = theFarmHas("alpha");

        farm.close();

        StepVerifier.create(Flux.from(farm.updates()))
                .assertNext(message -> {
                    assertEquals(alpha, message.id());
                    assertState(message);
                })
                .verifyComplete();
    }

    // must not reach a terminated sink and trigger FAIL_TERMINATED, and must not throw out at the
    // caller, which here is an @EventListener on a printer's own listener thread.
    @Test
    void anEventOfferedAfterCloseGoesNowhereAndDoesNotThrow() {
        var alpha = theFarmHas("alpha");

        farm.close();

        assertDoesNotThrow(() -> farm.onMachineEvent(new EventMessage(alpha, temperature(210))),
                "an event after close() must be silently swallowed, not throw past the sink");
    }
}

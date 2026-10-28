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
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReported;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

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

    /**
     * A subscriber is told where every machine is before anything live arrives.
     *
     * <p>That is what makes one subscription enough to draw a page: the alternative is a row of
     * blanks until each printer next says something, which for an idle one could be never.
     */
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
        org.junit.jupiter.api.Assertions.assertTrue(
                message.id().equals(first) || message.id().equals(second),
                "the snapshot should only carry machines the farm has: " + message.id());
    }

    private static void assertState(EventMessage message) {
        org.junit.jupiter.api.Assertions.assertInstanceOf(ConnectionStateChanged.class, message.event());
        org.junit.jupiter.api.Assertions.assertEquals(MachineConnectionState.OFFLINE,
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
                    org.junit.jupiter.api.Assertions.assertEquals(alpha, message.id());
                    org.junit.jupiter.api.Assertions.assertEquals("TemperatureReported", message.type());
                })
                .then(() -> farm.onMachineEvent(new EventMessage(beta, temperature(60))))
                .assertNext(message -> org.junit.jupiter.api.Assertions.assertEquals(beta, message.id()))
                .verifyComplete();
    }

    /**
     * A printer added while the page is open appears - the done-when, as a stream event.
     */
    @Test
    void aPrinterAddedWhileWatchingAppears() {
        StepVerifier.create(Flux.from(farm.updates()).take(1))
                .then(() -> theFarmHas("latecomer"))
                .assertNext(message -> {
                    var joined = org.junit.jupiter.api.Assertions.assertInstanceOf(
                            MachineJoinedFarm.class, message.event());
                    org.junit.jupiter.api.Assertions.assertEquals("latecomer", joined.name());
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
                    org.junit.jupiter.api.Assertions.assertEquals(doomed, message.id());
                    org.junit.jupiter.api.Assertions.assertInstanceOf(MachineLeftFarm.class, message.event());
                })
                .verifyComplete();
    }

    /**
     * A machine re-announcing itself is not a second arrival.
     *
     * <p>{@code MRegisteredEvent} is a statement of current fact, re-published for every machine at
     * startup and again whenever the roster learns something - so a dashboard would otherwise be
     * told a printer had just appeared every time it reported its firmware.
     */
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
                .assertNext(message -> org.junit.jupiter.api.Assertions.assertEquals(
                        "TemperatureReported", message.type(),
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
                .assertNext(message -> org.junit.jupiter.api.Assertions.assertInstanceOf(
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
     * The snapshot is built without blocking, asserted on a thread that will not permit it.
     *
     * <p><strong>This test exists because every other one here missed a live 500.</strong> The
     * first snapshot called {@code reactor.connection().block()}, and a snapshot is built on
     * whichever thread subscribed - which for an SSE endpoint is a netty event loop. Every test
     * above subscribes on an ordinary thread, where blocking is merely rude rather than fatal, so
     * the whole suite passed while {@code GET /api-farm/stream} answered
     * <em>"block()/blockFirst()/blockLast() are blocking, which is not supported in thread
     * reactor-http-nio-10"</em> to every browser that opened it.
     *
     * <p>{@code Schedulers.parallel()} is the cheap way to pin that: its workers implement
     * Reactor's {@code NonBlocking} marker, which is the same marker a netty event loop carries and
     * the same check that produced the 500. A machine with a runtime is required - a machine
     * without one never reaches the connection read at all.
     */
    @Test
    void theSnapshotDoesNotBlockTheThreadItIsBuiltOn() {
        var id = theFarmHas("alpha");
        reactors.openReactor(id);

        StepVerifier.create(Flux.from(farm.updates())
                        .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
                        .take(1))
                .assertNext(FarmEventsTest::assertState)
                .verifyComplete();
    }

    private static TemperatureReported temperature(double hotend) {
        return new TemperatureReported(new TemperatureReport(hotend, hotend, 100),
                new TemperatureReport(60, 60, 50));
    }
}

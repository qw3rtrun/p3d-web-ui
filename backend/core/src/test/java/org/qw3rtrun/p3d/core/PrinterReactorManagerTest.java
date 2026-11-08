package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.msg.MSpecDeclaredEvent;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reactor is a resource, and this is the thing that owns it. The map used to only ever grow:
 * deleting a printer left its reactor running (a TCP connection, a send window, a replay sink
 * holding every SSE subscriber), uncounted. No management module appears anywhere here - the
 * replica means this module is only ever told which machines exist, so a test tells it too, with no
 * database or service needed. Most of this opens no connection ({@code openReactor} no longer
 * dials); the two redial tests at the end are the exception and point at port 1 rather than
 * {@code theFarmHas}'s 8099, where a developer's Marlin simulator would actually answer.
 */
class PrinterReactorManagerTest {

    private final UUID id = UUID.randomUUID();
    private final PrinterDirectory directory = new PrinterDirectory();
    private final PrinterReactorManager manager =
            new PrinterReactorManager(directory, PrinterState::new, event -> {
            });

    // the manager a test builds for itself when it needs to hold the aggregate it asserts on, since
    // the redial tests open a runtime through an announcement rather than openReactor.
    private PrinterReactorManager dialled;

    // a reactor owns two schedulers, so a test that opens one and walks away leaks two threads;
    // idempotent and a no-op for a machine nothing opened.
    @AfterEach
    void letGo() {
        manager.release(id);
        if (dialled != null) {
            dialled.release(id);
        }
    }

    private void theFarmHas(UUID machine) {
        directory.onRegistered(new MRegisteredEvent(machine, "a printer", "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now()));
    }

    @Test
    void aSecondCallerGetsTheRunningReactorRatherThanANewOne() {
        theFarmHas(id);

        assertSame(manager.openReactor(id), manager.openReactor(id));
    }

    @Test
    void releasingLetsGoSoTheNextCallerStartsAFreshOne() {
        theFarmHas(id);
        var first = manager.openReactor(id);

        manager.release(id);

        assertNotSame(first, manager.openReactor(id));
    }

    @Test
    void releasingAPrinterNobodyEverOpenedIsANoOp() {
        manager.release(id);
        manager.release(id);
    }

    @Test
    void releasingTwiceIsIdempotent() {
        theFarmHas(id);
        manager.openReactor(id);

        manager.release(id);
        manager.release(id);
    }

    // distinct from "the farm has no such machine": it can also mean the registration has not
    // arrived here yet.
    @Test
    void anUnknownMachineCannotBeOpened() {
        assertThrows(IllegalArgumentException.class, () -> manager.openReactor(id));
    }

    // through an event rather than a call: printer-management must not reach into this module.
    @Test
    void aDeletedMachineReleasesItsRuntime() {
        theFarmHas(id);
        var first = manager.openReactor(id);

        var deleted = new MDeletedEvent(id, LocalDateTime.now());
        manager.onDeleted(deleted);
        directory.onDeleted(deleted);

        assertEquals(0, directory.size());
        assertThrows(IllegalArgumentException.class, () -> manager.openReactor(id));
        assertNotSame(first, null);
    }

    // has to: printer-management re-announces every row at startup, since a replica built from
    // events starts empty.
    @Test
    void beingToldAboutTheSameMachineTwiceIsFine() {
        theFarmHas(id);
        theFarmHas(id);

        assertEquals(1, directory.size());
    }

    // plan item B4: without this, a declared extruder count would reach the database and the
    // replica but not the object that refuses the command, so an edit would appear to do nothing
    // until a restart.
    @Test
    void anEditReachesAMachineThatIsAlreadyRunning() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var owner = inlineFarm(machine -> aggregates.computeIfAbsent(machine, PrinterState::new),
                ConnectionPolicy.standard());
        theFarmHas(id);
        owner.openReactor(id);
        assertEquals(1, aggregates.get(id).extruderCount(), "nothing declared, nothing reported");

        owner.onSpecDeclared(declaring(2));

        assertEquals(2, aggregates.get(id).extruderCount());
        owner.release(id);
    }

    /**
     * An operator's edit is not undone by an announcement built before it - issue #93's fourth root
     * cause, and the same mistake as {@code PrinterDirectory}'s tombstone seen from the other side:
     * a stale announcement applied unconditionally. The sequence here is the capture's, on one
     * machine over seven milliseconds: the roster re-publishes {@code MRegisteredEvent} about seven
     * times per connect from that machine's own listener thread, the {@code PUT} lands on a
     * {@code boundedElastic} worker in the middle of them, and the next straggler carries the spec
     * the roster had when <em>it</em> was read - {@code extruderCount=null} - 1.5 ms after the edit
     * and puts it back. The cost was permanent rather than late: {@code M104 S210 T1} was refused
     * with {@code This machine has only 1 extruders}, nothing reached the wire, and a command is
     * sent once and never retried.
     *
     * <p>Ordering cannot fix it, which is why there are two events: the straggler's own timestamp
     * is <em>later</em> than the edit's, because it is taken when the listener runs. See
     * {@code MSpecDeclaredEvent}'s KDoc.
     */
    @Test
    void aStaleAnnouncementDoesNotUndoAnOperatorsDeclaration() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var owner = inlineFarm(machine -> aggregates.computeIfAbsent(machine, PrinterState::new),
                ConnectionPolicy.standard());
        theFarmHas(id);
        owner.openReactor(id);
        owner.onSpecDeclared(declaring(2));
        assertEquals(2, aggregates.get(id).extruderCount(), "the premise is a declaration that took");

        owner.onRegistered(announcing(MachineSpec.undeclared()));

        assertEquals(2, aggregates.get(id).extruderCount(),
                "an announcement built before the edit put the old spec back, so the next command "
                        + "for tool 1 is refused and never sent again");
        owner.release(id);
    }

    /**
     * The other half of why dropping the announcement is safe: a machine standing up <em>after</em>
     * it was declared gets the declaration anyway, through the replica rather than through a
     * re-announcement. {@code CoreConfiguration.aggregates} is the production seeding lambda itself,
     * not a stand-in - the point is that nobody can later "simplify" it and silently take plan item
     * B4's startup half away, leaving a farm where an edit only reaches a machine that happened to
     * be running at the time.
     */
    @Test
    void aMachineDeclaredBeforeItWasRunningIsSeededWithThatSpec() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var seeding = new CoreConfiguration().aggregates(directory);
        var owner = inlineFarm(machine -> aggregates.computeIfAbsent(machine,
                it -> (PrinterState) seeding.loadState(it)), ConnectionPolicy.standard());
        directory.onRegistered(new MRegisteredEvent(id, "a printer", "localhost", 8099,
                MachineProfile.unknown(), new MachineSpec(null, 2, List.of(), Set.of(), Set.of()),
                MachineConnectionState.OFFLINE, LocalDateTime.now()));

        owner.openReactor(id);

        assertEquals(2, aggregates.get(id).extruderCount(),
                "a fresh aggregate takes its declared spec from the replica at construction, which "
                        + "is what makes the edit event's 'already running only' scope safe");
        owner.release(id);
    }

    private MSpecDeclaredEvent declaring(int extruders) {
        return new MSpecDeclaredEvent(id,
                new MachineSpec(null, extruders, List.of(), Set.of(), Set.of()), LocalDateTime.now());
    }

    // MRegisteredEvent is re-published for every machine at startup and on every firmware report;
    // starting a reactor for each would open a socket per row in the farm - the leak A3 closed.
    @Test
    void anAnnouncementDoesNotStartARuntimeForAMachineNobodyOpened() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var owner = new PrinterReactorManager(directory,
                machine -> aggregates.computeIfAbsent(machine, PrinterState::new), event -> {
        });
        theFarmHas(id);

        owner.onRegistered(announcing(new MachineSpec(null, 2, List.of(), Set.of(), Set.of())));

        assertTrue(aggregates.isEmpty(), "an announcement is not a reason to stand a printer up");
    }

    private MRegisteredEvent announcing(MachineSpec spec) {
        return new MRegisteredEvent(id, "a printer", "localhost", 8099,
                MachineProfile.unknown(), spec, MachineConnectionState.OFFLINE, LocalDateTime.now());
    }

    // five attempts an hour apart - in a test, one attempt and then nothing. The hour matters: with
    // a single attempt the refused dial on port 1 would end the series via gaveUp and race the
    // assertion; with more in hand it reaches doBeforeRetry instead, leaving the machine CONNECTING
    // and still wanting a connection regardless of timing.
    private static final ConnectionPolicy ONE_SLOW_SERIES =
            new ConnectionPolicy(5, Duration.ofHours(1), Duration.ofHours(1));

    // port 1, not theFarmHas's 8099: these two tests actually dial, and 8099 might have a real
    // simulator listening on a developer's machine. PrinterReactorEmissionTest does the same.
    private MRegisteredEvent announcing(MachineConnectionState state) {
        return new MRegisteredEvent(id, "a printer", "localhost", 1,
                MachineProfile.unknown(), MachineSpec.undeclared(), state, LocalDateTime.now());
    }

    /** One announcement down the whole listener chain, in Spring's order: the replica is {@code HIGHEST_PRECEDENCE} and the manager asks it where to dial. */
    private void announce(PrinterReactorManager farm, MRegisteredEvent event) {
        directory.onRegistered(event);
        farm.onRegistered(event);
    }

    /**
     * A farm whose machines all run on {@code Schedulers.immediate()} - the
     * {@link PrinterThreads#shared} seam. Since #80 a connect command and a spec edit are applied
     * on the machine's own thread rather than the caller's, so without this seam every assertion
     * below about an aggregate would be a race; with it the hop is inline and the aggregate can be
     * read on the next line. Only the tests that hold the aggregate need it.
     */
    private PrinterReactorManager inlineFarm(PrinterAggregateManager aggregates, ConnectionPolicy policy) {
        return new PrinterReactorManager(directory, aggregates, event -> {
        }, policy, PrinterThreads.shared(Schedulers.immediate()), Schedulers.parallel());
    }

    private PrinterReactorManager aFarmThatDials(Map<UUID, PrinterState> aggregates) {
        directory.onRegistered(announcing(MachineConnectionState.OFFLINE));
        dialled = inlineFarm(machine -> aggregates.computeIfAbsent(machine, PrinterState::new),
                ONE_SLOW_SERIES);
        return dialled;
    }

    /**
     * A stale announcement must not redial a machine that was told to disconnect - the
     * construction-time version of a test that previously only caught this by timing:
     * {@code ConnectivityIntegrationTest.disconnectingClosesTheSocket} in {@code :app} timed out
     * after thirty seconds, the aggregate having reached {@code OFFLINE} and been dialled straight
     * back out of it thirty times in one window. The sequence matches a real farm: the roster
     * republishes {@code MRegisteredEvent} about seven times per connect, queued on the listener
     * thread since #54 rather than running inline, so "it was linked" can arrive after a disconnect
     * asked for in between - always possible, but the hand-off made it happen every run. Observed
     * via the aggregate's intent and state, not a socket, since {@code handleConnectCmd} sets
     * {@code printer.handle(cmd)} synchronously before anything touches netty.
     */
    @Test
    void aStaleAnnouncementDoesNotRedialAMachineThatWasToldToDisconnect() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var farm = aFarmThatDials(aggregates);
        var linked = announcing(MachineConnectionState.IDLE);
        farm.onRegistered(linked);

        farm.runtimeOf(id).handleConnectCmd(new ConnectCmd(false));
        farm.onRegistered(linked);

        assertEquals(MachineConnectionState.OFFLINE, aggregates.get(id).getConnection().state(),
                "a republication that arrived late is not a reason to dial");
        assertFalse(aggregates.get(id).wantsToBeConnected(),
                "and it is not a reason to want a connection either");
    }

    /**
     * A deleted machine is not dialled again by an announcement that arrived after its deletion -
     * issue #93, the damage half of {@code PrinterDirectoryTest}'s tombstone. Measured over five
     * milliseconds on one machine: the directory forgot it, the manager released its runtime, and a
     * straggler from the same listener backlog put the entry back and had the manager open a
     * <em>second</em> runtime - which nothing would ever release, since the {@code MDeletedEvent}
     * that would have was long gone, so it held a socket, a send window, a replay sink and two
     * threads and went on redialling the closed port for another 45 seconds into the test classes
     * that followed. The sequence here is the farm's own: announce, delete both sides, re-announce.
     *
     * <p>Asserted on the runtime rather than on a mock's call count - {@code runtimeOf} answering
     * null is the observable that means no socket was opened.
     */
    @Test
    void aStaleAnnouncementDoesNotRedialAMachineTheFarmHasDeleted() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var farm = aFarmThatDials(aggregates);
        var linked = announcing(MachineConnectionState.IDLE);
        announce(farm, linked);
        assertNotNull(farm.runtimeOf(id), "the premise is a machine that was running");

        var deleted = new MDeletedEvent(id, LocalDateTime.now());
        directory.onDeleted(deleted);
        farm.onDeleted(deleted);

        announce(farm, linked);

        assertTrue(directory.find(id).isEmpty(), "the farm deleted it, so the replica has let go");
        assertNull(farm.runtimeOf(id),
                "a second runtime was opened for a deleted machine, and nothing will ever release"
                        + " it - it holds a socket and two threads and keeps redialling a dead port");
    }

    /**
     * The interleaving the listener ordering exists for: the two delete listeners run on one
     * thread, but a stale announcement arrives on the machine's own listener thread and can land
     * between them. With the replica's {@code onDeleted} ordered first the tombstone is already
     * written, so there is no window in which the entry is present and the runtime has gone.
     */
    @Test
    void aStaleAnnouncementLandingInsideTheDeleteDoesNotDialEither() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var farm = aFarmThatDials(aggregates);
        var linked = announcing(MachineConnectionState.IDLE);
        announce(farm, linked);
        var deleted = new MDeletedEvent(id, LocalDateTime.now());

        directory.onDeleted(deleted);
        announce(farm, linked);
        farm.onDeleted(deleted);

        assertTrue(directory.find(id).isEmpty());
        assertNull(farm.runtimeOf(id), "the runtime released by the delete must stay released");
    }

    /**
     * The first announcement of a linked machine does dial it, which is the whole point. Nothing
     * covered this before - {@code StartupAnnouncementFlowTest} in {@code :app} stops at the
     * directory entry's state - so the intent gate in {@code PrinterReactor.connect()} could have
     * killed the startup redial outright and the suite would have stayed green. Also pins the
     * non-staleness half of the fix: the redial crosses the aggregate, so the intent is set, which
     * it never used to be - leaving {@code connect()}'s retry filter asking about an intent nobody
     * had set, so a machine redialled at startup gave up after its first refusal.
     */
    @Test
    void aFirstAnnouncementOfALinkedMachineDialsIt() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var farm = aFarmThatDials(aggregates);

        farm.onRegistered(announcing(MachineConnectionState.IDLE));

        assertNotNull(farm.runtimeOf(id), "the startup redial is what stands the runtime up");
        assertTrue(aggregates.get(id).wantsToBeConnected(),
                "the redial is a command, so it sets the intent the retry filter reads");
        assertEquals(MachineConnectionState.CONNECTING, aggregates.get(id).getConnection().state());
    }

    // an SSE subscriber is a browser holding a connection; abandoning the sink leaves it waiting on
    // a machine that no longer exists, and Sinks.Many keeps it referenced until it terminates.
    @Test
    void aReleasedReactorCompletesItsUpdateStream() {
        theFarmHas(id);
        var updates = manager.openReactor(id).updates();

        StepVerifier.create(updates)
                .expectNextCount(1)
                .then(() -> manager.release(id))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- releaseAll() - issue #87

    // release(UUID) for the whole farm, not one machine - every running printer's own stream has to
    // notice, not just the first one touched.
    @Test
    void releaseAllCompletesTheUpdateStreamOfEveryRunningPrinter() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        theFarmHas(first);
        theFarmHas(second);
        var firstUpdates = manager.openReactor(first).updates();
        var secondUpdates = manager.openReactor(second).updates();

        // both subscribed before releaseAll() runs, since updates() ends with publishOn(executor),
        // which releaseAll() disposes once its StreamEnd has drained; Flux.merge subscribes to both
        // at once, proving neither stream was merely the first one touched.
        StepVerifier.create(Flux.merge(firstUpdates, secondUpdates))
                .expectNextCount(2)
                .then(manager::releaseAll)
                .verifyComplete();
    }

    // issue #87: a request racing the web server's shutdown must not build a fresh reactor into a
    // farm that has said goodbye; knows(id) is exempt since it opens nothing and a cancel still
    // deserves a truthful answer.
    @Test
    void afterReleaseAllOpenReactorIfKnownIsEmptyAndRuntimeOfIsNullWhileKnowsIsStillTrue() {
        theFarmHas(id);
        manager.openReactor(id);

        manager.releaseAll();

        assertTrue(manager.openReactorIfKnown(id).isEmpty(),
                "a shut-down farm must not open a reactor for a request racing the shutdown");
        assertNull(manager.runtimeOf(id), "nothing is running any more");
        assertTrue(manager.knows(id),
                "the directory still knows the machine; releaseAll() closes runtimes, not the roster");
        assertThrows(IllegalArgumentException.class, () -> manager.openReactor(id),
                "a shut-down farm answers like an unknown machine, not like a working one");
    }

    // does not try to park a second terminal signal on a reactor already told to stop.
    @Test
    void releaseAllTwiceDoesNotThrowOrEmitASecondTerminalSignal() {
        theFarmHas(id);
        var updates = manager.openReactor(id).updates();

        StepVerifier.create(updates)
                .expectNextCount(1)
                .then(() -> {
                    manager.releaseAll();
                    manager.releaseAll();
                })
                .verifyComplete();
    }

    /**
     * {@code releaseAll()} waits for the printers it releases, not merely parks a terminal signal.
     * {@code completed.getCount()} is read the instant {@code releaseAll()} returns, with no
     * {@code await} between: {@code doFinally} runs strictly after its downstream has received the
     * terminal signal (Reactor's own ordering), so if {@code releaseAll()} already saw
     * {@code awaitSubscribersGone} answer true, this subscriber's {@code onComplete} has
     * unquestionably already run.
     */
    @Test
    void releaseAllWaitsForThePrintersItReleased() {
        theFarmHas(id);
        var completed = new CountDownLatch(1);
        var subscription = manager.openReactor(id).updates()
                .subscribe(message -> {
                }, failure -> completed.countDown(), completed::countDown);

        manager.releaseAll();

        assertEquals(0, completed.getCount(),
                "releaseAll() returned before this printer's own update stream had actually "
                        + "finished at its subscriber - it is supposed to wait for exactly that");
        subscription.dispose();
    }

    /**
     * {@code releaseAll()} must not hold the manager's monitor across its own bounded wait - the
     * worse mistake, since it would block every concurrent {@link PrinterReactorManager#knows(UUID)}
     * or {@link PrinterReactorManager#runtimeOf(UUID)} caller for the whole release budget. The hold
     * is real, not hoped for: a genuine backlog (five firmware reports) is queued on the live sink
     * before {@code stop()} runs, drained one item at a time with a pause
     * ({@code PrinterReactorAwaitSubscribersGoneTest}'s mechanism), so with a release budget far
     * shorter than the drain, {@code releaseAll()} is certain to still be inside its wait when the
     * second thread below calls - a prompt answer proves the monitor was free.
     */
    @Test
    void releaseAllDoesNotHoldTheMonitorWhileItWaits() throws InterruptedException {
        var aggregates = new HashMap<UUID, PrinterState>();
        var releaseBudget = Duration.ofSeconds(2);
        var testManager = new PrinterReactorManager(directory,
                machine -> aggregates.computeIfAbsent(machine, PrinterState::new), event -> {
        }, ConnectionPolicy.standard(), PrinterThreads.virtualPerPrinter(), Schedulers.parallel(),
                releaseBudget);
        theFarmHas(id);
        var reactor = testManager.openReactor(id);

        var firstMessage = new CountDownLatch(1);
        var slow = new BaseSubscriber<EventMessage>() {
            @Override
            protected void hookOnSubscribe(Subscription s) {
                request(1);
            }

            @Override
            protected void hookOnNext(EventMessage message) {
                firstMessage.countDown();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                request(1);
            }
        };
        reactor.updates().subscribe(slow);
        assertTrue(firstMessage.await(10, TimeUnit.SECONDS),
                "the snapshot never arrived, so nothing is actually watching this machine");

        for (var i = 0; i < 5; i++) {
            aggregates.get(id).onEvent(new FirmwareInfoRs(Map.of("FIRMWARE_NAME", "Marlin 2.1." + i)));
        }

        var releaseStarted = new CountDownLatch(1);
        var releaseReturned = new CountDownLatch(1);
        var releasing = new Thread(() -> {
            releaseStarted.countDown();
            testManager.releaseAll();
            releaseReturned.countDown();
        }, "releasing");
        releasing.start();
        assertTrue(releaseStarted.await(5, TimeUnit.SECONDS), "releaseAll() was never even started");
        // gives releaseAll() time to flip shuttingDown and enter its bounded wait - the backlog
        // takes at least 1.5s to drain, so this is comfortably inside that window.
        Thread.sleep(200);
        assertFalse(releaseReturned.await(0, TimeUnit.MILLISECONDS),
                "releaseAll() returned before this test could even ask whether it held the monitor");

        var answered = new CountDownLatch(1);
        var started = System.nanoTime();
        var answering = new Thread(() -> {
            testManager.knows(UUID.randomUUID());
            testManager.runtimeOf(id);
            answered.countDown();
        }, "answering");
        answering.start();
        var wasAnswered = answered.await(500, TimeUnit.MILLISECONDS);
        var answeredWithin = Duration.ofNanos(System.nanoTime() - started);

        releasing.join(TimeUnit.SECONDS.toMillis(10));
        answering.join(TimeUnit.SECONDS.toMillis(10));
        slow.dispose();

        assertTrue(wasAnswered, "a concurrent caller was blocked behind releaseAll()'s own bounded "
                + "wait for " + answeredWithin.toMillis() + "ms - the monitor must not be held "
                + "across it");
        assertTrue(answeredWithin.compareTo(releaseBudget) < 0,
                "the concurrent call took " + answeredWithin.toMillis() + "ms, as long as "
                        + "releaseAll()'s own budget - it was almost certainly blocked behind the "
                        + "monitor rather than answered directly");
    }
}

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
 * A reactor is a resource, and this is the thing that owns it.
 *
 * <p>The map used to only ever grow. Deleting a printer through the management API left its reactor
 * running - a TCP connection to a machine the farm no longer knew about, a send window, and a
 * replay sink holding every SSE subscriber that had ever attached. Nothing noticed, because nothing
 * ever counted them.
 *
 * <p><strong>There is no management module anywhere in this test.</strong> That is the point of the
 * replica rather than a convenience: this module is told which machines exist, so a test tells it,
 * and nothing here needs a database, a repository or a service to stand a printer up. It used to
 * need a fake {@code MachineManagementService} - or, before that, a fake command gateway - purely
 * to answer one question about where to dial.
 *
 * <p>No connection is opened by most of this: {@code openReactor} no longer dials anything, and
 * {@link org.qw3rtrun.p3d.terminal.TerminalManager}'s constructor only builds a {@code TcpClient}.
 * The two redial tests at the end are the exception - dialling is the behaviour they are about -
 * and they point at port 1 rather than at {@code theFarmHas}'s 8099, where the Marlin simulator a
 * developer has running would answer for real.
 */
class PrinterReactorManagerTest {

    private final UUID id = UUID.randomUUID();
    private final PrinterDirectory directory = new PrinterDirectory();
    private final PrinterReactorManager manager =
            new PrinterReactorManager(directory, PrinterState::new, event -> {
            });

    /**
     * The manager a test builds for itself, when it needs to hold the aggregate it asserts on.
     *
     * <p>Released below alongside the shared one. The redial tests open a runtime through an
     * announcement rather than through {@code openReactor}, so nothing else in the test would
     * know to let its two threads and its retry subscription go.
     */
    private PrinterReactorManager dialled;

    /**
     * A reactor now owns two schedulers, so a test that opens one and walks away leaks two threads.
     *
     * <p>Idempotent and a no-op for a machine nothing opened, which is most of these. Virtual
     * threads are daemon so a leak would not hang the suite - this is tidiness, and a reminder that
     * {@code release} is the pair of {@code openReactor} for threads now as well as for sockets.
     */
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

    /**
     * A machine this module has not been told about cannot be opened.
     *
     * <p>Distinct from "the farm has no such machine": it can also mean the registration has not
     * arrived here yet. Either way the caller gets an answer rather than a reactor pointed at
     * nowhere.
     */
    @Test
    void anUnknownMachineCannotBeOpened() {
        assertThrows(IllegalArgumentException.class, () -> manager.openReactor(id));
    }

    /**
     * Deleting the machine releases its runtime, through an event rather than a call.
     *
     * <p>{@code printer-management} must not reach into this module, and the two facts are separate
     * anyway: the row is gone whether or not anything was holding a socket to it.
     */
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

    /**
     * The replica takes the same machine twice without complaint.
     *
     * <p>It has to: {@code printer-management} re-announces every row at startup, because a replica
     * built from events is empty when the process begins. {@code MRegisteredEvent} is therefore a
     * statement of current fact, and arriving twice is the normal case rather than a fault.
     */
    @Test
    void beingToldAboutTheSameMachineTwiceIsFine() {
        theFarmHas(id);
        theFarmHas(id);

        assertEquals(1, directory.size());
    }

    /**
     * An edit reaches a machine that is already running (plan item B4).
     *
     * <p>An aggregate is seeded with the spec when it is built, which covers a restart and nothing
     * else. Without this, declaring a second extruder on a printer that is connected right now
     * would reach the database and the replica but not the object that refuses the command - so
     * the edit would appear to do nothing until the farm was restarted, which is the same shape of
     * defect as the write-only {@code connected} column plan item B3 removed.
     */
    @Test
    void anEditReachesAMachineThatIsAlreadyRunning() {
        var aggregates = new HashMap<UUID, PrinterState>();
        var owner = new PrinterReactorManager(directory,
                machine -> aggregates.computeIfAbsent(machine, PrinterState::new), event -> {
        });
        theFarmHas(id);
        owner.openReactor(id);
        assertEquals(1, aggregates.get(id).extruderCount(), "nothing declared, nothing reported");

        owner.onRegistered(announcing(new MachineSpec(null, 2, List.of(), Set.of(), Set.of())));

        assertEquals(2, aggregates.get(id).extruderCount());
        owner.release(id);
    }

    /**
     * And it does not build one for a machine nobody opened.
     *
     * <p>{@code MRegisteredEvent} is re-published for every machine at startup and again on every
     * firmware report. Starting a reactor for each would open a socket, a send window and a sink
     * for every row in the farm, which is precisely the leak plan item A3 closed.
     */
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

    /**
     * Five attempts an hour apart, which in a test means one attempt and then nothing.
     *
     * <p>The dial below is refused - nothing listens on port 1 - and what that refusal does is the
     * reason for the hour. With a single-attempt policy the series would end on the refusal,
     * {@code gaveUp} would take the intent away, and a test asserting the intent would be racing a
     * netty thread. With more attempts in hand the refusal reaches {@code doBeforeRetry} instead,
     * which leaves the machine in {@code CONNECTING} and still wanting a connection - so both
     * assertions below hold whichever side of the race the dial lands on.
     */
    private static final ConnectionPolicy ONE_SLOW_SERIES =
            new ConnectionPolicy(5, Duration.ofHours(1), Duration.ofHours(1));

    /**
     * Port 1, because the owner's Marlin simulator really is listening on 8099 on this machine.
     *
     * <p>{@code theFarmHas} points at 8099, which is fine for every test above because none of
     * them dials. These two do, so they point somewhere nothing can answer -
     * {@code PrinterReactorEmissionTest} uses the same port for the same reason.
     */
    private MRegisteredEvent announcing(MachineConnectionState state) {
        return new MRegisteredEvent(id, "a printer", "localhost", 1,
                MachineProfile.unknown(), MachineSpec.undeclared(), state, LocalDateTime.now());
    }

    private PrinterReactorManager aFarmThatDials(Map<UUID, PrinterState> aggregates) {
        directory.onRegistered(announcing(MachineConnectionState.OFFLINE));
        dialled = new PrinterReactorManager(directory,
                machine -> aggregates.computeIfAbsent(machine, PrinterState::new), event -> {
        }, ONE_SLOW_SERIES);
        return dialled;
    }

    /**
     * A stale announcement must not redial a machine that was told to disconnect.
     *
     * <p><strong>This is the construction-time version of a test that previously only caught it by
     * timing.</strong> {@code ConnectivityIntegrationTest.disconnectingClosesTheSocket} in
     * {@code :app} failed with "timed out after PT30S waiting for the machine to report OFFLINE":
     * the aggregate reached {@code OFFLINE} immediately and was dialled straight back out of it,
     * thirty times in one test window, so the await expired on a machine that never settled.
     *
     * <p>The sequence is the one that happens on a real farm. The roster republishes
     * {@code MRegisteredEvent} about seven times per connect - once per firmware report, once per
     * capability - and since #54 those republications are queued on the printer's listener thread
     * rather than running inline on the aggregate's, so a copy of "it was linked" can arrive
     * <em>after</em> a disconnect the user asked for in between. It always could; the hand-off
     * changed the interleaving from unlikely to every run.
     *
     * <p>The observable is the aggregate's intent and its state, not whether a socket opened.
     * {@code handleConnectCmd} puts {@code printer.handle(cmd)} first, synchronously on the
     * calling thread and before anything touches netty, so a redial that happened at all would be
     * visible here whatever the dial did afterwards.
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
     * The first announcement of a linked machine does dial it, which is the whole point.
     *
     * <p>Nothing covered this before. {@code StartupAnnouncementFlowTest} in {@code :app} asserts
     * the directory entry's state and stops there, and no test anywhere said that this branch
     * opens a runtime or asks it for a connection - so the intent gate in
     * {@code PrinterReactor.connect()} could have killed the startup redial outright and the whole
     * suite would have stayed green.
     *
     * <p>It also pins the half of the fix that is not about staleness: the redial crosses the
     * aggregate now, so the intent is <em>set</em>. It never used to be, which left
     * {@code connect()}'s own retry filter asking whether a connection was wanted on a machine
     * nobody had said so about - and a machine redialled at startup therefore gave up after its
     * first refusal.
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

    /**
     * A released reactor tells its subscribers it is over.
     *
     * <p>An SSE subscriber is a browser holding a connection. Abandoning the sink leaves it waiting
     * on a machine that no longer exists, and {@code Sinks.Many} keeps every one of them referenced
     * until it terminates - so this is both a correctness and a leak question.
     */
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

    /**
     * {@code releaseAll()} is {@code release(UUID)} for the whole farm, not for one machine - and
     * every running printer's own stream has to notice, not just the first one it happens to touch.
     */
    @Test
    void releaseAllCompletesTheUpdateStreamOfEveryRunningPrinter() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        theFarmHas(first);
        theFarmHas(second);
        var firstUpdates = manager.openReactor(first).updates();
        var secondUpdates = manager.openReactor(second).updates();

        // Both subscribed before releaseAll() runs, not after: updates() ends with
        // publishOn(executor), the aggregate's own scheduler, which releaseAll() disposes once its
        // StreamEnd has drained - so a subscription built afterwards has nothing left to be
        // scheduled on. Flux.merge subscribes to both sources at once, which is what proves
        // neither reactor's stream was merely the first one touched.
        StepVerifier.create(Flux.merge(firstUpdates, secondUpdates))
                .expectNextCount(2)
                .then(manager::releaseAll)
                .verifyComplete();
    }

    /**
     * The gate {@link PrinterReactorManager#releaseAll()} closes behind it - issue #87.
     *
     * <p>A request racing the web server's own shutdown must not be able to build a fresh reactor
     * into a farm that has already said goodbye to every other one; {@code knows(id)} is
     * deliberately exempt, because it opens nothing and a cancel still deserves a truthful answer
     * about whether this module has ever heard of the machine.
     */
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

    /**
     * A second {@code releaseAll()} releases nothing and does not try to park a second terminal
     * signal on a reactor that has already been told to stop.
     */
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
     * {@code releaseAll()} does not merely park a terminal signal for the printers it releases, it
     * waits for them - the second-pass fix for the flake recorded against this issue.
     *
     * <p><strong>The assertion is on timing relative to the call, not on eventual truth.</strong>
     * {@code completed.getCount()} is read the instant {@code releaseAll()} returns, with no
     * {@code await} in between: a subscriber's {@code doFinally} runs strictly <em>after</em> its
     * downstream has already received the terminal signal (Reactor's own ordering for
     * {@code doFinally}), so if {@code releaseAll()} has already seen
     * {@code awaitSubscribersGone} answer {@code true} for this printer, this subscriber's
     * {@code onComplete} has unquestionably already run.
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
     * mistake the brief for this pass warns is worse than the bug being fixed: a shutdown that
     * blocks every concurrent {@link PrinterReactorManager#knows(UUID)} or
     * {@link PrinterReactorManager#runtimeOf(UUID)} caller for the whole release budget.
     *
     * <p><strong>The hold is real, not hoped for.</strong> A genuine backlog - five firmware reports
     * - is queued on the live sink before {@code stop()} runs, and the one subscriber drains it one
     * item at a time with a pause, exactly {@code PrinterReactorAwaitSubscribersGoneTest}'s
     * mechanism. With a release budget far shorter than the backlog takes to drain,
     * {@code releaseAll()} is certain to still be inside its bounded wait when the second thread
     * below makes its call - so an answer that arrives promptly is this test's proof that the
     * monitor was free, not a coincidence of scheduling.
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
        // Give releaseAll() time to flip shuttingDown, drop the monitor and enter its bounded wait -
        // the backlog above takes at least 1.5s to drain, so this is comfortably inside that window.
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

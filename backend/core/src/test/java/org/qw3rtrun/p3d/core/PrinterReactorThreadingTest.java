package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MFirmwareReportedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.msg.ManagementEvent;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A printer gets its own thread, and its listeners get another one - issue #54. Before this, every
 * {@code PrinterReactor} was handed {@code Schedulers.single()} (the JVM-wide shared scheduler, not
 * a fresh thread), so every machine's replies, every command and every roster
 * {@code @Transactional} write shared one thread - invisible with one printer in the test farm,
 * the farm's bottleneck with forty auto-reporting once a second. Four properties, each with a hard
 * bound so a broken one fails rather than hangs: one machine's slow hop does not delay another's
 * replies; a slow listener still sees one aggregate's events in the order produced; a released
 * printer leaves no thread behind; and it does not, even when the release races another thread
 * mid-emission. Ordering is per aggregate and deliberately not global - nothing in the farm decides
 * anything from "A spoke before B" - so one test asserts in-order for a single machine and another
 * asserts two machines are explicitly not ordered against each other.
 */
class PrinterReactorThreadingTest {

    private final PrinterDirectory directory = new PrinterDirectory();

    private final Map<UUID, PrinterState> aggregates = new ConcurrentHashMap<>();

    // frees any listener this test deliberately parked; counted down before teardown runs, so a
    // failed assertion ends the test instead of leaving a stuck listener blocking teardown.
    private final CountDownLatch release = new CountDownLatch(1);

    private final List<Runnable> teardown = new CopyOnWriteArrayList<>();

    @AfterEach
    void letGo() {
        release.countDown();
        teardown.forEach(Runnable::run);
    }

    private UUID theFarmHas(String name) {
        var id = UUID.randomUUID();
        directory.onRegistered(new MRegisteredEvent(id, name, "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now()));
        return id;
    }

    private PrinterState aggregate(UUID id) {
        return aggregates.computeIfAbsent(id, PrinterState::new);
    }

    // a distinguishable reply the aggregate will not suppress: PrinterState.on(FirmwareInfoRs) only
    // announces a changed profile, so each needs a different firmware string.
    private static FirmwareInfoRs firmware(String which) {
        return new FirmwareInfoRs(Map.of("FIRMWARE_NAME", which));
    }

    /** Which machine an event published to the application bus is about, or null. */
    private static UUID machineIn(Object event) {
        return switch (event) {
            case EventMessage message -> message.id();
            case ManagementEvent management -> management.getMachineId();
            default -> null;
        };
    }

    private PrinterReactorManager managerOn(PrinterThreads threads, ApplicationEventPublisher roster) {
        return new PrinterReactorManager(directory, this::aggregate, roster,
                ConnectionPolicy.standard(), threads, Schedulers.parallel());
    }

    // opens a machine's runtime and keeps every update, dropping the replay seed (the current
    // connection state every subscriber gets on attach) - waiting for it also proves the
    // subscription is live before the test emits anything.
    private LinkedBlockingQueue<EventMessage> watch(PrinterReactorManager manager, UUID id)
            throws InterruptedException {
        var seen = new LinkedBlockingQueue<EventMessage>();
        Disposable subscription = manager.openReactor(id).updates().subscribe(seen::add);
        teardown.add(subscription::dispose);
        assertNotNull(seen.poll(10, TimeUnit.SECONDS),
                "the replay seed never arrived, so nothing is watching " + id);
        return seen;
    }

    /**
     * One machine's slow hop does not delay another machine's replies - and does not delay its own.
     * Machine A's roster listener is held on a latch (standing in for a slow {@code @Transactional}
     * write); machine B's reply then has to reach B's own subscriber while A's listener is stuck.
     * Replies are driven onto each machine's own aggregate scheduler, the same hop
     * {@code PrinterReactor.gEvent()} makes with {@code publishOn(executor)} - faithful only because
     * if both printers shared one scheduler (as before #54, and as
     * {@link PrinterThreads#shared(reactor.core.scheduler.Scheduler)} still arranges), B's reply
     * would queue behind A's stuck task and the test would hang rather than fail correctly. The
     * second half asserts A's own replies keep flowing while A's own listener is stuck (property 2,
     * reusing the same stuck listener). It checks what arrived, not how many: counting two messages
     * would pass today only because {@code PrinterState.heard()} happens to announce nothing for an
     * {@code OFFLINE} machine, and a lifecycle change nearby could make {@code alpha-1} alone produce
     * two while {@code alpha-2} never arrives, with the count still passing.
     */
    @Test
    void oneMachinesStuckListenerDelaysNeitherTheOtherMachineNorItsOwnReplies() throws Exception {
        var alpha = theFarmHas("alpha");
        var beta = theFarmHas("beta");
        var threads = new Recorded(PrinterThreads.virtualPerPrinter());
        var listenerIsStuck = new CountDownLatch(1);
        var manager = managerOn(threads, event -> {
            if (alpha.equals(machineIn(event))) {
                listenerIsStuck.countDown();
                park(release);
            }
        });
        teardown.add(() -> {
            manager.release(alpha);
            manager.release(beta);
        });

        var fromAlpha = watch(manager, alpha);
        var fromBeta = watch(manager, beta);

        threads.aggregateOf(alpha).schedule(() -> aggregate(alpha).onEvent(firmware("alpha-1")));
        assertTrue(listenerIsStuck.await(10, TimeUnit.SECONDS),
                "alpha's roster listener was never reached, so nothing is being held and the rest "
                        + "of this test would prove nothing");

        threads.aggregateOf(beta).schedule(() -> aggregate(beta).onEvent(firmware("beta-1")));
        var betasReply = fromBeta.poll(5, TimeUnit.SECONDS);
        assertNotNull(betasReply, "beta's reply never reached beta's subscriber while alpha's "
                + "listener was held - the farm is back on one thread");
        assertEquals(beta, betasReply.id());

        threads.aggregateOf(alpha).schedule(() -> aggregate(alpha).onEvent(firmware("alpha-2")));
        var secondFromAlpha = drain(fromAlpha, 2, Duration.ofSeconds(5));
        assertTrue(carries(secondFromAlpha, alpha, firmware("alpha-2")),
                "alpha's own replies have to keep flowing while alpha's listener is stuck: a "
                        + "listener is not allowed to stop the machine it is listening to. "
                        + "Expected alpha-2's FirmwareInfoRs on alpha's stream; it carried "
                        + secondFromAlpha);
    }

    // whether one machine's stream actually carried this reply, rather than that many arrived.
    private static boolean carries(List<EventMessage> messages, UUID machine, Object event) {
        return messages.stream()
                .anyMatch(message -> machine.equals(message.id()) && event.equals(message.event()));
    }

    /**
     * A few hundred events out of one aggregate reach a slow listener in the order it produced them -
     * held by each machine having exactly one listener consumer thread, not by {@code @Async} (a
     * {@code ThreadPoolTaskExecutor} with more than one thread reorders freely). Asserted on the
     * roster's facts, not the farm stream: the farm queue is bounded and allowed to drop (and will,
     * genuinely, at three hundred events against a slow listener), while the roster queue is
     * lossless, so it alone can promise "exactly these, in exactly this order". Uses
     * {@code FirmwareInfoRs} because the aggregate emits one per distinct firmware string with no
     * socket, retry or clock needed.
     */
    @Test
    void oneAggregatesEventsReachASlowListenerInOrder() throws Exception {
        var machine = theFarmHas("alpha");
        var events = 300;
        var heard = new ArrayList<String>();
        var all = new CountDownLatch(events);
        var manager = managerOn(PrinterThreads.virtualPerPrinter(), event -> {
            if (event instanceof MFirmwareReportedEvent report) {
                // slow enough that two consumer threads would overtake each other; short enough that
                // three hundred of these are a fraction of a second.
                LockSupport.parkNanos(300_000L);
                heard.add(report.firmware().get("FIRMWARE_NAME"));
                all.countDown();
            }
        });
        teardown.add(() -> manager.release(machine));
        manager.openReactor(machine);

        var produced = new ArrayList<String>();
        for (var i = 0; i < events; i++) {
            var which = "Marlin 2.1." + i;
            produced.add(which);
            aggregate(machine).onEvent(firmware(which));
        }

        assertTrue(all.await(30, TimeUnit.SECONDS),
                "only " + (events - all.getCount()) + " of " + events
                        + " roster facts arrived - the lossless hand-off dropped some");
        assertEquals(produced, heard,
                "the roster saw this machine's facts in a different order from the one the "
                        + "aggregate decided them in");
    }

    /**
     * A released printer leaves no thread behind - a scheduler created per reactor and never
     * disposed is a thread per deleted printer, the same leak {@code release()} closes for sockets
     * and sinks. Platform threads stand in for virtual ones, the only dishonest part: virtual threads
     * are not enumerable by any supported API, but {@code stop()} disposing the scheduler and
     * {@code shutdownNow()} terminating the executor's worker is identical either way, so
     * {@code Thread.ofPlatform()...daemon()} (daemon, so a leak fails rather than hangs the JVM)
     * stands in for {@code Thread.ofVirtual()}. {@link #bothSchedulersOfAReleasedPrinterAreDisposed()}
     * is the production-exact half of the same claim.
     */
    @Test
    void aReleasedPrinterLeavesNoThreadBehind() throws Exception {
        var machine = theFarmHas("alpha");
        var aggregateThread = "p3d-printer-" + machine;
        var listenerThread = "p3d-listener-" + machine;
        var threads = new Recorded(new PrinterThreads() {

            @Override
            public Scheduler aggregateOf(UUID printer) {
                return platform("p3d-printer-" + printer);
            }

            @Override
            public Scheduler listenersOf(UUID printer) {
                return platform("p3d-listener-" + printer);
            }
        });
        var manager = managerOn(threads, event -> {
        });
        teardown.add(() -> manager.release(machine));

        var reactor = manager.openReactor(machine);
        // a single-thread executor's thread exists once given something to do: a connection query
        // uses publishOn(aggregate), an event puts items on the listener hand-off.
        assertNotNull(reactor.connection().block(Duration.ofSeconds(10)));
        aggregate(machine).onEvent(firmware("Marlin 2.1.2"));
        assertTrue(awaitThread(aggregateThread, true), "the aggregate's thread was never started");
        assertTrue(awaitThread(listenerThread, true), "the listener's thread was never started");

        manager.release(machine);

        assertTrue(awaitThread(aggregateThread, false),
                "the aggregate's thread outlived the printer it was named for");
        assertTrue(awaitThread(listenerThread, false),
                "the listener's thread outlived the printer it was named for");
    }

    // the production factory's own schedulers are the ones disposed - nothing substituted, recorded
    // on the way out so the test holds the same two objects the reactor was given.
    @Test
    void bothSchedulersOfAReleasedPrinterAreDisposed() throws Exception {
        var machine = theFarmHas("alpha");
        var threads = new Recorded(PrinterThreads.virtualPerPrinter());
        var manager = managerOn(threads, event -> {
        });
        teardown.add(() -> manager.release(machine));

        var reactor = manager.openReactor(machine);
        assertNotNull(reactor.connection().block(Duration.ofSeconds(10)));
        assertFalse(threads.aggregateOf(machine).isDisposed(),
                "nothing has released this printer yet, so the test below would prove nothing");

        manager.release(machine);

        assertTrue(awaitDisposed(threads.aggregateOf(machine)),
                "the aggregate's scheduler was never disposed, so its thread is still there");
        assertTrue(awaitDisposed(threads.listenersOf(machine)),
                "the listener's scheduler was never disposed, so its thread is still there");
    }

    /**
     * A release that lands while another thread is emitting still completes the update stream - one
     * thread later than the bug {@code disposeWhenIdle} closed. {@code stop()} only parks a
     * {@code StreamEnd} in the outbox, and {@code Outbox.emit} drains only when nobody else already
     * is; a machine deleted mid-conversation has its own thread inside that loop publishing a reply,
     * so {@code stop()} returns with the marker still queued, the disposal runs at once on an empty
     * queue, and the {@code onComplete} produced a moment later by the other thread is submitted to a
     * scheduler that has already gone - a browser on a just-deleted printer then waits forever.
     * {@code PrinterReactorManagerTest.aReleasedReactorCompletesItsUpdateStream} does not catch this
     * because nothing else is emitting there and the ordering comes out right by accident. The
     * collision is forced, not hoped for, the way {@code PrinterReactorEmissionTest} forces its own:
     * a subscriber blocks inside {@code tryEmitNext}, holding the emitting thread inside the drain,
     * while the release happens from this thread.
     *
     * @see #onTheCallersThread()
     */
    @Test
    void aStopThatRacesAnEmissionStillCompletesTheUpdateStream() throws Exception {
        var machine = theFarmHas("alpha");
        var threads = new Recorded(onTheCallersThread());
        var manager = managerOn(threads, event -> {
        });
        var insideTheSink = new CountDownLatch(1);
        var releaseHasReturned = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var failed = new AtomicReference<Throwable>();
        var seen = new LinkedBlockingQueue<EventMessage>();
        // first, so a failed assertion frees the held thread before anything else tears down.
        teardown.add(releaseHasReturned::countDown);
        teardown.add(() -> manager.release(machine));

        Disposable subscription = manager.openReactor(machine).updates().subscribe(
                message -> {
                    seen.add(message);
                    if (message.event() instanceof FirmwareInfoRs) {
                        insideTheSink.countDown();
                        park(releaseHasReturned);
                    }
                },
                failed::set,
                completed::countDown);
        teardown.add(subscription::dispose);
        assertNotNull(seen.poll(10, TimeUnit.SECONDS),
                "the replay seed never arrived, so nothing is watching " + machine);

        var emitting = new Thread(() -> aggregate(machine).onEvent(firmware("alpha-1")), "held-emitter");
        emitting.start();
        assertTrue(insideTheSink.await(10, TimeUnit.SECONDS),
                "no thread is held inside the outbox, so the release below would publish the "
                        + "StreamEnd itself and this test would prove nothing");

        manager.release(machine);
        releaseHasReturned.countDown();
        emitting.join(TimeUnit.SECONDS.toMillis(15));

        assertTrue(completed.await(10, TimeUnit.SECONDS),
                "the update stream of a released printer never completed"
                        + (failed.get() == null ? "" : " - it failed with " + failed.get())
                        + ", so a browser watching this deleted machine is still waiting. It saw "
                        + seen);
        assertTrue(awaitDisposed(threads.aggregateOf(machine)),
                "the aggregate's scheduler was never disposed, so its thread is still there");
        assertTrue(awaitDisposed(threads.listenersOf(machine)),
                "the listener's scheduler was never disposed, so its thread is still there");
    }

    /**
     * Production's scheduler with the caller's thread in place of its own - needed because the test
     * above needs two properties no stock scheduler has together: a subscriber running inline inside
     * {@code tryEmitNext} ({@code Schedulers.immediate()} gives this), and a disposal that actually
     * costs something ({@code immediate()}'s {@code dispose()} is a no-op). So this is production's
     * {@code Schedulers.fromExecutorService} with the same {@code dispose() -> shutdownNow()}, only
     * the thread pool underneath runs tasks on whoever submitted them - a task submitted after
     * {@code shutdownNow()} is refused with {@code RejectedExecutionException}, same as the real one,
     * so an {@code onComplete} submitted to a disposed scheduler never reaches anybody either way.
     */
    private static PrinterThreads onTheCallersThread() {
        return new PrinterThreads() {

            @Override
            public Scheduler aggregateOf(UUID printer) {
                return inline("p3d-printer-" + printer);
            }

            @Override
            public Scheduler listenersOf(UUID printer) {
                return inline("p3d-listener-" + printer);
            }
        };
    }

    private static Scheduler inline(String name) {
        return Schedulers.fromExecutorService(new CallerRuns(name), name);
    }

    /** An {@code ExecutorService} with no threads of its own that can still be shut down. */
    private static final class CallerRuns extends AbstractExecutorService {

        private final String name;
        private final AtomicBoolean down = new AtomicBoolean();

        private CallerRuns(String name) {
            this.name = name;
        }

        @Override
        public void execute(Runnable command) {
            if (down.get()) {
                throw new RejectedExecutionException(name + " has been shut down");
            }
            command.run();
        }

        @Override
        public void shutdown() {
            down.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            down.set(true);
            // nothing is ever queued, because nothing is ever deferred.
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return down.get();
        }

        @Override
        public boolean isTerminated() {
            return down.get();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return down.get();
        }
    }

    /**
     * Remembers which scheduler each machine was handed, since the real factory hands out a new one
     * per call (deliberate in production - a reactor owns what it asked for) and a test otherwise has
     * no way to name the object under test. Single-use per machine: asking again after a release
     * returns the disposed one, which is what the disposal assertions want.
     */
    private static final class Recorded implements PrinterThreads {

        private final PrinterThreads delegate;
        private final Map<UUID, Scheduler> aggregates = new ConcurrentHashMap<>();
        private final Map<UUID, Scheduler> listeners = new ConcurrentHashMap<>();

        private Recorded(PrinterThreads delegate) {
            this.delegate = delegate;
        }

        @Override
        public Scheduler aggregateOf(UUID printer) {
            return aggregates.computeIfAbsent(printer, delegate::aggregateOf);
        }

        @Override
        public Scheduler listenersOf(UUID printer) {
            return listeners.computeIfAbsent(printer, delegate::listenersOf);
        }
    }

    // production's shape with a platform thread in it - see the test that uses it.
    private static Scheduler platform(String name) {
        return Schedulers.fromExecutorService(
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name(name).daemon().factory()),
                name);
    }

    private static boolean awaitThread(String name, boolean present) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (threadNames().contains(name) == present) {
                return true;
            }
            Thread.sleep(20);
        }
        return threadNames().contains(name) == present;
    }

    private static Set<String> threadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .collect(Collectors.toSet());
    }

    private static boolean awaitDisposed(Scheduler scheduler) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (scheduler.isDisposed()) {
                return true;
            }
            Thread.sleep(20);
        }
        return scheduler.isDisposed();
    }

    /** Takes {@code wanted} items, or as many as arrive before the deadline. */
    private static List<EventMessage> drain(LinkedBlockingQueue<EventMessage> queue, int wanted,
                                            Duration within) throws InterruptedException {
        var taken = new ArrayList<EventMessage>();
        var deadline = System.nanoTime() + within.toNanos();
        while (taken.size() < wanted) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            var next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            taken.add(next);
        }
        return taken;
    }

    /** Blocks the calling thread until {@link #letGo()} frees it, or the test has gone wrong. */
    private static void park(CountDownLatch until) {
        try {
            if (!until.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("a parked listener was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

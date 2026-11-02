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
 * A printer gets its own thread, and its listeners get another one - issue #54.
 *
 * <p><strong>What the farm looked like before.</strong> Every {@code PrinterReactor} was handed
 * {@code Schedulers.single()}, which is not "a fresh single thread" but the <em>JVM-wide shared
 * one</em>. So one thread carried every machine's decoded replies, every application command,
 * every HTTP query about a connection or a temperature - and, because {@code onEvent} called
 * {@code ApplicationEventPublisher.publishEvent} inline, <strong>every {@code @Transactional} JPA
 * write the roster's listeners do</strong>. The database was on the farm's only thread. One slow
 * write stalled all forty machines, and with each of them auto-reporting a temperature once a
 * second that is the farm's bottleneck - entirely invisible when the test farm has one printer in
 * it.
 *
 * <p>Three properties, each with a hard bound so that a broken one fails rather than hangs:
 *
 * <ol>
 *   <li>one machine's slow hop does not delay another machine's replies;</li>
 *   <li>a listener that is slow still sees one aggregate's events in the order it produced them;</li>
 *   <li>a released printer leaves no thread behind;</li>
 *   <li>and it leaves none <em>without first telling its subscribers it is over</em>, even when
 *       the release lands while another thread is in the middle of emitting.</li>
 * </ol>
 *
 * <p><strong>Ordering is per aggregate and not global, on purpose.</strong> Nothing in the farm
 * decides anything from "machine A spoke before machine B", and guaranteeing that would mean one
 * consumer for the whole farm - which is the shared thread this change exists to remove. So (2) is
 * asserted for one machine and (1) asserts that two machines are explicitly <em>not</em> ordered
 * against each other.
 */
class PrinterReactorThreadingTest {

    private final PrinterDirectory directory = new PrinterDirectory();

    private final Map<UUID, PrinterState> aggregates = new ConcurrentHashMap<>();

    /**
     * Frees any listener this test deliberately parked.
     *
     * <p>Counted down in {@link #letGo()} before anything is released, so a failed assertion ends
     * the test instead of leaving a stuck listener thread holding up the teardown that would have
     * disposed it.
     */
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

    /**
     * A distinguishable reply the aggregate will not suppress.
     *
     * <p>{@code PrinterState.on(FirmwareInfoRs)} only announces a report that <em>changed</em> the
     * profile, so every one of these has to carry a different firmware string. That is also what
     * makes a sequence of them numbered.
     */
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

    /**
     * Opens a machine's runtime and keeps every update it produces, dropping the replay seed.
     *
     * <p>The seed is {@code PrinterReactor.updates()}'s current connection state, handed to every
     * subscriber the moment it attaches; waiting for it here is also what proves the subscription
     * is live before the test emits anything.
     */
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
     *
     * <p><strong>This is the property the whole change is for.</strong> Machine A's roster listener
     * is held on a latch, which is what a {@code @Transactional} write to a database under load
     * is, only reliably. Then machine B produces a reply, and it has to reach a subscriber of B's
     * stream while A's listener is still stuck.
     *
     * <p>The replies are driven onto each machine's <em>own aggregate scheduler</em>, which is
     * exactly what {@code PrinterReactor.gEvent()} does with {@code publishOn(executor)} - this is
     * that hop without needing a socket. That detail is what makes the test faithful: if both
     * printers shared one scheduler, as they did before #54 and as
     * {@link PrinterThreads#shared(reactor.core.scheduler.Scheduler)} still arranges, B's reply
     * would be a task queued behind A's - and A's task would be sitting inside
     * {@code publishEvent}, on that same thread, waiting for a latch nothing had reached yet. B's
     * reply never arrives and the poll below times out.
     *
     * <p>The second half is property 2 rather than property 1, and is asserted here because it
     * needs the same stuck listener: <strong>A's own replies keep flowing while A's own listener is
     * stuck</strong>. Before the hand-off there was nothing between the aggregate and the listener,
     * so a listener that blocked blocked the machine it was listening to.
     *
     * <p><strong>That half asserts what arrived and not how much.</strong> It used to count two
     * messages, which is the right number today only because {@code PrinterState.heard()} happens
     * to announce nothing for a machine in {@code OFFLINE} - {@code ConnectionLifecycle.replyReceived()}
     * returns null outside {@code BUSY} and {@code ERROR}. Anything that changes near that
     * lifecycle makes {@code alpha-1} alone produce two messages, and the count then passes while
     * {@code alpha-2} never arrives, which is the whole property going quietly untested.
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

    /** Whether one machine's stream actually carried this reply, rather than that many arrived. */
    private static boolean carries(List<EventMessage> messages, UUID machine, Object event) {
        return messages.stream()
                .anyMatch(message -> machine.equals(message.id()) && event.equals(message.event()));
    }

    /**
     * A few hundred events out of one aggregate reach a slow listener in the order it produced them.
     *
     * <p>Without this the ordering guarantee is a sentence in a comment. {@code @Async} would not
     * hold it - a {@code ThreadPoolTaskExecutor} with more than one thread reorders freely - and
     * what does hold it is that each machine's listeners have exactly one consumer thread.
     *
     * <p><strong>Asserted on the roster's facts and not on the farm stream, deliberately.</strong>
     * The farm queue is bounded and is allowed to drop; an ordering assertion on a droppable queue
     * would be asserting the wrong contract, and at three hundred events against a listener this
     * slow it will genuinely drop some. The roster queue is lossless, so "exactly these, in exactly
     * this order" is a statement it is entitled to make.
     *
     * <p>{@code FirmwareInfoRs} is the numbered sequence rather than {@code ConnectionStateChanged}
     * because the aggregate will emit one per distinct firmware string without needing a socket, a
     * retry or a clock - {@code PrinterState.on(FirmwareInfoRs)} suppresses a repeat and announces
     * a change, and {@code PrinterReactor.report} turns each into an {@code MFirmwareReportedEvent}
     * on the lossless hand-off.
     */
    @Test
    void oneAggregatesEventsReachASlowListenerInOrder() throws Exception {
        var machine = theFarmHas("alpha");
        var events = 300;
        var heard = new ArrayList<String>();
        var all = new CountDownLatch(events);
        var manager = managerOn(PrinterThreads.virtualPerPrinter(), event -> {
            if (event instanceof MFirmwareReportedEvent report) {
                // Slow enough that anything with two consumer threads would overtake itself, and
                // short enough that three hundred of them are a fraction of a second.
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
     * A released printer leaves no thread behind.
     *
     * <p><strong>The observable is a thread, so this asserts on threads.</strong> A scheduler
     * created per reactor and never disposed is a thread per deleted printer, which is the same
     * leak {@code release()} was added to close for sockets and sinks.
     *
     * <p><strong>Platform threads stand in for virtual ones, and that substitution is the only
     * dishonest thing here.</strong> Virtual threads are not enumerable: neither
     * {@code Thread.getAllStackTraces()} nor any {@code ThreadGroup} sees one, and no supported API
     * does. What is under test is that {@code stop()} disposes the scheduler and the
     * single-threaded executor's worker then terminates - {@code shutdownNow()} on an
     * {@code ExecutorService}, which is the identical call and the identical consequence whichever
     * kind of thread the factory made. So the shape is production's exactly, with
     * {@code Thread.ofPlatform()...daemon()} substituted for {@code Thread.ofVirtual()}: daemon so
     * that a leak fails this assertion rather than hanging the JVM.
     *
     * <p>{@link #bothSchedulersOfAReleasedPrinterAreDisposed()} is the production-exact half, and
     * the two together are the claim: the real factory's schedulers are disposed, and disposing a
     * scheduler of this shape ends its thread.
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
        // A thread of a single-thread executor exists once it has been given something to do, so
        // both are made to do something: a connection query goes through publishOn(aggregate), and
        // an event puts two items on the listener hand-off.
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

    /**
     * And the production factory's own schedulers are the ones disposed.
     *
     * <p>The companion to the assertion above, with nothing substituted:
     * {@code PrinterThreads.virtualPerPrinter()} exactly as the application configures it, recorded
     * on the way out so the test holds the same two objects the reactor was given.
     */
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
     * A release that lands while another thread is emitting still completes the update stream.
     *
     * <p><strong>The bug this is for is one thread later than the one {@code disposeWhenIdle}
     * closed.</strong> That method's whole argument is that {@code tryEmitComplete()} hands an
     * {@code onComplete} to each subscriber's {@code publishOn} worker as a task on the
     * aggregate's scheduler, so the disposal has to be <em>queued behind</em> it. It holds only
     * while {@code tryEmitComplete()} has actually run by the time the disposal is scheduled - and
     * {@code stop()} could not promise that. {@code stop()} <em>parks</em> a {@code StreamEnd} in
     * the outbox, and {@code Outbox.emit} drains only when no other thread is already draining. A
     * machine being deleted mid-conversation has its own thread inside that loop publishing a
     * reply, which is the case below: {@code stop()} returns with the marker still queued, the
     * disposal is scheduled onto an executor whose queue is <em>empty</em> and runs at once, and
     * the {@code onComplete} the other thread produces a moment later is submitted to a scheduler
     * that has gone. <strong>A browser holding an SSE connection to a printer that has just been
     * deleted then waits forever</strong>, which is exactly the failure that design exists to
     * prevent.
     *
     * <p>{@code PrinterReactorManagerTest.aReleasedReactorCompletesItsUpdateStream} does not catch
     * it because nothing else is emitting there, so the marker is published inline by {@code stop()}
     * itself and the ordering is accidentally right.
     *
     * <p><strong>The collision is forced, not hoped for</strong>, the way
     * {@code PrinterReactorEmissionTest} forces its own: a subscriber blocks inside
     * {@code tryEmitNext}, which holds the emitting thread inside the outbox drain, and the
     * release happens from this thread while it is held. Nothing here is timing-dependent.
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
        // First, so that a failed assertion frees the held thread before anything else is torn
        // down - the same reason letGo() counts its own latch down before running this list.
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
     * Production's scheduler with the caller's thread in place of its own.
     *
     * <p><strong>The test above needs two properties no stock scheduler has together.</strong> It
     * has to run a subscriber <em>inline, inside {@code tryEmitNext}</em>, which is the only way to
     * hold the emitting thread inside {@code Outbox.drain} - {@code Schedulers.immediate()} is the
     * one that does this and {@code PrinterReactorEmissionTest} relies on it. And disposing it has
     * to actually cost something, which {@code immediate()}'s empty {@code dispose()} does not.
     *
     * <p>So the scheduler is production's exactly - {@code Schedulers.fromExecutorService}, the
     * same {@code DelegateServiceScheduler} {@link PrinterThreads#virtualPerPrinter()} builds,
     * with the same {@code dispose() -> shutdownNow()} - and only the thread pool underneath it is
     * replaced by one that runs its tasks on whoever submitted them. A task submitted after
     * {@code shutdownNow()} is refused with {@code RejectedExecutionException}, which is what a
     * real one does too; the real one <em>also</em> discards what was already queued, and that
     * half cannot arise here because the queue is always empty. Both come to the same thing for
     * this test: an {@code onComplete} submitted to a disposed scheduler never reaches anybody.
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
            // Nothing is ever queued, because nothing is ever deferred.
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
     * Remembers which scheduler each machine was handed, because the real factory hands out a new
     * one per call.
     *
     * <p>That is deliberate in production - a reactor owns what it asked for - and it means a test
     * cannot otherwise name the object under test. The memoisation makes this wrapper single-use
     * per machine: asking again after a release returns the disposed one, which is what the
     * disposal assertions want and what a reopen would not survive.
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

    /** Production's shape with a platform thread in it - see the test that uses it. */
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

package org.qw3rtrun.p3d.core;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Which threads a machine's runtime is allowed to use - one machine's, and nobody else's.
 *
 * <p><strong>This exists because the farm used to have one thread for all of it.</strong> Every
 * {@code PrinterReactor} was handed {@code Schedulers.single()}, which is not "a new single
 * thread" but the JVM-wide shared one - so forty machines' decoded replies, forty machines'
 * commands, every HTTP query about a connection or a temperature, and (because the roster's
 * listeners were called inline) every {@code @Transactional} JPA write the farm does all queued
 * behind the same thread. One slow database hop stalled every printer. It is invisible with one
 * printer on a developer's desk and it is the bottleneck with forty in a room, which is why it
 * survived as long as it did.
 *
 * <p><strong>Two schedulers per machine, because they are two different jobs.</strong> The
 * aggregate's thread is where a decision is taken; the listener thread is where the consequences
 * of that decision are charged to somebody else. Keeping them apart is what stops a roster write
 * from delaying the next reply off the wire - and keeping each of them to <em>one</em> machine is
 * what stops that printer's slowness from being the farm's.
 *
 * <p>A seam rather than a constant so that a test can put the whole farm back on one thread, or on
 * {@code Schedulers.immediate()}, and keep its assertions synchronous - see
 * {@link #shared(Scheduler)}.
 */
public interface PrinterThreads {

    /** Where this machine's aggregate is touched, and nothing else's. */
    Scheduler aggregateOf(UUID printer);

    /** Where this machine's listeners are called, so that none of them is charged to the aggregate. */
    Scheduler listenersOf(UUID printer);

    /**
     * One serialised virtual thread per machine for the aggregate, and one more for its listeners.
     *
     * <p><strong>Virtual, which is what makes this hold past the forty-machine case.</strong> Two
     * platform threads per printer would be eighty threads for a farm of forty, most of them
     * parked most of the time; two virtual ones are a pair of continuations on the carrier pool
     * and cost almost nothing to have sitting idle. Java 25 is the toolchain, so this is simply
     * available.
     *
     * <p><strong>Single-threaded and not thread-per-task, and both halves of that are load-bearing.</strong>
     * A single consumer is the whole of the ordering guarantee on the listener side - events
     * produced by one aggregate reach its listeners in the order it produced them, with no further
     * argument needed and no reliance on {@code @Async}, which hands work to a
     * {@code ThreadPoolTaskExecutor} that reorders freely the moment it has more than one thread.
     * It is also what lets {@code PrinterReactor.stop()} dispose the scheduler by scheduling the
     * disposal <em>as a task on that scheduler</em>: everything already queued runs first and then
     * the thread exits. A thread-per-task executor has no "last task" to hang that on.
     *
     * <p><strong>Ordering is per aggregate and deliberately not global.</strong> Two different
     * printers' events have no relative order worth preserving - nothing in the farm decides
     * anything from "machine A spoke before machine B" - and enforcing one would mean a single
     * consumer for the whole farm, which is exactly the shared thread this class exists to remove.
     *
     * <p><strong>Each call returns a new scheduler.</strong> This is a factory and not a cache: the
     * reactor that asked for one owns it and disposes it when it stops. A cache would have to
     * decide when an entry goes away, which is the same question {@code release()} already answers
     * for the reactor itself.
     */
    static PrinterThreads virtualPerPrinter() {
        return new PrinterThreads() {

            @Override
            public Scheduler aggregateOf(UUID printer) {
                return virtual("p3d-printer-" + printer);
            }

            @Override
            public Scheduler listenersOf(UUID printer) {
                return virtual("p3d-listener-" + printer);
            }

            @Override
            public String toString() {
                return "PrinterThreads.virtualPerPrinter()";
            }
        };
    }

    /**
     * Every machine on one scheduler, which is what the farm had before #54. For tests.
     *
     * <p>The one it is handed is returned for both jobs, so a listener runs on the aggregate's
     * thread exactly as it used to. With {@code Schedulers.immediate()} that means the hand-off is
     * inline and a test can assert what the roster was told on the line after the emission instead
     * of awaiting it.
     *
     * <p><strong>Nothing disposes what is passed here, and that is safe for every scheduler a test
     * would reasonably pass.</strong> {@code PrinterReactor.stop()} now disposes the schedulers it
     * was given, which would be alarming if this returned a shared one - but in reactor-core 3.8.7
     * {@code Schedulers.immediate()} has an explicitly empty {@code dispose()}, and
     * {@code single()}, {@code parallel()} and {@code boundedElastic()} are all wrapped in
     * {@code Schedulers.CachedScheduler}, whose {@code dispose()} is likewise an empty method - the
     * real one is the package-private {@code _dispose()} that only {@code Schedulers.shutdownNow()}
     * reaches. Verified in the sources rather than assumed.
     */
    static PrinterThreads shared(Scheduler one) {
        return new PrinterThreads() {

            @Override
            public Scheduler aggregateOf(UUID printer) {
                return one;
            }

            @Override
            public Scheduler listenersOf(UUID printer) {
                return one;
            }

            @Override
            public String toString() {
                return "PrinterThreads.shared(" + one + ")";
            }
        };
    }

    /**
     * One serialised virtual thread, named for the machine at both the thread and the scheduler.
     *
     * <p><strong>The whole UUID, not a prefix, and the long name is the point.</strong> A farm-wide
     * stall is diagnosed from a thread dump, and {@code single-1} says nothing at all about which
     * printer is holding things up; eight hex characters would say it ambiguously, which is worse
     * than saying nothing because it reads as certain. The full id can be pasted into the roster
     * and matched to a row.
     *
     * <p>The two-argument {@code fromExecutorService} is used so the {@code Scheduler} carries the
     * same name as its thread - that is what shows up in a Reactor error message or an
     * {@code onSchedulerHook} trace, where there is no thread to look at.
     */
    private static Scheduler virtual(String name) {
        return Schedulers.fromExecutorService(
                Executors.newSingleThreadExecutor(Thread.ofVirtual().name(name).factory()),
                name);
    }
}

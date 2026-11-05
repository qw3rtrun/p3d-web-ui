package org.qw3rtrun.p3d.core;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Which threads a machine's runtime is allowed to use - one machine's, and nobody else's.
 *
 * <p>Exists because the farm used to have one thread for all of it: every {@code PrinterReactor}
 * got {@code Schedulers.single()}, the JVM-wide shared scheduler and not a fresh one, so every
 * machine's replies and commands, every connection/temperature query, and (while the roster's
 * listeners were called inline) every {@code @Transactional} JPA write queued behind the same
 * thread - invisible with one printer on a desk, the bottleneck with forty in a room.
 *
 * <p>Two schedulers per machine because they are two different jobs: the aggregate's thread takes a
 * decision, the listener thread charges its consequences to somebody else, which is what stops a
 * roster write from delaying the next reply - and keeping each to one machine is what stops that
 * printer's slowness from becoming the farm's.
 *
 * <p>A seam rather than a constant, so a test can put the whole farm back on one thread or on
 * {@code Schedulers.immediate()} and keep its assertions synchronous - see {@link #shared(Scheduler)}.
 */
public interface PrinterThreads {

    /** Where this machine's aggregate is touched, and nothing else's. */
    Scheduler aggregateOf(UUID printer);

    /** Where this machine's listeners are called, so that none of them is charged to the aggregate. */
    Scheduler listenersOf(UUID printer);

    /**
     * One serialised virtual thread per machine for the aggregate, and one more for its listeners.
     * Virtual rather than platform threads because two platform threads per printer would be eighty
     * for a farm of forty, mostly parked; virtual ones cost almost nothing idle (Java 25). Single
     * threaded rather than thread-per-task: a single consumer is the whole of the listener-side
     * ordering guarantee (events from one aggregate reach its listeners in the order produced, with
     * no reliance on {@code @Async}, which reorders freely past one thread), and it is also what
     * lets {@code PrinterReactor.stop()} dispose the scheduler by scheduling the disposal as a task
     * on it - everything already queued runs first, then the thread exits; a thread-per-task
     * executor has no "last task" to hang that on. Ordering is per aggregate and deliberately not
     * global, since two printers' events have no relative order worth preserving and enforcing one
     * would mean the single shared consumer this class exists to remove. A factory, not a cache:
     * each call returns a new scheduler, owned and disposed by the reactor that asked for it.
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
     * Every machine on one scheduler, which is what the farm had before #54 - for tests. The one
     * scheduler is returned for both jobs, so with {@code Schedulers.immediate()} a listener hand-off
     * is inline and a test can assert what the roster was told on the next line rather than awaiting
     * it. Nothing disposes what is passed here, which is safe for every scheduler a test would
     * reasonably pass: in reactor-core 3.8.7, {@code Schedulers.immediate()}'s {@code dispose()} is
     * explicitly empty, and {@code single()}/{@code parallel()}/{@code boundedElastic()} are wrapped
     * in {@code Schedulers.CachedScheduler}, whose {@code dispose()} is likewise empty - the real one
     * is a package-private {@code _dispose()} only {@code Schedulers.shutdownNow()} reaches. Verified
     * in the sources, not assumed.
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
     * One serialised virtual thread, named for the machine at both the thread and the scheduler -
     * the full UUID, not a prefix, because a farm-wide stall is diagnosed from a thread dump and
     * {@code single-1} says nothing (eight hex characters would say it ambiguously, which is worse -
     * it reads as certain); the full id can be pasted straight into the roster. The two-argument
     * {@code fromExecutorService} gives the {@code Scheduler} the same name as its thread, which is
     * what a Reactor error message or {@code onSchedulerHook} trace shows when there is no thread to
     * look at.
     */
    private static Scheduler virtual(String name) {
        return Schedulers.fromExecutorService(
                Executors.newSingleThreadExecutor(Thread.ofVirtual().name(name).factory()),
                name);
    }
}

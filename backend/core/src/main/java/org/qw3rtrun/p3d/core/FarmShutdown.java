package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets the farm go before the web layer starts waiting for it to - issue #87.
 *
 * <h2>What was wrong</h2>
 *
 * <p>Two separate defects produced one symptom. First, nothing released a printer's runtime when
 * the application context closed: {@link PrinterReactorManager#release(java.util.UUID)} was
 * reached from exactly one place, a machine being removed from the farm, so a process told to stop
 * with machines connected left every socket open, every send window mid-flight and every
 * per-printer scheduler alive - it stopped talking to them only because it died. Second, the SSE
 * responses those runtimes fed never completed, and Spring Boot 4 turns on graceful shutdown by
 * default: {@code WebServerGracefulShutdownLifecycle} waits for every in-flight request - an open
 * {@code EventSource} is one - for the whole of
 * {@code spring.lifecycle.timeout-per-shutdown-phase} (thirty seconds by default) and then gives up
 * anyway, having spent the whole of it.
 *
 * <p>This bean is both halves of the fix in one place, because they have to happen together and in
 * order: letting go of the printers is what gives their streams an end, and that end has to be
 * written to the sink <em>before</em> the web server starts the wait, or the wait still happens and
 * only then finds nothing left to wait for a moment too late to matter.
 *
 * <h2>Why {@link SmartLifecycle} and not {@code @PreDestroy}</h2>
 *
 * <p><strong>{@code @PreDestroy} (and {@code DisposableBean}) runs during bean destruction, which
 * is after every {@link org.springframework.context.Lifecycle#stop()} phase has already finished -
 * in particular, after {@code WebServerGracefulShutdownLifecycle} has already spent its whole
 * thirty seconds waiting.</strong> A {@code @PreDestroy} here would still close every socket
 * correctly and would still leave the entire hang in place, because by the time it ran the web
 * layer's wait would already be over. Do not "simplify" this to one; that is the mistake this
 * paragraph exists to stop.
 *
 * <h2>Why {@link #getPhase()} is the load-bearing part of the whole change</h2>
 *
 * <p>{@code DefaultLifecycleProcessor.stopBeans()} stops the highest-numbered phase first.
 * {@code WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE} is
 * {@code Integer.MAX_VALUE - 1024} (2147482623) - that is the phase number Boot's own shutdown log
 * line names. {@link #getPhase()} answers {@link SmartLifecycle#DEFAULT_PHASE}, which is
 * {@code Integer.MAX_VALUE} itself, and {@code Integer.MAX_VALUE > Integer.MAX_VALUE - 1024} is
 * what makes this bean's {@link #stop()} run <em>before</em> the web layer begins draining
 * in-flight requests rather than during or after it. Lower this phase below
 * {@code Integer.MAX_VALUE - 1024} - "tidy" it to {@code 0}, say - and the thirty-second hang this
 * exists to remove comes straight back, silently: every test that does not boot a real web server
 * and a real graceful shutdown will stay green while it does.
 *
 * <h2>What {@link #stop()} does, and in which order</h2>
 *
 * <ol>
 *   <li>{@link PrinterReactorManager#releaseAll()} - stops every running printer (which
 *       disconnects it, parks completion on its {@code PrinterReactor.updates()} sink and
 *       disposes that machine's two schedulers) and closes the manager's gate against opening
 *       another, so a request arriving in the window between this and the web server actually
 *       refusing new connections cannot dial a machine the farm is on its way out of - and then
 *       <strong>waits, bounded, for every one of those streams to actually have finished</strong>
 *       at its subscribers before returning. See the heading below for why that wait belongs
 *       here;</li>
 *   <li>{@link FarmEvents#close()} - ends the farm-wide stream, so {@code GET /api-farm/stream}
 *       stops being an in-flight request too. It completes synchronously and owns no scheduler
 *       of its own, so this step needs no wait of its own kind.</li>
 * </ol>
 *
 * <p>Both of those are idempotent on their own side, and this adds a third guard on top -
 * {@link #running} - so that a second {@link #stop()} (which nothing in this application issues,
 * but nothing stops a future caller from trying) does not even reach them.
 *
 * <h2>Why this blocks, and why that is not the rule it looks like it breaks</h2>
 *
 * <p>{@link PrinterReactorManager#releaseAll()} waits, with a bounded budget, for confirmation
 * that each released printer's subscribers have actually seen its stream end - not merely that
 * {@code PrinterReactor.stop()} parked a terminal signal for them. <strong>That is a blocking
 * call, and it is correct here precisely because of where it runs.</strong> This repository's
 * rule against blocking is about the threads WebFlux hands a request to: a handful of netty
 * event-loop threads every concurrent request shares, where one blocked call stalls every other
 * request on it. {@code SmartLifecycle.stop()} never runs there - {@code DefaultLifecycleProcessor}
 * calls it from the shutdown sequence, on a thread with no request waiting behind it - and the
 * whole phase is already bounded by {@code spring.lifecycle.timeout-per-shutdown-phase}. This is
 * the one place in the application where waiting, bounded, is the fix rather than the bug it is
 * everywhere else.
 *
 * <h2>{@link #isRunning()}</h2>
 *
 * <p>{@code DefaultLifecycleProcessor} only calls {@link #stop()} on a bean whose
 * {@link #isRunning()} currently answers {@code true} - a {@link SmartLifecycle} that always
 * answers {@code false} is simply never stopped. {@link #start()}, which runs automatically because
 * {@link #isAutoStartup()}'s default of {@code true} is correct here, is what flips it; {@link #stop()}
 * flips it back as the first thing it does, guarding against a second call at the same time.
 */
@Slf4j
public class FarmShutdown implements SmartLifecycle {

    private final PrinterReactorManager reactors;

    private final FarmEvents farmEvents;

    /**
     * Whether {@link #start()} has run and {@link #stop()} has not - see the class javadoc for why
     * {@code DefaultLifecycleProcessor} needs this to answer {@code true} before it will ever call
     * {@link #stop()} at all.
     */
    private final AtomicBoolean running = new AtomicBoolean();

    public FarmShutdown(PrinterReactorManager reactors, FarmEvents farmEvents) {
        this.reactors = reactors;
        this.farmEvents = farmEvents;
    }

    @Override
    public void start() {
        running.set(true);
    }

    /**
     * Releases every printer's runtime and ends the farm-wide stream, once.
     *
     * <p>{@code compareAndSet} rather than a plain write: it is both the idempotency guard and what
     * makes {@link #isRunning()} answer {@code false} to anyone who asks after the first call
     * returns, which is the contract {@link SmartLifecycle} describes for it.
     */
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("farm shutdown: releasing every printer's runtime and ending the farm stream");
        reactors.releaseAll();
        farmEvents.close();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * {@link SmartLifecycle#DEFAULT_PHASE} - see the class javadoc for why this number, and not a
     * smaller one, is the entire point of this class existing as a {@link SmartLifecycle} at all.
     */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }
}

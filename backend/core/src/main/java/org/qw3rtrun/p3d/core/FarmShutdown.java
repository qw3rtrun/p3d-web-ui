package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets the farm go before the web layer starts waiting for it to - issue #87.
 *
 * <p>Two defects produced one symptom. Nothing released a printer's runtime when the context
 * closed ({@link PrinterReactorManager#release(java.util.UUID)} was reached only from a machine
 * being removed from the farm), and the SSE responses those runtimes fed never completed - so Spring
 * Boot 4's default graceful shutdown (every in-flight request, including an open {@code EventSource},
 * waited out the whole of {@code spring.lifecycle.timeout-per-shutdown-phase}, thirty seconds by
 * default, and gave up anyway). This bean is both halves together, in order: letting go of the
 * printers is what gives their streams an end, and that end must reach the sink before the web
 * server starts waiting, or the wait still runs and only then finds nothing left to wait for.
 *
 * <p><strong>{@code @PreDestroy}/{@code DisposableBean} is the wrong hook</strong>: bean destruction
 * runs after every {@link org.springframework.context.Lifecycle#stop()} phase has finished - in
 * particular, after {@code WebServerGracefulShutdownLifecycle} has already spent its whole timeout
 * waiting. A {@code @PreDestroy} version would still close every socket correctly and still leave
 * the hang in place.
 *
 * <p><strong>{@link #getPhase()} is the load-bearing part.</strong>
 * {@code DefaultLifecycleProcessor.stopBeans()} stops the highest-numbered phase first;
 * {@code WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE} is
 * {@code Integer.MAX_VALUE - 1024} (2147482623, the number in Boot's own shutdown log line), and
 * answering {@link SmartLifecycle#DEFAULT_PHASE} ({@code Integer.MAX_VALUE}) is what runs this
 * bean's {@link #stop()} before the web layer begins draining requests. Lower this phase and the
 * thirty-second hang comes back silently - no test that skips a real web server and a real shutdown
 * will ever notice.
 *
 * <p>{@link #stop()}, in order: {@link PrinterReactorManager#releaseAll()} stops every printer
 * (disconnects it, parks completion on its update sink, disposes its two schedulers), closes the
 * manager's gate against opening another, and then <strong>waits, bounded, for every one of those
 * streams to have actually finished</strong> at its subscribers before returning; then
 * {@link FarmEvents#close()} ends the farm-wide stream, synchronously and with no scheduler of its
 * own to wait on. Both are independently idempotent, and {@link #running} is a third guard so a
 * second {@link #stop()} does not even reach them.
 *
 * <p><strong>The wait is a blocking call, and correct here precisely because of where it runs.</strong>
 * This repository's rule against blocking is about the netty event-loop threads a request shares;
 * {@code SmartLifecycle.stop()} never runs there - {@code DefaultLifecycleProcessor} calls it from
 * the shutdown sequence, already bounded by {@code spring.lifecycle.timeout-per-shutdown-phase} -
 * so this is the one place where waiting, bounded, is the fix rather than the bug.
 *
 * <p>{@code DefaultLifecycleProcessor} only calls {@link #stop()} on a bean whose
 * {@link #isRunning()} currently answers {@code true}; {@link #start()} (run automatically via the
 * default {@link #isAutoStartup()}) is what flips it, and {@link #stop()} flips it back first,
 * guarding against a concurrent second call.
 */
@Slf4j
public class FarmShutdown implements SmartLifecycle {

    private final PrinterReactorManager reactors;

    private final FarmEvents farmEvents;

    private final AtomicBoolean running = new AtomicBoolean();

    public FarmShutdown(PrinterReactorManager reactors, FarmEvents farmEvents) {
        this.reactors = reactors;
        this.farmEvents = farmEvents;
    }

    @Override
    public void start() {
        running.set(true);
    }

    /** {@code compareAndSet}, not a plain write: both the idempotency guard and what makes {@link #isRunning()} answer false to anyone asking after the first call returns. */
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

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }
}

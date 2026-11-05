package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bean that lets the farm go when the application is told to stop - issue #87.
 *
 * <p>No mocks: both collaborators are the real {@link PrinterReactorManager} and
 * {@link FarmEvents}, the same ones {@link PrinterReactorManagerTest} and {@link FarmEventsTest}
 * exercise directly - "reaches both collaborators" is proven by their own observable contracts
 * (a completed update stream, a closed farm stream) rather than by a recorded call.
 */
class FarmShutdownTest {

    private final PrinterDirectory directory = new PrinterDirectory();
    private final PrinterReactorManager reactors =
            new PrinterReactorManager(directory, PrinterState::new, event -> {
            });
    private final FarmEvents farmEvents = new FarmEvents(directory, reactors);
    private final FarmShutdown shutdown = new FarmShutdown(reactors, farmEvents);

    private void theFarmHas(UUID machine) {
        directory.onRegistered(new MRegisteredEvent(machine, "a printer", "localhost", 8099,
                MachineProfile.unknown(), MachineSpec.undeclared(),
                MachineConnectionState.OFFLINE, LocalDateTime.now()));
    }

    /**
     * {@code DefaultLifecycleProcessor} only calls {@code stop()} on a bean whose
     * {@code isRunning()} currently answers {@code true} - so a {@link SmartLifecycle} that never
     * says so is simply never stopped.
     */
    @Test
    void isRunningIsTrueAfterStartAndFalseAfterStop() {
        assertFalse(shutdown.isRunning(), "nothing has started it yet");

        shutdown.start();
        assertTrue(shutdown.isRunning());

        shutdown.stop();
        assertFalse(shutdown.isRunning());
    }

    /**
     * The one number in this change that silently breaks the fix if someone "tidies" it: lower
     * than {@code Integer.MAX_VALUE - 1024}
     * ({@code WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE}) and the thirty-second
     * hang of #87 comes straight back, because {@code DefaultLifecycleProcessor} would then stop
     * the web layer first and start waiting for requests this bean had not released yet.
     */
    @Test
    void thePhaseIsTheSmartLifecycleDefaultAndNotSomethingSmaller() {
        assertEquals(SmartLifecycle.DEFAULT_PHASE, shutdown.getPhase());
        assertEquals(Integer.MAX_VALUE, shutdown.getPhase());
        assertTrue(shutdown.getPhase() > Integer.MAX_VALUE - 1024,
                "must stay above Boot's WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE, "
                        + "or the 30s hang this bean exists to remove comes back silently");
    }

    /**
     * {@code stop()} reaches {@link PrinterReactorManager#releaseAll()}: a printer that was
     * running has its update stream completed, not merely left alone.
     */
    @Test
    void stopReleasesEveryRunningPrinter() {
        var id = UUID.randomUUID();
        theFarmHas(id);
        var updates = reactors.openReactor(id).updates();
        shutdown.start();

        StepVerifier.create(updates)
                .expectNextCount(1)
                .then(shutdown::stop)
                .verifyComplete();
    }

    /**
     * {@code stop()} reaches {@link FarmEvents#close()} too, and not only
     * {@link PrinterReactorManager#releaseAll()} - both collaborators, which is the whole of what
     * this bean is for. An empty farm is enough to prove it: the farm stream completes once
     * {@code close()} has run, which only {@code stop()} could have called.
     */
    @Test
    void stopEndsTheFarmStreamToo() {
        shutdown.start();

        shutdown.stop();

        StepVerifier.create(Flux.from(farmEvents.updates()))
                .verifyComplete();
    }

    /** A second {@code stop()} - nothing in this application issues one, but nothing forbids it either. */
    @Test
    void stopTwiceDoesNotThrow() {
        shutdown.start();

        shutdown.stop();
        shutdown.stop();

        assertFalse(shutdown.isRunning());
    }
}

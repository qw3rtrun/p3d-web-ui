package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot, then the live stream, and nothing that happened in between is lost.
 */
class CatchUpTest {

    private final Sinks.Many<String> live = Sinks.many().multicast().directBestEffort();

    // the case the obvious Flux.concat(snapshot, live) gets wrong: the event fires while the
    // snapshot builds, before concat would have subscribed live; here it is listening already.
    @Test
    void anEventDuringTheSnapshotArrivesAfterIt() {
        var stream = CatchUp.snapshotThenLive("test", () -> {
            live.tryEmitNext("during");
            return Flux.just("snapshot");
        }, live.asFlux(), 16);

        StepVerifier.create(stream.take(2))
                .expectNext("snapshot", "during")
                .verifyComplete();
    }

    @Test
    void liveEventsFollowTheSnapshot() {
        var stream = CatchUp.snapshotThenLive("test", () -> Flux.just("a", "b"), live.asFlux(), 16);

        StepVerifier.create(stream.take(3))
                .expectNext("a", "b")
                .then(() -> live.tryEmitNext("c"))
                .expectNext("c")
                .verifyComplete();
    }

    /** The end of the live stream is the end of the subscriber's - a deleted printer's browser is let go. */
    @Test
    void theLiveStreamEndingEndsIt() {
        var stream = CatchUp.snapshotThenLive("test", () -> Flux.just("a"), live.asFlux(), 16);

        StepVerifier.create(stream)
                .expectNext("a")
                .then(live::tryEmitComplete)
                .verifyComplete();
    }

    /** A subscriber that leaves takes its feed with it, so the shared sink does not keep it. */
    @Test
    void leavingStopsListening() {
        var stream = CatchUp.snapshotThenLive("test", () -> Flux.just("a"), live.asFlux(), 16);

        StepVerifier.create(stream.take(1)).expectNext("a").verifyComplete();

        assertEquals(0, live.currentSubscriberCount());
    }

    // nothing is requested past the snapshot, so the buffer fills; the emitter is never refused and
    // never waits - a printer must not wait on a page. How many survive is Reactor's business; what
    // is pinned is that some were dropped and the oldest kept.
    @Test
    void aSubscriberThatStopsReadingLosesMessagesAndHoldsNothingUp() {
        var stream = CatchUp.snapshotThenLive("test", () -> Flux.just("a"), live.asFlux(), 4);
        var received = new ArrayList<String>();

        StepVerifier.create(stream, 1)
                .expectNext("a")
                .then(() -> {
                    for (int i = 0; i < 100; i++) {
                        assertEquals(Sinks.EmitResult.OK, live.tryEmitNext("live-" + i));
                    }
                })
                .thenRequest(Long.MAX_VALUE)
                .then(live::tryEmitComplete)
                .recordWith(() -> received)
                .thenConsumeWhile(message -> true)
                .verifyComplete();

        assertTrue(received.size() >= 4 && received.size() < 100,
                "the backlog should have been bounded: " + received.size() + " delivered");
        assertEquals("live-0", received.get(0));
    }
}

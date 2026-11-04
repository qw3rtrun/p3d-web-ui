package org.qw3rtrun.p3d.core;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import java.util.function.Supplier;

/**
 * A snapshot of where things are, then everything that happens next - with nothing in between lost.
 *
 * <p><strong>The live stream is subscribed <em>before</em> the snapshot is taken, and that order is
 * the whole point.</strong> The obvious {@code Flux.concat(snapshot, live)} subscribes to the live
 * stream only once the snapshot has completed, so an event emitted between the two reads is in
 * neither: the subscriber is shown the state from before it and never told it changed. On an idle
 * printer that is a stale connection state for as long as nothing else happens, which could be
 * for ever.
 *
 * <p>Subscribed first, what arrives during the snapshot is held in a buffer and delivered after it.
 * The cost is the opposite and much smaller failure: an event whose change the snapshot already
 * includes arrives once more, and when an emission was still on its way, an older value can be
 * shown for an instant before the newer one that follows it. Nothing is ever <em>missing</em>, and
 * the last thing a subscriber sees is the current state, which is what a page needs.
 *
 * <p><strong>The buffer is bounded and drops, like every other per-subscriber queue in this
 * module.</strong> A browser that stops reading must not make the host hold its backlog, and a
 * printer must never wait on a dashboard - so an overflow loses that subscriber one message, says
 * so, and nothing else is affected.
 *
 * <p>Feeding the buffer from the live stream is single-producer by the Reactive Streams rules: a
 * publisher never signals one subscriber from two threads at once, so {@code tryEmitNext} on the
 * unicast sink cannot collide with itself.
 */
@Slf4j
final class CatchUp {

    private CatchUp() {
    }

    /**
     * @param what     what this stream is, for the overflow warning
     * @param snapshot built per subscriber, after the live stream is already being listened to
     * @param live     the stream every subscriber shares; it should not replay anything itself
     * @param backlog  how many live messages one subscriber may fall behind before one is dropped
     */
    static <T> Flux<T> snapshotThenLive(String what, Supplier<? extends Publisher<T>> snapshot,
                                        Flux<T> live, int backlog) {
        return Flux.defer(() -> {
            Sinks.Many<T> buffer = Sinks.many().unicast().onBackpressureBuffer(Queues.<T>get(backlog).get());
            Disposable feed = live.subscribe(
                    message -> {
                        var result = buffer.tryEmitNext(message);
                        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                            log.warn("{}: a subscriber is {} messages behind, so {} was dropped for it",
                                    what, backlog, message);
                        }
                    },
                    buffer::tryEmitError,
                    buffer::tryEmitComplete);
            return Flux.concat(snapshot.get(), buffer.asFlux())
                    .doFinally(signal -> feed.dispose());
        });
    }
}

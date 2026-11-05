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
 * <p>The live stream is subscribed <em>before</em> the snapshot is taken, which is the whole point:
 * the obvious {@code Flux.concat(snapshot, live)} subscribes live only once the snapshot completes,
 * so an event emitted between the two is in neither - a stale connection state on an idle printer,
 * possibly forever. Subscribing first instead buffers what arrives meanwhile and delivers it after,
 * at the cost of a much smaller failure: a value the snapshot already covers can arrive once more,
 * or an older value flash before the newer one. Nothing is ever missing, and the last thing a
 * subscriber sees is current.
 *
 * <p>The buffer is bounded and drops, like every other per-subscriber queue in this module - a
 * browser that stops reading must not make the host hold its backlog. Feeding it from the live
 * stream is single-producer by the Reactive Streams rules (a publisher never signals one subscriber
 * from two threads at once), so {@code tryEmitNext} on the unicast sink cannot collide with itself.
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

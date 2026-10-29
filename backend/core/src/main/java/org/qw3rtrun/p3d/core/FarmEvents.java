package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.control.FarmView;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.qw3rtrun.p3d.api.msg.MRegisteredEvent;
import org.qw3rtrun.p3d.api.msg.MachineJoinedFarm;
import org.qw3rtrun.p3d.api.msg.MachineLeftFarm;
import org.reactivestreams.Publisher;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * One stream for the whole farm - plan item D2.
 *
 * <p><strong>Fed by the reactors rather than polling them.</strong> {@code PrinterReactor} already
 * publishes each machine's events on the application event bus - which this repository's skill
 * calls the one dispatch mechanism - so this listens where the facts already go rather than
 * subscribing to forty per-machine streams and having to notice when a forty-first appears.
 *
 * <p><strong>That also avoids the trap the item exists to close.</strong> Watching every machine
 * through {@code PrinterControl.updates(id)} would have been the obvious wiring and would have
 * instantiated a runtime for every registered printer - forty sockets opened to render a page that
 * is only looking. Nothing here opens anything.
 *
 * <p><strong>No replay sink.</strong> A replaying sink would hold the last event for the farm, not
 * per machine, which is useless - the one event it kept would be whichever printer spoke most
 * recently. A new subscriber gets a built snapshot instead; see {@link #updates()}.
 */
@Slf4j
@RequiredArgsConstructor
public class FarmEvents implements FarmView {

    private final PrinterDirectory directory;
    private final PrinterReactorManager reactors;

    /**
     * Multicast, lossy on a slow subscriber, and <strong>without a warm-up buffer</strong>.
     *
     * <p>{@code directBestEffort} and emphatically not {@code onBackpressureBuffer}, which was the
     * obvious choice and leaks: that one <em>warms up</em>, buffering everything emitted while
     * nothing is subscribed and handing the whole backlog to the first subscriber that arrives. On
     * a farm that runs for days with no dashboard open, that is every temperature report every
     * machine has produced since boot, held in memory to be replayed at a page that wanted to know
     * what is happening now. Caught by a test whose snapshot came back with a week of history in
     * front of it.
     *
     * <p>Lossy is the right answer for the other end too: a browser that stops reading must not
     * stall the reactor that is emitting, so that subscriber drops messages and every other one -
     * and the printer - carries on. A printer must never wait on a dashboard.
     */
    private final Sinks.Many<EventMessage> farm =
            Sinks.many().multicast().directBestEffort();

    /** Which machines have been announced, so a re-announcement is not a second arrival. */
    private final Set<UUID> known = ConcurrentHashMap.newKeySet();

    /**
     * The current state of every machine, then everything that happens next.
     *
     * <p>The snapshot is built rather than replayed, and it is what makes one subscription enough:
     * a page attaches and immediately knows where each printer is, instead of showing a row of
     * blanks until each machine next says something - which for an idle printer could be never.
     *
     * <p><strong>A machine with no runtime reads as offline, and that is the truth rather than a
     * default.</strong> No reactor means nothing is connected to it, so there is nothing it could
     * be doing.
     */
    @Override
    public Publisher<EventMessage> updates() {
        return Flux.defer(() -> Flux.fromIterable(directory.all()))
                .concatMap(entry -> currentStateOf(entry.id())
                        .map(change -> new EventMessage(entry.id(), change)))
                .concatWith(farm.asFlux())
                .doOnSubscribe(s -> log.info("a farm-wide subscriber attached"));
    }

    /**
     * Where one machine is, as the event that would have announced it.
     *
     * <p><strong>A {@code Mono} and not a blocking read, which is not a style preference.</strong>
     * The first version called {@code reactor.connection().block()} while building the snapshot,
     * and a snapshot is built on whichever thread subscribed - for an SSE endpoint that is a netty
     * event loop. Reactor's own non-blocking-thread check turned it into a 500 rather than letting
     * it stall the loop, which is the only reason it was a bug report instead of a farm that went
     * quiet under load. The unit test missed it because a test subscribes on an ordinary thread.
     */
    private Mono<ConnectionStateChanged> currentStateOf(UUID machineId) {
        var runtime = reactors.runtimeOf(machineId);
        if (runtime == null) {
            return Mono.just(offline());
        }
        return runtime.connection()
                .map(status -> new ConnectionStateChanged(
                        status.state(), status.state(), status.attempt(), status.reason()))
                .defaultIfEmpty(offline());
    }

    /** Nothing has dialled it, so there is nothing it could be doing. */
    private static ConnectionStateChanged offline() {
        return new ConnectionStateChanged(
                MachineConnectionState.OFFLINE, MachineConnectionState.OFFLINE, 0, null);
    }

    /** Everything a machine says, on its way to whoever is watching the whole farm. */
    @EventListener
    public void onMachineEvent(EventMessage message) {
        emit(message);
    }

    /**
     * A machine joined - but only the first time it is announced.
     *
     * <p>{@code MRegisteredEvent} is a statement of current fact, re-published for every machine at
     * startup and again every time the roster learns something, so taking each one as an arrival
     * would tell a dashboard that a printer had just appeared every time it reported its firmware.
     */
    @EventListener
    public void onRegistered(MRegisteredEvent event) {
        if (known.add(event.getMachineId())) {
            emit(new EventMessage(event.getMachineId(), new MachineJoinedFarm(event.getName())));
        }
    }

    @EventListener
    public void onDeleted(MDeletedEvent event) {
        known.remove(event.getMachineId());
        emit(new EventMessage(event.getMachineId(), new MachineLeftFarm()));
    }

    /**
     * Puts a message on the farm stream, and says so when it did not go.
     *
     * <p>{@code tryEmitNext} fails silently when it is not {@code OK} - with no subscriber that is
     * the normal case and not worth a line, which is why only a real failure is logged.
     */
    private void emit(EventMessage message) {
        var result = farm.tryEmitNext(message);
        if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            log.warn("the farm stream dropped {} ({})", message.type(), result);
        }
    }
}

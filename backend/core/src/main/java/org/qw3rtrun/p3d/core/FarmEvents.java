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
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.reactivestreams.Publisher;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * One stream for the whole farm - plan item D2. Fed by listening where every machine's events
 * already go (the application event bus), rather than subscribing to {@code PrinterControl.updates(id)}
 * for each - which would instantiate a runtime, and so open a socket, for every registered printer
 * just to render a page that is only looking.
 *
 * <p>{@link #farm} is a safe multicast {@code directBestEffort} sink, fed only through
 * {@link #outbox} for the same reason {@code PrinterReactor.updates} is: several machines' listener
 * threads can emit at once, and routing through the outbox makes {@code FAIL_NON_SERIALIZED}
 * unreachable. It carries <strong>no warm-up buffer</strong> - {@code onBackpressureBuffer} looked
 * like the obvious choice and leaks: it buffers everything emitted while nobody is subscribed, so a
 * farm that runs for days with no dashboard open hands the first subscriber every temperature report
 * since boot (caught by a test whose snapshot came back a week deep). It is lossy for the same
 * reason the farm must never wait on a browser: a slow dashboard drops events for itself alone.
 *
 * <p><strong>No replay either.</strong> {@link #updates()} instead opens with a snapshot built from
 * {@link PrinterReactorManager#runtimeOf(UUID)} for every known machine (offline if there is no
 * runtime, which is the truth rather than a default) - a replaying sink would hold only the farm's
 * single last event, which is useless. Building that snapshot used to call
 * {@code reactor.connection().block()} on whatever thread subscribed - a netty event loop for an SSE
 * endpoint - and Reactor's non-blocking-thread check turned every page load into a 500 rather than a
 * stalled loop, which is the only reason it was a bug report instead of a farm gone quiet under load.
 */
@Slf4j
@RequiredArgsConstructor
public class FarmEvents implements FarmView {

    private final PrinterDirectory directory;
    private final PrinterReactorManager reactors;

    private final Sinks.Many<EventMessage> farm =
            Sinks.many().multicast().directBestEffort();

    private final Outbox<Parked> outbox = new Outbox<>(this::publish);

    /**
     * One thing waiting for the farm sink: a message, or the end of the stream. Sealed so
     * {@link #publish} is an exhaustive switch with no {@code default}, the same discipline
     * {@code PrinterReactor.Parked} uses.
     */
    private sealed interface Parked {

        record Update(EventMessage message) implements Parked {
        }

        record End() implements Parked {
        }
    }

    private boolean closed;

    /** Which machines have been announced, so a re-announcement is not a second arrival. */
    private final Set<UUID> known = ConcurrentHashMap.newKeySet();

    @Override
    public Publisher<EventMessage> updates() {
        return CatchUp.snapshotThenLive("the farm stream",
                        () -> Flux.defer(() -> Flux.fromIterable(directory.all()))
                                .concatMap(entry -> currentStateOf(entry.id())),
                        farm.asFlux(), SUBSCRIBER_BACKLOG)
                .doOnSubscribe(s -> log.info("a farm-wide subscriber attached"));
    }

    /** Forty machines auto-reporting once a second is a few seconds of this; further behind than that, a dashboard has stopped reading. */
    private static final int SUBSCRIBER_BACKLOG = 1024;

    /** A dashboard's share of {@link PrinterReactor#snapshot()} - connection and last temperature only, not the firmware/capabilities a printer's own page wants. */
    private Flux<EventMessage> currentStateOf(UUID machineId) {
        var runtime = reactors.runtimeOf(machineId);
        if (runtime == null) {
            return Flux.just(new EventMessage(machineId, offline()));
        }
        return runtime.snapshot()
                .filter(message -> message.event() instanceof ConnectionStateChanged
                        || message.event() instanceof TemperatureRs<?>);
    }

    /** Nothing has dialled it, so there is nothing it could be doing. */
    private static ConnectionStateChanged offline() {
        return new ConnectionStateChanged(
                MachineConnectionState.OFFLINE, MachineConnectionState.OFFLINE, 0, null);
    }

    @EventListener
    public void onMachineEvent(EventMessage message) {
        emit(message);
    }

    /** First announcement only: {@code MRegisteredEvent} re-publishes on every firmware report too, not just an arrival. */
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

    // routed through the outbox because a direct tryEmitNext's FAIL_NON_SERIALIZED log line read
    // exactly like the backpressure drop this sink is chosen for, and meant something else entirely
    // (every dashboard losing the event, not one slow one) - measured live, losing a real
    // IDLE -> CONNECTING transition with a warning that said the design was working as intended.
    private void emit(EventMessage message) {
        outbox.emit(new Parked.Update(message));
    }

    /**
     * Ends the farm-wide stream - issue #87, called once by {@code FarmShutdown} before the web
     * server starts waiting out its graceful-shutdown timeout on this in-flight SSE request. A
     * subscriber attaching after this has run still gets a snapshot - every machine reading
     * {@code OFFLINE}, which is simply true once the farm has released every runtime - and then
     * completes at once, because {@code CatchUp.snapshotThenLive} subscribes the live stream (already
     * finished) before building the snapshot. Idempotent via {@link #closed}, the same way
     * {@code PrinterReactor.stop()} is.
     */
    public void close() {
        outbox.emit(new Parked.End());
    }

    /**
     * One parked item onto the sink - {@link #outbox} only, so no second thread is ever in here.
     * {@code FAIL_NON_SERIALIZED} is unreachable through this path; {@code FAIL_ZERO_SUBSCRIBER} is
     * the ordinary case on a farm nobody is watching, so only other failures are logged.
     */
    private void publish(Parked parked) {
        switch (parked) {
            case Parked.End _ -> {
                if (closed) {
                    return;
                }
                closed = true;
                Sinks.EmitResult result = farm.tryEmitComplete();
                if (result.isFailure()) {
                    log.warn("the farm stream did not complete ({})", result);
                }
            }
            case Parked.Update(EventMessage message) -> {
                if (closed) {
                    // an event still travelling when the farm was told to close is normal.
                    log.debug("the farm stream has ended, so {} goes nowhere", message);
                    return;
                }
                var result = farm.tryEmitNext(message);
                if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                    log.warn("the farm stream refused {} ({}) - not the backpressure drop",
                            message.type(), result);
                }
            }
        }
    }
}

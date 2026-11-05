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
     * <p>Touched by exactly one thread at a time - see {@link #outbox}. A <em>safe</em>
     * {@code Sinks.Many} does not serialise for its caller; it refuses a concurrent emission with
     * {@code FAIL_NON_SERIALIZED}, and this one is reached from whatever thread published the Spring
     * event - which since #54 is each machine's own {@code p3d-listener-<id>}, so the number of
     * threads that can collide here is the size of the farm rather than one.
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

    /**
     * Everything on its way to {@link #farm}, so that two machines never emit at once.
     *
     * <p><strong>In steady state there is no end-of-stream marker here, and that part of the old
     * note stands</strong>: this stream belongs to the farm rather than to any one machine, so
     * there is no "this machine is gone" moment for it the way {@code PrinterReactor} has one, and
     * an ordinary subscriber leaves by cancelling rather than by being told the stream is over.
     *
     * <p><strong>What was wrong is "nothing ever terminates it", full stop - issue #87.</strong> The
     * farm itself has an end: the application context closing. {@link #close()} parks a
     * {@link Parked.End} through this same outbox, exactly the way {@code PrinterReactor.stop()}
     * parks a {@code StreamEnd} through its own - so the terminal signal cannot land ahead of an
     * event another thread is still publishing and have that event refused with
     * {@code FAIL_TERMINATED}.
     */
    private final Outbox<Parked> outbox = new Outbox<>(this::publish);

    /**
     * One thing waiting for the farm sink: a message, or the end of the stream.
     *
     * <p>A sealed interface so {@link #publish} is an exhaustive switch with no {@code default} -
     * a third kind of parked item fails the build instead of falling through a hole, the same
     * discipline {@code PrinterReactor.Parked} uses for its own sink.
     */
    private sealed interface Parked {

        record Update(EventMessage message) implements Parked {
        }

        /**
         * The farm saying goodbye to every subscriber at once - issue #87.
         *
         * <p>Parked rather than completing {@link #farm} straight from the caller's thread, for the
         * reason {@code PrinterReactor.Parked.StreamEnd} gives for itself: calling
         * {@code tryEmitComplete} while another thread is mid-drain terminates the sink underneath
         * an event that is still being published, and that event is then refused with
         * {@code FAIL_TERMINATED} rather than reaching anybody.
         */
        record End() implements Parked {
        }
    }

    /**
     * Set once {@link #close()}'s {@code End} has been published, and read only inside
     * {@link #publish}.
     *
     * <p>A plain field and not a volatile one, for the reason {@code PrinterReactor.streamEnded} is
     * one: {@code Outbox} calls its consumer from one thread at a time through an
     * {@code AtomicInteger}, so the hand-off from one draining thread to the next carries the
     * happens-before this needs.
     */
    private boolean closed;

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
        return CatchUp.snapshotThenLive("the farm stream",
                        () -> Flux.defer(() -> Flux.fromIterable(directory.all()))
                                .concatMap(entry -> currentStateOf(entry.id())),
                        farm.asFlux(), SUBSCRIBER_BACKLOG)
                .doOnSubscribe(s -> log.info("a farm-wide subscriber attached"));
    }

    /**
     * How far one dashboard may fall behind the farm before it loses a message. Forty machines
     * auto-reporting once a second is a few seconds of this, and a dashboard that far behind is
     * one that has stopped reading.
     */
    private static final int SUBSCRIBER_BACKLOG = 1024;

    /**
     * What a dashboard draws for one machine: where its connection is, and its last temperature if
     * it has reported one - both as the events that would have announced them.
     *
     * <p><strong>A dashboard's share of the machine's snapshot, not all of it.</strong>
     * {@link PrinterReactor#snapshot()} also carries the firmware and every capability, which a
     * printer's own page may want and a row on a forty-machine dashboard does not; forty of those
     * on every page load is a few thousand messages nobody draws.
     *
     * <p><strong>Non-blocking, which is not a style preference.</strong> The first version called
     * {@code reactor.connection().block()} while building the snapshot, and a snapshot is built on
     * whichever thread subscribed - for an SSE endpoint that is a netty event loop. Reactor's own
     * non-blocking-thread check turned it into a 500 rather than letting it stall the loop, which
     * is the only reason it was a bug report instead of a farm that went quiet under load. The
     * snapshot read is built from volatile references and queues behind nothing.
     */
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
     * Parks a message for the farm stream, and publishes what is parked if nobody else is.
     *
     * <p>It used to call {@code tryEmitNext} from whatever thread published the Spring event, and
     * log the {@code FAIL_NON_SERIALIZED} the safe sink answered a collision with. <strong>That log
     * line read exactly like the drop this sink was deliberately chosen for, and it was a different
     * thing wearing the same coat.</strong> {@code directBestEffort} drops for <em>backpressure</em>
     * - one slow browser, one event, for that subscriber only - which is wanted, and is why the sink
     * is still lossy. A refused emission costs <em>every</em> dashboard the same event and has
     * nothing to do with how fast anyone is reading. Two minutes of simulator traffic against one
     * printer lost an {@code IDLE -> CONNECTING} transition that way, and the warning it left behind
     * said the design was working as intended.
     *
     * @see Outbox
     */
    private void emit(EventMessage message) {
        outbox.emit(new Parked.Update(message));
    }

    /**
     * Ends the farm-wide stream - issue #87.
     *
     * <p>Called once, when the farm is shutting down, by {@code FarmShutdown}. A dashboard holding
     * {@code GET /api-farm/stream} open is an in-flight HTTP request for as long as this stream has
     * not told it otherwise, and Spring Boot's graceful shutdown waits for every in-flight request
     * before it gives up on them - thirty seconds by default, and then it gives up anyway. See
     * {@code FarmShutdown} for why this has to run before the web server starts that wait rather
     * than during it.
     *
     * <p><strong>A subscriber that attaches after this has already run still gets the snapshot,
     * then completes immediately.</strong> Every machine in it reads {@code OFFLINE}, because
     * {@link PrinterReactorManager#runtimeOf(UUID)} returns {@code null} for all of them once the
     * farm has released every runtime - which is the truth, not a stale answer, so there is nothing
     * dishonest about showing it on the way out. {@code CatchUp.snapshotThenLive} subscribes to the
     * live stream before it builds the snapshot; by the time a late subscriber's snapshot is built,
     * the live stream it subscribed to first has already finished, so the concatenation ends the
     * moment the snapshot does.
     *
     * <p>Idempotent for the reason {@code PrinterReactor.stop()} is: {@link #publish} only acts on
     * the first {@code End} it ever drains, through {@link #closed}.
     */
    public void close() {
        outbox.emit(new Parked.End());
    }

    /**
     * One parked item onto the sink. {@link #outbox} only, so no second thread is ever in here.
     *
     * <p>{@code FAIL_ZERO_SUBSCRIBER} is the normal case on a farm nobody is watching and not worth
     * a line. Everything else now says something real: {@code FAIL_NON_SERIALIZED} is unreachable
     * because this is the only path to the sink, and {@code directBestEffort} drops a subscriber
     * that cannot keep up <em>without</em> failing the emission - so a warning here is a cancelled
     * or terminated stream rather than a slow browser.
     *
     * <p>An exhaustive switch over {@link Parked} with no {@code default}: a third arm added there
     * without one added here fails the build instead of silently doing nothing.
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
                    // Nothing follows a terminal signal, ever. An event still travelling when the
                    // farm was told to close is the normal way to get here.
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

package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.protocol.GRq;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * This module's side of {@link PrinterControl}: the only way in from the web.
 *
 * <p>It exists so that {@code frontend} can stop naming {@code PrinterReactorManager}. A submodel
 * depending on another submodel is rule 1 of {@code doc/sub_boundaries.txt}, and the boundary check
 * could report that crossing but not prevent it.
 *
 * <p><strong>Nothing is translated here any more.</strong> This class used to hold a
 * {@code toMarlin} switch over a sealed {@code api.msg.PrinterCommand}, the outbound half of a
 * mirror of {@code :gcode} that {@code ReplyEvents} kept on the inbound side. Both are gone: a
 * command arrives as the {@code GRq} it is, and the one place that still turns a browser's words
 * into Marlin's is the controller that received them, because a request body is the web surface's
 * business and not the runtime's.
 */
@RequiredArgsConstructor
public class PrinterControlService implements PrinterControl {

    private final PrinterReactorManager manager;

    /** Held here rather than reached through {@link PrinterReactor} because a transfer outlives the request that started it and must be findable again to be stopped - state about work in progress, not about a printer. */
    private final SdUploader uploads;

    /** Opened off the caller's thread: {@code openReactor} is {@code synchronized}, and a monitor is still not something an event-loop thread should queue on for several requests to one printer. Deferred, so a request cancelled before subscription opens nothing at all. */
    private Mono<PrinterReactor> reactor(UUID id) {
        return Mono.fromSupplier(() -> manager.openReactor(id)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<HeaterReading> state(UUID id) {
        return reactor(id).flatMap(PrinterReactor::state);
    }

    @Override
    public Flux<EventMessage> updates(UUID id) {
        return reactor(id).flatMapMany(PrinterReactor::updates);
    }

    @Override
    public Mono<Void> send(UUID id, List<GRq<?>> commands) {
        return reactor(id).flatMap(r -> r.handle(commands));
    }

    /**
     * The reactor is opened first (with {@link PrinterReactorManager#openReactorIfKnown}, so an
     * unknown machine is an {@code ABSENT} outcome rather than an {@code IllegalArgumentException}
     * the web layer would have to classify), and handed to the uploader rather than looked up by it
     * - so "not connected" is a statement about this machine, not about the farm's bookkeeping, the
     * way the old {@code runtimeOf(id) == null} branch read.
     */
    @Override
    public Mono<ControlOutcome> uploadToCard(UUID id, UUID fileId, String cardName) {
        return Mono.fromSupplier(() -> manager.openReactorIfKnown(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(found -> found
                        .map(reactor -> uploads.start(reactor, id, fileId, cardName))
                        .orElseGet(() -> Mono.just(ControlOutcome.absent(id,
                                "the farm has no machine " + id))));
    }

    /**
     * Stops a transfer, opening nothing to do it: {@link PrinterReactorManager#knows}, not
     * {@code openReactorIfKnown}, since building a runtime (a socket's worth of resource, two
     * threads since #54) just to say "there is no such machine" would be wasted on a transfer that
     * is not running - the uploader's own map needs no reactor to be looked in. Nothing to stop
     * stays {@code Accepted}; the page's button and the machine's state are different clocks.
     */
    @Override
    public Mono<ControlOutcome> cancelCardUpload(UUID id) {
        return Mono.defer(() -> manager.knows(id)
                ? uploads.cancel(id).thenReturn(ControlOutcome.accepted(id))
                : Mono.just(ControlOutcome.absent(id, "the farm has no machine " + id)));
    }

    @Override
    public Mono<Void> connect(UUID id, ConnectCmd command) {
        return reactor(id).flatMap(r -> r.handleConnectCmd(Mono.just(command)));
    }

    @Override
    public Mono<ConnectionStatus> connection(UUID id) {
        return reactor(id).flatMap(PrinterReactor::connection);
    }
}

package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
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

    /**
     * The one operation that is not a command on a machine but a piece of work against it.
     *
     * <p>It is held here rather than reached through {@link PrinterReactor} because a transfer
     * outlives the request that started it and has to be findable again to be stopped - which is
     * state about work in progress, not about a printer.
     */
    private final SdUploader uploads;

    /**
     * The machine's runtime, opened off the caller's thread.
     *
     * <p>{@code openReactor} is {@code synchronized} and builds a resource on a miss. It no longer
     * reads a database - {@link PrinterDirectory} answers that - but a monitor is still not
     * something an event-loop thread should queue on when several requests arrive for one printer.
     *
     * <p>The deferral also means a request cancelled before it is subscribed opens nothing at all.
     */
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
     * The reactor is opened first, so that a transfer to a machine nobody has dialled fails for
     * that reason rather than for "there is no runtime".
     */
    @Override
    public Mono<Void> uploadToCard(UUID id, UUID fileId, String cardName) {
        return reactor(id).then(uploads.start(id, fileId, cardName));
    }

    @Override
    public Mono<Void> cancelCardUpload(UUID id) {
        return uploads.cancel(id);
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

package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.PrinterCommand;
import org.qw3rtrun.p3d.api.msg.RequestTemperatureReport;
import org.qw3rtrun.p3d.api.msg.SetBedTarget;
import org.qw3rtrun.p3d.api.msg.SetHotendTarget;
import org.qw3rtrun.p3d.api.msg.SetTemperatureAutoReport;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
import org.qw3rtrun.p3d.g.protocol.GRq;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * This module's side of {@link PrinterControl}: the only way in from the web.
 *
 * <p>It exists so that {@code frontend} can stop naming {@code PrinterReactorManager}. A submodel
 * depending on another submodel is rule 1 of {@code doc/sub_boundaries.txt}, and the boundary check
 * could report that crossing but not prevent it, because the alternative - an interface in
 * {@code api} - needed an application command model first. That model is
 * {@link PrinterCommand}, and this is what reads it.
 *
 * <p><strong>Where the outbound translation happens.</strong> {@code ReplyEvents} in
 * {@code :backend:terminal} turns the wire's reply model into the application's; this does the same
 * thing in the other direction, turning the application's commands into Marlin's. The REST surface
 * no longer binds generated `:gcode` classes, so a renamed parameter in Marlin's documentation is
 * no longer a breaking HTTP contract.
 */
@RequiredArgsConstructor
public class PrinterControlService implements PrinterControl {

    private final PrinterReactorManager manager;

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
    public Mono<TemperatureReport> state(UUID id) {
        return reactor(id).flatMap(PrinterReactor::state);
    }

    @Override
    public Flux<EventMessage> updates(UUID id) {
        return reactor(id).flatMapMany(PrinterReactor::updates);
    }

    @Override
    public Mono<Void> send(UUID id, PrinterCommand command) {
        return reactor(id).flatMap(r -> r.handle(Mono.just(toMarlin(command))));
    }

    @Override
    public Mono<Void> connect(UUID id, ConnectCmd command) {
        return reactor(id).flatMap(r -> r.handleConnectCmd(Mono.just(command)));
    }

    /**
     * One application command as the Marlin command that carries it.
     *
     * <p><strong>Exhaustive, with no default arm</strong>, because {@link PrinterCommand} is sealed
     * and this module owns every case. Adding a command to the application's vocabulary without
     * teaching this method to send it fails the build - which is the property plan item A5 wanted
     * for the inbound dispatch and could not have, since neither {@code GEvent} nor `:gcode`'s
     * {@code GRq} can be closed.
     *
     * <p>The scale of a temperature is the caller's to choose and reaches the wire as written;
     * {@code PrinterState} is what rounds it, because the wire format of a temperature is that
     * layer's business.
     */
    static GRq<?> toMarlin(PrinterCommand command) {
        return switch (command) {
            case SetHotendTarget c -> MarlinG.INSTANCE.m104(c.temp(), c.index() == null ? 0 : c.index());
            case SetBedTarget c -> MarlinG.INSTANCE.m140(c.temp());
            case RequestTemperatureReport c -> MarlinG.INSTANCE.m105(c.index());
            case SetTemperatureAutoReport c -> MarlinG.INSTANCE.m155(c.period());
        };
    }
}

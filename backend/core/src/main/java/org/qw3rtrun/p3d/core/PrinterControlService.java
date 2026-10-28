package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.PrinterCommand;
import org.qw3rtrun.p3d.api.msg.RequestTemperatureReport;
import org.qw3rtrun.p3d.api.msg.SetBedTarget;
import org.qw3rtrun.p3d.api.msg.SetHotendTarget;
import org.qw3rtrun.p3d.api.msg.SetTemperatureAutoReport;
import org.qw3rtrun.p3d.api.msg.HomeAxes;
import org.qw3rtrun.p3d.api.msg.MoveAxes;
import org.qw3rtrun.p3d.api.msg.RequestPosition;
import org.qw3rtrun.p3d.api.msg.SetFanSpeed;
import org.qw3rtrun.p3d.api.msg.StoreSettings;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.g.marlin.MarlinG;
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
        return reactor(id).flatMap(r -> r.handle(toMarlin(command)));
    }

    @Override
    public Mono<Void> connect(UUID id, ConnectCmd command) {
        return reactor(id).flatMap(r -> r.handleConnectCmd(Mono.just(command)));
    }

    @Override
    public Mono<ConnectionStatus> connection(UUID id) {
        return reactor(id).flatMap(PrinterReactor::connection);
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
    static List<GRq<?>> toMarlin(PrinterCommand command) {
        return switch (command) {
            case SetHotendTarget c ->
                    List.of(MarlinG.INSTANCE.m104(c.temp(), c.index() == null ? 0 : c.index()));
            case SetBedTarget c -> List.of(MarlinG.INSTANCE.m140(c.temp()));
            case RequestTemperatureReport c -> List.of(MarlinG.INSTANCE.m105(c.index()));
            case SetTemperatureAutoReport c -> List.of(MarlinG.INSTANCE.m155(c.period()));

            case HomeAxes c -> List.of(c.all()
                    ? new org.qw3rtrun.p3d.g.marlin.command.AutoHome()
                    : new org.qw3rtrun.p3d.g.marlin.command.AutoHome(
                            false, null, false, null,
                            Boolean.TRUE.equals(c.x()),
                            Boolean.TRUE.equals(c.y()),
                            Boolean.TRUE.equals(c.z()),
                            false, false, false, false, false, false));

            case MoveAxes c -> move(c);

            // The fan index goes in P and not in I. Marlin's own documentation lists both as
            // "index" and the generated class carries both fields, but P is the one mainline
            // firmware reads as the fan number - and it is what M107 uses, so the pair stays
            // consistent. A null index sends no index word at all, which means fan 0.
            case SetFanSpeed c -> List.of(c.off()
                    ? new org.qw3rtrun.p3d.g.marlin.command.FanOff(c.index())
                    : new org.qw3rtrun.p3d.g.marlin.command.SetFanSpeed(
                            null, c.speed(), c.index(), null));

            case StoreSettings ignored ->
                    List.of(new org.qw3rtrun.p3d.g.marlin.command.SaveSettings());

            case RequestPosition ignored ->
                    List.of(new org.qw3rtrun.p3d.g.marlin.command.GetCurrentPosition(
                            false, false, false));
        };
    }

    /**
     * A move, and the mode switch a relative one needs around it.
     *
     * <p>Marlin has no per-command relative flag: {@code G91} puts the machine in relative mode
     * and it <strong>stays there</strong> until {@code G90} puts it back. So a relative jog is
     * three commands, and leaving the machine in relative mode by forgetting the third would make
     * every later absolute move - including a print's - land somewhere else entirely.
     *
     * <p>That is also why they are returned together rather than sent as three requests:
     * {@code PrinterReactor.handle} applies the whole list as one task on the aggregate's thread,
     * so no other command can be interpreted while the machine is in the wrong mode.
     */
    private static List<GRq<?>> move(MoveAxes c) {
        var g1 = new org.qw3rtrun.p3d.g.marlin.command.LinearMoveG1(
                c.x(), c.y(), c.z(), null, null, null, null, null, null,
                c.e(), c.feedRate(), null);
        if (!c.isRelative()) {
            return List.of(g1);
        }
        return List.of(
                new org.qw3rtrun.p3d.g.marlin.command.RelativePositioning(),
                g1,
                new org.qw3rtrun.p3d.g.marlin.command.AbsolutePositioning());
    }
}

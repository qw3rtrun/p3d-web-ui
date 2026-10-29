package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.frontend.command.HomeAxes;
import org.qw3rtrun.p3d.frontend.command.MoveAxes;
import org.qw3rtrun.p3d.frontend.command.PrinterCommand;
import org.qw3rtrun.p3d.frontend.command.RequestPosition;
import org.qw3rtrun.p3d.frontend.command.RequestTemperatureReport;
import org.qw3rtrun.p3d.frontend.command.SetBedTarget;
import org.qw3rtrun.p3d.frontend.command.SetFanSpeed;
import org.qw3rtrun.p3d.frontend.command.SetHotendTarget;
import org.qw3rtrun.p3d.frontend.command.SetTemperatureAutoReport;
import org.qw3rtrun.p3d.frontend.command.StoreSettings;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * One machine, over HTTP: its state, its live stream, and the handful of things it can be told.
 *
 * <p><strong>It names nothing in {@code core}.</strong> Every handler goes through
 * {@link PrinterControl}, an interface in {@code api}, which is rule 1 of
 * {@code doc/sub_boundaries.txt} holding rather than being reported. The controllers used to call
 * {@code PrinterReactorManager} directly, and closing that needed an application command model
 * first: the interface could not take a `:gcode` command class without {@code api} depending on
 * `:gcode`.
 *
 * <p><strong>The request bodies are this module's records, and they translate themselves.</strong>
 * They are not a model of what a printer can do - that is `:gcode`'s, and the runtime speaks it
 * directly now - they are the shape the browser posts, which is a property of this surface. Binding
 * `:gcode`'s generated classes here instead would make the HTTP contract follow the Marlin command
 * set, where a field the generator renames is a breaking API change; keeping a second command model
 * in `api` for it, which is what this used to do, made the whole application carry a hand-written
 * mirror of the protocol. See {@link PrinterCommand}.
 *
 * <p>The scheduling that used to live here moved with the lookup, into
 * {@code PrinterControlService}: which thread a runtime is opened on is that module's business, not
 * this one's.
 */
@Slf4j
@RestController
@RequestMapping("/api-printer/{id}")
@RequiredArgsConstructor
public class PrinterController {

    private final PrinterControl printers;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<HeaterReading> getState(@PathVariable UUID id) {
        return Mono.from(printers.state(id));
    }

    /**
     * Where this machine's connection is, right now.
     *
     * <p>The health signal of plan item B3, answered without opening the stream. The page used to
     * have no way to ask: it inferred "online" from the fact that <em>any</em> event had arrived on
     * the SSE channel, which meant a printer that was connected but quiet read as offline until it
     * said something.
     */
    @GetMapping(value = "/connection", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ConnectionStatus> getConnection(@PathVariable UUID id) {
        return Mono.from(printers.connection(id));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<EventMessage> getUpdates(@PathVariable UUID id) {
        return Flux.from(printers.updates(id));
    }

    @PostMapping("/set-temp")
    public Mono<Void> setTemp(@PathVariable UUID id, @RequestBody Mono<SetHotendTarget> command) {
        return send(id, command);
    }

    @PostMapping("/set-bed-temp")
    public Mono<Void> setBedTemp(@PathVariable UUID id, @RequestBody Mono<SetBedTarget> command) {
        return send(id, command);
    }

    @PostMapping("/auto-report-temp")
    public Mono<Void> autoReportTemp(@PathVariable UUID id, @RequestBody Mono<SetTemperatureAutoReport> command) {
        return send(id, command);
    }

    @PostMapping("/report-temp")
    public Mono<Void> reportTemp(@PathVariable UUID id) {
        return send(id, new RequestTemperatureReport(null));
    }

    /**
     * Send the named axes home - {@code G28}.
     *
     * <p>Naming no axis homes everything, which is Marlin's own rule. Homing is a move, and a slow
     * one that drags the nozzle across whatever is on the bed, so it is something an operator asks
     * for rather than something another endpoint does on their behalf.
     */
    @PostMapping("/home")
    public Mono<Void> home(@PathVariable UUID id, @RequestBody Mono<HomeAxes> command) {
        return send(id, command);
    }

    /**
     * Move the toolhead - a jog when {@code relative}, a positioning move when not.
     *
     * <p>A relative move reaches the machine as three commands with the mode switch around it;
     * {@code core} sends them as one unit, so nothing can land between them and be read in the
     * wrong mode. See {@link MoveAxes}.
     */
    @PostMapping("/move")
    public Mono<Void> move(@PathVariable UUID id, @RequestBody Mono<MoveAxes> command) {
        return send(id, command);
    }

    /** Set a part-cooling fan, on Marlin's 0..255 scale. Zero is sent as {@code M107}. */
    @PostMapping("/fan")
    public Mono<Void> fan(@PathVariable UUID id, @RequestBody Mono<SetFanSpeed> command) {
        return send(id, command);
    }

    /**
     * Write the machine's current settings to its EEPROM - {@code M500}.
     *
     * <p>The one command here whose effect outlives the connection, which is why it is a
     * {@code POST} with a body rather than something a stray link could trigger - and why, like
     * every write under this path, it is OPERATOR-only.
     */
    @PostMapping("/store-settings")
    public Mono<Void> storeSettings(@PathVariable UUID id,
                                    @RequestBody Mono<StoreSettings> command) {
        return send(id, command);
    }

    /**
     * Ask where the toolhead is - {@code M114}.
     *
     * <p>The answer does not come back here. It arrives on the event stream as
     * {@code PositionReported}, because every reply in this application is an event: a reply can
     * also arrive unasked, and a caller that waited only for its own would miss those.
     */
    @PostMapping("/position")
    public Mono<Void> position(@PathVariable UUID id) {
        return send(id, new RequestPosition());
    }

    @PostMapping("/connect")
    public Mono<Void> connect(@PathVariable UUID id, @RequestBody Mono<ConnectCmd> cmd) {
        log.info("Cmd Connect received");
        return cmd.flatMap(c -> Mono.from(printers.connect(id, c)));
    }

    private Mono<Void> send(UUID id, Mono<? extends PrinterCommand> command) {
        return command.flatMap(c -> send(id, c));
    }

    /**
     * Where a request body becomes G-code, and the only place in this application that still does.
     *
     * <p>One command can be several: {@link MoveAxes#toMarlin()} answers a relative jog with
     * {@code G91}, the move and {@code G90}, and {@code PrinterControl.send} takes the list as a
     * unit so nothing can land between them.
     */
    private Mono<Void> send(UUID id, PrinterCommand command) {
        return Mono.from(printers.send(id, command.toMarlin()));
    }
}

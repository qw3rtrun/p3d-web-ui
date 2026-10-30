package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.frontend.command.DeleteFile;
import org.qw3rtrun.p3d.frontend.command.HomeAxes;
import org.qw3rtrun.p3d.frontend.command.ListFiles;
import org.qw3rtrun.p3d.frontend.command.MountCard;
import org.qw3rtrun.p3d.frontend.command.MoveAxes;
import org.qw3rtrun.p3d.frontend.command.PrintFile;
import org.qw3rtrun.p3d.frontend.command.PrinterCommand;
import org.qw3rtrun.p3d.frontend.command.ReleaseCard;
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

    /**
     * Mount the card - {@code M21}, and the only way to ask whether there is one.
     *
     * <p>No command asks "is a card present", so the Storage tab infers it: the machine answers
     * {@code echo:SD card ok} when there is one, which reaches the browser as {@code SdCardOk} on
     * the event stream. Anything else is read as no card. See {@link MountCard}.
     */
    @PostMapping("/sd/mount")
    public Mono<Void> mountCard(@PathVariable UUID id) {
        return send(id, new MountCard());
    }

    /**
     * Release the card - {@code M22}.
     *
     * <p>Ends a print that is running from it, because the reader is the job's source. The page
     * disables the button while the machine is printing from the card; this endpoint does not
     * second-guess that, for the reason {@link ReleaseCard} gives.
     */
    @PostMapping("/sd/release")
    public Mono<Void> releaseCard(@PathVariable UUID id) {
        return send(id, new ReleaseCard());
    }

    /**
     * Ask for the card's contents - {@code M20}.
     *
     * <p>The listing does not come back here. It arrives on the event stream as one
     * {@code SdFileList}, which the transport assembles from the three or more lines the firmware
     * writes - a reply is an event in this application, and this one is several lines besides.
     *
     * <p>Only printable extensions are listed, which is Marlin's filter and not this one's: a file
     * uploaded under another name is on the card and invisible here. See {@link ListFiles}.
     */
    @PostMapping("/sd/list")
    public Mono<Void> listFiles(@PathVariable UUID id) {
        return send(id, new ListFiles());
    }

    /**
     * Delete a file from the card - {@code M30}.
     *
     * <p>Irreversible and unconfirmed by the machine, so the confirmation is the page's. The name
     * must be the card's own spelling - what the listing reported - because that is what the
     * firmware matches on.
     */
    @PostMapping("/sd/delete")
    public Mono<Void> deleteFile(@PathVariable UUID id, @RequestBody Mono<DeleteFile> command) {
        return send(id, command);
    }

    /**
     * Start printing a file already on the card - {@code M23} then {@code M24}.
     *
     * <p>Two commands sent as one unit, because {@code :gcode}'s {@code M32} cannot carry a
     * filename - see {@link PrintFile}, which records why. Watching the print is a different screen
     * and a different issue; this only starts it.
     */
    @PostMapping("/sd/print")
    public Mono<Void> printFile(@PathVariable UUID id, @RequestBody Mono<PrintFile> command) {
        return send(id, command);
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

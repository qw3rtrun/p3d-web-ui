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
import org.qw3rtrun.p3d.frontend.command.ReadSettings;
import org.qw3rtrun.p3d.frontend.command.ReleaseCard;
import org.qw3rtrun.p3d.frontend.command.RequestPosition;
import org.qw3rtrun.p3d.frontend.command.RequestTemperatureReport;
import org.qw3rtrun.p3d.frontend.command.ResetSettingsToDefaults;
import org.qw3rtrun.p3d.frontend.command.RevertSettings;
import org.qw3rtrun.p3d.frontend.command.SetAccelerations;
import org.qw3rtrun.p3d.frontend.command.SetAdvancedMotion;
import org.qw3rtrun.p3d.frontend.command.SetBedTarget;
import org.qw3rtrun.p3d.frontend.command.SetFanSpeed;
import org.qw3rtrun.p3d.frontend.command.SetHomeOffset;
import org.qw3rtrun.p3d.frontend.command.SetHotendTarget;
import org.qw3rtrun.p3d.frontend.command.SetMaxAccelerations;
import org.qw3rtrun.p3d.frontend.command.SetMaxFeedrates;
import org.qw3rtrun.p3d.frontend.command.SetStepsPerUnit;
import org.qw3rtrun.p3d.frontend.command.SetTemperatureAutoReport;
import org.qw3rtrun.p3d.frontend.command.StoreSettings;
import org.qw3rtrun.p3d.frontend.command.ValidateSettings;
import org.qw3rtrun.p3d.frontend.command.UploadToCard;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
     * Ask the machine what its settings are - {@code M503}.
     *
     * <p>The report does not come back here. It arrives on the event stream as a run of
     * {@code SettingsSection} and {@code SettingsValue} events - one per line of the dump, in the
     * order the firmware wrote them - which is how a value ends up belonging to the section above
     * it. The aggregate keeps the run and hands it to a late subscriber, so a tab opened after
     * somebody else pressed Read is given the report rather than an empty table.
     *
     * <p><strong>A firmware built with {@code DISABLE_M503} answers this with a refusal</strong>,
     * and that refusal is the only notice anybody gets: there is no capability line for it, under
     * any spelling. It reaches the browser as the firmware's own {@code UnknownCommand} naming
     * {@code M503}. See {@link ReadSettings}.
     */
    @PostMapping("/settings/read")
    public Mono<Void> readSettings(@PathVariable UUID id) {
        return send(id, new ReadSettings());
    }

    /**
     * Load the EEPROM's copy back over live memory - {@code M501}.
     *
     * <p>The undo for this session's edits, and the recoverable one of the two discarding
     * operations: what is stored stays stored, so the cost is only the changes that were never
     * saved. That is why the page asks for no confirmation here and does for the other two.
     *
     * <p><strong>The firmware says nothing about what it changed</strong> - a bare {@code ok} -
     * so the aggregate re-reads behind this one and the new values arrive on the stream. A browser
     * does not have to prompt for a Read afterwards, and must not: the values simply turn up.
     */
    @PostMapping("/settings/revert")
    public Mono<Void> revertSettings(@PathVariable UUID id) {
        return send(id, new RevertSettings());
    }

    /**
     * Load the values the firmware was compiled with - {@code M502}.
     *
     * <p><strong>The consequence that outlives the connection is one command away, which is why
     * this deserves the same care as {@code /store-settings}.</strong> On its own it changes live
     * memory and the EEPROM still holds the machine's calibration, so {@code /settings/revert}
     * recovers from a mistake. Followed by {@code /store-settings} it writes the compiled defaults
     * over the only record of every calibration the machine has ever been given - and nothing
     * anywhere can get those back. The pair is destructive; neither half is, alone.
     *
     * <p>Measured rather than assumed: on MarlinSim the stored settings and the defaults genuinely
     * differ, so this is not the no-op it can look like on a board nobody has tuned.
     *
     * <p>Like {@code M501} it announces nothing, so the aggregate re-reads. See
     * {@link ResetSettingsToDefaults}.
     */
    @PostMapping("/settings/defaults")
    public Mono<Void> resetSettingsToDefaults(@PathVariable UUID id) {
        return send(id, new ResetSettingsToDefaults());
    }

    /**
     * Ask whether what is in the EEPROM is readable - {@code M504}.
     *
     * <p>The verdict arrives on the event stream as an {@code EepromReport} carrying the firmware's
     * words verbatim. {@code echo:EEPROM OK} is what has been measured; <strong>the failure text
     * has not</strong>, which is why there is no boolean anywhere in this path for a page to render
     * a tick or a cross from. Changes nothing, so it is safe on a machine mid-print.
     */
    @PostMapping("/settings/validate")
    public Mono<Void> validateSettings(@PathVariable UUID id) {
        return send(id, new ValidateSettings());
    }

    /**
     * Steps per millimetre, per axis - {@code M92}.
     *
     * <p><strong>Live memory only.</strong> Every endpoint in this group changes what the firmware
     * is using and nothing else; the machine forgets it on a power cycle unless
     * {@code /store-settings} is called. The split is the point of the feature rather than an
     * omission - an operator calibrating an extruder wants to try a value and print before
     * committing it to flash.
     *
     * <p>A null axis is left alone, which is not the same as zero: an axis with no steps per
     * millimetre cannot move. See {@link SetStepsPerUnit}.
     */
    @PostMapping("/settings/steps-per-unit")
    public Mono<Void> setStepsPerUnit(@PathVariable UUID id,
                                      @RequestBody Mono<SetStepsPerUnit> command) {
        return send(id, command);
    }

    /**
     * The speed ceiling for each axis - {@code M203}, in mm per <strong>second</strong>.
     *
     * <p>Not the same unit as a move's {@code F}, which is mm per minute. Live memory only, and a
     * null axis is left alone. See {@link SetMaxFeedrates}.
     */
    @PostMapping("/settings/max-feedrate")
    public Mono<Void> setMaxFeedrates(@PathVariable UUID id,
                                      @RequestBody Mono<SetMaxFeedrates> command) {
        return send(id, command);
    }

    /**
     * The acceleration ceiling for each axis - {@code M201}, in mm/s².
     *
     * <p>What the hardware can take, as against what a move is asked for by
     * {@code /settings/acceleration}. Marlin applies the lower of the two. Live memory only. See
     * {@link SetMaxAccelerations}.
     */
    @PostMapping("/settings/max-acceleration")
    public Mono<Void> setMaxAccelerations(@PathVariable UUID id,
                                          @RequestBody Mono<SetMaxAccelerations> command) {
        return send(id, command);
    }

    /**
     * What a print, retract or travel move is asked to accelerate at - {@code M204 P R T}.
     *
     * <p>Clamped by {@code /settings/max-acceleration}, so a value above that ceiling changes
     * nothing. Live memory only. See {@link SetAccelerations}.
     */
    @PostMapping("/settings/acceleration")
    public Mono<Void> setAccelerations(@PathVariable UUID id,
                                       @RequestBody Mono<SetAccelerations> command) {
        return send(id, command);
    }

    /**
     * The planner's own limits - {@code M205 B S T J}.
     *
     * <p>Four unrelated numbers in one Marlin command; the jerk words the same command carries are
     * deliberately not exposed, because a build using junction deviation ignores them while
     * appearing to accept them. Live memory only. See {@link SetAdvancedMotion}.
     */
    @PostMapping("/settings/advanced")
    public Mono<Void> setAdvancedMotion(@PathVariable UUID id,
                                        @RequestBody Mono<SetAdvancedMotion> command) {
        return send(id, command);
    }

    /**
     * Where the machine thinks its origin is - {@code M206 X Y Z}.
     *
     * <p>Added to every position after homing, so the {@code Z} value is the one that decides how
     * close a nozzle comes to the bed. A null axis is left alone - an {@code M206} naming one axis
     * must not zero the other two. Live memory only. See {@link SetHomeOffset}.
     */
    @PostMapping("/settings/home-offset")
    public Mono<Void> setHomeOffset(@PathVariable UUID id,
                                    @RequestBody Mono<SetHomeOffset> command) {
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

    /**
     * Write a file the server holds onto the card - {@code M28}, the file, {@code M29}.
     *
     * <p><strong>It answers when the transfer has started, not when it has finished.</strong> Every
     * line is a round trip inside the send window, so a real file takes minutes; a request held
     * open for that is a request that times out. Progress and the end both arrive on the event
     * stream as {@code SdUploadProgressed}.
     *
     * <p>The bytes are not in this request. The browser posts them to {@code /api-files} first and
     * names the result here - see {@link UploadToCard}.
     *
     * <p><strong>The machine takes nothing else until it is over.</strong> That is not a policy of
     * this endpoint, it is what {@code M28} does to the wire, and the transport enforces it by
     * holding the connection exclusively for the transfer.
     *
     * <p><strong>There are three ways this is refused, and none of them is a 500 any more.</strong>
     * A machine the farm does not have and a file the store does not hold are both <em>404</em>; a
     * machine that is not connected, and one that is already writing to its card, are both
     * <em>409</em> - the request was well formed and the caller was entitled to make it, the
     * printer simply is not where they thought. The body is the refusal, with a sentence a page can
     * show. {@link Outcomes} is where that mapping is decided, once for this module.
     *
     * <p><strong>Accepted is 200 with an empty body and not 202</strong>, which it arguably should
     * be. That is the contract the browser and the tests beside it already hold, and changing it is
     * a separate decision from stopping this endpoint answering 500 to a disconnected printer. Left
     * alone deliberately.
     *
     * <p>A name that would corrupt the block is still a <em>400</em>, raised by
     * {@link UploadToCard}'s compact constructor while the body is being bound - a decoding failure
     * rather than an outcome, and nothing about it goes through {@code Outcomes}.
     */
    @PostMapping("/sd/upload")
    public Mono<ResponseEntity<?>> uploadToCard(@PathVariable UUID id, @RequestBody Mono<UploadToCard> command) {
        return command.flatMap(c -> Mono.from(printers.uploadToCard(id, c.fileId(), c.name())))
                .map(Outcomes::of);
    }

    /**
     * Stop a transfer that is running - and close the file behind it.
     *
     * <p>Not a cancel in the Reactive Streams sense, deliberately: the content stops and
     * {@code M29} still goes out, so the card is left with a short but <em>closed</em> file.
     * Tearing it out instead would leave the file open, and the next command the machine was given
     * would be written into it.
     *
     * <p>Answers OK whether or not anything was running. The page's button and the machine's state
     * are different clocks, so nothing to stop is <em>accepted</em> rather than refused.
     *
     * <p>The one refusal it can give is a <em>404</em>, for a machine the farm does not have. There
     * is no 409 here by construction - see {@code PrinterControl.cancelCardUpload}, which records
     * why. As above, accepted is 200 with an empty body rather than 202, because that is what the
     * browser already expects.
     */
    @PostMapping("/sd/upload/cancel")
    public Mono<ResponseEntity<?>> cancelCardUpload(@PathVariable UUID id) {
        return Mono.from(printers.cancelCardUpload(id)).map(Outcomes::of);
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

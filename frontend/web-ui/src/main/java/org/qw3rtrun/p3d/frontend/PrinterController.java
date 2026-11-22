package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.frontend.command.DeleteFile;
import org.qw3rtrun.p3d.frontend.command.HomeAxes;
import org.qw3rtrun.p3d.frontend.command.ListFiles;
import org.qw3rtrun.p3d.frontend.command.MountCard;
import org.qw3rtrun.p3d.frontend.command.MoveAxes;
import org.qw3rtrun.p3d.frontend.command.PauseSdPrint;
import org.qw3rtrun.p3d.frontend.command.PrintFile;
import org.qw3rtrun.p3d.frontend.command.PrinterCommand;
import org.qw3rtrun.p3d.frontend.command.ReadEndstops;
import org.qw3rtrun.p3d.frontend.command.ReadSettings;
import org.qw3rtrun.p3d.frontend.command.ReleaseCard;
import org.qw3rtrun.p3d.frontend.command.ResumeSdPrint;
import org.qw3rtrun.p3d.frontend.command.StopSdPrint;
import org.qw3rtrun.p3d.frontend.command.SendRawLine;
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
 * <p>Every handler goes through {@link PrinterControl} ({@code api}), never {@code core} directly -
 * rule 1 of {@code doc/sub_boundaries.txt}. Request bodies are this module's own records
 * ({@code frontend/command}), not a mirror of `:gcode`'s generated classes - binding those here
 * would make the HTTP contract follow the Marlin command set - so each record translates itself via
 * {@code toMarlin()}. See {@link PrinterCommand}.
 *
 * <p><strong>Every reply is an event, never a return value.</strong> {@code /stream} is the one
 * channel for state the machine reports asynchronously - temperature, position, settings dumps, file
 * listings, upload progress - because a reply can also arrive unasked and a caller waiting only for
 * its own would miss those. {@code getState}/{@code getConnection} are read back directly, the
 * latter being the health signal answered without opening the stream.
 *
 * <p><strong>{@code /settings/*} endpoints change live memory only</strong> unless said otherwise; a
 * null axis/value is left alone, not treated as zero. {@code /store-settings} ({@code M500}) is the
 * one write here whose effect outlives the connection and, like every write under this path, is
 * OPERATOR-only. {@code /settings/revert} ({@code M501}) and {@code /settings/defaults}
 * ({@code M502}) both answer with a bare {@code ok}, so the aggregate re-reads behind them and the
 * new values simply arrive on the stream - a browser must not prompt for a Read afterwards. Measured
 * on MarlinSim: the compiled defaults genuinely differ from a tuned machine's stored settings, so
 * {@code /settings/defaults} followed by {@code /store-settings} is destructive even though neither
 * step is, alone. A firmware built with {@code DISABLE_M503} answers {@code /settings/read} with its
 * own {@code UnknownCommand} naming {@code M503} - there is no capability line for it. {@code
 * /settings/validate} ({@code M504}) reports {@code echo:EEPROM OK} verbatim on success; the failure
 * text has not been observed, which is why nothing here renders a boolean for it.
 *
 * <p><strong>{@code /endstops/read}</strong> ({@code M119}) is the plainest shape on this surface and
 * is worth naming as the one with no gate and no refusal: no {@code Cap:} line reports it and no
 * firmware has been measured declining it, so it is always sent. Its answer is one event - an
 * {@code EndstopReport} carrying every endstop (issue #35) - and the aggregate keeps it, so a panel
 * opened after somebody else pressed the button is handed it without asking again. Which endstops a
 * machine reports, and how many, is the machine's business.
 *
 * <p><strong>{@code /sd/*} endpoints:</strong> card presence is inferred, not reported - {@code
 * /sd/mount} ({@code M21}) succeeds silently and presence shows up as {@code SdCardOk} on the
 * stream. {@code /sd/list} ({@code M20}) returns Marlin's printable extensions only. {@code
 * /sd/print} sends {@code M23} then {@code M24} as one unit, and stays a pair because
 * {@code M32} would read a {@code p} in the filename as a parameter and print a second file after
 * it - #109, measured; {@link PrintFile} carries the capture. {@code /sd/upload}
 * ({@code M28}, the file, {@code M29}) answers when the
 * transfer has <em>started</em>: a real file is minutes of round trips inside the send window, and
 * the wire takes nothing else until {@code M29} closes it. Refusals are 404 (unknown machine or
 * file) or 409 (not connected, or already writing) rather than 500; {@link Outcomes} holds that
 * mapping. Accepted is 200 with an empty body, not 202 - the existing browser/test contract, kept
 * deliberately. A name that would corrupt the block is a 400 raised by {@link UploadToCard}'s
 * compact constructor, before {@code Outcomes} ever sees it. <strong>{@code binary} on that body
 * asks for Marlin's Binary Protocol instead</strong> ({@code M28 B1}, packets, then an {@code M21}
 * because the firmware releases the card when the transfer closes); it defaults to {@code false}
 * when omitted, and a machine that never reported {@code Cap:BINARY_FILE_TRANSFER} - or a file over
 * {@code p3d.sd.binary-max-bytes}, since a binary transfer is held in memory - is a 409 rather than
 * a silent fall back to ASCII: the page disables the checkbox for an incapable machine, but a client
 * that ignored that is told so instead of being given a mode it did not ask for. Which mode actually
 * ran is observable on the stream as {@code SdUploadProgressed.binary}. <strong>{@code trim} on that
 * body asks for comments and blank lines to be dropped</strong> (#146) - a question only a binary
 * transfer has to answer, since the ASCII write loop strips on every transfer and always has, so
 * this is the byte-exact path being asked to behave the same way. It defaults to {@code false} when
 * omitted, keeping a binary transfer byte-exact, and nothing refuses it: there is no capability
 * behind it and no second mode to fall back to. What actually happened is
 * {@code SdUploadProgressed.trimmed}, which an ASCII transfer reports {@code true} as a fact rather
 * than as a choice. {@code /sd/upload/cancel} is not a
 * Reactive Streams cancel: the content stops but {@code M29} still goes out, closing a short but
 * valid file; it answers 200 whether or not anything was running, and its only refusal is 404.
 *
 * <p><strong>{@code /sd/pause}, {@code /sd/resume} and {@code /sd/stop}</strong> (#147) act on
 * whatever the card is already printing, so none of them takes a body. {@code /sd/resume} sends
 * {@code M24} - the same command {@code /sd/print} ends with, which the firmware reads as a resume
 * once a file is selected ({@link PrintFile}'s javadoc carries the capture) - and {@code /sd/stop}
 * sends {@code M524} alone, with no cooldown behind it; see {@link StopSdPrint} for why. None of the
 * three is gated or refused, and that is also the limit of what they report back: Marlin's
 * {@code SD printing byte} answer to {@code M27} does not distinguish a paused print from a running
 * one, so a page cannot tell the two apart and must not pretend to.
 *
 * <p><strong>{@code /emergency-stop} is the second route here that is not a {@link PrinterCommand}</strong>
 * (#147), and for the opposite reason to {@code /terminal} below: {@code M112} has to reach the wire
 * ahead of everything already queued, including an {@code M28} transfer holding it exclusively, which
 * {@code PrinterControl.send} cannot promise for a translated command. It is not the escape hatch
 * {@link PrinterCommand} rules out - it is a named intent with no payload whose only unusual property
 * is its priority.
 *
 * <p><strong>{@code /terminal} is the one endpoint that takes G-code text</strong> (#135), and it is
 * a narrow exception rather than an escape hatch: everything with a shape keeps its own endpoint and
 * its own payload record, and a control that posted to {@code /terminal} to do what a named command
 * should do would be a defect. See {@link PrinterCommand} and §6.3 of
 * {@code doc/specs/INTEGRATION_TESTS_spec.md}. It is at least {@code OPERATOR}, like every other
 * {@code POST} here; its companion {@code /terminal/stream} is readable by a {@code VIEWER}, like
 * every other {@code GET} - so a viewer may watch the console and may not type into it, which
 * {@code FarmSecurityTest} asserts rather than assumes.
 */
@RestController
@RequestMapping("/api-printer/{id}")
@RequiredArgsConstructor
public class PrinterController {

    private final PrinterControl printers;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<HeaterReading> getState(@PathVariable UUID id) {
        return Mono.from(printers.state(id));
    }

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

    // G28: naming no axis homes everything, Marlin's own default.
    @PostMapping("/home")
    public Mono<Void> home(@PathVariable UUID id, @RequestBody Mono<HomeAxes> command) {
        return send(id, command);
    }

    // A relative move reaches the machine as G91, the move, G90 - sent as one unit (see MoveAxes)
    // so nothing can land between them and be read in the wrong mode.
    @PostMapping("/move")
    public Mono<Void> move(@PathVariable UUID id, @RequestBody Mono<MoveAxes> command) {
        return send(id, command);
    }

    // Marlin's 0..255 scale; zero is sent as M107, not M106 S0.
    @PostMapping("/fan")
    public Mono<Void> fan(@PathVariable UUID id, @RequestBody Mono<SetFanSpeed> command) {
        return send(id, command);
    }

    // A POST with a body, not a link a stray GET could trigger - this write outlives the connection.
    @PostMapping("/store-settings")
    public Mono<Void> storeSettings(@PathVariable UUID id,
                                    @RequestBody Mono<StoreSettings> command) {
        return send(id, command);
    }

    // M503's dump arrives as one SettingsReport, kept by the aggregate so a tab opened late still
    // gets the report rather than an empty table.
    @PostMapping("/settings/read")
    public Mono<Void> readSettings(@PathVariable UUID id) {
        return send(id, new ReadSettings());
    }

    // M119. Answers as one EndstopReport; a tab opened late still gets the last-known report from
    // the aggregate's kept run, same as /settings/read. Nothing gates it - no capability names it
    // and no refusal of it has been measured.
    @PostMapping("/endstops/read")
    public Mono<Void> readEndstops(@PathVariable UUID id) {
        return send(id, new ReadEndstops());
    }

    @PostMapping("/settings/revert")
    public Mono<Void> revertSettings(@PathVariable UUID id) {
        return send(id, new RevertSettings());
    }

    @PostMapping("/settings/defaults")
    public Mono<Void> resetSettingsToDefaults(@PathVariable UUID id) {
        return send(id, new ResetSettingsToDefaults());
    }

    // The verdict arrives on the stream as an EepromReport carrying the firmware's words verbatim.
    @PostMapping("/settings/validate")
    public Mono<Void> validateSettings(@PathVariable UUID id) {
        return send(id, new ValidateSettings());
    }

    // M92, steps per millimetre: live memory only, so an operator can try a value and print before
    // committing it with /store-settings. A null axis is left alone - it is not the same as zero.
    @PostMapping("/settings/steps-per-unit")
    public Mono<Void> setStepsPerUnit(@PathVariable UUID id,
                                      @RequestBody Mono<SetStepsPerUnit> command) {
        return send(id, command);
    }

    // M203, mm/second - not the same unit as a move's F, which is mm/minute.
    @PostMapping("/settings/max-feedrate")
    public Mono<Void> setMaxFeedrates(@PathVariable UUID id,
                                      @RequestBody Mono<SetMaxFeedrates> command) {
        return send(id, command);
    }

    // M201, mm/s^2 - the hardware ceiling; Marlin applies the lower of this and /settings/acceleration.
    @PostMapping("/settings/max-acceleration")
    public Mono<Void> setMaxAccelerations(@PathVariable UUID id,
                                          @RequestBody Mono<SetMaxAccelerations> command) {
        return send(id, command);
    }

    // M204 P R T - clamped by /settings/max-acceleration, so a value above that ceiling changes nothing.
    @PostMapping("/settings/acceleration")
    public Mono<Void> setAccelerations(@PathVariable UUID id,
                                       @RequestBody Mono<SetAccelerations> command) {
        return send(id, command);
    }

    // M205 B S T J - the jerk words it also carries are deliberately not exposed: a build using
    // junction deviation ignores them while appearing to accept them.
    @PostMapping("/settings/advanced")
    public Mono<Void> setAdvancedMotion(@PathVariable UUID id,
                                        @RequestBody Mono<SetAdvancedMotion> command) {
        return send(id, command);
    }

    // M206 X Y Z, added to every position after homing - a null axis is left alone, not zeroed.
    @PostMapping("/settings/home-offset")
    public Mono<Void> setHomeOffset(@PathVariable UUID id,
                                    @RequestBody Mono<SetHomeOffset> command) {
        return send(id, command);
    }

    // M114's answer arrives on the stream as PositionReported, not from this call.
    @PostMapping("/position")
    public Mono<Void> position(@PathVariable UUID id) {
        return send(id, new RequestPosition());
    }

    // M21 is also the only way to tell whether a card is present: success shows up as SdCardOk on
    // the stream (the machine's own "echo:SD card ok"), anything else reads as no card.
    @PostMapping("/sd/mount")
    public Mono<Void> mountCard(@PathVariable UUID id) {
        return send(id, new MountCard());
    }

    @PostMapping("/sd/release")
    public Mono<Void> releaseCard(@PathVariable UUID id) {
        return send(id, new ReleaseCard());
    }

    // M20's listing arrives on the stream as one SdFileList, assembled from the several lines the
    // firmware writes.
    @PostMapping("/sd/list")
    public Mono<Void> listFiles(@PathVariable UUID id) {
        return send(id, new ListFiles());
    }

    @PostMapping("/sd/delete")
    public Mono<Void> deleteFile(@PathVariable UUID id, @RequestBody Mono<DeleteFile> command) {
        return send(id, command);
    }

    @PostMapping("/sd/print")
    public Mono<Void> printFile(@PathVariable UUID id, @RequestBody Mono<PrintFile> command) {
        return send(id, command);
    }

    // The three that act on whatever is already printing, so none takes a body. M24 here is a
    // resume, which is the same command /sd/print ends with - see ResumeSdPrint.
    @PostMapping("/sd/pause")
    public Mono<Void> pauseSdPrint(@PathVariable UUID id) {
        return send(id, new PauseSdPrint());
    }

    @PostMapping("/sd/resume")
    public Mono<Void> resumeSdPrint(@PathVariable UUID id) {
        return send(id, new ResumeSdPrint());
    }

    @PostMapping("/sd/stop")
    public Mono<Void> stopSdPrint(@PathVariable UUID id) {
        return send(id, new StopSdPrint());
    }

    // Progress and completion both arrive on the stream as SdUploadProgressed; the bytes themselves
    // are posted to /api-files first and only named here (see UploadToCard).
    @PostMapping("/sd/upload")
    public Mono<ResponseEntity<?>> uploadToCard(@PathVariable UUID id, @RequestBody Mono<UploadToCard> command) {
        return command.flatMap(c -> Mono.from(
                        printers.uploadToCard(id, c.fileId(), c.name(), c.binary(), c.trim())))
                .map(Outcomes::of);
    }

    @PostMapping("/sd/upload/cancel")
    public Mono<ResponseEntity<?>> cancelCardUpload(@PathVariable UUID id) {
        return Mono.from(printers.cancelCardUpload(id)).map(Outcomes::of);
    }

    @PostMapping("/connect")
    public Mono<Void> connect(@PathVariable UUID id, @RequestBody Mono<ConnectCmd> cmd) {
        return cmd.flatMap(c -> Mono.from(printers.connect(id, c)));
    }

    // Not a PrinterCommand, and deliberately so: M112 has to go out ahead of everything already
    // queued, including an SD transfer holding the wire, which is the one thing send() cannot
    // promise - so this is PrinterControl's own call rather than a record with a toMarlin().
    @PostMapping("/emergency-stop")
    public Mono<ResponseEntity<?>> emergencyStop(@PathVariable UUID id) {
        return Mono.from(printers.emergencyStop(id)).map(Outcomes::of);
    }

    // The Terminal tab's typed line (#135) - the one body on this surface that is G-code rather than
    // a description of an intent, and so the one that is not a PrinterCommand. Every gate it passes
    // is in core, and all three of its refusals come back through Outcomes: 404 for a machine the
    // farm does not hold, 400 for a line that must never reach a wire, 409 for no link.
    @PostMapping("/terminal")
    public Mono<ResponseEntity<?>> sendLine(@PathVariable UUID id,
                                            @RequestBody Mono<SendRawLine> command) {
        return command.flatMap(c -> Mono.from(printers.sendRaw(id, c.line())))
                .map(Outcomes::of);
    }

    // A second long-lived stream per machine, deliberately separate from /stream: raw wire lines in
    // both directions, which are high-volume, are fanned out to no dashboard, and include the lines
    // no decoder claimed and the acknowledgements the monitor stream withholds as flow control.
    // Unlike /stream it replays a bounded scrollback, because a console's content is its scrollback.
    @GetMapping(value = "/terminal/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<EventMessage> terminal(@PathVariable UUID id) {
        return Flux.from(printers.traffic(id));
    }

    private Mono<Void> send(UUID id, Mono<? extends PrinterCommand> command) {
        return command.flatMap(c -> send(id, c));
    }

    // The only place a request body becomes G-code; PrinterControl.send takes the resulting list
    // as a unit, which is what lets MoveAxes#toMarlin() answer with G91/move/G90 safely.
    private Mono<Void> send(UUID id, PrinterCommand command) {
        return Mono.from(printers.send(id, command.toMarlin()));
    }
}

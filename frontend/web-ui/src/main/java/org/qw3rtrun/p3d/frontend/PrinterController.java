package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.msg.*;
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
 * <p><strong>The request bodies are the application's records now, not Marlin's.</strong> They were
 * `:gcode`'s generated command classes, which made this HTTP contract follow the Marlin command set
 * - a field the generator renamed was a breaking API change. The field names here are the ones the
 * browser already sends, so nothing on the other side moved.
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
    public Mono<TemperatureReport> getState(@PathVariable UUID id) {
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
        return Mono.from(printers.send(id, new RequestTemperatureReport(null)));
    }

    @PostMapping("/connect")
    public Mono<Void> connect(@PathVariable UUID id, @RequestBody Mono<ConnectCmd> cmd) {
        log.info("Cmd Connect received");
        return cmd.flatMap(c -> Mono.from(printers.connect(id, c)));
    }

    private Mono<Void> send(UUID id, Mono<? extends PrinterCommand> command) {
        return command.flatMap(c -> Mono.from(printers.send(id, c)));
    }
}

package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.core.PrinterReactor;
import org.qw3rtrun.p3d.core.PrinterReactorManager;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.msg.TemperatureReport;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.g.marlin.command.ReportHotendTemperature;
import org.qw3rtrun.p3d.g.marlin.command.SetBedTemperature;
import org.qw3rtrun.p3d.g.marlin.command.SetHotendTemperature;
import org.qw3rtrun.p3d.g.marlin.command.TemperatureAutoReport;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api-printer/{id}")
@RequiredArgsConstructor
public class PrinterController {

    private final MachineManagementService machines;
    private final PrinterReactorManager manager;

    /**
     * The printer's runtime, looked up off the event loop.
     *
     * <p>{@code openReactor} is not a lookup. On a miss it reads the printer's
     * row through the command gateway - blocking JDBC - builds a reactor, and connects a socket if
     * the row says the printer was connected, all inside a {@code synchronized} block. Every
     * handler below used to call it directly in its own body, so that ran on a Netty event-loop
     * thread, eagerly, before the returned publisher was even subscribed.
     *
     * <p>Deferring it into a {@code Mono} does two things: the work moves to a pool that is allowed
     * to block, and it stops happening at all for a request that is cancelled before it is
     * subscribed.
     *
     * <p>The side effect is still there and still wrong - a lookup should not open a socket - but
     * that is plan item A3, and it is a lifecycle change rather than a threading one.
     */
    private Mono<PrinterReactor> reactor(UUID id) {
        return Mono.fromSupplier(() -> manager.openReactor(id)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<TemperatureReport> getState(@PathVariable UUID id) {
        return reactor(id).flatMap(PrinterReactor::state);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<EventMessage> getUpdates(@PathVariable UUID id) {
        return reactor(id).flatMapMany(PrinterReactor::updates);
    }

    @PostMapping("/set-temp")
    public Mono<Void> setTemp(@PathVariable UUID id, @RequestBody Mono<SetHotendTemperature> newState) {
        return reactor(id).flatMap(r -> r.handle(newState));
    }

    @PostMapping("/set-bed-temp")
    public Mono<Void> setBedTemp(@PathVariable UUID id, @RequestBody Mono<SetBedTemperature> newState) {
        return reactor(id).flatMap(r -> r.handle(newState));
    }

    @PostMapping("/connect")
    public Mono<Void> connect(@PathVariable UUID id, @RequestBody Mono<ConnectCmd> cmd) {
        log.info("Cmd Connect received");
        return reactor(id).flatMap(r -> r.handleConnectCmd(cmd));
    }

    @PostMapping("/auto-report-temp")
    public Mono<Void> autoReportTemp(@PathVariable UUID id, @RequestBody Mono<TemperatureAutoReport> auto) {
        return reactor(id).flatMap(r -> r.handle(auto));
    }

    @PostMapping("/report-temp")
    public Mono<Void> autoReportTemp(@PathVariable UUID id) {
        return reactor(id).flatMap(r -> r.handle(Mono.just(new ReportHotendTemperature())));
    }
}

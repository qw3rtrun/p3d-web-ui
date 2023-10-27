package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.msg.ConnectCmd;
import org.qw3rtrun.p3d.core.msg.EventMessage;
import org.qw3rtrun.p3d.g.code.AutoReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.ReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.SetBedTemperature;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;
import org.qw3rtrun.p3d.core.msg.TemperatureReport;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api-printer/{id}")
@RequiredArgsConstructor
public class PrinterController {

    private final PrinterReactorManager manager;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<TemperatureReport> getState(@PathVariable UUID id) {
        return  manager.getReactor(id).state();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<EventMessage> getUpdates(@PathVariable UUID id) {
        return manager.getReactor(id).updates();
    }

    @PostMapping("/set-temp")
    public Mono<Void> setTemp(@PathVariable UUID id, @RequestBody Mono<SetHotendTemperature> newState) {
        return manager.getReactor(id).handle(newState);
    }

    @PostMapping("/set-bed-temp")
    public Mono<Void> setBedTemp(@PathVariable UUID id, @RequestBody Mono<SetBedTemperature> newState) {
        return manager.getReactor(id).handle(newState);
    }

    @PostMapping("/connect")
    public Mono<Void> connect(@PathVariable UUID id, @RequestBody Mono<ConnectCmd> cmd) {
        log.info("Cmd Connect received");
        return manager.getReactor(id).handleConnectCmd(cmd);
    }

    @PostMapping("/auto-report-temp")
    public Mono<Void> autoReportTemp(@PathVariable UUID id, @RequestBody Mono<AutoReportHotendTemperature> auto) {
        return manager.getReactor(id).handle(auto);
    }

    @PostMapping("/report-temp")
    public Mono<Void> autoReportTemp(@PathVariable UUID id) {
        return manager.getReactor(id).handle(Mono.just(ReportHotendTemperature.m105()));
    }
}

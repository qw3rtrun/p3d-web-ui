package org.qw3rtrun.p3d.api;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.g.code.AutoReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.ReportHotendTemperature;
import org.qw3rtrun.p3d.g.code.SetHotendTemperature;
import org.qw3rtrun.p3d.g.event.TemperatureReport;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api-printer")
@RequiredArgsConstructor
public class PrinterController {

    private final PrinterReactor printer;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<TemperatureReport> getState() {
        return printer.state();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<TemperatureReport> getUpdates() {
        return printer.updates();
    }

    @PostMapping("/set-temp")
    public Mono<Void> setTemp(@RequestBody Mono<SetHotendTemperature> newState) {
        return printer.handle(newState);
    }

    @PostMapping("/auto-report-temp")
    public Mono<Void> autoReportTemp(@RequestBody Mono<AutoReportHotendTemperature> auto) {
        return printer.handle(auto);
    }

    @PostMapping("/report-temp")
    public Mono<Void> autoReportTemp() {
        return printer.handle(Mono.just(ReportHotendTemperature.m105()));
    }
}

package org.qw3rtrun.p3d.ui.view;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController()
@RequestMapping("/printer/th1")
class PrinterViewController {

    int currentTemp = 24;

    boolean heating = false;
    int targetTemp = -1;

    @GetMapping()
    public Mono<Heater> getTh1() {
        if (heating) {
            currentTemp = currentTemp > targetTemp ? currentTemp - 1 : currentTemp + 1;
        }
        return Mono.just(new Heater(currentTemp, heating, targetTemp));
    }

    @PostMapping()
    public Mono<Heater> setTh1(@RequestBody Mono<Heater> heater) {
        return heater.map(th1 -> {
            heating = th1.heating();
            targetTemp = th1.target();
            return new Heater(currentTemp, heating, targetTemp);
        });
    }
}

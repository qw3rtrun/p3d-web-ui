package org.qw3rtrun.p3d.mvc;

import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.core.service.MachineDescriptionManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.stream.Collectors;

@Controller()
@RequiredArgsConstructor
public class MvcPrinterController {

    private final MachineDescriptionManager manager;

    @GetMapping({
            "/",
            "/index.html",
    })
    public Mono<String> defaultPage(Model model) {
        var printers = manager.fetchAll().collect(Collectors.toList());
        model.addAttribute("printers", printers);
        return Mono.just("redirect:dashboard.html");
    }

    @GetMapping("/printers/add.html")
    public Mono<String> add(Model model) {
        var printers = manager.fetchAll().collect(Collectors.toList());
        model.addAttribute("printers", printers);
        return Mono.just("add.html");
    }

    @GetMapping("/printers/{uuid}")
    public Mono<String> monitor(@PathVariable UUID uuid, Model model) {
        var printers = manager.fetchAll().collect(Collectors.toList());
        model.addAttribute("printers", printers);
        model.addAttribute("printer", printers.stream().filter(p -> p.uuid().equals(uuid)).findFirst().orElseThrow());
        return Mono.just("monitor.html");
    }

    @GetMapping("/dashboard.html")
    public Mono<String> dashboard(Model model) {
        var printers = manager.fetchAll().collect(Collectors.toList());
        model.addAttribute("printers", printers);
        return Mono.just("dashboard.html");
    }
}

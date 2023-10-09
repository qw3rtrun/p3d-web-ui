package org.qw3rtrun.p3d.mvc;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Mono;

@Controller()
public class MvcPrinterController {

    @GetMapping({
            "/",
            "/index.html",
    })
    public Mono<String> defaultPage() {
        return Mono.just("redirect:dashboard.html");
    }

    @GetMapping("/printers/add.html")
    public Mono<String> add(Model model) {
        return Mono.just("add.html");
    }

    @GetMapping("/dashboard.html")
    public Mono<String> dashboard(Model model) {
        return Mono.just("dashboard.html");
    }

}

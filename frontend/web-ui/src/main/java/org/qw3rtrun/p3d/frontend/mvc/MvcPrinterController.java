package org.qw3rtrun.p3d.frontend.mvc;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

@Controller()
@RequiredArgsConstructor
public class MvcPrinterController {

    private final MachineManagementService manager;

    /**
     * The printer list, read off the event loop.
     *
     * <p>Every page here needs it, and every page used to call {@code manager.fetchAll()} straight
     * from its handler body - blocking JDBC on a Netty event-loop thread, with not even a
     * {@code Mono} wrapper to disguise it. A handler returning {@code Mono<String>} is still a
     * handler running on the loop; what makes work non-blocking is where it is scheduled, not what
     * the method signature says.
     *
     * <p>The stream is drained to a list inside the supplier on purpose: a lazy JPA stream handed
     * to a template would be iterated during rendering, which is back on the loop and outside the
     * transaction.
     */
    private Mono<List<Descriptor>> printers() {
        return Mono.fromSupplier(() -> manager.fetchAll().toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping({
            "/",
            "/index.html",
    })
    public Mono<String> defaultPage() {
        return Mono.just("redirect:/dashboard.html");
    }

    @GetMapping("/printers/add.html")
    public Mono<String> add(Model model) {
        model.addAttribute("machine", new MachineInfo());
        return printers()
                .doOnNext(printers -> model.addAttribute("printers", printers))
                .thenReturn("add.html");
    }

    @PostMapping("/printers/add.html")
    public Mono<String> postAdd(Model model, @ModelAttribute("machine") MachineInfo newMachine) {
        return Mono.fromSupplier(() ->
                        manager.add(newMachine.name, new ConnectionDetails(newMachine.host, newMachine.port)))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(added -> model.addAttribute("printers", added))
                .thenReturn("redirect:/printers/add.html");
    }

    @GetMapping("/printers/{uuid}")
    public Mono<String> monitor(@PathVariable UUID uuid, Model model) {
        return printers()
                .doOnNext(printers -> {
                    model.addAttribute("printers", printers);
                    model.addAttribute("printer", printers.stream()
                            .filter(p -> p.uuid().equals(uuid)).findFirst().orElseThrow());
                })
                .thenReturn("monitor.html");
    }

    @GetMapping("/dashboard.html")
    public Mono<String> dashboard(Model model) {
        return printers()
                .doOnNext(printers -> model.addAttribute("printers", printers))
                .thenReturn("dashboard.html");
    }

    @Data
    public static final class MachineInfo {
        String name;
        String host;
        int port;
    }
}

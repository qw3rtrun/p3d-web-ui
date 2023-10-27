package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.model.Descriptor;
import org.qw3rtrun.p3d.core.model.ConnectionDetails;
import org.qw3rtrun.p3d.core.service.MachineDescriptionManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(value = "/api-manager",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FDMPrinterManagementController {

    private final MachineDescriptionManager machineDescriptionManager;

    @GetMapping(value = "/{uuid}")
    public Mono<Descriptor> fetch(@PathVariable UUID uuid) {
        return Mono.fromSupplier(() -> machineDescriptionManager.fetch(uuid));
    }

    @GetMapping
    public Flux<Descriptor> fetchAll() {
        return Flux.fromStream(machineDescriptionManager::fetchAll);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Descriptor> create(@RequestBody Mono<PrinterPayload> payload) {
        return payload.map(p -> machineDescriptionManager.add(p.name(), new ConnectionDetails(p.host(), p.port())));
    }

    @DeleteMapping("/{uuid}")
    public Mono<Void> delete(@PathVariable UUID uuid) {
        return Mono.fromRunnable(() -> machineDescriptionManager.delete(uuid));
    }

    public record PrinterPayload(
            String name,
            String host,
            int port
    ) {
    }
}

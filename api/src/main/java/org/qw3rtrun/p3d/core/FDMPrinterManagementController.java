package org.qw3rtrun.p3d.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.core.model.Printer;
import org.qw3rtrun.p3d.core.model.PrinterConnection;
import org.qw3rtrun.p3d.core.service.PrinterManager;
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

    private final PrinterManager printerManager;

    @GetMapping(value = "/{uuid}")
    public Mono<Printer> fetch(@PathVariable UUID uuid) {
        return Mono.fromSupplier(() -> printerManager.fetch(uuid));
    }

    @GetMapping
    public Flux<Printer> fetchAll() {
        return Flux.fromStream(printerManager::fetchAll);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Printer> create(@RequestBody Mono<PrinterPayload> payload) {
        return payload.map(p -> printerManager.add(p.name(), new PrinterConnection(p.host(), p.port())));
    }

    @DeleteMapping("/{uuid}")
    public Mono<Void> delete(@PathVariable UUID uuid) {
        return Mono.fromRunnable(() -> printerManager.delete(uuid));
    }

    public record PrinterPayload(
            String name,
            String host,
            int port
    ) {
    }
}

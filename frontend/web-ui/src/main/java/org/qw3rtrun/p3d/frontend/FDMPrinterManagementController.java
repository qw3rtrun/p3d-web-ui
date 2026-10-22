package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(value = "/api-manager",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FDMPrinterManagementController {

    private final MachineManagementService management;

    /**
     * The blocking boundary, in one place.
     *
     * <p>Everything behind {@link MachineManagementService} is Spring Data JPA, which is blocking
     * JDBC, and WebFlux serves every request on a handful of Netty event-loop threads. Blocking one
     * of those stalls every other request sharing it, and at one printer and one browser it is
     * invisible - which is how it survived this long.
     *
     * <p>{@code Mono.fromSupplier} alone does <strong>not</strong> move the work: a {@code Mono} is
     * lazy, so its supplier runs on whichever thread subscribes, and for a controller that is the
     * event loop. The {@code subscribeOn} is what moves it, and it is the whole of the fix.
     */
    private <T> Mono<T> offLoop(java.util.function.Supplier<T> blocking) {
        return Mono.fromSupplier(blocking).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/{uuid}")
    public Mono<Descriptor> fetch(@PathVariable UUID uuid) {
        return offLoop(() -> management.fetch(uuid));
    }

    @GetMapping
    public Flux<Descriptor> fetchAll() {
        return offLoop(() -> management.fetchAll().toList()).flatMapIterable(d -> d);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Descriptor> create(@RequestBody Mono<PrinterPayload> payload) {
        return payload.flatMap(p -> offLoop(() -> management.add(p.name(), new ConnectionDetails(p.host(), p.port()))));
    }

    @DeleteMapping("/{uuid}")
    public Mono<Void> delete(@PathVariable UUID uuid) {
        return offLoop(() -> {
            management.delete(uuid);
            return null;
        }).then();
    }

    public record PrinterPayload(
            String name,
            String host,
            int port
    ) {
    }
}

package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.function.Supplier;

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
    private <T> Mono<T> offLoop(Supplier<T> blocking) {
        return Mono.fromSupplier(blocking).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> offLoop(Runnable blocking) {
        return Mono.<Void>fromRunnable(blocking).subscribeOn(Schedulers.boundedElastic());
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
        return payload.flatMap(p -> offLoop(() ->
                management.add(p.name(), new ConnectionDetails(p.host(), p.port()), p.spec())));
    }

    /**
     * Changes what a human declared about a machine (plan item B4).
     *
     * <p>{@code PUT} and not {@code PATCH}: the body is the whole declaration, and a material left
     * out of it is a material the submitter meant to remove. A partial update would need a way to
     * say "leave this alone" that is distinct from "clear this", and every field here already uses
     * absent to mean cleared.
     *
     * <p>It cannot reach the profile. What a machine reported about itself is written from firmware
     * reports alone, so there is no request that can make the farm claim the firmware said
     * something it did not - which is what keeps plan item B4's "discovered is distinguishable from
     * typed" true at the edge and not just in the schema.
     */
    @PutMapping(value = "/{uuid}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Descriptor> update(@PathVariable UUID uuid, @RequestBody Mono<PrinterPayload> payload) {
        return payload.flatMap(p -> offLoop(() -> management.update(uuid, p.name(),
                new ConnectionDetails(p.host(), p.port()), p.spec())));
    }

    @DeleteMapping("/{uuid}")
    public Mono<Void> delete(@PathVariable UUID uuid) {
        return offLoop(() -> management.delete(uuid)).then();
    }

    /**
     * One machine as a browser sends it: where to reach it, and what a person declares about it.
     *
     * <p>The spec is optional on a create, because registering a machine you have just plugged in
     * should not require knowing its bed size. An absent {@code spec} is
     * {@link MachineSpec#undeclared()} and not null, so no handler here has to check.
     */
    public record PrinterPayload(
            String name,
            String host,
            int port,
            MachineSpec spec
    ) {
        public PrinterPayload {
            spec = spec == null ? MachineSpec.undeclared() : spec;
        }
    }
}

package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * CRUD for the farm's roster of machines.
 *
 * <p>Every call into {@link MachineManagementService} is blocking JDBC, so it runs through
 * {@link #offLoop}, which moves it off the Netty event loop with {@code subscribeOn} -
 * {@code Mono.fromSupplier} alone would not: a {@code Mono} is lazy and its supplier runs on
 * whichever thread subscribes, which for a controller is the event loop.
 *
 * <p>{@link #fetch} used to end in {@code orElseThrow} and answer 500 for an id the farm no longer
 * holds; it returns an {@code Optional} now, and {@link Outcomes#found} turns the empty case into a
 * 404, downstream of the subscription so deciding it touches no JDBC.
 *
 * <p>{@link #update} is {@code PUT}, not {@code PATCH}: the body is the whole declaration, and a
 * field left out of it means "clear this", not "leave this alone". It cannot reach the profile,
 * which is written from firmware reports alone, so no request here can make the farm claim the
 * firmware said something it did not (plan item B4).
 */
@Slf4j
@RestController
@RequestMapping(value = "/api-manager",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FDMPrinterManagementController {

    private final MachineManagementService management;

    private <T> Mono<T> offLoop(Supplier<T> blocking) {
        return Mono.fromSupplier(blocking).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> offLoop(Runnable blocking) {
        return Mono.<Void>fromRunnable(blocking).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/{uuid}")
    public Mono<ResponseEntity<Descriptor>> fetch(@PathVariable UUID uuid) {
        return offLoop(() -> management.fetch(uuid)).map(Outcomes::found);
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

    @PutMapping(value = "/{uuid}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Descriptor> update(@PathVariable UUID uuid, @RequestBody Mono<PrinterPayload> payload) {
        return payload.flatMap(p -> offLoop(() -> management.update(uuid, p.name(),
                new ConnectionDetails(p.host(), p.port()), p.spec())));
    }

    @DeleteMapping("/{uuid}")
    public Mono<Void> delete(@PathVariable UUID uuid) {
        return offLoop(() -> management.delete(uuid)).then();
    }

    // Spec is optional on a create - registering a plugged-in machine should not require its bed
    // size - and an absent one is MachineSpec#undeclared(), never null.
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

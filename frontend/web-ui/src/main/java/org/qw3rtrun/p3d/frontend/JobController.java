package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.qw3rtrun.p3d.api.jobs.JobRequirements;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The print queue over HTTP.
 *
 * <p><strong>A refused transition is a 409 with its reason, not a 500.</strong> That is the whole
 * point of {@link JobOutcome} being a sealed type: the service answers "a job that is COMPLETED
 * cannot become PAUSED" as a value, and something turns it into a status a browser can act on.
 * That something is {@link Outcomes}, which is where the argument for each status is written and
 * where this module's <em>only</em> status mapping lives.
 */
@Slf4j
@RestController
@RequestMapping("/api-jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobs;

    // The blocking boundary, in one place.
    private <T> Mono<T> offLoop(Supplier<T> blocking) {
        return Mono.fromSupplier(blocking).subscribeOn(Schedulers.boundedElastic());
    }

    // Runs the move off the event loop and lets Outcomes say what its answer is worth - the 404/409
    // argument lives there now, beside the control surface's identical decision.
    private Mono<ResponseEntity<?>> respond(Supplier<JobOutcome> action) {
        return offLoop(action).map(Outcomes::of);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Job> list(@RequestParam(required = false) UUID machineId) {
        return offLoop(() -> machineId == null ? jobs.fetchAll() : jobs.forMachine(machineId))
                .flatMapIterable(j -> j);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Job>> fetch(@PathVariable UUID id) {
        return offLoop(() -> jobs.fetch(id)).map(Outcomes::found);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> queue(@RequestBody QueuePayload payload) {
        return respond(() -> jobs.queue(payload.fileId(), payload.requires()));
    }

    @PostMapping(value = "/{id}/assign", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> assign(@PathVariable UUID id, @RequestBody AssignPayload payload) {
        return respond(() -> jobs.assign(id, payload.machineId()));
    }

    @PostMapping("/{id}/start")
    public Mono<ResponseEntity<?>> start(@PathVariable UUID id) {
        return respond(() -> jobs.start(id));
    }

    @PostMapping("/{id}/pause")
    public Mono<ResponseEntity<?>> pause(@PathVariable UUID id) {
        return respond(() -> jobs.pause(id));
    }

    @PostMapping("/{id}/resume")
    public Mono<ResponseEntity<?>> resume(@PathVariable UUID id) {
        return respond(() -> jobs.resume(id));
    }

    // DELETE because from a user's side this is "take it off the queue"; it does not remove the
    // row - a cancelled job is a record, and plan item C5 writes recovery information onto it.
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<?>> cancel(@PathVariable UUID id,
                                          @RequestParam(required = false) String reason) {
        return respond(() -> jobs.cancel(id, reason == null ? "cancelled by the operator" : reason));
    }

    // An absent `requires` is JobRequirements#any(), not null, so a caller wanting anything printed
    // sends just {"fileId": "..."} and the scheduler puts it on the first idle printer.
    public record QueuePayload(UUID fileId, JobRequirements requires) {
        public QueuePayload {
            requires = requires == null ? JobRequirements.any() : requires;
        }
    }

    public record AssignPayload(UUID machineId) {
    }
}

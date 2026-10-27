package org.qw3rtrun.p3d.frontend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.springframework.http.HttpStatus;
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
 * cannot become PAUSED" as a value, and this turns it into a status a browser can act on and a
 * sentence it can show. Letting it arrive as an exception would mean this layer guessing from the
 * exception's class which of "not found", "illegal" and "the database said no" had happened.
 *
 * <p>The common cause is an ordinary race - somebody clicks Pause as the last line is acknowledged -
 * so the right answer is to tell them where the job actually got to, which the body carries.
 */
@Slf4j
@RestController
@RequestMapping("/api-jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobs;

    /** The blocking boundary, in one place. */
    private <T> Mono<T> offLoop(Supplier<T> blocking) {
        return Mono.fromSupplier(blocking).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Maps an outcome onto a response.
     *
     * <p>409 and not 400: the request was well formed and the caller was entitled to make it; the
     * job simply is not where they thought. 404 when the job or the file does not exist at all,
     * which {@code Refused} distinguishes by having no state to report.
     */
    private Mono<ResponseEntity<?>> respond(Supplier<JobOutcome> action) {
        return offLoop(action).map(outcome -> switch (outcome) {
            case JobOutcome.Done done -> ResponseEntity.ok((Object) done.job());
            case JobOutcome.Refused refused -> ResponseEntity
                    .status(refused.state() == null ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT)
                    .body((Object) refused);
        });
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Job> list(@RequestParam(required = false) UUID machineId) {
        return offLoop(() -> machineId == null ? jobs.fetchAll() : jobs.forMachine(machineId))
                .flatMapIterable(j -> j);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Job>> fetch(@PathVariable UUID id) {
        return offLoop(() -> jobs.fetch(id))
                .map(found -> found.map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> queue(@RequestBody QueuePayload payload) {
        return respond(() -> jobs.queue(payload.fileId()));
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

    /**
     * Cancels a job.
     *
     * <p>{@code DELETE} rather than another {@code POST} verb, because from a user's side this is
     * "take it off the queue" - and it deliberately does not remove the row. A cancelled job is a
     * record of something somebody decided, and plan item C5 writes recovery information onto
     * exactly these.
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<?>> cancel(@PathVariable UUID id,
                                          @RequestParam(required = false) String reason) {
        return respond(() -> jobs.cancel(id, reason == null ? "cancelled by the operator" : reason));
    }

    public record QueuePayload(UUID fileId) {
    }

    public record AssignPayload(UUID machineId) {
    }
}

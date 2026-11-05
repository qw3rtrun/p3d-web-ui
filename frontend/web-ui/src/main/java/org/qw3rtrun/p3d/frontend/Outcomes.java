package org.qw3rtrun.p3d.frontend;

import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

/**
 * What a refusal is worth over HTTP - the whole of this module's status mapping, in one place.
 *
 * <p>Three sentences and no others: <strong>not here is 404</strong>, <strong>not now is
 * 409</strong>, and anything that got as far as happening is 200. Nothing else in this package
 * decides a status for a refusal, and that is the point of the class existing at all - the mapping
 * had been written out three times in three controllers and the roster had never been given one,
 * so {@code GET /api-manager/{uuid}} answered 500 for an id the farm did not hold.
 *
 * <p><strong>It maps values, never exception classes.</strong> There is deliberately no
 * {@code @ExceptionHandler} and no {@code @RestControllerAdvice} here, and there must not be: an
 * advice keyed on exception type is a lookup table that drifts away from the code it describes, and
 * it forces this layer to guess which of "not found", "that is not allowed" and "the database said
 * no" a given {@code IllegalStateException} meant. The service says what happened -
 * {@link ControlOutcome}, {@link JobOutcome} - and this translates the sentence it said. A new
 * refusal therefore arrives here as a compile error rather than as a 500 in production.
 *
 * <p><strong>409 and not 400.</strong> The request was well formed and the caller was entitled to
 * make it; the job, the file or the machine simply is not where they thought. The common cause is
 * an ordinary race - somebody clicks Pause as the last line is acknowledged, or Upload as a printer
 * drops - so the right answer tells them where things actually got to, and the body carries it.
 *
 * <p><strong>Why a refused command carries a body and an absent query does not.</strong> A query's
 * absence is fully described by its status: there is no record, and there is nothing more to say
 * about one that does not exist. A refusal has a sentence - "there is no file … to upload",
 * "printer … is already writing to its card" - which is worth putting in front of a person, and
 * {@code client.ts} shows it. So {@link #found(Optional)} answers an empty 404 and both
 * {@code of} methods put the {@code Refused} record itself in the body.
 *
 * <p>The two {@code of} methods are exhaustive {@code switch}es over their sealed types with no
 * {@code default}, so a third variant added later fails the build here rather than falling through
 * to whatever the last arm happened to be.
 */
final class Outcomes {

    private Outcomes() {
    }

    // Accepted is 200 with an empty body, not 202 - the contract PrinterController already holds.
    static ResponseEntity<?> of(ControlOutcome outcome) {
        return switch (outcome) {
            case ControlOutcome.Accepted ignored -> ResponseEntity.ok().build();
            case ControlOutcome.Refused refused -> ResponseEntity
                    .status(refused.refusal() == ControlOutcome.Refusal.ABSENT
                            ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT)
                    .body(refused);
        };
    }

    // 404 when the job or file does not exist at all: Refused then carries no state, since a job
    // that is somewhere cannot also be nowhere.
    static ResponseEntity<?> of(JobOutcome outcome) {
        return switch (outcome) {
            case JobOutcome.Done done -> ResponseEntity.ok(done.job());
            case JobOutcome.Refused refused -> ResponseEntity
                    .status(refused.state() == null ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT)
                    .body(refused);
        };
    }

    // Shared by every roster/file-store/job-queue lookup. An empty Mono returned straight from a
    // handler would be 200 with a zero-length body, and client.ts's getJson calls response.json()
    // on whatever arrives - a missing id would surface as a parse failure, not a status.
    static <T> ResponseEntity<T> found(Optional<T> value) {
        return value.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}

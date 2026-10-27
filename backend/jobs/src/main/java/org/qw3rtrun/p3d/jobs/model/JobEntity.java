package org.qw3rtrun.p3d.jobs.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One print job, and the only thing that moves it.
 *
 * <p><strong>The state machine is on the entity rather than in the service, which is the same
 * choice {@code ConnectionLifecycle} made one phase earlier and for the same reason.</strong> A
 * transition is a fact about a job - what it is allowed to do next depends only on where it is -
 * so it belongs with the job. Putting it in the service would mean every caller of every verb
 * re-deriving it, and the first one to forget would write an illegal row that the next reader has
 * to cope with.
 *
 * <p><strong>{@link #moveTo} returns the event it caused, or null when the move is not legal.</strong>
 * Null and not an exception: whether a move is legal is a question with a perfectly good negative
 * answer, and the service turns that into {@code JobOutcome.Refused}. Plan item C2's "a typed
 * failure rather than an exception from the database" is that pair - this decides, the service
 * phrases it, and nothing reaches the database to find out.
 */
@Entity
@Table(name = "print_job")
@NoArgsConstructor
@Getter
public class JobEntity {

    @Id
    UUID id;

    @Column(name = "file_id", nullable = false)
    UUID fileId;

    /** Safe to copy because a stored file is immutable - see {@link Job}. */
    @Column(name = "file_name", nullable = false)
    String fileName;

    @Column(name = "machine_id")
    UUID machineId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    JobState state;

    @Column(length = 1024)
    String reason;

    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;

    @Column(name = "started_at")
    LocalDateTime startedAt;

    @Column(name = "finished_at")
    LocalDateTime finishedAt;

    /** A new job: queued, on no machine, with nothing having happened to it yet. */
    public JobEntity(UUID id, UUID fileId, String fileName, LocalDateTime createdAt) {
        this.id = id;
        this.fileId = fileId;
        this.fileName = fileName;
        this.state = JobState.QUEUED;
        this.createdAt = createdAt;
    }

    /**
     * Moves the job, or answers null because it may not move that way.
     *
     * <p>The timestamps are set here rather than by the caller because they are part of the
     * transition and not beside it: {@code startedAt} is the moment it became {@code RUNNING} for
     * the first time, and a resume after a pause must not overwrite it - the job did not start
     * twice. {@code finishedAt} is set once, by whichever terminal state it reached.
     *
     * @param next    where it should go
     * @param onto    the machine involved, or null to leave the machine as it is
     * @param why     a reason worth keeping on the job, or null
     * @param clock   when this is happening
     * @return the event this caused, or null when the move is not legal from here
     */
    public JobStateChanged moveTo(JobState next, UUID onto, String why, LocalDateTime clock) {
        if (!state.canMoveTo(next)) {
            return null;
        }
        var previous = state;
        state = next;
        if (onto != null) {
            machineId = onto;
        }
        if (why != null) {
            reason = why;
        }
        if (next == JobState.RUNNING && startedAt == null) {
            startedAt = clock;
        }
        if (next.isTerminal()) {
            finishedAt = clock;
        }
        if (next == JobState.QUEUED) {
            // Back in the queue means back to nobody's machine, or the next scheduler pass would
            // see a queued job that still claims one.
            machineId = null;
        }
        return new JobStateChanged(id, previous, next, machineId, why, clock);
    }

    /** This row as the record that leaves the module. An entity never does. */
    public Job describe() {
        return new Job(id, fileId, fileName, machineId, state, reason, createdAt, startedAt, finishedAt);
    }
}

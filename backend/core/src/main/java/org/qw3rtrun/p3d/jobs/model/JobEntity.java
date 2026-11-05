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
import org.qw3rtrun.p3d.api.jobs.JobProgress;
import org.qw3rtrun.p3d.api.jobs.JobRequirements;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;
import org.qw3rtrun.p3d.api.model.BedVolume;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One print job, and the only thing that moves it. The state machine lives on the entity rather
 * than the service - the same choice {@code ConnectionLifecycle} made, since a transition is a fact
 * about a job and putting it in the service would mean every caller re-deriving it. {@link #moveTo}
 * returns the event it caused, or null when the move is not legal (not an exception - the service
 * turns that into {@code JobOutcome.Refused}, plan item C2's "a typed failure rather than an
 * exception from the database").
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

    /** The last progress checkpoint, approximate - see the changeset. Null means never run, which is a different statement from zero. */
    @Column(name = "blocks_sent")
    Long blocksSent;
    @Column(name = "blocks_in_flight")
    Integer blocksInFlight;
    @Column(name = "bytes_sent")
    Long bytesSent;
    @Column(name = "bytes_total")
    Long bytesTotal;
    @Column(name = "layer")
    Integer layer;
    @Column(name = "progress_at")
    LocalDateTime progressAt;

    /** What the job needs of a machine - see {@link JobRequirements}. Null throughout means any. */
    @Column(name = "requires_machine")
    UUID requiresMachine;
    @Column(name = "requires_bed_x")
    Integer requiresBedX;
    @Column(name = "requires_bed_y")
    Integer requiresBedY;
    @Column(name = "requires_bed_z")
    Integer requiresBedZ;
    @Column(name = "requires_extruders")
    Integer requiresExtruders;
    @Column(name = "requires_nozzle")
    BigDecimal requiresNozzle;
    @Column(name = "requires_material")
    String requiresMaterial;
    @Column(name = "requires_tag")
    String requiresTag;

    /** Why this queued job has not been given a machine yet - separate from {@link #reason} (why a job ended), since a job blocked for an hour can still run perfectly. */
    @Column(name = "blocked_reason", length = 1024)
    String blockedReason;

    /** A new job: queued, on no machine, with nothing having happened to it yet. */
    public JobEntity(UUID id, UUID fileId, String fileName, LocalDateTime createdAt,
                     JobRequirements requires) {
        this.id = id;
        this.fileId = fileId;
        this.fileName = fileName;
        this.state = JobState.QUEUED;
        this.createdAt = createdAt;
        var needs = requires == null ? JobRequirements.any() : requires;
        this.requiresMachine = needs.machineId();
        if (needs.bedVolume() != null) {
            this.requiresBedX = needs.bedVolume().x();
            this.requiresBedY = needs.bedVolume().y();
            this.requiresBedZ = needs.bedVolume().z();
        }
        this.requiresExtruders = needs.extruders();
        this.requiresNozzle = needs.nozzleDiameter();
        this.requiresMaterial = needs.material();
        this.requiresTag = needs.tag();
    }

    /** What this job needs, never null. */
    public JobRequirements requires() {
        var bed = requiresBedX == null || requiresBedY == null || requiresBedZ == null
                ? null
                : new BedVolume(requiresBedX, requiresBedY, requiresBedZ);
        return new JobRequirements(requiresMachine, bed, requiresExtruders, requiresNozzle,
                requiresMaterial, requiresTag);
    }

    /** Records why the farm cannot run this yet. Only meaningful while it is queued. */
    public void blocked(String why) {
        this.blockedReason = why;
    }

    /** Whether the stated reason has changed, so a scheduler only writes when it has something new. */
    public boolean blockedReasonIs(String why) {
        return Objects.equals(blockedReason, why);
    }

    /**
     * Moves the job, or answers null because it may not move that way. Timestamps are set here
     * because they are part of the transition: {@code startedAt} is set only the first time it
     * becomes {@code RUNNING}, so a resume after a pause does not make it look like it started
     * twice; {@code finishedAt} is set once, by whichever terminal state is reached.
     *
     * @param onto  the machine involved, or null to leave the machine as it is
     * @param why   a reason worth keeping on the job, or null
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
        if (next != JobState.QUEUED) {
            blockedReason = null;
        }
        if (next == JobState.QUEUED) {
            // Back in the queue means back to nobody's machine, or the next scheduler pass would
            // see a queued job that still claims one.
            machineId = null;
        }
        return new JobStateChanged(id, fileId, previous, next, machineId, why, clock);
    }

    /**
     * Records a progress checkpoint, if it is ahead of the one already here - monotone rather than
     * state-gated, and the difference cost a reprint. The first version ignored any checkpoint
     * arriving once the job had stopped, which also threw away the exact reading a pause takes: a
     * resume read a checkpoint up to a second stale and restarted thousands of blocks early,
     * extruding over what was already printed (observed live as the E value going backwards from
     * 50.3952 to 46.4220). Comparing the count instead keeps what the state check was for - a stale
     * reading is behind, so it loses - while letting the accurate final one through: plan item C4's
     * "progress never moves backwards" as an invariant of the row.
     */
    public void progressed(JobProgress progress) {
        if (blocksSent != null && progress.blocksSent() < blocksSent) {
            return;
        }
        blocksSent = progress.blocksSent();
        blocksInFlight = progress.inFlight();
        bytesSent = progress.bytesSent();
        bytesTotal = progress.bytesTotal();
        layer = progress.layer();
        progressAt = progress.at();
    }

    /** This row as the record that leaves the module. An entity never does. */
    public Job describe() {
        return new Job(id, fileId, fileName, machineId, state,
                state == JobState.QUEUED && blockedReason != null ? blockedReason : reason,
                createdAt, startedAt, finishedAt, progress(), requires());
    }

    /** The checkpoint as a value, or {@link JobProgress#none()} for a job that never ran. */
    private JobProgress progress() {
        if (progressAt == null) {
            return JobProgress.none();
        }
        return new JobProgress(blocksSent == null ? 0 : blocksSent,
                blocksInFlight == null ? 0 : blocksInFlight,
                bytesSent == null ? 0 : bytesSent,
                bytesTotal == null ? 0 : bytesTotal,
                layer, startedAt, progressAt);
    }
}

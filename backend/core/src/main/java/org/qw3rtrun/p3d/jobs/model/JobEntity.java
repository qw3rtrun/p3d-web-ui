package org.qw3rtrun.p3d.jobs.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OptimisticLock;
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
 *
 * <p><strong>{@code @DynamicUpdate} is load-bearing, not tuning.</strong> Two writers annotate a
 * job without moving it - {@link #blocked} (why a queued job has no machine) and
 * {@link #progressed} (how far a running one has got) - and Hibernate's default is to write
 * <em>every</em> column, so each of them also wrote back the {@code state} and {@code machine_id}
 * its own transaction had read. There was no {@code @Version} then, so nothing refused it: a write
 * that changed one field silently undid a transition another transaction had committed in between.
 * Measured as issue #97's second defect, with {@code FarmScheduler}'s pass on its own thread:
 * {@code PrintJobIntegrationTest} failed three runs out of three, a different test each time, all
 * of them {@code POST /api-jobs/&#123;id&#125;/start} answering
 * {@code &#123;"state":"QUEUED","attempted":"RUNNING"&#125;} - and the log showed
 * {@code QUEUED -&gt; ASSIGNED} at 52.012 with the scheduler's {@code is waiting: it is pinned to
 * recoverable resend, which is OFFLINE} at 52.016, four milliseconds behind it. The checkpoint is
 * the sharper edge of the same bug: it arrives about once a second per running printer and
 * {@code JobFeeder} publishes an exact one immediately before the event that stops the job, so a
 * pause reverted to {@code RUNNING} by the checkpoint that preceded it is the designed sequence.
 * Writing only what changed makes "this does not move the job" true in SQL as well as in Java.
 *
 * <p><strong>{@code @Version} closes the half {@code @DynamicUpdate} cannot</strong> (#100). Two
 * writers that both genuinely move the job both carry the {@code state} column, because moving it
 * is the whole of what they do - so narrowing the write does nothing for them and the later commit
 * silently overwrote the earlier one. Every update now carries {@code WHERE version = ?}, and a
 * writer that reads a row somebody else has since moved is refused at flush rather than landing on
 * top of them. {@code JobServiceImpl} turns that refusal into {@code JobOutcome.Refused} carrying
 * the state the job actually went to, which is a 409 and the same shape an illegal transition
 * answers with; an {@code OptimisticLockingFailureException} must never reach a REST caller as a
 * 500.
 *
 * <p><strong>The lock covers the transition and nothing else</strong>, which is why the checkpoint
 * columns and {@code blocked_reason} are {@code @OptimisticLock(excluded = true)}. Without that,
 * {@code @Version} turns the two annotate-the-row writers into competitors for the version in the
 * other direction: a scheduler pass writing <em>why</em> a job is waiting bumps the version, and
 * the operator's {@code assign} - which read the same row a moment earlier - is then refused by a
 * write that decided nothing. Measured: {@code PrintJobIntegrationTest} failed intermittently (two
 * of twelve on one run, three across a full build, none on the next) with
 * {@code POST /api-jobs/&#123;id&#125;/start} answering 409 because the {@code assign} before it
 * had quietly lost to {@code job … is waiting: it is pinned to …, which is OFFLINE}. Excluding
 * them states the same rule {@code @DynamicUpdate} states in SQL - "this does not move the job" -
 * in the lock as well. The exclusion is one-way, which is what is wanted: such a write does not
 * <em>increment</em> the version, so it cannot beat a transition, but it is still
 * <em>checked</em> against it, so a reason or a reading that has been overtaken is still dropped
 * rather than landing on a job that has moved.
 *
 * <p>A boxed {@code Long}, not a {@code long}, so Spring Data reads "is this new" off the version
 * rather than off the assigned {@code @Id} - a primitive would fall back to the id check, find one
 * set by the constructor, and turn every insert into a merge and a wasted {@code SELECT}.
 */
@Entity
@DynamicUpdate
@Table(name = "print_job")
@NoArgsConstructor
@Getter
public class JobEntity {

    @Id
    UUID id;

    @Version
    @Column(nullable = false)
    Long version;

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
    @OptimisticLock(excluded = true)
    @Column(name = "blocks_sent")
    Long blocksSent;
    @OptimisticLock(excluded = true)
    @Column(name = "blocks_in_flight")
    Integer blocksInFlight;
    @OptimisticLock(excluded = true)
    @Column(name = "bytes_sent")
    Long bytesSent;
    @OptimisticLock(excluded = true)
    @Column(name = "bytes_total")
    Long bytesTotal;
    @OptimisticLock(excluded = true)
    @Column(name = "layer")
    Integer layer;
    @OptimisticLock(excluded = true)
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
    @OptimisticLock(excluded = true)
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

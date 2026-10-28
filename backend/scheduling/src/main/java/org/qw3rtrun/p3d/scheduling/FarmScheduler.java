package org.qw3rtrun.p3d.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * First fit over capability and availability - plan item D1, and the "group" in group of printers.
 *
 * <p><strong>Deliberately the dullest thing that works.</strong> The plan is explicit that a cost
 * model, bin-packing or a scheduling framework is not warranted at this size and should be argued
 * for separately if ever. So: the oldest queued job that fits the first eligible machine gets it.
 * Nothing is weighted, nothing is predicted, and a farm of identical printers - which is most of
 * them - never needs any of that.
 *
 * <p><strong>A machine is eligible when three things are true</strong>, and they come from three
 * different modules, which is the whole reason this one exists:
 *
 * <ul>
 *   <li>it is {@code IDLE} - not merely connected. {@code ONLINE} means a socket is open;
 *       {@code IDLE} means the firmware took something this host sent. Starting a print on a
 *       machine that has not acknowledged anything is how plan item B3's dead outbound half used
 *       to look.</li>
 *   <li>no job is holding it. {@code JobState.holdsAMachine} is that question, and it covers
 *       {@code PAUSED} as well as {@code RUNNING}: a paused job still owns its printer.</li>
 *   <li>its declared spec satisfies what the job asked for - {@code JobRequirements.satisfiedBy}.</li>
 * </ul>
 *
 * <p><strong>A pinned job waits for its machine and nothing widens that.</strong> An operator who
 * named a printer knows something the farm does not, and a capability match must never quietly
 * override them.
 */
@Slf4j
@RequiredArgsConstructor
public class FarmScheduler {

    private final JobService jobs;
    private final MachineManagementService machines;
    private final PrinterControl printers;

    /**
     * Anything that moves a job may have freed a machine or added work.
     *
     * <p>Cheaper and far more responsive than waiting for the timer: a print that finishes hands
     * its printer to the next job in the same breath rather than up to a sweep later.
     */
    @EventListener
    public void onJobStateChanged(JobStateChanged event) {
        schedule();
    }

    /**
     * The backstop, for everything a job transition cannot see.
     *
     * <p>A machine coming online, a spec being edited, a printer being registered: none of those
     * moves a job, and all of them can make a blocked job runnable. A sweep is the honest way to
     * notice - the alternative is listening to three more modules' events for a result that is
     * recomputed from scratch anyway.
     */
    @Scheduled(fixedDelayString = "${p3d.scheduling.interval:5000}")
    public void sweep() {
        schedule();
    }

    /**
     * One pass: every queued job, oldest first, against whatever is free.
     *
     * <p>{@code synchronized} because two passes at once would each see the same idle printer and
     * each give it a job - the event listener and the timer genuinely race. The work is a handful
     * of lookups over a farm of tens of machines, so one at a time costs nothing worth measuring.
     *
     * <p>{@code taken} is what stops one pass doing the same thing to itself: a machine assigned to
     * the first job must not look free to the second, and the queue's own view of it does not
     * change until the transaction behind {@code assign} commits.
     */
    public synchronized void schedule() {
        List<Job> waiting = jobs.queued();
        if (waiting.isEmpty()) {
            return;
        }
        List<Candidate> farm = farm();
        Set<UUID> taken = new HashSet<>();

        for (Job job : waiting) {
            var chosen = choose(job, farm, taken);
            if (chosen == null) {
                jobs.blocked(job.id(), whyNothingFits(job, farm));
                continue;
            }
            taken.add(chosen.id());
            start(job, chosen);
        }
    }

    /**
     * Assign, then start. Both, because the plan's done-when is that a job <em>starts</em> without
     * a human - an assignment that waited for somebody to press Go would be half the feature.
     */
    private void start(Job job, Candidate machine) {
        log.info("giving job {} ({}) to {}", job.id(), job.fileName(), machine.name());
        if (jobs.assign(job.id(), machine.id()) instanceof JobOutcome.Refused refused) {
            log.info("job {} could not be assigned after all: {}", job.id(), refused.reason());
            return;
        }
        if (jobs.start(job.id()) instanceof JobOutcome.Refused refused) {
            log.warn("job {} was assigned to {} and would not start: {}", job.id(), machine.name(),
                    refused.reason());
        }
    }

    /** The first machine that will do, or null. */
    private Candidate choose(Job job, List<Candidate> farm, Set<UUID> taken) {
        for (Candidate machine : farm) {
            if (taken.contains(machine.id()) || !machine.free()) {
                continue;
            }
            if (job.requires().isPinned() && !machine.id().equals(job.requires().machineId())) {
                continue;
            }
            if (!job.requires().satisfiedBy(machine.spec())) {
                continue;
            }
            return machine;
        }
        return null;
    }

    /**
     * Why a job is still waiting, in words an operator can act on - D1's "a stated reason".
     *
     * <p>It answers the question the operator is actually asking, which is not "is there a free
     * machine" but "why not mine": a pinned job says what its machine is doing, and a capability
     * mismatch names the first machine that was free and the reason it did not fit.
     */
    private String whyNothingFits(Job job, List<Candidate> farm) {
        var requires = job.requires();
        if (farm.isEmpty()) {
            return "the farm has no machines";
        }
        if (requires.isPinned()) {
            var pinned = farm.stream().filter(m -> m.id().equals(requires.machineId())).findFirst();
            if (pinned.isEmpty()) {
                return "it is pinned to machine " + requires.machineId() + ", which the farm does not have";
            }
            return "it is pinned to " + pinned.get().name() + ", which is " + pinned.get().state()
                    + (pinned.get().held() ? " and already has a job" : "");
        }
        var free = farm.stream().filter(Candidate::free).toList();
        if (free.isEmpty()) {
            return "no machine is idle and free - " + farm.size() + " in the farm";
        }
        if (requires.isAny()) {
            return "waiting for a machine";
        }
        var first = free.getFirst();
        return "no free machine fits: " + first.name() + ", for one, "
                + requires.whyNot(first.spec());
    }

    /** Every machine, with the three facts eligibility is decided on. */
    private List<Candidate> farm() {
        var candidates = new ArrayList<Candidate>();
        machines.fetchAll().forEach(descriptor -> candidates.add(describe(descriptor)));
        return candidates;
    }

    private Candidate describe(Descriptor machine) {
        var state = liveStateOf(machine);
        boolean held = jobs.forMachine(machine.uuid()).stream()
                .anyMatch(job -> job.state().holdsAMachine());
        return new Candidate(machine.uuid(), machine.name(), state, held, machine.spec());
    }

    /**
     * The machine's state now, not the roster's record of it.
     *
     * <p>The roster's column is written when {@code core} reports a change and is accurate at a
     * restart; it is stale the moment anything connects. A scheduler asking it would hand jobs to
     * printers that went offline minutes ago.
     *
     * <p>A machine the runtime knows nothing about reads as {@code OFFLINE}, which is the right
     * answer: nothing has dialled it, so nothing can print on it.
     */
    private MachineConnectionState liveStateOf(Descriptor machine) {
        try {
            var status = Mono.from(printers.connection(machine.uuid())).block();
            return status == null ? MachineConnectionState.OFFLINE : status.state();
        } catch (RuntimeException notRunning) {
            return MachineConnectionState.OFFLINE;
        }
    }

    /** One machine as the scheduler sees it. */
    private record Candidate(UUID id, String name, MachineConnectionState state, boolean held,
                             org.qw3rtrun.p3d.api.model.MachineSpec spec) {

        /** Idle and nobody's - see the class note on why {@code IDLE} and not merely connected. */
        boolean free() {
            return state == MachineConnectionState.IDLE && !held;
        }
    }
}

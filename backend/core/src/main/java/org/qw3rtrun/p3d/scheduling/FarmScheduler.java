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
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * First fit over capability and availability - plan item D1, and the "group" in group of printers.
 * Deliberately the dullest thing that works: the plan rules out a cost model or bin-packing at this
 * size, so the oldest queued job that fits the first eligible machine gets it, nothing weighted or
 * predicted.
 *
 * <p>A machine is eligible when three facts, from three different modules, all hold - the whole
 * reason this class exists: it is {@code IDLE}, not merely connected ({@code ONLINE} is a socket
 * open, {@code IDLE} is the firmware having taken something this host sent - printing on the
 * difference is what B3's dead outbound half used to look like); no job holds it
 * ({@code JobState.holdsAMachine} covers {@code PAUSED} as well as {@code RUNNING}); and its
 * declared spec satisfies the job's requirements. A pinned job waits for its named machine and
 * nothing widens that - an operator who named a printer knows something the farm does not.
 */
@Slf4j
@RequiredArgsConstructor
public class FarmScheduler {

    private final JobService jobs;
    private final MachineManagementService machines;
    private final PrinterControl printers;

    /**
     * Anything that moves a job may have freed a machine or added work - more responsive than the
     * timer, since a finishing print hands its printer on in the same breath rather than at the next
     * sweep.
     *
     * <p>The pass is handed to another thread because {@link #schedule()} blocks and this runs on
     * whichever thread moved the job, which for both of {@code JobFeeder}'s outcomes is a thread
     * that cannot take it. A watcher fails a job from that machine's own {@code p3d-printer-<uuid>}
     * thread (its {@code updates()} is {@code publishOn(executor)}), and {@link #liveStateOf} then
     * queues a task on that same single thread - so the pass waited on the thread it was waiting
     * for, for ever, holding this class's monitor against every later sweep. A feed that reaches the
     * end of its file completes on a reactor-netty thread, where Reactor refuses {@code block()}
     * outright and every machine therefore read {@code OFFLINE}. After commit so the pass sees the
     * move it was told about, with {@code fallbackExecution} so a publisher outside a transaction is
     * still heard rather than silently dropped.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onJobStateChanged(JobStateChanged event) {
        Schedulers.boundedElastic().schedule(this::schedule);
    }

    /** The backstop for everything a job transition cannot see - a machine coming online, a spec edited, a printer registered - any of which can make a blocked job runnable without moving a job itself. */
    @Scheduled(fixedDelayString = "${p3d.scheduling.interval:5000}")
    public void sweep() {
        schedule();
    }

    /**
     * One pass: every queued job, oldest first, against whatever is free. {@code synchronized}
     * because the event listener and the timer genuinely race, and two passes at once would each see
     * the same idle printer and both give it a job; the work is a handful of lookups over tens of
     * machines, so serialising it costs nothing worth measuring. {@code taken} stops one pass doing
     * that to itself, since a machine assigned to the first job in a pass does not look taken to the
     * queue until {@code assign}'s transaction commits.
     *
     * <p>A pass also races whoever is using the farm, and the monitor does nothing about that: the
     * queue read above is a snapshot, so every write below may land on a job an operator moved in
     * between. {@code assign} and {@code start} re-read and are refused; what a reason costs is
     * {@code JobEntity}'s business, and is the whole of that class's note on {@code @DynamicUpdate}.
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

    /** Assign, then start - both, since the plan's done-when is that a job starts without a human, and an assignment waiting for somebody to press Go would be half the feature. */
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
     * Why a job is still waiting, in words an operator can act on - D1's "a stated reason". Answers
     * "why not mine", not "is there a free machine": a pinned job says what its machine is doing, a
     * capability mismatch names the first free machine and why it did not fit.
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
     * The machine's state now, not the roster's record of it - the roster's column is accurate at a
     * restart and stale the moment anything connects, so asking it would hand jobs to printers that
     * went offline minutes ago. A machine the runtime knows nothing about reads as {@code OFFLINE},
     * correctly: nothing has dialled it, so nothing can print on it.
     *
     * <p>Bounded, because the answer is a task on that machine's own thread and an unbounded wait
     * would hold {@link #schedule()}'s monitor against every later sweep. A second is well inside
     * the default five-second interval, so one unresponsive aggregate cannot push a pass past the
     * next tick, and a machine that cannot say where it is within one is not a machine that can take
     * a print - which is what {@code OFFLINE} already means here. Which threads may block at all is
     * argued on {@link #onJobStateChanged}.
     */
    private MachineConnectionState liveStateOf(Descriptor machine) {
        try {
            var status = Mono.from(printers.connection(machine.uuid())).block(Duration.ofSeconds(1));
            return status == null ? MachineConnectionState.OFFLINE : status.state();
        } catch (RuntimeException notRunning) {
            return MachineConnectionState.OFFLINE;
        }
    }

    /** One machine as the scheduler sees it. */
    private record Candidate(UUID id, String name, MachineConnectionState state, boolean held,
                             MachineSpec spec) {

        /** Idle and nobody's - see the class note on why {@code IDLE} and not merely connected. */
        boolean free() {
            return state == MachineConnectionState.IDLE && !held;
        }
    }
}

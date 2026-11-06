package org.qw3rtrun.p3d.scheduling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.control.ControlOutcome;
import org.qw3rtrun.p3d.api.control.PrinterControl;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.qw3rtrun.p3d.api.jobs.JobProgress;
import org.qw3rtrun.p3d.api.jobs.JobRequirements;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;
import org.qw3rtrun.p3d.api.model.BedVolume;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.model.Descriptor;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.model.MachineProfile;
import org.qw3rtrun.p3d.api.model.MachineSpec;
import org.qw3rtrun.p3d.api.msg.ConnectCmd;
import org.qw3rtrun.p3d.api.msg.EventMessage;
import org.qw3rtrun.p3d.api.service.MachineManagementService;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.protocol.GRq;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First fit over capability and availability - plan item D1.
 *
 * <p>All three modules the scheduler reads are stubbed, which is the point of it depending on
 * interfaces in {@code api}: the rule can be driven against any farm at all without a database, a
 * socket or a printer. What is under test is only the rule.
 */
class FarmSchedulerTest {

    private final Map<UUID, Descriptor> farm = new LinkedHashMap<>();
    private final Map<UUID, MachineConnectionState> live = new LinkedHashMap<>();
    private final List<Job> queue = new ArrayList<>();
    private final Map<UUID, List<Job>> onMachine = new LinkedHashMap<>();
    private final List<String> actions = Collections.synchronizedList(new ArrayList<>());
    private final Map<UUID, String> blockedReasons =
            Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Where the stubbed {@code connection(id)} answers from, mirroring
     * {@code PrinterReactor.connection()}'s {@code publishOn(executor)} - the machine's own single
     * thread in production, inline here except in the two tests about which thread the listener is
     * called on.
     */
    private Scheduler aggregate = Schedulers.immediate();

    private FarmScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new FarmScheduler(jobs(), machines(), printers());
    }

    // --- the farm ---------------------------------------------------------------------------

    private UUID machine(String name, MachineConnectionState state, MachineSpec spec) {
        var id = UUID.randomUUID();
        farm.put(id, new Descriptor(id, name, new ConnectionDetails("localhost", 8099), state,
                MachineProfile.unknown(), spec));
        live.put(id, state);
        return id;
    }

    private UUID idle(String name) {
        return machine(name, MachineConnectionState.IDLE, MachineSpec.undeclared());
    }

    private UUID idle(String name, MachineSpec spec) {
        return machine(name, MachineConnectionState.IDLE, spec);
    }

    private Job queued(String file, JobRequirements requires) {
        var job = new Job(UUID.randomUUID(), UUID.randomUUID(), file, null, JobState.QUEUED, null,
                LocalDateTime.now().plusNanos(queue.size()), null, null, JobProgress.none(), requires);
        queue.add(job);
        return job;
    }

    private void holding(UUID machineId, JobState state) {
        onMachine.computeIfAbsent(machineId, id -> new ArrayList<>()).add(
                new Job(UUID.randomUUID(), UUID.randomUUID(), "other.gcode", machineId, state, null,
                        LocalDateTime.now(), null, null, JobProgress.none(), JobRequirements.any()));
    }

    private String nameOf(UUID id) {
        return farm.get(id).name();
    }

    // --- the stubs --------------------------------------------------------------------------

    private JobService jobs() {
        return new JobService() {
            @Override
            public JobOutcome queue(UUID fileId, JobRequirements requires) {
                throw new UnsupportedOperationException();
            }

            @Override
            public JobOutcome assign(UUID jobId, UUID machineId) {
                actions.add("assign " + shortId(jobId) + " -> " + nameOf(machineId));
                queue.removeIf(job -> job.id().equals(jobId));
                return new JobOutcome.Done(null);
            }

            @Override
            public JobOutcome unassign(UUID jobId, String reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public JobOutcome start(UUID jobId) {
                actions.add("start " + shortId(jobId));
                return new JobOutcome.Done(null);
            }

            @Override
            public JobOutcome pause(UUID jobId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public JobOutcome resume(UUID jobId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public JobOutcome complete(UUID jobId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public JobOutcome fail(UUID jobId, String reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public JobOutcome cancel(UUID jobId, String reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public JobOutcome blocked(UUID jobId, String why) {
                blockedReasons.put(jobId, why);
                return new JobOutcome.Done(null);
            }

            @Override
            public List<Job> queued() {
                return List.copyOf(queue);
            }

            @Override
            public Optional<Job> fetch(UUID jobId) {
                return Optional.empty();
            }

            @Override
            public List<Job> fetchAll() {
                return List.copyOf(queue);
            }

            @Override
            public List<Job> forMachine(UUID machineId) {
                return onMachine.getOrDefault(machineId, List.of());
            }
        };
    }

    private MachineManagementStub machines() {
        return new MachineManagementStub();
    }

    private class MachineManagementStub implements MachineManagementService {
        @Override
        public Optional<Descriptor> fetch(UUID uuid) {
            return Optional.ofNullable(farm.get(uuid));
        }

        @Override
        public Descriptor add(String name, ConnectionDetails connection, MachineSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Descriptor update(UUID uuid, String name, ConnectionDetails connection, MachineSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<Descriptor> fetchAll() {
            return farm.values().stream();
        }

        @Override
        public void delete(UUID uuid) {
            throw new UnsupportedOperationException();
        }
    }

    private PrinterControl printers() {
        return new PrinterControl() {
            @Override
            public Publisher<HeaterReading> state(UUID id) {
                return Mono.empty();
            }

            @Override
            public Publisher<EventMessage> updates(UUID id) {
                return Mono.empty();
            }

            @Override
            public Publisher<Void> send(UUID id, List<GRq<?>> commands) {
                return Mono.empty();
            }

            @Override
            public Publisher<Void> connect(UUID id, ConnectCmd command) {
                return Mono.empty();
            }

            @Override
            public Publisher<ConnectionStatus> connection(UUID id) {
                var state = live.get(id);
                return (state == null ? Mono.<ConnectionStatus>empty()
                        : Mono.just(new ConnectionStatus(state, LocalDateTime.now(), 0, null)))
                        .publishOn(aggregate);
            }

            // Writing to a card is nothing to do with scheduling a job onto a machine, so these
            // two are here to satisfy the interface and for no other reason.
            @Override
            public Publisher<ControlOutcome> uploadToCard(UUID id, UUID fileId, String cardName) {
                return Mono.just(ControlOutcome.accepted(id));
            }

            @Override
            public Publisher<ControlOutcome> cancelCardUpload(UUID id) {
                return Mono.just(ControlOutcome.accepted(id));
            }
        };
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    // --- the rule ---------------------------------------------------------------------------

    /** The plan's headline: a job queued with no target starts, without a human. */
    @Test
    void aJobWithNoTargetStartsOnTheFirstIdleMachine() {
        var printer = idle("alpha");
        var job = queued("part.gcode", JobRequirements.any());

        scheduler.schedule();

        assertEquals(List.of("assign " + shortId(job.id()) + " -> alpha",
                "start " + shortId(job.id())), actions);
    }

    /** Oldest first, and one machine takes one job per pass. */
    @Test
    void jobsAreTakenOldestFirstAndAMachineTakesOne() {
        idle("alpha");
        var first = queued("first.gcode", JobRequirements.any());
        queued("second.gcode", JobRequirements.any());

        scheduler.schedule();

        assertEquals(2, actions.size(), "only one machine was free: " + actions);
        assertTrue(actions.getFirst().contains(shortId(first.id())));
    }

    // the queue's own view of a machine does not change until assign's transaction commits, so a
    // pass has to remember what it has already given away.
    @Test
    void onePassDoesNotGiveOneMachineTwoJobs() {
        idle("alpha");
        idle("beta");
        queued("first.gcode", JobRequirements.any());
        queued("second.gcode", JobRequirements.any());

        scheduler.schedule();

        assertEquals(4, actions.size(), actions.toString());
        assertTrue(actions.stream().anyMatch(a -> a.endsWith("-> alpha")));
        assertTrue(actions.stream().anyMatch(a -> a.endsWith("-> beta")));
    }

    // ONLINE is a socket; IDLE is the firmware having taken something this host sent - starting a
    // print on an unacknowledging machine is B3's dead outbound half.
    @Test
    void aConnectedButUnacknowledgingMachineIsNotEligible() {
        machine("alpha", MachineConnectionState.ONLINE, MachineSpec.undeclared());
        var job = queued("part.gcode", JobRequirements.any());

        scheduler.schedule();

        assertEquals(List.of(), actions);
        assertTrue(blockedReasons.get(job.id()).contains("no machine is idle"),
                blockedReasons.get(job.id()));
    }

    /** A machine holding a job is not free, and a paused job still holds one. */
    @Test
    void aMachineWithAJobOnItIsNotFree() {
        var printer = idle("alpha");
        holding(printer, JobState.PAUSED);
        var job = queued("part.gcode", JobRequirements.any());

        scheduler.schedule();

        assertEquals(List.of(), actions, "a paused job still owns its printer");
        assertTrue(blockedReasons.get(job.id()).contains("no machine is idle"));
    }

    /** Capability narrows it, and the machine that fits is chosen over the one that does not. */
    @Test
    void theMachineThatFitsIsChosen() {
        idle("small", new MachineSpec(new BedVolume(100, 100, 100), 1, List.of(0.4),
                Set.of("PLA"), Set.of()));
        idle("large", new MachineSpec(new BedVolume(300, 300, 400), 1, List.of(0.4),
                Set.of("PLA"), Set.of()));
        var job = queued("big.gcode", new JobRequirements(null, new BedVolume(250, 250, 300),
                null, null, null, null));

        scheduler.schedule();

        assertEquals(List.of("assign " + shortId(job.id()) + " -> large",
                "start " + shortId(job.id())), actions);
    }

    /** A pinned job waits for its machine however free the rest of the farm is. */
    @Test
    void aPinnedJobWaitsForItsOwnMachine() {
        idle("alpha");
        var mine = machine("beta", MachineConnectionState.OFFLINE, MachineSpec.undeclared());
        var job = queued("part.gcode",
                new JobRequirements(mine, null, null, null, null, null));

        scheduler.schedule();

        assertEquals(List.of(), actions, "alpha is free and is not the machine that was asked for");
        assertTrue(blockedReasons.get(job.id()).contains("beta"), blockedReasons.get(job.id()));
        assertTrue(blockedReasons.get(job.id()).contains("OFFLINE"));
    }

    /** And runs on it the moment it is free. */
    @Test
    void aPinnedJobRunsOnItsMachineOnceItIsIdle() {
        var mine = idle("beta");
        idle("alpha");
        var job = queued("part.gcode", new JobRequirements(mine, null, null, null, null, null));

        scheduler.schedule();

        assertEquals(List.of("assign " + shortId(job.id()) + " -> beta",
                "start " + shortId(job.id())), actions);
    }

    /** An ineligible farm leaves the job queued with a reason an operator can act on. */
    @Test
    void anIneligibleFarmLeavesTheJobQueuedWithAStatedReason() {
        idle("alpha", new MachineSpec(null, 1, List.of(0.4), Set.of("PLA"), Set.of()));
        var job = queued("petg.gcode",
                new JobRequirements(null, null, null, null, "PETG", null));

        scheduler.schedule();

        assertEquals(List.of(), actions);
        var why = blockedReasons.get(job.id());
        assertTrue(why.contains("alpha"), why);
        assertTrue(why.contains("PETG"), why);
    }

    @Test
    void anEmptyFarmSaysSo() {
        var job = queued("part.gcode", JobRequirements.any());

        scheduler.schedule();

        assertEquals("the farm has no machines", blockedReasons.get(job.id()));
    }

    /** Nothing queued is the normal case and must cost nothing. */
    @Test
    void anEmptyQueueDoesNothingAtAll() {
        idle("alpha");

        scheduler.schedule();

        assertEquals(List.of(), actions);
        assertTrue(blockedReasons.isEmpty());
    }

    /** A machine the runtime has never heard of is offline, not eligible. */
    @Test
    void aMachineTheRuntimeKnowsNothingAboutIsNotEligible() {
        var id = idle("alpha");
        live.remove(id);
        var job = queued("part.gcode", JobRequirements.any());

        scheduler.schedule();

        assertEquals(List.of(), actions);
        assertTrue(blockedReasons.get(job.id()).contains("no machine is idle"),
                "a machine nothing has dialled cannot print, and the reason should say so");
    }

    // --- the threads a transition is announced on -------------------------------------------

    private JobStateChanged started(Job job, UUID machineId) {
        return new JobStateChanged(job.id(), job.fileId(), JobState.ASSIGNED, JobState.RUNNING,
                machineId, null, LocalDateTime.now());
    }

    /**
     * {@code JobFeeder}'s two watchers run on {@code reactor.updates()}, which is
     * {@code publishOn(executor)} - this machine's own single thread - so a job failed there
     * announces its transition from that thread. Asking the same machine where it is from inside
     * that thread is a task queued behind the one making the call.
     */
    @Test
    void aTransitionAnnouncedOnAMachinesOwnThreadDoesNotWedgeIt() throws Exception {
        var printer = idle("alpha");
        var job = queued("part.gcode", JobRequirements.any());
        ExecutorService one = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("p3d-printer-" + printer).factory());
        aggregate = Schedulers.fromExecutorService(one, "p3d-printer-" + printer);

        try {
            one.submit(() -> scheduler.onJobStateChanged(started(job, printer)))
                    .get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(5)).until(() -> actions.size() == 2);
            assertEquals(List.of("assign " + shortId(job.id()) + " -> alpha",
                    "start " + shortId(job.id())), actions);
        } finally {
            aggregate.dispose();
        }
    }

    /**
     * A feed that reaches the end of its file reports it from a reactor-netty thread, and Reactor
     * refuses a blocking read there outright - so the pass has to leave that thread rather than
     * read every machine as unreachable. {@code Schedulers.parallel()} carries the same
     * {@code NonBlocking} marker an event loop does.
     */
    @Test
    void aTransitionAnnouncedOnANonBlockingThreadStillGivesTheJobAMachine() {
        var printer = idle("alpha");
        var job = queued("part.gcode", JobRequirements.any());

        Schedulers.parallel().schedule(() -> scheduler.onJobStateChanged(started(job, printer)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(
                List.of("assign " + shortId(job.id()) + " -> alpha",
                        "start " + shortId(job.id())), actions));
    }
}

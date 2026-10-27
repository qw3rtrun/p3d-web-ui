package org.qw3rtrun.p3d.jobs;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.files.SliceInfo;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;
import org.qw3rtrun.p3d.api.msg.MDeletedEvent;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue, against the <strong>real Liquibase schema</strong>.
 *
 * <p>The file store is stubbed rather than booted: this module depends on
 * {@code GcodeFileService}, an interface in {@code api}, and the point of that is being able to
 * stand the queue up without the module behind it. What is <em>not</em> stubbed is the database -
 * an entity and a changeset disagreeing is the failure worth catching here.
 */
@SpringBootTest
class JobServiceTest {

    @SpringBootApplication
    static class TestApp {
    }

    /** A file store with a couple of files in it and no bytes anywhere. */
    static class StubFiles implements GcodeFileService {
        final ConcurrentHashMap<UUID, GcodeFile> held = new ConcurrentHashMap<>();

        GcodeFile add(String name) {
            var file = new GcodeFile(UUID.randomUUID(), name, UUID.randomUUID().toString(),
                    1024, 42, LocalDateTime.now(), SliceInfo.unknown());
            held.put(file.id(), file);
            return file;
        }

        @Override
        public Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content) {
            throw new UnsupportedOperationException("the queue never stores a file");
        }

        @Override
        public Optional<GcodeFile> fetch(UUID id) {
            return Optional.ofNullable(held.get(id));
        }

        @Override
        public Stream<GcodeFile> fetchAll() {
            return held.values().stream();
        }

        @Override
        public Publisher<ByteBuffer> read(UUID id) {
            throw new UnsupportedOperationException("the queue never reads a file");
        }

        @Override
        public void delete(UUID id) {
            held.remove(id);
        }
    }

    /** Catches what the queue announces. A plain listener, so it sees events synchronously. */
    static class Announcements {
        final List<JobStateChanged> heard = new ArrayList<>();

        @EventListener
        void on(JobStateChanged event) {
            heard.add(event);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        StubFiles stubFiles() {
            return new StubFiles();
        }

        @Bean
        Announcements announcements() {
            return new Announcements();
        }
    }

    @Autowired
    private JobService jobs;

    @Autowired
    private StubFiles files;

    @Autowired
    private Announcements announcements;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @Autowired
    private org.springframework.modulith.events.CompletedEventPublications completed;

    private Job queued(String name) {
        var file = files.add(name);
        var outcome = jobs.queue(file.id());
        return assertInstanceOf(JobOutcome.Done.class, outcome).job();
    }

    private static Job done(JobOutcome outcome) {
        return assertInstanceOf(JobOutcome.Done.class, outcome).job();
    }

    private static JobOutcome.Refused refused(JobOutcome outcome) {
        return assertInstanceOf(JobOutcome.Refused.class, outcome);
    }

    @Test
    void aQueuedJobCarriesTheFileItWillPrint() {
        var job = queued("benchy.gcode");

        assertNotNull(job.id());
        assertEquals(JobState.QUEUED, job.state());
        assertEquals("benchy.gcode", job.fileName());
        assertNull(job.machineId(), "nothing has been chosen yet");
        assertNull(job.startedAt());
        assertNull(job.finishedAt());
    }

    /** A job pointing at no file is refused at the queue rather than at the printer. */
    @Test
    void queueingAFileThatDoesNotExistIsRefused() {
        var refusal = refused(jobs.queue(UUID.randomUUID()));

        assertTrue(refusal.reason().contains("no file"), refusal.reason());
    }

    @Test
    void theHappyPathRunsAllTheWayThrough() {
        var machine = UUID.randomUUID();
        var job = queued("happy.gcode");

        assertEquals(JobState.ASSIGNED, done(jobs.assign(job.id(), machine)).state());
        var running = done(jobs.start(job.id()));
        assertEquals(JobState.RUNNING, running.state());
        assertNotNull(running.startedAt());

        var finished = done(jobs.complete(job.id()));
        assertEquals(JobState.COMPLETED, finished.state());
        assertNotNull(finished.finishedAt());
        assertEquals(machine, finished.machineId());
    }

    /**
     * <strong>An illegal move is a value, which is plan item C2's first done-when.</strong>
     *
     * <p>Not an exception, and above all not a constraint violation surfacing from the database:
     * the usual cause is a user clicking Pause as the last line is acknowledged, and the right
     * answer to that is to say where the job actually is.
     */
    @Test
    void anIllegalTransitionIsRefusedWithWhereTheJobActuallyIs() {
        var job = queued("illegal.gcode");

        var refusal = refused(jobs.pause(job.id()));

        assertEquals(job.id(), refusal.jobId());
        assertEquals(JobState.QUEUED, refusal.state(), "where it really is");
        assertEquals(JobState.PAUSED, refusal.attempted());
        assertTrue(refusal.reason().contains("QUEUED"), refusal.reason());

        assertEquals(JobState.QUEUED, jobs.fetch(job.id()).orElseThrow().state(),
                "and the refusal changed nothing");
    }

    @Test
    void anUnknownJobIsRefusedRatherThanThrowing() {
        var id = UUID.randomUUID();

        var refusal = refused(jobs.start(id));

        assertEquals(id, refusal.jobId());
        assertNull(refusal.state(), "there is no state, because there is no job");
    }

    /** Nothing leaves a terminal state, so a finished job absorbs every further request. */
    @Test
    void aFinishedJobRefusesEverything() {
        var job = queued("finished.gcode");
        jobs.assign(job.id(), UUID.randomUUID());
        jobs.start(job.id());
        jobs.complete(job.id());

        refused(jobs.pause(job.id()));
        refused(jobs.cancel(job.id(), "too late"));
        refused(jobs.fail(job.id(), "too late"));
        refused(jobs.start(job.id()));

        assertEquals(JobState.COMPLETED, jobs.fetch(job.id()).orElseThrow().state());
    }

    /** A pause and resume does not make the job look as though it started twice. */
    @Test
    void resumingDoesNotMoveTheStartTime() {
        var job = queued("paused.gcode");
        jobs.assign(job.id(), UUID.randomUUID());
        var started = done(jobs.start(job.id()));

        var persistedStart = jobs.fetch(job.id()).orElseThrow().startedAt();

        jobs.pause(job.id());
        jobs.resume(job.id());

        var afterResume = jobs.fetch(job.id()).orElseThrow();
        assertEquals(JobState.RUNNING, afterResume.state());
        assertEquals(persistedStart, afterResume.startedAt());
        assertNotNull(started.startedAt());
    }

    @Test
    void cancellingKeepsTheReasonOnTheJob() {
        var job = queued("doomed.gcode");

        var cancelled = done(jobs.cancel(job.id(), "the operator changed their mind"));

        assertEquals(JobState.CANCELLED, cancelled.state());
        assertEquals("the operator changed their mind", cancelled.reason());
        assertNotNull(cancelled.finishedAt());
    }

    /** Back in the queue means back to nobody's machine. */
    @Test
    void unassigningClearsTheMachine() {
        var job = queued("returned.gcode");
        jobs.assign(job.id(), UUID.randomUUID());

        var back = done(jobs.unassign(job.id(), "the machine was busy after all"));

        assertEquals(JobState.QUEUED, back.state());
        assertNull(back.machineId(), "a queued job that still claimed a machine would be assigned twice");
    }

    /** Every transition announces itself, and the announcement says what it did to the machine. */
    @Test
    void everyTransitionIsAnnounced() {
        var machine = UUID.randomUUID();
        announcements.heard.clear();

        var job = queued("announced.gcode");
        jobs.assign(job.id(), machine);
        jobs.start(job.id());
        jobs.complete(job.id());

        var mine = announcements.heard.stream().filter(e -> job.id().equals(e.jobId())).toList();
        assertEquals(List.of(JobState.QUEUED, JobState.ASSIGNED, JobState.RUNNING, JobState.COMPLETED),
                mine.stream().map(JobStateChanged::to).toList());
        assertTrue(mine.getFirst().isNew(), "the first one is the job appearing, not moving");
        assertTrue(mine.getLast().releasedAMachine(), "and the last one hands the printer back");
        assertTrue(mine.getLast().finished());
    }

    /** A refused move announces nothing, because nothing happened. */
    @Test
    void aRefusedTransitionAnnouncesNothing() {
        var job = queued("quiet.gcode");
        announcements.heard.clear();

        jobs.pause(job.id());

        assertEquals(List.of(), announcements.heard);
    }

    /**
     * A deleted machine releases what it was holding, and the two cases differ.
     *
     * <p>An assigned job can go to another printer with nothing lost. A running one cannot - the
     * file has been partly consumed, and a second machine starting it from line one would print
     * the top half of the part onto an empty bed - so it fails with a reason that says why.
     */
    @Test
    void aDeletedMachineReleasesItsAssignedJobAndFailsItsRunningOne() {
        var machine = UUID.randomUUID();
        var waiting = queued("waiting.gcode");
        var printing = queued("printing.gcode");
        jobs.assign(waiting.id(), machine);
        jobs.assign(printing.id(), machine);
        jobs.start(printing.id());

        // Published inside a transaction, because @ApplicationModuleListener is a
        // @TransactionalEventListener: an event published outside one is dropped, which is the
        // whole point - it is only durable if there was a commit to tie it to. In production this
        // comes from MachineManagementImpl.delete, which is @Transactional.
        transactions.executeWithoutResult(status ->
                publisher.publishEvent(new MDeletedEvent(machine, LocalDateTime.now())));

        // And awaited, because the same annotation makes it @Async - the delete returns before the
        // queue has reacted, which is the behaviour that keeps one module's transaction out of
        // another module's.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var wasWaiting = jobs.fetch(waiting.id()).orElseThrow();
            assertEquals(JobState.QUEUED, wasWaiting.state());
            assertNull(wasWaiting.machineId());

            var wasPrinting = jobs.fetch(printing.id()).orElseThrow();
            assertEquals(JobState.FAILED, wasPrinting.state());
            assertTrue(wasPrinting.reason().contains("removed"), wasPrinting.reason());
        });
    }

    /**
     * The event publication registry is doing its job, which is C2's second done-when.
     *
     * <p>A durable listener means Modulith writes a row inside the publishing transaction and
     * marks it complete when the listener returns. That is what makes delivery at-least-once
     * across a commit - without it an event is a method call that happens to be late, and a
     * printer deleted as the application shut down would leave its jobs pointing at a machine that
     * no longer exists with nothing to notice.
     *
     * <p>Asserted on {@code CompletedEventPublications} rather than by counting table rows: what
     * matters is not that something was written but that it was <em>completed</em>, because an
     * incomplete publication is exactly what gets replayed on the next start.
     */
    @Test
    void aDurableEventIsRegisteredAndCompleted() {
        var machine = UUID.randomUUID();
        var job = queued("registered.gcode");
        jobs.assign(job.id(), machine);

        transactions.executeWithoutResult(status ->
                publisher.publishEvent(new MDeletedEvent(machine, LocalDateTime.now())));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(JobState.QUEUED, jobs.fetch(job.id()).orElseThrow().state(),
                    "the listener ran");
            assertTrue(completed.findAll().stream()
                            .anyMatch(p -> p.getEvent() instanceof MDeletedEvent deleted
                                    && machine.equals(deleted.getMachineId())),
                    "the registry recorded this publication and marked it complete");
        });
    }

    /**
     * A checkpoint is kept, and read back - plan item C4's second done-when.
     *
     * <p>"Within one persisted checkpoint" is the promise, and the point of writing it at all is a
     * restart: a job that was half printed when the process died should say so rather than
     * starting from nothing.
     */
    @Test
    void progressIsCheckpointedAndReadBack() {
        var machine = UUID.randomUUID();
        var job = queued("progress.gcode");
        jobs.assign(job.id(), machine);
        jobs.start(job.id());

        publisher.publishEvent(new org.qw3rtrun.p3d.api.jobs.JobProgressed(job.id(), machine,
                new org.qw3rtrun.p3d.api.jobs.JobProgress(4200, 3, 150_000, 1_000_000, 12,
                        LocalDateTime.now().minusMinutes(2), LocalDateTime.now())));

        var read = jobs.fetch(job.id()).orElseThrow().progress();
        assertEquals(4200, read.blocksSent());
        assertEquals(4197, read.blocksAccepted(), "three were still in the send window");
        assertEquals(150_000, read.bytesSent());
        assertEquals(12, read.layer());
        assertEquals(0.15, read.fraction(), 0.0001);
    }

    /**
     * A checkpoint that lands after the job stopped does not revive its progress.
     *
     * <p>The timer and the cancel race by construction - {@code core} publishes about once a second
     * and a cancel can land between a reading and its delivery - so a late one would otherwise
     * overwrite the last real checkpoint and make a stopped job look as though it had got further.
     */
    @Test
    void aCheckpointArrivingAfterTheJobStoppedIsIgnored() {
        var machine = UUID.randomUUID();
        var job = queued("late-progress.gcode");
        jobs.assign(job.id(), machine);
        jobs.start(job.id());
        publisher.publishEvent(new org.qw3rtrun.p3d.api.jobs.JobProgressed(job.id(), machine,
                new org.qw3rtrun.p3d.api.jobs.JobProgress(100, 0, 1000, 10_000, 1,
                        LocalDateTime.now(), LocalDateTime.now())));
        jobs.cancel(job.id(), "stopped");

        publisher.publishEvent(new org.qw3rtrun.p3d.api.jobs.JobProgressed(job.id(), machine,
                new org.qw3rtrun.p3d.api.jobs.JobProgress(9999, 0, 9999, 10_000, 99,
                        LocalDateTime.now(), LocalDateTime.now())));

        var read = jobs.fetch(job.id()).orElseThrow().progress();
        assertEquals(100, read.blocksSent(), "the checkpoint from while it was running");
    }

    /** A job that never ran reports nothing rather than zero-of-zero. */
    @Test
    void aJobThatNeverRanHasNoProgress() {
        var job = queued("never-run.gcode");

        var read = jobs.fetch(job.id()).orElseThrow().progress();
        assertEquals(0, read.blocksSent());
        assertNull(read.at());
    }

    /** The listing is newest first, and a machine's own jobs are a narrower question. */    /** The listing is newest first, and a machine's own jobs are a narrower question. */
    @Test
    void jobsCanBeListedWholeOrPerMachine() {
        var machine = UUID.randomUUID();
        var mine = queued("mine.gcode");
        var theirs = queued("theirs.gcode");
        jobs.assign(mine.id(), machine);

        var forMachine = jobs.forMachine(machine).stream().map(Job::id).toList();
        assertTrue(forMachine.contains(mine.id()));
        assertTrue(!forMachine.contains(theirs.id()));
        assertTrue(jobs.fetchAll().size() >= 2);
    }
}

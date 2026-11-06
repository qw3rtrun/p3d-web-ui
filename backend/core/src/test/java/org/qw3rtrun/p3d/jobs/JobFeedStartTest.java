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
import org.qw3rtrun.p3d.core.JobFeeder;
import org.qw3rtrun.p3d.core.PrinterDirectory;
import org.qw3rtrun.p3d.core.PrinterReactorManager;
import org.qw3rtrun.p3d.core.PrinterState;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A feed is started only once the transition that started it has committed - issue #93.
 *
 * <p>A sibling of {@link JobServiceTest} rather than more cases in it, because what is under test
 * is {@code jobs}' transaction boundary against {@code core}'s listener and both halves have to be
 * real: the defect does not exist without a transaction manager, and it is invisible without
 * something that acts on the announcement. The file store is still stubbed; the database is not.
 *
 * <p>What it defends. {@code JobFeeder} starts a feed on {@code ASSIGNED -> RUNNING}, and the
 * feed's own outcome moves the job again. Started from inside the still-uncommitted transition, a
 * feed against a mock printer ran out in about five milliseconds - sooner than the commit - and the
 * second transaction carrying the outcome then read the row as {@code ASSIGNED}:
 * {@code refused to move job e0417935... from ASSIGNED to COMPLETED}, with every block of the file
 * already on the wire and the job left {@code RUNNING} for ever, because a feed outcome is
 * at-most-once by design and nothing retried. Measured as 4 of 10 loaded runs of
 * {@code PrintJobIntegrationTest} in {@code :app}, 0 of 10 idle.
 *
 * <p>Everything asserted on is read through a method, never a field: these beans are auto-proxied
 * in this context, and a field read off the proxy sees the proxy's own default-valued copy rather
 * than the target's - which reads exactly like a listener that never fired.
 */
@SpringBootTest
class JobFeedStartTest {

    @SpringBootApplication
    static class TestApp {
    }

    /** One file, no bytes: no path under test gets as far as reading one. */
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
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException("no feed here ever reaches a file");
        }

        @Override
        public void delete(UUID id) {
            held.remove(id);
        }
    }

    /**
     * {@code JobFeeder}'s start listener, standing in for it so the invariant can be asserted with
     * no printer in the picture: told that a job started, it reads that job back in a transaction
     * of its own and records what it found. Same annotation, same phase, same
     * {@code fallbackExecution} - the signature is what is being proved. Keyed by job id, because
     * this is a cached {@code @SpringBootTest} context and both test methods in this class start a
     * job on the one bean.
     */
    public static class WhatTheFeedWouldSee {
        private final SeparateTransaction read;

        private final ConcurrentHashMap<UUID, JobState> seenByJob = new ConcurrentHashMap<>();

        WhatTheFeedWouldSee(SeparateTransaction read) {
            this.read = read;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        public void onJobStarted(JobStateChanged event) {
            if (event.to() != JobState.RUNNING || event.from() != JobState.ASSIGNED) {
                return;
            }
            seenByJob.put(event.jobId(), read.stateOf(event.jobId()));
        }

        public Optional<JobState> saw(UUID jobId) {
            return Optional.ofNullable(seenByJob.get(jobId));
        }
    }

    /**
     * Reads a job in a transaction that shares nothing with the one that published the event -
     * {@code PROPAGATION_REQUIRES_NEW}, so it genuinely cannot see an uncommitted write. That is
     * the position {@code FeedOutcomes} is in when a short feed has already run out, and it is the
     * only reason the answer means anything.
     */
    static class SeparateTransaction {
        private final TransactionTemplate ownTransaction;
        private final JobService jobs;

        SeparateTransaction(PlatformTransactionManager transactions, JobService jobs) {
            this.ownTransaction = new TransactionTemplate(transactions);
            this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.jobs = jobs;
        }

        JobState stateOf(UUID jobId) {
            return ownTransaction.execute(status -> jobs.fetch(jobId).map(Job::state).orElse(null));
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        StubFiles stubFiles() {
            return new StubFiles();
        }

        @Bean
        SeparateTransaction separateTransaction(PlatformTransactionManager transactions, JobService jobs) {
            return new SeparateTransaction(transactions, jobs);
        }

        @Bean
        WhatTheFeedWouldSee whatTheFeedWouldSee(SeparateTransaction read) {
            return new WhatTheFeedWouldSee(read);
        }

        /** A farm with nothing running in it, so {@code runtimeOf} answers null for every machine - the end-to-end case below. */
        @Bean
        PrinterReactorManager noRuntimes() {
            return new PrinterReactorManager(new PrinterDirectory(), PrinterState::new, event -> {
            });
        }

        @Bean
        JobFeeder jobFeeder(PrinterReactorManager reactors, GcodeFileService files, JobService jobs,
                            ApplicationEventPublisher events) {
            return new JobFeeder(reactors, files, jobs, events);
        }
    }

    @Autowired
    private JobService jobs;

    @Autowired
    private StubFiles files;

    @Autowired
    private WhatTheFeedWouldSee feed;

    private Job queued(String name) {
        var file = files.add(name);
        return assertInstanceOf(JobOutcome.Done.class, jobs.queue(file.id())).job();
    }

    /**
     * The invariant the measured failure violated, asserted without racing anything: by the time
     * whatever starts a feed is told the job started, {@code RUNNING} is visible to a transaction
     * that is not the transition's own. Against a plain {@code @EventListener} the same stand-in
     * records {@code ASSIGNED}.
     */
    @Test
    void whenTheFeedIsToldToStartTheJobIsAlreadyCommittedAsRunning() {
        var job = queued("committed-first.gcode");
        jobs.assign(job.id(), UUID.randomUUID());

        jobs.start(job.id());

        var seen = feed.saw(job.id());
        assertTrue(seen.isPresent(),
                "nothing heard the job start at all, so this test is proving nothing");
        assertEquals(JobState.RUNNING, seen.orElseThrow(),
                "a separate transaction could not see the move that started the feed, so the"
                        + " feed's own outcome will be refused and the job left at RUNNING");
    }

    /**
     * The same window from the other end, and the half that needs no timing at all:
     * {@code JobFeeder.start} finds no runtime for the machine and fails the job there and then.
     * Run inside the uncommitted transition, that failure is read from a row that still says
     * {@code ASSIGNED} and is refused - either as an illegal move or, once it has been overtaken,
     * by the row's own version - leaving a job that reads {@code RUNNING} against a machine the
     * farm is not even connected to. {@code FeedOutcomes} hops to {@code boundedElastic}, hence the
     * wait.
     */
    @Test
    void startingAJobWithNoRuntimeForItsMachineFailsItRatherThanLeavingItRunning() {
        var machine = UUID.randomUUID();
        var job = queued("no-runtime.gcode");
        jobs.assign(job.id(), machine);

        jobs.start(job.id());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var after = jobs.fetch(job.id()).orElseThrow();
            assertEquals(JobState.FAILED, after.state(),
                    "there is no runtime for this machine, so nothing can be printing on it");
            assertTrue(after.reason().contains("no runtime"), after.reason());
        });
    }
}

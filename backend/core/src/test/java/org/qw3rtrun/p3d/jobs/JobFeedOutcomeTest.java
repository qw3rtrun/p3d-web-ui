package org.qw3rtrun.p3d.jobs;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobFeedCompleted;
import org.qw3rtrun.p3d.api.jobs.JobFeedFailed;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.qw3rtrun.p3d.api.jobs.JobProgress;
import org.qw3rtrun.p3d.api.jobs.JobProgressed;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job is never visible as over while its checkpoint still says something else - issue #102, and
 * the end-of-feed half of #62 stated as a property of the row rather than of two publications'
 * order.
 *
 * <p>A sibling of {@link JobServiceTest} rather than more cases in it, and for the same reason
 * {@link JobFeedStartTest} is: the defect does not exist without a real transaction manager. What
 * is under test is that the final reading and the terminal transition are <em>one write</em>, which
 * can only be observed by something reading the row from outside that write's own transaction. The
 * file store is stubbed; the database is not.
 *
 * <p><strong>What it defends.</strong> The reading taken when a feed ends is the one checkpoint
 * that cannot be taken again - the feed is removed from {@code JobFeeder} the moment it ends, so
 * the timer never writes that job again and whatever is on the row then is what it keeps for ever.
 * Before #62 a live twelve-block job recorded {@code blocksSent 4} and a three-block one recorded
 * {@code 0}. #62 bought the right number by publishing a {@code JobProgressed} inline, immediately
 * before the outcome event - which measured, through a listener standing alongside
 * {@code JobServiceImpl.onProgress} in a booted {@code :app} context, as
 * {@code JobProgressed reactor-tcp-nio-6 txn=false} followed by
 * {@code JobFeedCompleted reactor-tcp-nio-6}: a JDBC transaction opened on an event loop, once per
 * feed, against the timer's {@code scheduling-1 txn=false} for the other eight of nine
 * publications. The reading now rides on {@link JobFeedCompleted}/{@link JobFeedFailed} and
 * {@code JobService} writes it in the transition's own transaction, so the ordering is structural
 * and nothing is published from the loop at all.
 *
 * <p>Each case arranges a <em>stale</em> checkpoint on the row first, so the assertion cannot pass
 * by reading a zero: a design that writes the reading after the transition sees that stale number
 * every time, with no race, which is what makes this test fail deterministically rather than
 * occasionally.
 *
 * <p>Everything asserted on is read through a method, never a field: these beans are auto-proxied
 * in this context, and a field read off the proxy sees the proxy's own default-valued copy rather
 * than the target's - which reads exactly like a listener that never fired.
 */
@SpringBootTest
class JobFeedOutcomeTest {

    @SpringBootApplication
    static class TestApp {
    }

    /**
     * What anybody but the transition's own transaction can see at the instant the job becomes
     * terminal. {@code AFTER_COMMIT}, so this is the earliest moment the move exists for anyone
     * else; {@code PROPAGATION_REQUIRES_NEW}, so what it reads back is genuinely committed state
     * and not the publisher's uncommitted context. Keyed by job id, because this is a cached
     * {@code @SpringBootTest} context and both cases end a job on the one bean.
     */
    public static class WhatAnObserverSeesWhenTheJobEnds {
        private final TransactionTemplate ownTransaction;
        private final JobService jobs;

        private final ConcurrentHashMap<UUID, Long> blocksSentByJob = new ConcurrentHashMap<>();

        WhatAnObserverSeesWhenTheJobEnds(PlatformTransactionManager transactions, JobService jobs) {
            this.ownTransaction = new TransactionTemplate(transactions);
            this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.jobs = jobs;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        public void onJobEnded(JobStateChanged event) {
            if (!event.to().isTerminal()) {
                return;
            }
            var seen = ownTransaction.execute(status -> jobs.fetch(event.jobId())
                    .map(job -> job.progress().blocksSent()).orElse(null));
            if (seen != null) {
                blocksSentByJob.put(event.jobId(), seen);
            }
        }

        public Optional<Long> saw(UUID jobId) {
            return Optional.ofNullable(blocksSentByJob.get(jobId));
        }
    }

    @TestConfiguration
    static class Config {
        /** The same stub {@link JobFeedStartTest} uses: one file, no bytes, since no feed here ever reads one. */
        @Bean
        JobFeedStartTest.StubFiles stubFiles() {
            return new JobFeedStartTest.StubFiles();
        }

        @Bean
        WhatAnObserverSeesWhenTheJobEnds observer(PlatformTransactionManager transactions,
                                                 JobService jobs) {
            return new WhatAnObserverSeesWhenTheJobEnds(transactions, jobs);
        }
    }

    @Autowired
    private JobService jobs;

    @Autowired
    private JobFeedStartTest.StubFiles files;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private WhatAnObserverSeesWhenTheJobEnds observer;

    private static final long STALE = 4;
    private static final long EXACT = 12;

    /** Where a feed got to, as a feed ending reports it. */
    private static JobProgress reading(long blocksSent) {
        return new JobProgress(blocksSent, 2, blocksSent * 11, EXACT * 11, 3,
                LocalDateTime.now().minusMinutes(7), LocalDateTime.now());
    }

    /**
     * A job printing on a machine, with a timer's reading already on the row. Without that, a test
     * that read zero could not tell "the reading landed first" from "nothing has been written at
     * all".
     */
    private UUID runningWithAStaleCheckpoint(String name) {
        var file = files.add(name);
        var job = assertInstanceOf(JobOutcome.Done.class, jobs.queue(file.id())).job();
        var machine = UUID.randomUUID();
        jobs.assign(job.id(), machine);
        jobs.start(job.id());

        publisher.publishEvent(new JobProgressed(job.id(), machine, reading(STALE)));
        assertEquals(STALE, jobs.fetch(job.id()).orElseThrow().progress().blocksSent(),
                "the premise is a stale checkpoint genuinely on the row, so that reading it back"
                        + " afterwards proves something");
        return job.id();
    }

    private void seen(UUID job, String what) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertTrue(observer.saw(job).isPresent(),
                        "nothing observed the job end at all, so this test is proving nothing"));
        assertEquals(EXACT, observer.saw(job).orElseThrow(),
                "the first transaction that could see this job " + what + " read a checkpoint of "
                        + observer.saw(job).orElseThrow() + " rather than the " + EXACT
                        + " the feed actually ended on, so the exact reading is written after the"
                        + " state that says it is over - and nothing ever checkpoints this job"
                        + " again (#62, #102)");
    }

    @Test
    void theRowAlreadyCarriesTheFinalReadingWhenAJobBecomesCompleted() {
        var job = runningWithAStaleCheckpoint("ends-completed.gcode");

        publisher.publishEvent(new JobFeedCompleted(job, UUID.randomUUID(), reading(EXACT),
                LocalDateTime.now()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(JobState.COMPLETED,
                jobs.fetch(job).map(Job::state).orElseThrow()));
        seen(job, "as COMPLETED");
    }

    /** A separate verb with a separate caller and a reason string, so it gets its own case rather than being assumed to follow. */
    @Test
    void theRowAlreadyCarriesTheFinalReadingWhenAJobBecomesFailed() {
        var job = runningWithAStaleCheckpoint("ends-failed.gcode");

        publisher.publishEvent(new JobFeedFailed(job, UUID.randomUUID(), reading(EXACT),
                "the connection to the machine went away while it was printing",
                LocalDateTime.now()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(JobState.FAILED,
                jobs.fetch(job).map(Job::state).orElseThrow()));
        seen(job, "as FAILED");
    }
}

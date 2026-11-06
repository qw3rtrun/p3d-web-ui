package org.qw3rtrun.p3d.jobs;

import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.jobs.service.FeedOutcomes;
import org.qw3rtrun.p3d.jobs.service.JobRepository;
import org.qw3rtrun.p3d.jobs.service.JobServiceImpl;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration
@EnableJpaRepositories
public class JobsModule {

    /** Injected rather than called, so every timestamp a job carries can be driven by a test - the same reason {@code ConnectionLifecycle} takes one, since a state machine that reads the wall clock can otherwise only be tested by sleeping. */
    @Bean
    public Clock jobClock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Teaches Jackson about Kotlin, so the event registry can read back what it wrote. Not
     * decoration: the registry serialises on publish and deserialises to replay - the half it exists
     * for - and {@code api}'s management events are Kotlin data classes, so without this publishing
     * and delivery both worked and only a restart failed
     * ({@code Cannot construct instance of MDeletedEvent (no Creators...)}), discovered only when a
     * test asked the registry for the publication it had just written. A {@code JacksonModule} bean,
     * not a mapper, since that is Modulith's own extension point
     * ({@code JacksonEventSerializationConfiguration} collects every such bean) - a {@code JsonMapper}
     * bean here would quietly become the whole application's. Lives here because {@code jobs} is the
     * only module publishing durably; moves to {@code :app} alongside the registry's changelog when a
     * second one does.
     */
    @Bean
    public tools.jackson.databind.JacksonModule kotlinEventsModule() {
        return new tools.jackson.module.kotlin.KotlinModule.Builder().build();
    }

    @Bean
    public JobService jobService(JobRepository repo, GcodeFileService files,
                                 ApplicationEventPublisher events, Clock jobClock,
                                 TransactionTemplate transactions) {
        return new JobServiceImpl(repo, files, events, jobClock, transactions);
    }

    /** A bean of its own, not two methods on the service - see {@link FeedOutcomes} for why the proxy boundary is the point. */
    @Bean
    public FeedOutcomes feedOutcomes(JobService jobService) {
        return new FeedOutcomes(jobService);
    }
}

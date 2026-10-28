package org.qw3rtrun.p3d.jobs;

import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.jobs.service.JobRepository;
import org.qw3rtrun.p3d.jobs.service.JobServiceImpl;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Clock;

@Configuration
@EnableJpaRepositories
public class JobsModule {

    /**
     * Injected rather than called, so that every timestamp a job carries can be driven by a test.
     *
     * <p>The same reason {@code ConnectionLifecycle} takes one: "it started at X and finished at Y"
     * is behaviour worth asserting, and a state machine that reads the wall clock can only be
     * tested by sleeping.
     */
    @Bean
    public Clock jobClock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Teaches Jackson about Kotlin, so the event registry can read back what it wrote.
     *
     * <p><strong>This is not decoration.</strong> The registry serialises an event to JSON on
     * publish and deserialises it to replay - which is the half it exists for - and {@code api}'s
     * management events are Kotlin data classes. Without this, publishing works, delivery works,
     * and a <em>restart</em> fails: {@code Cannot construct instance of MDeletedEvent (no
     * Creators, like default constructor, exist)}. Everything looked fine until a test asked the
     * registry for the publication it had just written.
     *
     * <p>A {@code JacksonModule} bean rather than a mapper, because that is Modulith's extension
     * point - {@code JacksonEventSerializationConfiguration} collects every such bean - and
     * because a {@code JsonMapper} bean here would quietly become the whole application's, letting
     * this module decide how the web layer renders JSON. It is additive and application-wide on
     * purpose: an application that publishes Kotlin events should be able to read them.
     *
     * <p>It lives here because {@code jobs} is the only module that publishes durably. When a
     * second one does, this moves to {@code :app} alongside the registry's changelog.
     */
    @Bean
    public tools.jackson.databind.JacksonModule kotlinEventsModule() {
        return new tools.jackson.module.kotlin.KotlinModule.Builder().build();
    }

    @Bean
    public JobService jobService(JobRepository repo, GcodeFileService files,
                                 ApplicationEventPublisher events, Clock jobClock) {
        return new JobServiceImpl(repo, files, events, jobClock);
    }
}

package org.qw3rtrun.p3d.jobs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.qw3rtrun.p3d.api.jobs.JobFeedCompleted;
import org.qw3rtrun.p3d.api.jobs.JobFeedFailed;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.springframework.context.event.EventListener;
import reactor.core.scheduler.Schedulers;

/**
 * The two ways a feed ends, heard off the thread that reported them.
 *
 * <p>Both events are published by {@code core} from a reactor-netty event loop - a feed that runs
 * out of file completes through {@code PublisherQueue} on the connection's own thread - and moving
 * a job means a transaction, a {@code findById}, a {@code save} and a commit. Left where it
 * arrived, that is blocking JDBC on an event loop on <em>every</em> print that finishes or fails,
 * with no symptom until the farm is under load, because H2 and PostgreSQL will both serve a query
 * from a netty thread perfectly happily. So the listener does nothing but hand the work to
 * {@code boundedElastic}.
 *
 * <p><strong>Its own bean, rather than two more methods on {@link JobServiceImpl}.</strong> The
 * transaction has to start on the thread that does the work, and {@code @Transactional} on a
 * listener that hops cannot do that - it would wrap the publishing thread's call, which returns the
 * moment the task is queued. Calling {@link JobService} from here crosses a bean boundary, so it
 * goes through the proxy and the transaction begins where the writing happens.
 *
 * <p><strong>A plain {@code @EventListener}, and that is not an oversight.</strong>
 * {@code @ApplicationModuleListener} is a {@code @TransactionalEventListener} and would silently
 * drop both of these, since there is no transaction on a netty thread to tie a durable publication
 * to - measured live as a 96,152-block print that reached the end of its file and stayed
 * {@code RUNNING} forever. That is what makes a feed outcome <strong>at-most-once</strong>: if the
 * process dies between the last block and this listener, the job is left {@code RUNNING} and
 * {@code JobServiceImpl.failWhatTheRestartInterrupted} is what notices at the next start. The hop
 * widens that window by however long the task waits to be scheduled, and changes nothing else about
 * it.
 *
 * <p><strong>The hop cannot disturb the checkpoint ordering {@code JobFeeder} depends on (#62),
 * because there is no longer an ordering to disturb</strong> (#102). The exact end-of-feed reading
 * arrives <em>on</em> the event rather than as a {@code JobProgressed} published just ahead of it,
 * and is handed to {@code JobService} to write in the terminal transition's own transaction - one
 * write, so a job cannot be visible as over while its checkpoint still says something else. That
 * also empties the netty thread entirely: it publishes the outcome and nothing else, where before
 * it opened a JDBC transaction for the reading first.
 */
@Slf4j
@RequiredArgsConstructor
public class FeedOutcomes {

    private final JobService jobs;

    @EventListener
    public void onFeedCompleted(JobFeedCompleted event) {
        Schedulers.boundedElastic().schedule(() -> {
            log.info("job {} was fed {} blocks and is done", event.jobId(),
                    event.progress().blocksSent());
            jobs.complete(event.jobId(), event.progress());
        });
    }

    @EventListener
    public void onFeedFailed(JobFeedFailed event) {
        Schedulers.boundedElastic().schedule(() -> jobs.fail(event.jobId(),
                event.reason() + " (after " + event.progress().blocksSent() + " blocks)",
                event.progress()));
    }
}

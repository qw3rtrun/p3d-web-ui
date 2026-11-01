package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.files.GcodeFile;
import org.qw3rtrun.p3d.api.files.GcodeFileService;
import org.qw3rtrun.p3d.api.jobs.Job;
import org.qw3rtrun.p3d.api.jobs.JobFeedCompleted;
import org.qw3rtrun.p3d.api.jobs.JobFeedFailed;
import org.qw3rtrun.p3d.api.jobs.JobOutcome;
import org.qw3rtrun.p3d.api.jobs.JobProgressed;
import org.qw3rtrun.p3d.api.jobs.JobRequirements;
import org.qw3rtrun.p3d.api.jobs.JobService;
import org.qw3rtrun.p3d.api.jobs.JobState;
import org.qw3rtrun.p3d.api.jobs.JobStateChanged;
import org.qw3rtrun.p3d.api.model.ConnectionDetails;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.reactivestreams.Publisher;
import org.qw3rtrun.p3d.terminal.TerminalManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What ends a feed, and what the farm is told when it ends - the half of plan item C3 that decides
 * whether a job is {@code COMPLETED} or {@code FAILED}.
 *
 * <p><strong>Nothing here opens a socket or boots a context.</strong> The reactor is a real one -
 * its aggregate, its outbox and its replay sink are the production objects - with the one method
 * that would need a printer on the end, {@link PrinterReactor#feed}, standing in for the transport.
 * So an event put to the aggregate travels the route it travels on a live machine:
 * {@code PrinterState.onEvent} to the emitter, to the outbox, onto {@code updates()}, and into
 * whatever {@code JobFeeder} subscribed there.
 *
 * <p>Both schedulers are {@link Schedulers#immediate()}, which makes that route synchronous on the
 * test thread rather than something to await. The ordering this suite asserts is the order the code
 * decides in, not the order two threads happened to run in.
 */
class JobFeederTest {

    private final UUID jobId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final UUID machineId = UUID.randomUUID();

    /** Everything the feeder told the farm, in order. */
    private final List<Object> published = new ArrayList<>();

    /** Every block that reached the transport - what {@code feed} would have written. */
    private final List<String> onTheWire = new ArrayList<>();

    private final PrinterState printer = new PrinterState(machineId);

    private final PrinterReactor reactor = new PrinterReactor(
            new TerminalManager(new ConnectionDetails("localhost", 8099)), printer,
            event -> {
            },
            ConnectionPolicy.standard(), Schedulers.immediate(), Schedulers.immediate()) {

        /**
         * The transport, minus the printer. The returned {@code Mono} completes when the source
         * does, which is the one thing about {@code PublisherQueue.addPublisher} this class cares
         * about - a feed is "under way" for exactly as long as the file has more to give.
         */
        @Override
        public Mono<Void> feed(Publisher<String> blocks) {
            return Flux.from(blocks).doOnNext(onTheWire::add).then();
        }
    };

    private final PrinterReactorManager reactors =
            new PrinterReactorManager(new PrinterDirectory(), uuid -> printer, event -> {
            }) {
                @Override
                public PrinterReactor runtimeOf(UUID uuid) {
                    return machineId.equals(uuid) ? reactor : null;
                }
            };

    private final JobFeeder feeder = new JobFeeder(reactors, new OneFile(), new NoQueue(),
            published::add);

    /**
     * The file, arriving chunk by chunk under the test's control.
     *
     * <p>A sink rather than a {@code Flux.just(...)} so that nothing reaches the machine until the
     * feed is <em>running</em> and registered, and so that the file ends when a test says it does.
     * A finite publisher would be drained inside {@code start}, before the feed is in the map -
     * which no real file store does, since reading one is I/O.
     */
    private final Sinks.Many<ByteBuffer> file = Sinks.many().unicast().onBackpressureBuffer();

    /** The file's one chunk: three blocks, which is a whole print as far as these tests care. */
    private void theFileSays(String text) {
        file.tryEmitNext(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    private JobStateChanged started() {
        return new JobStateChanged(jobId, fileId, JobState.ASSIGNED, JobState.RUNNING, machineId,
                null, LocalDateTime.now());
    }

    private <T> List<T> publishedOf(Class<T> type) {
        return published.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * A job whose blocks the transport threw away fails, and says so - issue #60.
     *
     * <p><strong>The blocks were written, so everything that counts writing says the job went
     * perfectly.</strong> {@code JobFeeder} counts what the source emitted and the source emitted
     * all three; the send window framed all three; and then a {@code Resend:} for a line this host
     * had never numbered made the window restart its sequence and drop what it was holding. Before
     * this test the feed carried on from there and the job reported {@code COMPLETED} - a print
     * that silently skipped its {@code G28} and homed nowhere.
     *
     * <p>Three claims, and they are separate. The job is failed, not merely stopped; the failure
     * names what happened in words an operator can act on - {@code :app}'s integration test asserts
     * on the word "abandoned" and so does this; and no {@code JobFeedCompleted} is published
     * <em>ever</em>, including by the feed completing a moment later when its source is told to
     * stop.
     */
    @Test
    void blocksAbandonedByTheTransportFailTheJobRatherThanFinishingIt() {
        feeder.onJobStateChanged(started());
        theFileSays("G21\nG90\nG28\n");
        assertEquals(List.of("G21", "G90", "G28"), onTheWire,
                "the premise is a feed under way, and these are the blocks it had written");

        printer.onEvent(new BlocksAbandoned(15, 3));

        var failed = publishedOf(JobFeedFailed.class);
        assertEquals(1, failed.size(),
                "the transport abandoned blocks this host had framed, so the job has to fail;"
                        + " published " + published);
        assertEquals(jobId, failed.getFirst().jobId());
        assertEquals(machineId, failed.getFirst().machineId());
        assertTrue(failed.getFirst().reason().contains("abandoned"),
                "the reason is what an operator is shown: " + failed.getFirst().reason());
        assertTrue(failed.getFirst().reason().contains("15"),
                "the line the firmware asked for is the only measure of how far the two ends had"
                        + " drifted: " + failed.getFirst().reason());
        assertEquals(List.of(), publishedOf(JobFeedCompleted.class),
                "a job that lost blocks must never also report that it completed");
    }

    /**
     * The control: a feed nothing happened to finishes, and is reported as finished.
     *
     * <p>Without this, "fail the job" would be satisfied by failing every job. A file that reaches
     * its end is the ordinary case and it has to stay ordinary - the same argument as
     * {@code PrintJobIntegrationTest.aResendTheHostCanAnswerDoesNotFailTheJob}, one layer down.
     */
    @Test
    void aFeedThatReachesTheEndOfItsFileStillCompletes() {
        feeder.onJobStateChanged(started());
        theFileSays("G21\nG90\nG28\n");
        file.tryEmitComplete();

        assertEquals(List.of("G21", "G90", "G28"), onTheWire);
        assertEquals(List.of(), publishedOf(JobFeedFailed.class), "published " + published);
        assertEquals(1, publishedOf(JobFeedCompleted.class).size(), "published " + published);
        assertEquals(3L, publishedOf(JobFeedCompleted.class).getFirst().lines());
    }

    /**
     * A job that reaches the end of its file writes down every block it sent - issue #62.
     *
     * <p><strong>This is the {@code 0} case, exactly as it was measured live.</strong> The
     * checkpoint {@code jobs} persists is written from {@link JobProgressed}, and the only thing
     * that published one was a timer running about once a second. Nothing in this test runs that
     * timer - no {@code @Scheduled} and no context - which makes it the shortest job imaginable:
     * three blocks, over before the first tick. Before the fix it published no checkpoint at all
     * and a {@code COMPLETED} job recorded {@code blocksSent = 0}; on the live run that produced
     * the issue a twelve-block job recorded {@code 4}, which is the same bug with one tick in it.
     *
     * <p><strong>The number and its order are two separate claims.</strong> {@code blocksSent} on
     * the last reading has to equal what the feed actually sent - the same count
     * {@link JobFeedCompleted#lines()} carries, which is the assertable form of "a completed job's
     * progress is its file's block count". And the reading has to be published <em>before</em> the
     * completion, because the completion is what moves the job to {@code COMPLETED}: published
     * after, the exact number would land on a row an operator had already been shown.
     *
     * <p>Nothing here sleeps or awaits. The file is a sink this test completes by hand and both of
     * the reactor's schedulers are {@link Schedulers#immediate()}, so the whole end of the feed
     * happens on the test thread inside {@code tryEmitComplete}.
     */
    @Test
    void aJobThatFinishesWritesDownEveryBlockItSent() {
        feeder.onJobStateChanged(started());
        theFileSays("G21\nG90\nG28\n");
        file.tryEmitComplete();

        var readings = publishedOf(JobProgressed.class);
        assertFalse(readings.isEmpty(),
                "a job that ended before the progress timer ever ticked still has to write down"
                        + " where it got to; published " + published);
        var last = readings.getLast();
        assertEquals(jobId, last.jobId());
        assertEquals(machineId, last.machineId());
        assertEquals(3L, last.progress().blocksSent(),
                "the last reading written down is the exact count, not a stale timer reading");
        assertEquals(publishedOf(JobFeedCompleted.class).getFirst().lines(),
                last.progress().blocksSent(),
                "the checkpoint and the completion are the same number or one of them is lying");
        assertTrue(published.indexOf(last) < published.indexOf(publishedOf(JobFeedCompleted.class).getFirst()),
                "the exact reading goes before the completion that moves the job to COMPLETED,"
                        + " or the record is briefly wrong where an operator reads it; published "
                        + published);
    }

    /**
     * A job that ends because its blocks were abandoned writes down what it <em>sent</em> - and
     * that is not what the machine ran.
     *
     * <p><strong>Both endings publish one exact reading now, so what the reading means has to be
     * said out loud.</strong> {@code blocksSent} is, and remains, blocks this host handed to the
     * transport. On the completed path that is also what the machine was given. Here it is not:
     * the send window had framed all three, could not answer a {@code Resend:} for a line it had
     * never numbered, renumbered the session and dropped what it was holding. The three blocks
     * were sent; some number of them never ran.
     *
     * <p><strong>The honest number for what was lost does not exist at this layer</strong>, which
     * is why none is invented here. {@code BlocksAbandoned.blocks} is what the window happened to
     * be holding - a lower bound, as #68 documented it, because a block released early by a stray
     * {@code ok} has already left the window and is gone from the tally. Subtracting a lower bound
     * from {@code blocksSent} would produce a figure that is neither what was sent nor what was
     * accepted, and a resume would then seek to it. So the checkpoint keeps saying "sent", the job
     * is {@code FAILED} rather than resumable, and the reason says in words why its checkpoint
     * cannot be resumed from.
     *
     * <p>One reading, not two: {@code stop} removes the feed and checkpoints it, and the
     * {@code finished} that follows when the source runs out finds nothing to checkpoint.
     */
    @Test
    void aJobFailedByAbandonedBlocksWritesDownWhatItSentRatherThanWhatArrived() {
        feeder.onJobStateChanged(started());
        theFileSays("G21\nG90\nG28\n");

        printer.onEvent(new BlocksAbandoned(15, 3));

        var readings = publishedOf(JobProgressed.class);
        assertEquals(1, readings.size(),
                "one exact reading when the feed stops, and the finished() behind it must not add"
                        + " a second; published " + published);
        assertEquals(3L, readings.getFirst().progress().blocksSent(),
                "all three blocks were sent - the window dropping them afterwards does not unsend"
                        + " them, and nothing here can say how many the machine actually ran");
        var failed = publishedOf(JobFeedFailed.class).getFirst();
        assertTrue(published.indexOf(readings.getFirst()) < published.indexOf(failed),
                "the reading goes before the failure that moves the job, or the number lands on a"
                        + " row already showing FAILED; published " + published);
    }

    // ------------------------------------------------------------------ the collaborators

    /** The file store, holding the one file these tests print. */
    private final class OneFile implements GcodeFileService {

        @Override
        public Publisher<GcodeFile> store(String name, Publisher<ByteBuffer> content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<GcodeFile> fetch(UUID id) {
            return fileId.equals(id)
                    ? Optional.of(new GcodeFile(fileId, "abandoned.gcode", "hash", 12, 3,
                    LocalDateTime.now(), null))
                    : Optional.empty();
        }

        @Override
        public Stream<GcodeFile> fetchAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Publisher<ByteBuffer> read(UUID id) {
            return file.asFlux();
        }

        @Override
        public void delete(UUID id) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * The queue, which this class only ever asks about a resume - and these tests do not resume.
     *
     * <p>Every method throws rather than returning an empty answer, so a feeder that started
     * calling into the queue would fail here loudly instead of quietly being handed nothing.
     */
    private static final class NoQueue implements JobService {

        private UnsupportedOperationException notAsked() {
            return new UnsupportedOperationException("JobFeeder does not call this");
        }

        @Override
        public JobOutcome queue(UUID fileId, JobRequirements requires) {
            throw notAsked();
        }

        @Override
        public JobOutcome assign(UUID jobId, UUID machineId) {
            throw notAsked();
        }

        @Override
        public JobOutcome unassign(UUID jobId, String reason) {
            throw notAsked();
        }

        @Override
        public JobOutcome start(UUID jobId) {
            throw notAsked();
        }

        @Override
        public JobOutcome pause(UUID jobId) {
            throw notAsked();
        }

        @Override
        public JobOutcome resume(UUID jobId) {
            throw notAsked();
        }

        @Override
        public JobOutcome complete(UUID jobId) {
            throw notAsked();
        }

        @Override
        public JobOutcome fail(UUID jobId, String reason) {
            throw notAsked();
        }

        @Override
        public JobOutcome cancel(UUID jobId, String reason) {
            throw notAsked();
        }

        @Override
        public JobOutcome blocked(UUID jobId, String why) {
            throw notAsked();
        }

        @Override
        public List<Job> queued() {
            throw notAsked();
        }

        @Override
        public Optional<Job> fetch(UUID jobId) {
            throw notAsked();
        }

        @Override
        public List<Job> fetchAll() {
            throw notAsked();
        }

        @Override
        public List<Job> forMachine(UUID machineId) {
            throw notAsked();
        }
    }
}

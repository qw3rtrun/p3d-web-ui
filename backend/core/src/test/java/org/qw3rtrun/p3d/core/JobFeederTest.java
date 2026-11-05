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
 * whether a job is {@code COMPLETED} or {@code FAILED}. Nothing here opens a socket or boots a
 * context: the reactor is real (its aggregate, outbox and replay sink are production objects), with
 * only {@link PrinterReactor#feed} - the one method that needs a printer - standing in for the
 * transport, so an event travels the real route: {@code PrinterState.onEvent} to the emitter, to the
 * outbox, onto {@code updates()}, into {@code JobFeeder}. Both schedulers are
 * {@link Schedulers#immediate()}, so that route is synchronous on the test thread and the ordering
 * asserted is the order the code decides in, not the order two threads happened to run in.
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

        // the transport, minus the printer: the returned Mono completes when the source does, so a
        // feed is "under way" for exactly as long as the file has more to give.
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

    // a sink rather than Flux.just(...), so nothing reaches the machine until the feed is running
    // and registered, and the file ends only when a test says so.
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
     * A job whose blocks the transport threw away fails, and says so - issue #60. Every count that
     * tracks writing says the job went perfectly: the source emitted all three blocks, the send
     * window framed all three, and only then did a {@code Resend:} for an unnumbered line make the
     * window restart and drop what it held. Before this, the feed carried on and reported
     * {@code COMPLETED} - a print silently missing its {@code G28}, homed nowhere. Three separate
     * claims: the job fails rather than merely stopping; the reason names it in words
     * ("abandoned"); and no {@code JobFeedCompleted} is ever published, including by the feed
     * completing moments later when its source is told to stop.
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

    // the control: without this, "fail the job" would be satisfied by failing every job - a file
    // reaching its end has to stay ordinary, same argument as
    // PrintJobIntegrationTest.aResendTheHostCanAnswerDoesNotFailTheJob, one layer down.
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
     * A job that reaches the end of its file writes down every block it sent - issue #62, the
     * {@code 0} case exactly as measured live: no {@code @Scheduled} timer runs here, making this
     * the shortest job imaginable (three blocks, over before the first tick), and before the fix
     * this published no checkpoint at all, recording {@code blocksSent = 0} on a {@code COMPLETED}
     * job (a live twelve-block job recorded {@code 4} - the same bug, one tick in). Two separate
     * claims: {@code blocksSent} on the last reading must equal {@link JobFeedCompleted#lines()}
     * exactly, and that reading must be published before the completion that moves the job to
     * {@code COMPLETED}, or the exact number lands on a row already shown as finished.
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
     * A job that ends because its blocks were abandoned writes down what it sent - which is not what
     * the machine ran. {@code blocksSent} always means blocks this host handed to the transport; on
     * the completed path that is also what the machine got, but here the window framed all three,
     * could not answer an unnumbered {@code Resend:}, renumbered, and dropped what it held - sent,
     * but not provably run. No honest number for what was lost exists at this layer, so none is
     * invented: {@code BlocksAbandoned.blocks} is only a lower bound (#68), and subtracting it would
     * produce a figure that is neither sent nor accepted, which a resume would wrongly seek to. So
     * the checkpoint keeps saying "sent" and the job is {@code FAILED} rather than resumable. One
     * reading, not two: {@code stop} checkpoints and removes the feed, so the {@code finished} that
     * follows finds nothing left to checkpoint.
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

    // the queue, only ever asked about a resume - and these tests do not resume. Every method
    // throws, so a feeder that started calling into it would fail loudly instead of quietly getting
    // nothing.
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

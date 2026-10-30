package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.StepVerifierOptions;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.util.concurrent.CancellationException;

import static org.qw3rtrun.p3d.terminal.msg.Priority.BACKGROUND;
import static org.qw3rtrun.p3d.terminal.msg.Priority.EMERGENCY;
import static org.qw3rtrun.p3d.terminal.msg.Priority.REALTIME;
import static org.qw3rtrun.p3d.terminal.msg.Priority.REGULAR;

/**
 * A source that may not be interleaved, and what it costs.
 *
 * <p><strong>The failure this guards against writes a corrupt file and says nothing.</strong>
 * {@code M28 <name>} redirects every line that follows into a file on the card instead of executing
 * it, until {@code M29}. So any other command that reaches the wire in between is <em>written into
 * the G-code file</em> - the printer stores it happily, and the fault surfaces much later as a line
 * of nonsense mid-print. {@code GCodeCorpusProbe} already quarantines {@code M28} for this reason.
 *
 * <p>Priority alone does not prevent it, and that is the point of these tests. Queueing an upload
 * above everything else keeps it at the head of the heap, but <strong>a source that was already
 * given demand keeps that demand</strong> and emits on it whenever it likes - so a print streaming
 * at {@code BACKGROUND}, or an interactive command that was handed a unit a moment earlier, lands
 * in the middle of the transfer. {@link #anUploadWaitsForDemandAlreadyOutWithOtherSources} is that
 * case, and it is the one the mechanism exists for.
 *
 * @see PublisherQueue#addExclusivePublisher
 */
class PublisherQueueExclusivityTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final PublisherQueue<String, Priority> queue = new PublisherQueue<>();

    /** The shape of a transfer: an open, some content, and a close. */
    private static Flux<String> upload() {
        return Flux.just("M28 test.gco", "G28", "M118 P0 done", "M29");
    }

    /**
     * The case priority cannot cover: demand that was handed out before the upload existed.
     *
     * <p>The print is given two units and spends one. The upload is then queued above it, and the
     * heap duly puts the upload at the head - but the print is still holding a unit and may emit on
     * it at any moment, which on the wire means a {@code G1} written into the file.
     *
     * <p>So the upload does not start. It waits until no other source is holding demand, and only
     * then takes the connection. That is the whole guarantee: <em>while an exclusive source holds
     * the wire, nothing else has demand to emit on.</em>
     */
    @Test
    void anUploadWaitsForDemandAlreadyOutWithOtherSources() {
        var print = TestPublisher.<String>create();
        queue.addPublisher(BACKGROUND, print.flux());

        StepVerifier.create(queue, StepVerifierOptions.create().initialRequest(2))
                .then(() -> print.next("G1 X1"))
                .expectNext("G1 X1")

                // The upload outranks the print, so it is the head from here on.
                .then(() -> queue.addExclusivePublisher(REALTIME, upload()))
                .thenRequest(1)

                // ...and still it does not start, because the print holds a unit of demand and a
                // line emitted on it would be written into the file.
                .expectNoEvent(Duration.ofMillis(50))

                // The print spends its last unit. Nothing else is outstanding now.
                .then(() -> print.next("G1 X2"))
                .expectNext("G1 X2")

                // Only now does the transfer take the connection - on the demand that was being
                // withheld, without anyone having to ask again.
                .expectNext("M28 test.gco")
                .thenRequest(3)
                .expectNext("G28", "M118 P0 done", "M29")
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * Once it holds the wire, nothing queued behind it gets a single unit until it is finished.
     *
     * <p>Three interactive commands arrive mid-transfer, which is the ordinary case - the Monitor
     * screen posts each one as its own source. All three wait, and the demand that would have gone
     * to them goes to the transfer instead.
     */
    @Test
    void nothingQueuedBehindAnUploadReachesTheWireUntilItIsDone() {
        queue.addExclusivePublisher(REALTIME, upload());

        StepVerifier.create(queue, StepVerifierOptions.create().initialRequest(1))
                .expectNext("M28 test.gco")
                .then(() -> {
                    queue.addPublisher(REGULAR, Mono.just("M105"));
                    queue.addPublisher(REGULAR, Mono.just("M114"));
                    queue.addPublisher(BACKGROUND, Flux.just("G1 X1", "G1 X2"));
                })
                .thenRequest(3)
                .expectNext("G28", "M118 P0 done", "M29")

                // The transfer completed, so the wire is shared again and the three sources behind
                // it run in priority order, interactive first.
                .thenRequest(4)
                .expectNext("M105", "M114", "G1 X1", "G1 X2")
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * An emergency stop goes through, and the transfer is abandoned rather than resumed.
     *
     * <p><strong>Holding an {@code M112} until a transfer finishes is not an option</strong> - a
     * real file is tens of thousands of lines at a round trip each, so the stop would arrive
     * minutes late, which is not a stop. So a source of strictly higher priority preempts an
     * exclusive one.
     *
     * <p>And preempting it <strong>aborts</strong> it. Marlin's emergency parser reads
     * {@code M112} out of the serial bytes as they arrive, before the queue and before the SD
     * redirect - so the stop does fire, and the same bytes are also written into the file. The file
     * is corrupt the moment anything else reaches the wire, and a transfer that is already
     * half-written cannot be picked up where it left off. Telling the caller is the honest answer:
     * the {@code Mono} that {@code addExclusivePublisher} returned errors.
     */
    @Test
    void anEmergencyPreemptsAnUploadAndAbortsIt() {
        var transfer = queue.addExclusivePublisher(REALTIME, upload());

        StepVerifier.create(queue, StepVerifierOptions.create().initialRequest(1))
                .expectNext("M28 test.gco")
                .then(() -> queue.addPublisher(EMERGENCY, Mono.just("M112")))
                .thenRequest(1)
                .expectNext("M112")

                // Nothing more of the transfer, ever - not even the M29 that would close the file.
                .thenRequest(3)
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify(TIMEOUT);

        StepVerifier.create(transfer)
                .expectError(PublisherQueue.Preempted.class)
                .verify(TIMEOUT);
    }

    /**
     * Two transfers do not interleave with each other either; the second waits for the first.
     *
     * <p>It falls out of the same rule rather than needing one of its own: the second is not the
     * head, so it is given nothing.
     */
    @Test
    void aSecondExclusiveSourceWaitsForTheFirst() {
        queue.addExclusivePublisher(REALTIME, Flux.just("M28 one.gco", "G28", "M29"));
        queue.addExclusivePublisher(REALTIME, Flux.just("M28 two.gco", "G90", "M29"));

        StepVerifier.create(queue, StepVerifierOptions.create().initialRequest(3))
                .expectNext("M28 one.gco", "G28", "M29")
                .thenRequest(3)
                .expectNext("M28 two.gco", "G90", "M29")
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * The {@code Mono} completes normally when the transfer finishes, as for any other source.
     *
     * <p>Which is how a caller tells a finished upload from an aborted one, and the only way it
     * can: the queue carries no other signal back.
     */
    @Test
    void anUploadThatFinishesCompletesItsMono() {
        var transfer = queue.addExclusivePublisher(REALTIME, upload());

        StepVerifier.create(queue, StepVerifierOptions.create().initialRequest(4))
                .expectNext("M28 test.gco", "G28", "M118 P0 done", "M29")

                // Not thenCancel() straight after the last value: StepVerifier cancels from inside
                // onNext once its script is exhausted, which would stop the source before it had
                // signalled its own completion - and then this would be asserting that a cancelled
                // transfer reports nothing, which is the case below.
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify(TIMEOUT);

        StepVerifier.create(transfer).expectComplete().verify(TIMEOUT);
    }

    /**
     * A transfer cut off by the session ending says so, rather than going quiet.
     *
     * <p><strong>This was the gap.</strong> Ending the queue cancelled every source still in it and
     * told none of them, so the {@code Mono} each caller holds simply never signalled - a wait with
     * no end, for the life of the process. Nothing noticed while nobody subscribed to those; an
     * upload is the first thing whose entire report to the user <em>is</em> that signal, and a
     * connection dropping mid-transfer is exactly the case it cannot afford to lose.
     *
     * <p>The file left open on the card is the other half of why the caller has to hear about it:
     * there is no {@code M29} coming, so what is on the card is a truncated file that the next
     * listing will show at whatever length it reached.
     */
    @Test
    void aTransferCutOffByTheSessionEndingReportsIt() {
        var transfer = queue.addExclusivePublisher(REALTIME, upload());

        StepVerifier.create(queue, StepVerifierOptions.create().initialRequest(2))
                .expectNext("M28 test.gco", "G28")
                .thenCancel()
                .verify(TIMEOUT);

        StepVerifier.create(transfer)
                .expectError(CancellationException.class)
                .verify(TIMEOUT);
    }

    /**
     * An ordinary source is still preempted mid-flight, which is what makes a print interruptible.
     *
     * <p>Characterisation, and the contrast that says exclusivity is a departure rather than the
     * rule: the same arrangement without {@code addExclusivePublisher} lets the later, more urgent
     * source cut in. A print is <em>meant</em> to be interrupted by a temperature change; a
     * transfer is not.
     */
    @Test
    void anOrdinarySourceIsStillPreemptedMidFlight() {
        queue.addPublisher(BACKGROUND, Flux.just("G1 X1", "G1 X2", "G1 X3"));

        StepVerifier.create(queue, StepVerifierOptions.create().initialRequest(1))
                .expectNext("G1 X1")
                .then(() -> queue.addPublisher(REGULAR, Mono.just("M104 S200")))
                .thenRequest(1)
                .expectNext("M104 S200")
                .thenRequest(2)
                .expectNext("G1 X2", "G1 X3")
                .thenCancel()
                .verify(TIMEOUT);
    }
}

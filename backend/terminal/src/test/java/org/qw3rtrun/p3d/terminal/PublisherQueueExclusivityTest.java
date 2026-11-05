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
 * A source that may not be interleaved, and what it costs. {@code M28 <name>} redirects every line
 * that follows into a file on the card instead of executing it, until {@code M29} - so any other
 * command reaching the wire in between is written into the file and surfaces later as nonsense
 * mid-print ({@code GCodeCorpusProbe} already quarantines {@code M28} for this reason).
 *
 * <p>Priority alone does not prevent it: a source already given demand keeps it and may emit
 * whenever it likes, queueing order notwithstanding -
 * {@link #anUploadWaitsForDemandAlreadyOutWithOtherSources} is the case the mechanism exists for.
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

    /** The case priority cannot cover: the print is given two units and spends one, then the upload
     * is queued above it - but the print still holds a unit and could emit it into the file. So the
     * upload waits until no other source holds demand before taking the connection. */
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

    /** Once it holds the wire, nothing queued behind it gets a unit until it is finished - three
     * interactive commands (the ordinary case: the Monitor screen posts each as its own source) wait
     * while the demand that would have gone to them goes to the transfer instead. */
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

    /** An emergency stop goes through and the transfer is abandoned, not resumed: holding
     * {@code M112} for a transfer to finish would arrive minutes late, so a higher-priority source
     * preempts an exclusive one - and since Marlin's emergency parser reads {@code M112} off the
     * serial bytes ahead of the SD redirect anyway, the bytes are also written into the now-corrupt
     * file, so the caller is told via the {@code Mono} erroring rather than left to assume completion. */
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

    /** Two transfers do not interleave either - the second is simply not the head, so it is given
     * nothing, by the same rule rather than one of its own. */
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

    /** The {@code Mono} completes normally when the transfer finishes - the only way a caller can
     * tell a finished upload from an aborted one, since the queue carries no other signal back. */
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

    /** A transfer cut off by the session ending says so rather than going quiet - ending the queue
     * used to cancel every source and tell none of them, so the {@code Mono} simply never signalled.
     * No {@code M29} is coming, so the card is left with a truncated file. */
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

    /** The contrast that makes exclusivity a departure rather than the rule: without
     * {@code addExclusivePublisher} a more urgent source cuts in mid-flight, which is what makes a
     * print interruptible by a temperature change. */
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

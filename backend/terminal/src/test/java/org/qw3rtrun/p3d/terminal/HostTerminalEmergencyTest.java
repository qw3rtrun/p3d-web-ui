package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.TerminalTraffic;
import org.qw3rtrun.p3d.terminal.msg.Priority;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qw3rtrun.p3d.api.msg.TerminalTraffic.Direction.SENT;

/**
 * The emergency lane: {@link HostTerminal#emergencyStop()} against a send window that is shut and
 * is going to stay shut.
 *
 * <p><strong>What these tests can see.</strong> The starved state itself, built out of nothing but
 * the printer's own words. A bare {@code ok} credits a slot and reports no counts, so the window is
 * one command wide; the printer is then given a block and simply stops answering, which is how a
 * wedged firmware looks from here. {@code canProcess()} is
 * {@code min(subscriberDemand, 1 - 1, 4 - 1) = 0} from that moment on, no demand ever goes upstream
 * again, and nothing queued behind it can reach the wire by the ordinary path however much demand
 * the subscriber is holding. The subscriber in each test below is given its demand once, up front,
 * and never asks again - the real one is {@code reactor-netty}'s send, which has plenty of room in
 * its socket queue while the printer has none in its buffer, and the whole point of the lane is
 * that those are different numbers.
 *
 * <p><strong>What they cannot see</strong> is that the bytes leave the machine: that is
 * {@code EmergencyStopIntegrationTest} at the {@code :app} level, with a {@code MockPrinter} holding
 * its acknowledgements. That test proves the symptom through four layers and cannot say which layer
 * was at fault. These prove the mechanism at the layer that owns it, which is the division of labour
 * the suite already uses for the send window.
 *
 * @see HostTerminal#emergencyStop()
 */
class HostTerminalEmergencyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Unframed, and the newline {@code write0} adds - the whole assertion, since a framed stop
     * would read {@code N2 M112*<sum>} and could not be mistaken for this. */
    private static final String STOP = "M112\n";

    private final Sinks.Many<String> wire = Sinks.many().multicast().onBackpressureBuffer();

    private final AtomicReference<Publisher<byte[]>> written = new AtomicReference<>();

    private final List<TerminalTraffic> traffic = Collections.synchronizedList(new ArrayList<>());

    /** Every {@code request(n)} the send window made of its G-code source, which is where the
     * starvation shows: it stops growing. */
    private final List<Long> pulled = Collections.synchronizedList(new ArrayList<>());

    private HostTerminal terminal;

    /** A terminal whose outbound side the test owns, so what is asserted is the demand the window
     * produced and not merely the values it carried. */
    private Flux<String> outbound(Publisher<String> gcode) {
        terminal = new HostTerminal(wire::asFlux, out -> {
            written.set(out);
            return Mono.empty();
        });
        terminal.observeTraffic(traffic::add);
        terminal.start(gcode).subscribe();
        return Flux.from(written.get()).map(bytes -> new String(bytes, StandardCharsets.US_ASCII));
    }

    /** An endless job, recording what the window asked it for. */
    private Flux<String> job() {
        return Flux.range(1, 100).map(i -> "G1 X" + i).doOnRequest(pulled::add);
    }

    private void receive(String line) {
        wire.tryEmitNext(line + "\n");
    }

    private List<String> sent() {
        return traffic.stream()
                .filter(t -> t.direction() == SENT)
                .map(TerminalTraffic::raw)
                .toList();
    }

    /**
     * The defect, directly: the printer is given one block and stops acknowledging, so
     * {@code canProcess()} is zero and the source is never pulled from again - and the stop reaches
     * the wire anyway.
     *
     * <p>The tail is the other half of the claim. Once an {@code ok} finally arrives the session
     * carries on at {@code N2}, so the stop burnt no line number, took no window slot and spent no
     * acknowledgement credit: it was never in the window's accounting at all, which is the point of
     * writing it unframed rather than merely writing it early.
     */
    @Test
    void anEmergencyStopReachesTheWireWhileTheSendWindowIsShut() {
        var out = outbound(job());

        StepVerifier.create(out, 10)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .expectNext("N1 G1 X1*96\n")

                // One block out, nothing coming back: the window is shut, and the source has been
                // asked for one command and will never be asked again.
                .then(() -> assertEquals(List.of(1L), pulled,
                        "the M110's ok bought one command, and with no second ok there is no second pull"))

                .then(() -> assertTrue(terminal.emergencyStop()))
                .expectNext(STOP)

                // The ASCII session is undisturbed: the next block is N2, not N3.
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .thenCancel()
                .verify(TIMEOUT);

        assertEquals(List.of("N1 M110 N0*124", "N1 G1 X1*96", "M112",
                        "N2 G1 X2*96", "N3 G1 X3*96", "N4 G1 X4*96"),
                sent(),
                "a console sees the stop in its place, as the unframed line it is");
    }

    /**
     * Why the lane had to exist, rather than a priority. The same starved session, with an
     * {@code M112} queued through the real {@link PublisherQueue} at {@link Priority#EMERGENCY} -
     * the shape {@code PrinterReactor.emergencyStop()} was written against - and that source is
     * never even <em>subscribed</em>: the queue hands demand to its head only when the subscriber
     * asks for some, and the subscriber here is {@code CommandProcessor}, which asks for exactly
     * {@code canProcess()} and so asks for nothing.
     *
     * <p>{@code PublisherQueueExclusivityTest.anEmergencyPreemptsAnUploadAndAbortsIt} is not in
     * dispute and still passes: it drives the queue with a {@code StepVerifier} that requests demand
     * itself, which is the case where the stream is <em>flowing</em>. Priority decides who gets
     * demand; it cannot conjure demand that the printer's buffer is refusing to release.
     */
    @Test
    void anEmergencyQueuedAsAGCodeSourceNeverReachesAShutWindow() {
        var queue = new PublisherQueue<String, Priority>();
        queue.addPublisher(Priority.BACKGROUND, job());
        var out = outbound(queue);
        var queuedStopSubscribed = new AtomicBoolean();

        StepVerifier.create(out, 10)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .expectNext("N1 G1 X1*96\n")

                .then(() -> queue.addPublisher(Priority.EMERGENCY,
                        Mono.just("M112").doOnSubscribe(s -> queuedStopSubscribed.set(true))))

                // It is the head of the queue, outranking the job, and it goes nowhere: there is no
                // demand to give it, because the printer stopped talking.
                .then(() -> assertFalse(queuedStopSubscribed.get(),
                        "a shut window asks for nothing, so the EMERGENCY source is not even subscribed"))

                // The lane does not care, and the value that arrives is the unframed one.
                .then(() -> terminal.emergencyStop())
                .expectNext(STOP)
                .then(() -> assertFalse(queuedStopSubscribed.get()))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * The case that decides <em>unframed</em> rather than "framed but written early". A printer
     * reporting {@code B8} is credited eight buffer slots, so the binding term becomes
     * {@link org.qw3rtrun.p3d.g.code.core.session.GSendWindow}'s own capacity of four: four blocks
     * go out, the printer stops answering, and the window is left full.
     *
     * <p>{@code GSendWindow.send} <strong>refuses</strong> a payload in that state - it returns null
     * once {@code inFlight >= capacity}, deliberately, so that a full window burns no line number.
     * So a stop that asked for a number would be handed nothing to write at exactly the moment it is
     * needed. The lane asks for no number, and goes out.
     */
    @Test
    void anEmergencyStopGoesOutWithTheWindowFullOfUnacknowledgedBlocks() {
        var out = outbound(job());

        StepVerifier.create(out, 20)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok P15 B8"))
                .expectNext("N1 G1 X1*96\n", "N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")

                // Four outstanding against a capacity of four: nothing else can be framed at all.
                .then(() -> assertEquals(List.of(4L), pulled,
                        "the window is full, so there is no second pull however wide the buffer"))

                .then(() -> assertTrue(terminal.emergencyStop()))
                .expectNext(STOP)
                .thenCancel()
                .verify(TIMEOUT);
    }

    /** It does not queue behind the session's own {@code M110} either - an unnumbered line needs no
     * agreed sequence to take a place in, which is the only thing the {@code M110} is owed for. The
     * lane waits for one unit of the subscriber's demand and for nothing else, since rule 1.1 has no
     * exemption for an emergency. */
    @Test
    void anEmergencyStopDoesNotWaitBehindTheSessionsOwnM110() {
        var out = outbound(job());

        StepVerifier.create(out, 0)
                .then(() -> terminal.emergencyStop())
                .thenRequest(1)
                .expectNext(STOP)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .thenCancel()
                .verify(TIMEOUT);
    }

    /** A terminal that was never started says so instead of throwing: {@code PrinterReactor} calls
     * this from a control endpoint, and a stop that answers an exception is worse than one that
     * answers "there was no session". */
    @Test
    void anEmergencyStopWithNoSessionIsReportedAndNotThrown() {
        terminal = new HostTerminal(wire::asFlux, out -> Mono.empty());

        assertFalse(terminal.emergencyStop());
    }
}

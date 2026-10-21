package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.BusyNotice;
import org.qw3rtrun.p3d.g.marlin.event.OkTemperatureRs;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import org.qw3rtrun.p3d.g.protocol.SimpleBusyRs;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Protocol-level tests: a {@code Sinks} wire in place of a socket, no sleeps, no printer.
 *
 * <p>Two halves, because {@link HostTerminal} is two coupled pipelines. {@link #listen()} builds a
 * terminal whose outbound side is drained with unbounded demand and asserts what reaches
 * {@link HostTerminal#messageFlux()}; {@link #sendWindow} builds one whose outbound side is handed
 * to a {@link StepVerifier}, so what is asserted is the demand the window produced and not just the
 * values it carried.
 */
class HostTerminalTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Sinks.Many<String> wire = Sinks.many().multicast().onBackpressureBuffer();
    private final List<GRs<?>> published = new ArrayList<>();

    /** Every {@code request(n)} the send window made of the G-code source. */
    private final List<Long> pulled = new ArrayList<>();

    /** Whatever terminated the reply bus, which in a healthy session is nothing. */
    private final AtomicReference<Signal<GRs<?>>> busEnd = new AtomicReference<>();

    private HostTerminal terminal;

    private void listen() {
        terminal = new HostTerminal(wire::asFlux, out -> Flux.from(out).then());
        subscribeToBus();
        terminal.start(Flux.never()).subscribe();
    }

    private void subscribeToBus() {
        terminal.messageFlux().subscribe(published::add,
                t -> busEnd.set(Signal.error(t)),
                () -> busEnd.set(Signal.complete()));
    }

    /**
     * The terminal's outbound side, with an endless G-code source behind it.
     *
     * <p>The outbound function keeps the {@link org.reactivestreams.Processor} instead of draining
     * it, so the test subscribes it itself and owns the demand. {@code doOnRequest} records what the
     * window asked the source for, which is the send window itself and not an effect of it:
     * {@code canProcess()} is what decides that number.
     */
    private Publisher<String> sendWindow() {
        var captured = new AtomicReference<Publisher<String>>();
        terminal = new HostTerminal(wire::asFlux, out -> {
            captured.set(out);
            return Mono.empty();
        });
        subscribeToBus();
        Flux<String> gcode = Flux.range(1, 100).map(i -> "G1 X" + i).doOnRequest(pulled::add);
        terminal.start(gcode).subscribe();
        return captured.get();
    }

    /**
     * The same outbound side, with the printer answering from inside the emission of {@code line}.
     *
     * <p>That is the ordering the send window actually lives in - the acknowledgement of one
     * command arrives on the netty inbound thread while the producer thread is still writing the
     * next - and the one a single-threaded test otherwise never reaches.
     */
    private Publisher<String> sendWindowAcking(String line, String reply) {
        var captured = new AtomicReference<Publisher<String>>();
        terminal = new HostTerminal(wire::asFlux, out -> {
            captured.set(out);
            return Mono.empty();
        });
        subscribeToBus();
        Flux<String> gcode = Flux.range(1, 100).map(i -> "G1 X" + i)
                .doOnRequest(pulled::add)
                .doOnNext(g -> {
                    if (g.equals(line)) {
                        receive(reply);
                    }
                });
        terminal.start(gcode).subscribe();
        return captured.get();
    }

    private void receive(String line) {
        wire.tryEmitNext(line + "\n");
    }

    private List<String> publishedLines() {
        return published.stream().map(GRs::encode).toList();
    }

    /**
     * {@code M105}, {@code M109} and {@code M190} answer on the {@code ok}. Taking that line only
     * as a window credit drops the answer, which is what happened before: an {@code M105} asked on
     * demand was never seen by anything downstream.
     */
    @Test
    void anOkThatCarriesAReplyIsPublishedWholeLine() {
        listen();

        receive("ok T:45.26 /45.00 B:25.00 /0.00 @:3 B@:0");

        assertEquals(List.of("ok T:45.26 /45.00 B:25.00 /0.00 @:3 B@:0"), publishedLines());
        assertInstanceOf(OkTemperatureRs.class, published.getFirst());
    }

    @Test
    void aBareOkCarriesNothingToPublish() {
        listen();

        receive("ok");

        assertEquals(List.of(), published);
    }

    /**
     * An advanced {@code ok} says only what the send window was already told.
     *
     * <p>Marlin's field order, not the one the deleted parser expected. {@code ok_to_send()} echoes
     * the {@code N} of the line it is acknowledging <em>before</em> it appends {@code P} and
     * {@code B}, so {@code ok N100 P15 B3} is what a printer writes; the copy this module used to
     * carry read {@code N} last, matched nothing a printer sends, and so published every
     * acknowledgement of a numbered line as if it were a reply.
     */
    @Test
    void anAdvancedOkIsWindowCreditAndNotAReply() {
        listen();

        receive("ok N100 P15 B3");

        assertEquals(List.of(), published);
    }

    @Test
    void anUnprefixedLineIsStillPublished() {
        listen();

        receive("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0");

        assertEquals(List.of("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0"), publishedLines());
    }

    /**
     * A line nothing in {@code :gcode} claims does not reach the bus.
     *
     * <p>{@code MarlinRsDecoder} answers null for it and {@code GRs} has no value for "unread", so
     * it is logged on {@code #traffic} and dropped. The layer above did exactly this with it
     * before, one decode later.
     */
    @Test
    void aLineNoDecoderClaimsIsDropped() {
        listen();

        receive("Marlin bugfix-2.1.x");

        assertEquals(List.of(), published);
    }

    /**
     * Marlin's spelling of the busy notice reaches the send window.
     *
     * <p>`host_keepalive()` goes out with `SERIAL_ECHO_MSG`, so what a real printer writes while it
     * homes is {@code echo:busy: processing} and not the bare {@code busy:} the RepRap spec
     * describes. The parser this replaced claimed the bare form only, so on a Marlin the notice fell
     * through to an undifferentiated message, {@code onBusy} never fired, and a command sent before
     * the stall sat in {@code sentWithoutAck} forever - the window closed and never reopened,
     * because the acknowledgement that would have reopened it is the one the printer is too busy to
     * send.
     *
     * <p>What that costs is visible only as demand: with the notice understood, the window has room
     * again and pulls a second command; without it, {@code canProcess()} is
     * {@code min(1, 1 - 1 - 0) = 0} and the second {@code thenRequest} moves nothing.
     */
    @Test
    void marlinsEchoBusyFreesTheSendWindow() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("G1 X1\n")
                .then(() -> assertEquals(List.of(1L), pulled))
                .then(() -> receive("echo:busy: processing"))
                .thenRequest(1)
                .expectNext("G1 X2\n")
                .thenCancel()
                .verify(TIMEOUT);

        assertEquals(List.of(1L, 1L), pulled);
        assertInstanceOf(BusyNotice.class, published.getFirst());
    }

    /** The RepRap spelling does the same thing, through the same {@code BusyRs} interface. */
    @Test
    void theBareBusyNoticeFreesTheSendWindowToo() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("G1 X1\n")
                .then(() -> receive("busy: paused for user"))
                .thenRequest(1)
                .expectNext("G1 X2\n")
                .thenCancel()
                .verify(TIMEOUT);

        assertInstanceOf(SimpleBusyRs.class, published.getFirst());
    }

    /**
     * The window is the printer's room, not the subscriber's demand.
     *
     * <p>Ten asked for downstream, one pulled from the source: until a printer says otherwise it
     * gets one command and nothing more until it acknowledges. An advanced {@code ok} reporting
     * {@code B3} then widens it to three, and the next pull is for three - which only happens if
     * {@code ok N2 P15 B3} decodes, so this is also where the {@code N}-first field order is pinned
     * on the flow-control side.
     */
    @Test
    void theWindowIsTheBlockQueueAndNotTheSubscribersDemand() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(10)
                .expectNext("G1 X1\n")
                .then(() -> assertEquals(List.of(1L), pulled))
                .then(() -> receive("ok N2 P15 B3"))
                .expectNext("G1 X2\n", "G1 X3\n", "G1 X4\n")
                .then(() -> assertEquals(List.of(1L, 3L), pulled))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * More acknowledgements than sends must not buy room the printer does not have.
     *
     * <p>A {@code busy:} says nothing is in flight that will ever be acknowledged, so the in-flight
     * count goes to zero - and then the {@code ok} for the command sent just before the stall
     * arrives anyway. On a {@code volatile int} with a bare {@code --} that lands on {@code -1},
     * and {@code blockQueue - (-1)} is a window one command wider than the printer's buffer, for
     * the rest of the session. The counter clamps at zero instead, so what follows the stale
     * acknowledgement is one command and not two.
     *
     * <p>Deterministic, and it fails on the old code: the assertion is the demand the window placed
     * on the source, {@code [1, 1]} against the old {@code [1, 2]}.
     */
    @Test
    void anAcknowledgementWithNothingInFlightDoesNotWidenTheWindow() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(10)
                .expectNext("G1 X1\n")
                .then(() -> receive("busy: paused for user"))
                .then(() -> receive("ok"))
                .expectNext("G1 X2\n")
                .then(() -> assertEquals(List.of(1L, 1L), pulled))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * An {@code ok} that lands mid-emission is credit for a command already counted, not a free
     * slot on top of it.
     *
     * <p>The printer answers {@code ok N1 P15 B3} while the host is inside the emission of
     * {@code G1 X1} - before {@link HostTerminal.CommandProcessor#onNext} has counted that command
     * as in flight. The window it reports is three blocks, and three commands is what should go
     * out. The old code decremented an in-flight count of zero to {@code -1} and then pulled
     * {@code 3 - (-1) - 1 = 3} more on top of the one being written: four commands into a
     * three-block buffer, which Marlin answers by dropping the overflow without saying so.
     *
     * <p>This is also the only deterministic reading of the drain loop's deferred work: the
     * acknowledgement's own {@code processRequest} is re-entrant, so it leaves a mark and the pull
     * it asked for happens on the next turn of the loop that was already running - {@code [1, 2]}
     * rather than the old {@code [1, 3]}.
     */
    @Test
    void anAcknowledgementArrivingMidEmissionIsCreditAndNotExtraRoom() {
        var out = sendWindowAcking("G1 X1", "ok N1 P15 B3");

        StepVerifier.create(out, 0)
                .thenRequest(10)
                .expectNext("G1 X1\n", "G1 X2\n", "G1 X3\n")
                .then(() -> assertEquals(List.of(1L, 2L), pulled))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * A printer error is a reply, not the end of the conversation.
     *
     * <p>Verbatim from a corpus sweep against the simulator - the printer recovers on its own and
     * keeps answering:
     *
     * <pre>
     * &gt;&gt; N3 M105*1
     * &lt;&lt; Error:checksum mismatch, Last Line: 2
     * &lt;&lt; Resend: 3
     * &lt;&lt; ok
     * </pre>
     *
     * <p>This used to go to {@code tryEmitError}, which terminates the replay sink behind
     * {@link HostTerminal#messageFlux()} - so the first mistyped checksum of a print killed the
     * host's whole reply stream while the printer carried on, and the temperature report at the end
     * of this sequence reached nobody.
     */
    @Test
    void aPrinterErrorIsPublishedAsAValueAndTheBusKeepsRunning() {
        listen();

        receive("Error:checksum mismatch, Last Line: 2");
        receive("Resend: 3");
        receive("ok");
        receive("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0");

        assertEquals(List.of("Error:checksum mismatch, Last Line: 2",
                "T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0"), publishedLines());
        assertInstanceOf(ErrorRs.class, published.getFirst());
        assertNull(busEnd.get(), "the reply bus terminated");
    }

    /** The error's own words survive the trip, because the host and not the decoder judges them. */
    @Test
    void anErrorCarriesTheMachinesOwnWordsAndItsLineNumber() {
        listen();

        receive("Error:Line Number is not Last Line Number+1, Last Line: 7");

        var error = assertInstanceOf(ErrorRs.class, published.getFirst());
        assertEquals("Line Number is not Last Line Number+1, Last Line: 7", error.getMessage());
        assertEquals(7, error.getLastLine().intValue());
    }

    /**
     * A subscriber that arrives after an error gets a live flux, not an immediate {@code onError}.
     *
     * <p>The damage of terminating the sink was not only to the subscriber that was there - the
     * terminal state is permanent, so every later {@code messageFlux()} was dead on subscribe too,
     * which is what a reconnecting UI does.
     */
    @Test
    void aSubscriberArrivingAfterAnErrorStillGetsReplies() {
        listen();
        receive("Error:checksum mismatch, Last Line: 2");

        StepVerifier.create(terminal.messageFlux())
                .expectNextMatches(ErrorRs.class::isInstance)
                .then(() -> receive("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0"))
                .expectNextCount(1)
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * The inbound side going away is still terminal, and is the only thing that is.
     *
     * <p>Reserving {@code onError} for transport death is the other half of publishing printer
     * errors as values: a subscriber has to be able to tell "the printer complained" from "the
     * socket is gone", and after this change the signal is what says which.
     */
    @Test
    void theInboundFluxFailingIsStillTerminal() {
        listen();

        wire.tryEmitError(new IllegalStateException("socket closed"));

        assertInstanceOf(IllegalStateException.class, busEnd.get().getThrowable());
    }

    @Test
    void theInboundFluxCompletingIsStillTerminal() {
        listen();

        wire.tryEmitComplete();

        assertEquals(Signal.complete(), busEnd.get());
    }
}

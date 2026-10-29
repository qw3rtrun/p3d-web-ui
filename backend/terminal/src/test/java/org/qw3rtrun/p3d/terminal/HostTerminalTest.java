package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.qw3rtrun.p3d.g.protocol.BusyRs;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p><strong>Every send-window test opens with {@code N1 M110 N0*124} and an {@code ok}.</strong>
 * That is the session handshake of spec 7.2 and not test ceremony: a host numbers from {@code N1}
 * while a firmware that was not power-cycled remembers the line it last accepted, so without it the
 * first block of every reconnection is rejected and the session never recovers. The reset takes a
 * unit of demand and the single command slot an unreported printer is credited with; its {@code ok}
 * returns both, leaving the window in exactly the state these tests used to start in.
 */
class HostTerminalTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Sinks.Many<String> wire = Sinks.many().multicast().onBackpressureBuffer();
    private final List<Object> published = new ArrayList<>();

    /** Every {@code request(n)} the send window made of the G-code source. */
    private final List<Long> pulled = new ArrayList<>();

    /** Whatever terminated the reply bus, which in a healthy session is nothing. */
    private final AtomicReference<Signal<Object>> busEnd = new AtomicReference<>();

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

    /** The same outbound side, over a source that emits exactly {@code payloads} and then ends. */
    private Publisher<String> sendWindowOf(String... payloads) {
        var captured = new AtomicReference<Publisher<String>>();
        terminal = new HostTerminal(wire::asFlux, out -> {
            captured.set(out);
            return Mono.empty();
        });
        subscribeToBus();
        terminal.start(Flux.fromArray(payloads).doOnRequest(pulled::add)).subscribe();
        return captured.get();
    }

    private void receive(String line) {
        wire.tryEmitNext(line + "\n");
    }

    private TemperatureRs<?> firstTemperature() {
        return (TemperatureRs<?>) assertInstanceOf(TemperatureRs.class, published.getFirst());
    }

    private static HeaterReading reading(String current, String target, Integer power) {
        return new HeaterReading(new BigDecimal(current), new BigDecimal(target), power);
    }

    /**
     * Just the acknowledgements on the bus, which is what a progress counter reads.
     *
     * <p>Filtered rather than asserted against the whole list, because the replies that happen to
     * be on the bus beside them - an {@code Error:}, a temperature - are a different test's
     * business and change the moment either of those does.
     */
    private List<BlockAcknowledged> acknowledged() {
        return published.stream()
                .filter(BlockAcknowledged.class::isInstance)
                .map(BlockAcknowledged.class::cast)
                .toList();
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

        assertEquals(1, published.size());
        assertEquals(reading("45.26", "45.00", 3), firstTemperature().getHotend());
        assertEquals(reading("25.00", "0.00", 0), firstTemperature().getBed());
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

        assertEquals(reading("24.31", "0.00", 0), firstTemperature().getHotend());
        assertEquals(reading("23.87", "0.00", 0), firstTemperature().getBed());
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
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(1)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> assertEquals(List.of(1L), pulled))
                .then(() -> receive("echo:busy: processing"))
                .thenRequest(1)
                .expectNext("N2 G1 X2*96\n")
                .thenCancel()
                .verify(TIMEOUT);

        assertEquals(List.of(1L, 1L), pulled);
        assertEquals("processing", assertInstanceOf(BusyRs.class, published.getFirst()).getReason());
    }

    /** The RepRap spelling does the same thing, through the same {@code BusyRs} interface. */
    @Test
    void theBareBusyNoticeFreesTheSendWindowToo() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(1)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("busy: paused for user"))
                .thenRequest(1)
                .expectNext("N2 G1 X2*96\n")
                .thenCancel()
                .verify(TIMEOUT);

        assertEquals("paused for user", assertInstanceOf(BusyRs.class, published.getFirst()).getReason());
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
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> assertEquals(List.of(1L), pulled))
                .then(() -> receive("ok N2 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
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
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("busy: paused for user"))
                .then(() -> receive("ok"))
                .expectNext("N2 G1 X2*96\n")
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
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n", "N2 G1 X2*96\n", "N3 G1 X3*96\n")
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

        assertEquals(2, published.size());
        var mismatch = assertInstanceOf(ErrorRs.class, published.getFirst());
        assertEquals("checksum mismatch, Last Line: 2", mismatch.getMessage());
        assertEquals(2, mismatch.getLastLine().intValue());
        assertInstanceOf(TemperatureRs.class, published.get(1));
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

    /**
     * What leaves the host is a framed block, not the text that was handed in.
     *
     * <p>Spec 7 and 8 through {@code :gcode}'s {@code GSendWindow}: an {@code N} field, one space,
     * the payload verbatim, and the XOR checksum of everything before the {@code *}. The literals
     * are the spec's own arithmetic - {@code N1 G28} xors to 18 - and not what the encoder happens
     * to produce, so this fails if the framing changes under it.
     */
    @Test
    void whatLeavesTheHostIsNumberedAndChecksummed() {
        var out = sendWindowOf("G28", "M105");

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(1)
                .expectNext("N1 G28*18\n")
                .then(() -> receive("ok"))
                .thenRequest(1)
                .expectNext("N2 M105*37\n")
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * Spec 8.5 step 5, answered: the blocks from the requested number onwards go out again, byte
     * for byte, before anything newer.
     *
     * <p>{@code Resend: 3} while 2, 3 and 4 are outstanding replays 3 and 4 - 2 was accepted and is
     * dropped from the window - and numbering then carries on at 5 rather than rewinding to it,
     * because the replayed blocks keep the numbers they were sent with.
     *
     * <p>Before the send window this was a log line and nothing else: the host did not number what
     * it sent, so it had nothing to replay and the session stayed broken from the first bad
     * checksum onwards.
     */
    @Test
    void aResendReplaysTheHeldBlocksByteForByteAndThenCarriesOn() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .then(() -> receive("Resend: 3"))
                .expectNext("N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .expectNext("N5 G1 X5*96\n")
                .then(() -> assertEquals(List.of(1L, 3L, 1L), pulled))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * <strong>A resend does not pull the source again</strong>, which is plan item C4's third
     * done-when and the thing its progress count rests on.
     *
     * <p>A replay comes out of {@code GSendWindow} into {@code replaying} and is written from
     * there; {@code pending}, which is what {@code onNext} fills from the source, is never
     * revisited. So a counter driven by what the <em>source</em> emitted cannot double-count on a
     * resend, and the plan's decision 3 is wrong to say it would - its conclusion that the
     * acknowledgement is the trustworthy signal stands, its reason for distrusting the other count
     * does not.
     *
     * <p>Measured as demand, because demand is what a source is asked for: five blocks are pulled
     * for five blocks of G-code, across a resend that put two of them on the wire twice.
     */
    @Test
    void aResendReplaysWithoutPullingTheSourceAgain() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .then(() -> receive("Resend: 3"))
                .expectNext("N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .expectNext("N5 G1 X5*96\n")
                .then(() -> assertEquals(5, pulled.stream().mapToLong(Long::longValue).sum(),
                        "the source was asked for " + pulled + ", which must total the five blocks"
                                + " it produced and not the seven that went on the wire"))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * An exhausted source completes the stream - which it used not to do at all.
     *
     * <p>{@code CommandProcessor.onComplete} was a log line and a commented-out
     * {@code subscriber.onComplete()}, so a {@code Processor} swallowed a terminal signal and its
     * subscriber could never learn that the source had ended. Against a live terminal the source is
     * a {@code PublisherQueue} that never completes, which is why nothing noticed.
     */
    @Test
    void anExhaustedSourceCompletesTheStream() {
        var out = sendWindowOf("G28", "M105");

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G28*18\n")
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 M105*37\n")
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * And it does not arrive before the last block is on the wire.
     *
     * <p>The source here runs out while the window is still waiting for an acknowledgement, so its
     * final block is owed. Completing on {@code onComplete} directly would strand it:
     * {@code write0} refuses to write once the stream is terminated, so it would be dropped in
     * silence, which is the one failure this module exists to prevent.
     *
     * <p>The {@code pending} half of {@link HostTerminal.CommandProcessor#completeIfDrained} is a
     * guard rather than something this can reach - a payload is only pulled when there is
     * subscriber demand to write it, and the same drain writes it - so what is asserted is the
     * reachable half: nothing completes while the window is waiting.
     */
    @Test
    void completionWaitsForTheLastBlockToReachTheWire() {
        var out = sendWindowOf("G28", "M105");

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G28*18\n")
                .expectNoEvent(Duration.ofMillis(50))
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 M105*37\n")
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * A cancel is not a completion, which is Reactive Streams rule 2.4.
     *
     * <p>{@code cancel()} answered with {@code subscriber.onComplete()}, a terminal signal from a
     * cancel path. Beyond being wrong it was expensive: {@code StepVerifier} cancels from inside
     * its own {@code onNext} when an expectation does not match, the {@code onComplete} re-entered
     * its state machine, and a failing send-window test span at 100% CPU until the build was
     * killed rather than reporting the mismatch.
     */
    @Test
    void aCancelIsNotACompletion() {
        var out = sendWindow();
        var completions = new AtomicInteger();

        StepVerifier.create(Flux.from(out).doOnComplete(completions::incrementAndGet), 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .thenCancel()
                .verify(TIMEOUT);

        assertEquals(0, completions.get(), "a cancelled stream must not also be completed");
    }

    /**
     * The {@code ok} that closes a resend handshake is credit, and nothing more.
     *
     * <p>Marlin answers a rejected block with {@code Error:}, {@code Resend: n} and then
     * {@code ok}. That last line says the firmware is ready again; it acknowledges no block,
     * because the block it is about to be sent has not been accepted yet. Letting it reach
     * {@code GSendWindow.acknowledge()} would release block {@code n} - the very one the rewind
     * just made outstanding again - and the next {@code Resend:} for it would find nothing held.
     *
     * <p>Measured as demand, which is where it shows: the first {@code ok} after the replay moves
     * nothing, and the second one - a real acknowledgement of the replayed block 3 - frees the slot
     * that pulls block 6.
     */
    @Test
    void theOkThatClosesAResendAcknowledgesNoBlock() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .then(() -> receive("Error:checksum mismatch, Last Line: 2"))
                .then(() -> receive("Resend: 3"))
                .expectNext("N3 G1 X3*96\n", "N4 G1 X4*96\n", "N5 G1 X5*96\n")
                .then(() -> receive("ok"))
                .then(() -> assertEquals(List.of(new BlockAcknowledged(1, 0)), acknowledged(),
                        "it acknowledges no block, so it announces none"))
                .then(() -> assertEquals(List.of(1L, 3L, 1L), pulled))
                .then(() -> receive("ok"))
                .expectNext("N6 G1 X6*96\n")
                .then(() -> assertEquals(
                        List.of(new BlockAcknowledged(1, 0), new BlockAcknowledged(3, 2)), acknowledged(),
                        "the next one is real, and it is block 3 - the one that was replayed"))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * An acknowledged block is announced, with the number of the block the firmware accepted.
     *
     * <p><strong>This is the signal plan decision 3 said the transport did not expose.</strong>
     * Both {@code ok} forms were consumed by the send window on the stated grounds that they said
     * nothing a subscriber had not already learned from it - and no subscriber can see the send
     * window, so what they actually said was nothing to anybody. Two things needed it: progress
     * measured in lines the firmware accepted rather than lines the host handed over, and the
     * answer to whether this session can send at all.
     *
     * <p><strong>Counting what was sent is wrong in exactly the case that matters.</strong> After a
     * {@code Resend:} the transport replays blocks the layer above already counted, so a count of
     * what went out overstates and then has to move backwards. The number here is the window's, so
     * a replayed block is announced under the number it was first sent with - see
     * {@link #theOkThatClosesAResendAcknowledgesNoBlock}, where the second real acknowledgement is
     * block 3 and not block 6.
     *
     * <p>The second field is what is still outstanding after this one. Without it, acknowledgements
     * stopping is ambiguous - nothing was sent, or nothing is coming back - and that distinction is
     * the whole of what a dead outbound half looks like from above.
     */
    @Test
    void anAcknowledgedBlockIsAnnouncedWithItsLineNumber() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .then(() -> assertEquals(List.of(), acknowledged(),
                        "the session's own reset holds no block, so its ok releases none"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .then(() -> assertEquals(List.of(new BlockAcknowledged(1, 0)), acknowledged()))
                .then(() -> receive("ok"))
                .expectNext("N5 G1 X5*96\n")
                .then(() -> assertEquals(
                        List.of(new BlockAcknowledged(1, 0), new BlockAcknowledged(2, 2)), acknowledged(),
                        "two more are outstanding behind block 2"))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * The window is the second gate, and it binds when the printer's buffer is the wider of the two.
     *
     * <p>{@code B8} offers room for eight commands; the host holds four, because four is what it
     * can still replay. A send the window would refuse is a command pulled off the source with
     * nowhere to go, which is a silent drop - so the gate reserves the slot before the pull rather
     * than discovering it afterwards.
     */
    @Test
    void theSendWindowCapsWhatIsOutstandingEvenWhenThePrinterOffersMore() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(20)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N1 P15 B8"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n", "N5 G1 X5*96\n")
                .then(() -> assertEquals(List.of(1L, 4L), pulled))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * A {@code Resend:} for a block the host no longer holds is repaired with a counter reset.
     *
     * <p>Block 1 was acknowledged, so it is gone - a well-behaved firmware cannot ask for it, since
     * it is the {@code ok} for a block that releases it. A peer that asks anyway has drifted
     * further than a replay can repair, and a partial answer would put a gap on the wire on top of
     * the one already there.
     *
     * <p>So the session is restarted rather than patched: {@code M110} puts the firmware's counter
     * back to zero, numbering resumes at {@code N1}, and the blocks the window was holding are
     * abandoned because the numbers they carry no longer mean anything. This was a log line and
     * nothing else until a live run against the simulator showed how a session reaches this state
     * in the first place.
     */
    @Test
    void aResendForABlockNoLongerHeldIsRepairedWithACounterReset() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .then(() -> receive("Resend: 1"))
                .expectNext("N5 M110 N0*120\n")
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * A session opens by telling the firmware where the numbering starts.
     *
     * <p><strong>Spec 7.2: {@code M110 N0} is how a host starts a session</strong>, and an
     * {@code M110} line is exempt from the continuity check precisely so that it can be sent when
     * the sequence is not yet agreed.
     *
     * <p>Without it a reconnect is dead on arrival. The host's window starts at {@code N1} while
     * the firmware - still powered, never reset - remembers the last line of the previous session,
     * so the first block is rejected with {@code Error:Line Number is not Last Line Number+1} and a
     * {@code Resend:} for a block this host has never held. Verified against the simulator: only
     * the first session after a printer powers on had ever worked.
     */
    @Test
    void aSessionOpensByResettingTheFirmwaresLineCounter() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> assertEquals(List.of(), pulled,
                        "nothing may be asked of the source before the counter is agreed"))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * Nothing is written in front of the counter reset, and it costs a real buffer slot.
     *
     * <p>The {@code M110} is an {@code onNext} like any other: it takes a unit of the subscriber's
     * demand and occupies one of the printer's command slots until its {@code ok} comes back. A
     * printer that has reported nothing yet is credited with one block, so the first G-code block
     * waits for that acknowledgement - the same arithmetic every other block obeys, and the reason
     * the reset goes through the drain loop rather than straight at the socket.
     *
     * <p>Numbering then restarts at {@code N1}, which is what proves the reset moved this host's
     * window and not only the firmware's counter.
     */
    @Test
    void theCounterResetTakesAUnitOfDemandAndABufferSlot() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(10)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> assertEquals(List.of(), pulled, "the block queue is full until it is acked"))
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N1 G1 X1*96\n", "N2 G1 X2*96\n", "N3 G1 X3*96\n")
                .thenCancel()
                .verify(TIMEOUT);
    }
}

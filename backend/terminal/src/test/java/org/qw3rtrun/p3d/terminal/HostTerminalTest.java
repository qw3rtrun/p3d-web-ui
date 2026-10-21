package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.g.marlin.event.BusyNotice;
import org.qw3rtrun.p3d.g.marlin.event.OkTemperatureRs;
import org.qw3rtrun.p3d.g.protocol.GRs;
import org.qw3rtrun.p3d.g.protocol.SimpleBusyRs;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    private HostTerminal terminal;

    private void listen() {
        terminal = new HostTerminal(wire::asFlux, out -> Flux.from(out).then());
        terminal.messageFlux().subscribe(published::add);
        terminal.start(Flux.never()).subscribe();
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
        terminal.messageFlux().subscribe(published::add);
        Flux<String> gcode = Flux.range(1, 100).map(i -> "G1 X" + i).doOnRequest(pulled::add);
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
}

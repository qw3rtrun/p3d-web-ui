package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.BlockAcknowledged;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.qw3rtrun.p3d.g.marlin.event.CapabilityRs;
import org.qw3rtrun.p3d.g.marlin.event.FirmwareInfoRs;
import org.qw3rtrun.p3d.g.marlin.event.HeaterReading;
import org.qw3rtrun.p3d.g.marlin.event.SdFile;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
import org.qw3rtrun.p3d.g.marlin.event.TemperatureRs;
import org.qw3rtrun.p3d.g.protocol.BusyRs;
import org.qw3rtrun.p3d.g.protocol.ErrorRs;
import org.qw3rtrun.p3d.g.protocol.SimpleBusyRs;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** What MarlinSim answers {@code M115} with, verbatim - kept whole because the tests that use it
     * are about six replies and an {@code ok} arriving as one burst. */
    private static final String M115_FIRMWARE =
            "FIRMWARE_NAME:Marlin bugfix-2.1.x (Oct 29 2026 10:28:56) "
                    + "SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 "
                    + "MACHINE_TYPE:MarlinSim KINEMATICS:Cartesian EXTRUDER_COUNT:1 "
                    + "UUID:00000000-0000-0000-0000-000000000000";

    private final Sinks.Many<String> wire = Sinks.many().multicast().onBackpressureBuffer();

    /** Synchronised, because two of these tests publish from a thread of their own. */
    private final List<Object> published = Collections.synchronizedList(new ArrayList<>());

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

    /** The terminal's outbound side, with an endless G-code source behind it. The outbound function
     * keeps the {@link org.reactivestreams.Processor} instead of draining it, so the test owns the
     * demand itself; {@code doOnRequest} records what {@code canProcess()} asked the source for. */
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

    /** The same outbound side, with the printer answering from inside the emission of {@code line} -
     * the ordering the send window actually lives in (an ack arrives on the netty inbound thread
     * while the producer is still writing the next command), which a single-threaded test otherwise
     * never reaches. */
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

    /** Just the acknowledgements on the bus - filtered rather than asserted against the whole list,
     * since other replies beside them are a different test's business. */
    private List<BlockAcknowledged> acknowledged() {
        return published.stream()
                .filter(BlockAcknowledged.class::isInstance)
                .map(BlockAcknowledged.class::cast)
                .toList();
    }

    /** Just the abandonments, for the same reason {@link #acknowledged()} filters. */
    private List<BlocksAbandoned> abandoned() {
        return published.stream()
                .filter(BlocksAbandoned.class::isInstance)
                .map(BlocksAbandoned.class::cast)
                .toList();
    }

    /** {@code M105}/{@code M109}/{@code M190} answer on the {@code ok} itself; taking that line only
     * as window credit used to drop the answer entirely. */
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

    /** Marlin's {@code ok_to_send()} writes {@code N} before {@code P}/{@code B} - {@code ok N100 P15 B3}
     * - the opposite of what the deleted parser expected, which read {@code N} last, matched nothing
     * a printer sends, and so published every acknowledgement of a numbered line as a reply. */
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

    /** {@code MarlinRsDecoder} answers null and {@code GRs} has no "unread" value, so the line is
     * logged on {@code #traffic} and dropped. */
    @Test
    void aLineNoDecoderClaimsIsDropped() {
        listen();

        receive("Marlin bugfix-2.1.x");

        assertEquals(List.of(), published);
    }

    /** {@code M20}'s answer reaches the bus as one assembled listing, not four lines. Entries are
     * deliberately not claimable replies ({@code TEST.GCO 47} is too common a
     * {@code <word> <number>} shape), so before this a Storage tab waited forever. */
    @Test
    void anSdListingArrivesAsOneAssembledReply() {
        listen();

        receive("Begin file list");
        receive("TEST.GCO 47");
        receive("BENCHY.GCO 2847561");
        receive("End file list");

        assertEquals(1, published.size(), "four lines, one reply: " + published);
        var listing = assertInstanceOf(SdFileList.class, published.getFirst());
        assertEquals(List.of(new SdFile("TEST.GCO", 47), new SdFile("BENCHY.GCO", 2847561)),
                listing.getFiles());
    }

    /** An empty card answers with an empty list - a different fact from "no listing arrived", and
     * the only way a page can tell the two apart. */
    @Test
    void anEmptySdListingIsStillAnAnswer() {
        listen();

        receive("Begin file list");
        receive("End file list");

        assertEquals(1, published.size());
        assertEquals(List.of(), assertInstanceOf(SdFileList.class, published.getFirst()).getFiles());
    }

    /** A listing something interrupted produces no listing at all - the strict reading, since a
     * short list reported as the card's contents is indistinguishable from the real thing. The
     * interrupting reply is still published as itself. */
    @Test
    void aListingSomethingInterruptedProducesNothing() {
        listen();

        receive("Begin file list");
        receive("TEST.GCO 47");
        receive("T:24.31 /0.00 @:0");
        receive("BENCHY.GCO 2847561");
        receive("End file list");

        assertEquals(1, published.size(), "only the interrupting reply: " + published);
        assertInstanceOf(TemperatureRs.class, published.getFirst());
    }

    /** The markers are scaffolding and are consumed, like a {@code Resend:} - a subscriber told "a
     * list is starting" can do nothing with that. */
    @Test
    void theListingMarkersThemselvesDoNotReachTheBus() {
        listen();

        receive("Begin file list");
        assertEquals(List.of(), published);

        receive("End file list");
        assertEquals(1, published.size());
        assertInstanceOf(SdFileList.class, published.getFirst());
    }

    /** The reader is per-session, not per-request, so it must reset itself - a tab that lists,
     * uploads and lists again needs two complete answers, not the first one repeated. */
    @Test
    void asecondListingOnTheSameConnectionIsItsOwnAnswer() {
        listen();

        receive("Begin file list");
        receive("A.GCO 1");
        receive("End file list");

        receive("Begin file list");
        receive("B.GCO 2");
        receive("End file list");

        assertEquals(2, published.size());
        assertEquals(List.of(new SdFile("A.GCO", 1)),
                assertInstanceOf(SdFileList.class, published.get(0)).getFiles());
        assertEquals(List.of(new SdFile("B.GCO", 2)),
                assertInstanceOf(SdFileList.class, published.get(1)).getFiles());
    }

    /** Marlin's {@code host_keepalive()} writes {@code echo:busy: processing}, not the RepRap bare
     * {@code busy:}. The parser this replaced claimed the bare form only, so the notice fell through
     * unrecognised, {@code onBusy} never fired, and the window stayed closed forever - visible here
     * as demand: {@code canProcess()} would be {@code min(1, 1 - 1 - 0) = 0} and the second
     * {@code thenRequest} would move nothing. */
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

    /** Ten asked for downstream, one pulled from the source: until a printer says otherwise it gets
     * one command and nothing more. An advanced {@code ok} reporting {@code B3} widens it to three -
     * which only happens if {@code ok N1 P15 B3} decodes with {@code N} first, pinning the field
     * order here on the flow-control side too. */
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
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .then(() -> assertEquals(List.of(1L, 3L), pulled))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /** A numbered {@code ok} for a block this host never sent releases nothing - counted instead, it
     * would release the oldest block in the window and desynchronise every later {@code Resend:}'s
     * correspondence, silently. The credit (a slot freed) is still applied regardless. */
    @Test
    void anOkNamingABlockTheWindowNeverSentReleasesNothing() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N99 P15 B3"))
                // Credit applied, so the printer's three slots are offered and filled...
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                // ...and nothing was announced, because nothing was released.
                .then(() -> assertEquals(List.of(), acknowledged(),
                        "an ok for a block never sent must not release one"))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /** End to end: the window still holds the right bytes under the right numbers after a stray
     * acknowledgement, so the {@code Resend:} that follows replays exactly what was asked for. */
    @Test
    void aStrayAcknowledgementLeavesTheResendReplayingTheRightBlocks() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .then(() -> assertEquals(List.of(new BlockAcknowledged(1, 0)), acknowledged()))
                // A stray one lands in the middle, naming a block long since released. It is still
                // a reply, so the credit frees a slot and a fifth block goes out - that half is not
                // in dispute and is asserted so the test is not surprised by it.
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N5 G1 X5*96\n")
                .then(() -> assertEquals(List.of(new BlockAcknowledged(1, 0)), acknowledged(),
                        "the stray ok released no second block"))
                // And the window is undisturbed: everything from 2 onwards replays, in order,
                // byte for byte, under the numbers it was first sent with.
                .then(() -> receive("Resend: 2"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n", "N5 G1 X5*96\n")
                .thenCancel()
                .verify(TIMEOUT);
    }

    /** Spec 8.5's in-order acknowledgement: an {@code ok N3} while 2 and 3 are outstanding says both
     * were taken and 2's own acknowledgement was lost in transit, not that 2 is still pending. One
     * {@code BlockAcknowledged} is published, under the wire's number - a high-water mark, not a
     * tally. */
    @Test
    void anOkNamingALaterBlockReleasesTheOnesInFrontOfIt() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N1 P15 B3"))
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                // The ok for 2 never arrives; the one for 3 does.
                .then(() -> receive("ok N3 P15 B3"))
                .then(() -> assertEquals(
                        List.of(new BlockAcknowledged(1, 0), new BlockAcknowledged(3, 1)),
                        acknowledged(),
                        "N2 and N3 are both released, and N4 is what is left in flight"))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /** A {@code busy:} zeroes in-flight, and then the {@code ok} for the command sent just before
     * the stall arrives anyway. On the old {@code volatile int --} that lands on {@code -1}, widening
     * the window by one command for the rest of the session; the clamp at zero keeps it at one
     * command, not two - {@code [1, 1]} pulled against the old code's {@code [1, 2]}. */
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

    /** {@code ok N1 P15 B3} arrives while the host is still inside the emission of {@code G1 X1},
     * before {@link HostTerminal.CommandProcessor#onNext} counted it as in flight. The old code
     * decremented an in-flight count of zero to {@code -1} and pulled {@code 3 - (-1) - 1 = 3} more
     * on top of the one being written - four commands into a three-block buffer, which Marlin drops
     * silently. Pulled here: {@code [1, 2]} against the old {@code [1, 3]}. */
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
     * A printer error is a reply, not the end of the conversation - verbatim from a corpus sweep
     * against the simulator, which recovers on its own and keeps answering:
     *
     * <pre>
     * &gt;&gt; N3 M105*1
     * &lt;&lt; Error:checksum mismatch, Last Line: 2
     * &lt;&lt; Resend: 3
     * &lt;&lt; ok
     * </pre>
     *
     * <p>Routing it to {@code tryEmitError} instead used to terminate {@link HostTerminal#messageFlux()}
     * on the first mistyped checksum of a print, so the temperature report at the end of this
     * sequence reached nobody.
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

    /** A subscriber arriving after an error gets a live flux, not an immediate {@code onError} -
     * terminating the sink used to leave every later {@code messageFlux()} dead too, which is what a
     * reconnecting UI does. */
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

    /** Reserving {@code onError} for transport death is the other half of publishing printer errors
     * as values: a subscriber must be able to tell "the printer complained" from "the socket is
     * gone". */
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

    // ------------------------------------------------------- a reply reaches the bus, or it is a bug

    /**
     * The opening exchange, with the bus subscribed where a real caller can first subscribe it -
     * every other test in this file subscribes before {@code start()}, which no caller can do, since
     * {@code start()} is what builds the two halves and begins reading the socket.
     * {@code PrinterReactor} lives in exactly that window: it calls {@code terminal.start(...)} and
     * {@code Mono.zip} subscribes the reply flux afterwards.
     *
     * <p>The shape is {@code M115}'s real MarlinSim answer - six replies then the {@code ok} that
     * releases the next block, which goes down a different road entirely ({@code onOk} never touches
     * the sink) - so a session could look healthy while the firmware report never reached anybody.
     * With a one-element history, a subscriber here got only the <em>last</em> capability.
     */
    @Test
    void everyReplyOfABurstReachesASubscriberThatAttachedAfterTheSocketWasRead() {
        terminal = new HostTerminal(wire::asFlux, out -> Flux.from(out).then());
        terminal.start(Flux.never()).subscribe();

        receive(M115_FIRMWARE);
        receive("Cap:AUTOREPORT_TEMP:1");
        receive("Cap:EEPROM:1");
        receive("Cap:SDCARD:1");
        receive("Cap:SD_WRITE:1");
        receive("Cap:EMERGENCY_PARSER:0");
        receive("ok P15 B3");

        StepVerifier.create(terminal.messageFlux())
                .expectNextMatches(FirmwareInfoRs.class::isInstance)
                .expectNext(new CapabilityRs("AUTOREPORT_TEMP", true))
                .expectNext(new CapabilityRs("EEPROM", true))
                .expectNext(new CapabilityRs("SDCARD", true))
                .expectNext(new CapabilityRs("SD_WRITE", true))
                .expectNext(new CapabilityRs("EMERGENCY_PARSER", false))
                .then(() -> receive("T:24.31 /0.00 B:23.87 /0.00 @:0 B@:0"))
                .expectNextMatches(TemperatureRs.class::isInstance)
                .thenCancel()
                .verify(TIMEOUT);
    }

    /**
     * A reply published while another thread is publishing is not refused, it waits. The decoded
     * reply arrives on the netty inbound loop; a {@link BlockAcknowledged} is released inside the
     * send window's drain loop, on whichever thread got there first - a real collision a safe
     * {@code Sinks} answers with {@code FAIL_NON_SERIALIZED}, which this module used to log and drop.
     * Measured: two threads into a safe sink, <strong>26,640 of 40,000</strong> emissions refused.
     *
     * <p>This test makes the collision happen rather than hoping for it: the other thread is held
     * <em>inside</em> the sink by a subscriber that will not return, and the burst is delivered while
     * it is in there - the real second emitter is the drain loop, and {@code onBusy} stands in for it
     * as the one path a test can drive from a thread of its own.
     */
    @Test
    void aReplyIsNotLostBecauseAnotherThreadWasPublishingAtTheSameMoment() throws InterruptedException {
        var inTheSink = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        terminal = new HostTerminal(wire::asFlux, out -> Flux.from(out).then());
        subscribeHolding(inTheSink, release);
        terminal.start(Flux.never()).subscribe();

        var other = Thread.ofPlatform().name("the-other-emitter")
                .start(() -> terminal.onBusy(new SimpleBusyRs("processing")));
        assertTrue(inTheSink.await(5, TimeUnit.SECONDS), "the other thread never reached the sink");

        receive(M115_FIRMWARE);
        receive("Cap:AUTOREPORT_TEMP:1");
        receive("Cap:EEPROM:1");
        receive("Cap:SDCARD:1");
        receive("Cap:SD_WRITE:1");
        receive("Cap:EMERGENCY_PARSER:0");
        receive("ok P15 B3");

        release.countDown();
        other.join();

        assertInstanceOf(BusyRs.class, published.getFirst());
        assertInstanceOf(FirmwareInfoRs.class, published.get(1));
        assertEquals(List.of(new CapabilityRs("AUTOREPORT_TEMP", true),
                        new CapabilityRs("EEPROM", true),
                        new CapabilityRs("SDCARD", true),
                        new CapabilityRs("SD_WRITE", true),
                        new CapabilityRs("EMERGENCY_PARSER", false)),
                published.subList(2, published.size()));
    }

    /** The socket closing is the last thing on the bus, not the first to jump the queue - a terminal
     * signal issued straight at the sink could be refused by a collision (leaving a bus that never
     * ends) or terminate it ahead of a reply still waiting to go out. */
    @Test
    void whatWasParkedIsPublishedBeforeTheBusEnds() throws InterruptedException {
        var inTheSink = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        terminal = new HostTerminal(wire::asFlux, out -> Flux.from(out).then());
        subscribeHolding(inTheSink, release);
        terminal.start(Flux.never()).subscribe();

        var other = Thread.ofPlatform().name("the-other-emitter")
                .start(() -> terminal.onBusy(new SimpleBusyRs("processing")));
        assertTrue(inTheSink.await(5, TimeUnit.SECONDS), "the other thread never reached the sink");

        receive("Error:checksum mismatch, Last Line: 2");
        wire.tryEmitComplete();

        release.countDown();
        other.join();

        assertInstanceOf(BusyRs.class, published.getFirst());
        assertInstanceOf(ErrorRs.class, published.get(1), "the parked error: " + published);
        assertEquals(Signal.complete(), busEnd.get(), "the bus never ended");
    }

    /** Subscribes with a collector that holds the first value, and so the sink - blocking inside
     * {@code onNext} is exactly what a pipeline must never do, and exactly what schedules the
     * collision deterministically rather than hoping the scheduler reproduces it. */
    private void subscribeHolding(CountDownLatch inTheSink, CountDownLatch release) {
        terminal.messageFlux().subscribe(value -> {
            published.add(value);
            if (inTheSink.getCount() > 0) {
                inTheSink.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, t -> busEnd.set(Signal.error(t)), () -> busEnd.set(Signal.complete()));
    }

    /** Spec 7 and 8 through {@code :gcode}'s {@code GSendWindow}: {@code N}, the payload verbatim,
     * and the XOR checksum of everything before {@code *}. The literals are the spec's own
     * arithmetic ({@code N1 G28} xors to 18), not whatever the encoder happens to produce. */
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

    /** Spec 8.5 step 5, answered: {@code Resend: 3} while 2, 3 and 4 are outstanding replays 3 and 4
     * (2 was accepted and dropped from the window), and numbering carries on at 5 - replayed blocks
     * keep the numbers they were first sent with. */
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

    /** A resend does not pull the source again: a replay comes out of {@code GSendWindow} into
     * {@code replaying} and is written from there, never through {@code pending}/{@code onNext}. So
     * a counter driven by what the source emitted cannot double-count on a resend - five blocks
     * pulled for five blocks of G-code, across a resend that put two of them on the wire twice. */
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

    /** An exhausted source completes the stream, which it used not to do at all -
     * {@code CommandProcessor.onComplete} swallowed the signal, unnoticed because a live
     * {@code PublisherQueue} source never completes. */
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

    /** The source runs out while the window still owes its final block - completing directly on
     * {@code onComplete} would strand it, since {@code write0} refuses to write once terminated. */
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

    /** Rule 2.4: a cancel is not a completion. {@code cancel()} used to answer with
     * {@code subscriber.onComplete()}, which re-entered {@code StepVerifier}'s state machine on a
     * mismatch and spun a failing test at 100% CPU instead of reporting it. */
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

    /** The {@code ok} that closes a {@code Resend:} handshake acknowledges no block - letting it
     * reach {@code GSendWindow.acknowledge()} would release the very block the rewind just made
     * outstanding again. Shows as demand: the first {@code ok} after the replay moves nothing, the
     * second (a real ack of replayed block 3) frees the slot that pulls block 6. */
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
     * An acknowledged block is announced, with the number the firmware accepted - both {@code ok}
     * forms used to be pure window credit, saying nothing to a subscriber who cannot see the window.
     * Counting what was <em>sent</em> is wrong after a {@code Resend:}, since a replay would
     * double-count - see {@link #theOkThatClosesAResendAcknowledgesNoBlock}, where the second real
     * acknowledgement is block 3, not 6. The second field (still outstanding) disambiguates
     * acknowledgements stopping because nothing was sent from nothing coming back.
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

    /** The window is the second gate and binds when the printer's buffer is wider: {@code B8} offers
     * eight, the host still holds only four, since four is what it can replay. */
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

    /** Block 1 was already acknowledged and is gone, so a {@code Resend: 1} is a drift a replay
     * cannot repair - the session is restarted instead: {@code M110} zeroes the firmware's counter,
     * numbering resumes at {@code N1}, and the blocks still held are abandoned. */
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
     * The blocks thrown away are announced, because nothing above can otherwise see that they went -
     * measured, not imagined. Against MarlinSim (bugfix-2.1.x) on 2026-11-01, with {@code last_N} at
     * 14, a block numbered as a fresh session numbers its first is answered:
     *
     * <pre>
     *   -&gt; N1 G21*27
     *      Error:Line Number is not Last Line Number+1, Last Line: 14
     *      Resend: 15
     *      ok
     * </pre>
     *
     * <p>The {@code Resend:} names {@code last_N + 1}, a number this host never framed, so the
     * window cannot answer it and everything held is dropped - until this record existed that was a
     * WARN and nothing else, so a print whose remaining blocks were just thrown away still reported
     * {@code COMPLETED}. {@code Resend: 99} stands in for the real {@code last_N + 1}: what makes a
     * resend unanswerable is that the number is not in the window, which holds 2, 3 and 4 here.
     */
    @Test
    void aResendForABlockNoLongerHeldAnnouncesTheBlocksItAbandoned() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(10)
                .expectNext("N1 G1 X1*96\n")
                .then(() -> receive("ok N1 P15 B3"))
                // 1 is acknowledged and gone; 2, 3 and 4 are what the window is still holding.
                .expectNext("N2 G1 X2*96\n", "N3 G1 X3*96\n", "N4 G1 X4*96\n")
                .then(() -> assertEquals(List.of(new BlockAcknowledged(1, 0)), acknowledged(),
                        "three blocks outstanding behind the one acknowledgement"))
                .then(() -> receive("Resend: 99"))
                .expectNext("N5 M110 N0*120\n")
                .then(() -> assertEquals(List.of(new BlocksAbandoned(99, 3)), abandoned(),
                        "the number the firmware asked for, and the three blocks that went"))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /** The control for {@link #aResendForABlockNoLongerHeldAnnouncesTheBlocksItAbandoned}: without
     * it, that test would pass just as happily against a transport that fires on every resend,
     * reporting a lost job on every mistyped checksum. Here the replay happens and the bus stays
     * silent. */
    @Test
    void aResendTheWindowCanAnswerAbandonsNothing() {
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
                .then(() -> assertEquals(List.of(), abandoned(),
                        "a resend that was answered lost nothing, so there is nothing to announce"))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /** A bare {@code ok} releases the one block outstanding, so the window is empty when a
     * {@code Resend:} for that very block arrives - {@code resynchronise} runs in full, but nothing
     * was lost. Without the {@code > 0} gate this would announce zero blocks, and the normal
     * three-{@code Resend:} pile-up would turn one lost print into three announcements of it. */
    @Test
    void aResynchronisationThatDroppedNothingAnnouncesNothing() {
        var out = sendWindow();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(1)
                .expectNext("N1 G1 X1*96\n")
                // The bare ok releases block 1, so the window holds nothing at all.
                .then(() -> receive("ok"))
                .then(() -> assertEquals(List.of(new BlockAcknowledged(1, 0)), acknowledged(),
                        "the window is empty, which is the state this test is about"))
                // And now the firmware asks for the block that was just released.
                .then(() -> receive("Resend: 1"))
                .thenRequest(1)
                .expectNext("N2 M110 N0*127\n")
                .then(() -> assertEquals(List.of(), abandoned(),
                        "the session resynchronised, but no block was abandoned by it"))
                .thenCancel()
                .verify(TIMEOUT);
    }

    /** Spec 7.2: {@code M110 N0} is how a host starts a session, exempt from the continuity check so
     * it can be sent before the sequence is agreed. Without it a reconnect is dead on arrival - the
     * firmware remembers the previous session's last line and rejects the first block; verified
     * against the simulator, only the first session after a power-on had ever worked. */
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

    /** The {@code M110} is an {@code onNext} like any other - it costs a unit of demand and a buffer
     * slot until its {@code ok} returns, so the first G-code block waits. Numbering restarting at
     * {@code N1} afterwards proves the reset moved this host's window, not just the firmware's. */
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

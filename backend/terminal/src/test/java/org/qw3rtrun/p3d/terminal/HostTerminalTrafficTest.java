package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.TerminalTraffic;
import org.qw3rtrun.p3d.g.marlin.event.SdFile;
import org.qw3rtrun.p3d.g.marlin.event.SdFileList;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qw3rtrun.p3d.api.msg.TerminalTraffic.Direction.RECEIVED;
import static org.qw3rtrun.p3d.api.msg.TerminalTraffic.Direction.SENT;

/**
 * The traffic observer: every line that crossed the wire, in both directions, with the decode as an
 * annotation rather than as the value.
 *
 * <p>The pair of assertions each test here makes is the point. A line reported as traffic must
 * <em>not</em> change what reaches {@link HostTerminal#messageFlux()}, because the two streams answer
 * different questions - the bus carries what the machine meant and drops the rest, the observer
 * carries what it said. Measured against MarlinSim (Marlin bugfix-2.1.x) on 2026-11-18 by
 * {@link TerminalConsoleProbe}: of 106 received lines, 10 reach no subscriber at all (the whole boot
 * banner) and 12 more are withheld as flow control (every {@code ok P15 B3}), so a console fed from
 * the bus alone would be blank where the machine spoke.
 *
 * <p>No test here needs two threads. Every report is parked in {@code HostTerminal}'s one outbox and
 * published by {@code drainBus()}, whose javadoc already argues that only the thread inside the loop
 * touches what it publishes - the same argument that covers a {@code BlockAcknowledged} released on
 * the producer thread beside a reply decoded on the netty inbound loop. A traffic item is a third
 * emitter through that same door and adds no concurrency of its own.
 */
class HostTerminalTrafficTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Sinks.Many<String> wire = Sinks.many().multicast().onBackpressureBuffer();

    /** Synchronised for the same reason {@link HostTerminalTest}'s lists are: the drain loop runs on
     * whichever thread got there first. */
    private final List<TerminalTraffic> traffic = Collections.synchronizedList(new ArrayList<>());

    private final List<Object> published = Collections.synchronizedList(new ArrayList<>());

    private final AtomicReference<Publisher<byte[]>> written = new AtomicReference<>();

    private HostTerminal terminal;

    /** A terminal whose outbound side is drained unbounded - for the received direction, where what
     * goes out does not matter. */
    private void listen() {
        terminal = new HostTerminal(wire::asFlux, out -> Flux.from(out).then());
        observe();
        terminal.start(Flux.never()).subscribe();
    }

    /** A terminal whose outbound side the test owns, so a {@code SENT} report can be lined up against
     * the demand that produced it. */
    private Flux<String> sendWindowOf(String... payloads) {
        terminal = new HostTerminal(wire::asFlux, out -> {
            written.set(out);
            return Mono.empty();
        });
        observe();
        terminal.start(Flux.fromArray(payloads)).subscribe();
        return Flux.from(written.get()).map(bytes -> new String(bytes, StandardCharsets.US_ASCII));
    }

    /** Both seams, attached where {@code PrinterReactor} attaches them: on the aggregate's thread,
     * before {@code start()} is subscribed. */
    private void observe() {
        terminal.observeTraffic(traffic::add);
        terminal.messageFlux().subscribe(published::add);
    }

    private void receive(String line) {
        wire.tryEmitNext(line + "\n");
    }

    private List<String> raw() {
        return traffic.stream().map(TerminalTraffic::raw).toList();
    }

    /** The inbound half only. Filtered rather than asserted against the whole list, because a
     * terminal whose outbound side is drained unbounded has already written its opening
     * {@code M110 N0} - which is {@link #theSessionsOpeningCounterResetIsTheFirstLineReported}'s
     * business and not every test's. */
    private List<TerminalTraffic> received() {
        return traffic.stream().filter(line -> line.direction() == RECEIVED).toList();
    }

    private TerminalTraffic onlyReceived() {
        var lines = received();
        assertEquals(1, lines.size(), "exactly one line came back: " + traffic);
        return lines.getFirst();
    }

    private TerminalTraffic only() {
        assertEquals(1, traffic.size(), "exactly one line crossed the wire: " + traffic);
        return traffic.getFirst();
    }

    // --------------------------------------------------------------- what the machine said

    /**
     * The simulator's whole boot banner is lines like this one - 10 of the 106 the probe censused,
     * every one of them dropped by {@code ReplyFlux.decode} to the {@code #traffic} logger with
     * nothing reaching a subscriber. The report has to precede that drop, which is the one of the
     * three report sites easiest to get subtly wrong: {@code decode} has three {@code return}s.
     */
    @Test
    void anUnclaimedLineIsReportedWithNothingToDecodeItAs() {
        listen();

        receive("Sim Startup!");

        var line = onlyReceived();
        assertEquals(RECEIVED, line.direction());
        assertEquals("Sim Startup!", line.raw());
        assertNull(line.decodedAs(), "no decoder claims the banner, and the report must say so");
        assertEquals(List.of(), published,
                "the bus must still drop it - this is not a second copy of messageFlux()");
        assertTrue(line.at() > 0, "a replayed console line has to be datable");
    }

    /**
     * An acknowledgement is flow control to the transport and the <em>answer</em> to a console: the
     * operator typed something, and this is what came back. 12 of the probe's 106 lines were these.
     * Still withheld from the bus, which is correct for the monitor view and is asserted here so the
     * two streams cannot drift into one.
     */
    @Test
    void aConsumedAcknowledgementIsReportedWithItsDecode() {
        listen();

        receive("ok P15 B3");

        var line = onlyReceived();
        assertEquals(RECEIVED, line.direction());
        assertEquals("ok P15 B3", line.raw());
        assertEquals("AdvancedOkRs", line.decodedAs());
        assertEquals(List.of(), published, "window credit is not a reply, and that does not change");
    }

    /**
     * A console renders an answer as a transcript, so the order of its lines is part of it -
     * {@code M115}'s shape, several replies and the acknowledgement that closes them.
     *
     * <p>The annotations are the per-line decode and are <em>unaffected</em> by the fold above them:
     * two {@code Cap:} lines read as two {@code CapabilityRs} here while reaching the bus as one
     * {@code CapabilitiesReport}, for the same reason {@code End file list} reads as
     * {@code SdFileListEnd} rather than as the {@code SdFileList} it assembles - {@code decodedAs}
     * annotates the line, not what the line produced.
     */
    @Test
    void everyLineOfAMultiLineAnswerIsReportedInOrder() {
        listen();

        receive("FIRMWARE_NAME:Marlin bugfix-2.1.x SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin");
        receive("Cap:EEPROM:1");
        receive("Cap:SDCARD:1");
        receive("ok P15 B3");

        assertEquals(List.of(
                        "FIRMWARE_NAME:Marlin bugfix-2.1.x SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin",
                        "Cap:EEPROM:1",
                        "Cap:SDCARD:1",
                        "ok P15 B3"),
                received().stream().map(TerminalTraffic::raw).toList());
        assertEquals(List.of("FirmwareInfoRs", "CapabilityRs", "CapabilityRs", "AdvancedOkRs"),
                received().stream().map(TerminalTraffic::decodedAs).toList());
        assertEquals(2, published.size(),
                "the header and one folded report, and the ok is still flow control: " + published);
    }

    /**
     * {@code decodedAs} is a property of the line, not of what the line produced. {@code decode} has
     * a branch where {@code listing.read} answers a whole {@link SdFileList} - but the line that
     * triggered it is {@code End file list}, and a console annotates the text in front of the
     * operator. An entry is deliberately not a claimable reply ({@code TEST.GCO 47} is too common a
     * {@code <word> <number>} shape to own), so its annotation is honestly absent while its text is
     * shown all the same.
     */
    @Test
    void aListingsLinesReadAsThemselvesAndNotAsTheAssembledList() {
        listen();

        receive("Begin file list");
        receive("TEST.GCO 47");
        receive("End file list");

        var lines = received();
        assertEquals(List.of("Begin file list", "TEST.GCO 47", "End file list"),
                lines.stream().map(TerminalTraffic::raw).toList());
        assertEquals("SdFileListBegin", lines.get(0).decodedAs());
        assertNull(lines.get(1).decodedAs(), "an entry is not a claimable reply, and saying so is"
                + " more honest than annotating it with something");
        assertEquals("SdFileListEnd", lines.get(2).decodedAs(),
                "the line in front of the operator is the terminator, not the list it assembled");

        assertEquals(1, published.size(), "three lines, one assembled listing: " + published);
        assertEquals(List.of(new SdFile("TEST.GCO", 47)),
                assertInstanceOf(SdFileList.class, published.getFirst()).getFiles());
    }

    // --------------------------------------------------------------- what the host said

    /**
     * Spec 7.2's counter reset is the first thing any session writes, so it is the first line a
     * console shows - and it reaches this seam at all only because it goes through the drain loop
     * like every other block rather than being poked at the socket.
     */
    @Test
    void theSessionsOpeningCounterResetIsTheFirstLineReported() {
        var out = sendWindowOf("M105");

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .thenCancel()
                .verify(TIMEOUT);

        var line = only();
        assertEquals(SENT, line.direction());
        assertEquals("N1 M110 N0*124", line.raw(), "the framed block, without the terminator");
        assertNull(line.decodedAs(), "an outbound block is not a reply and has nothing to decode it as");
    }

    /** The framed block and not the typed command: the {@code N} field and the checksum are the send
     * window's, and a console showing what was asked for rather than what happened would hide
     * exactly the fault that lives in those two fields. */
    @Test
    void aSentBlockIsReportedNumberedAndChecksummed() {
        var out = sendWindowOf("M105");

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(1)
                .expectNext("N1 M105*38\n")
                .thenCancel()
                .verify(TIMEOUT);

        var sent = traffic.stream().filter(line -> line.direction() == SENT)
                .map(TerminalTraffic::raw).toList();
        assertEquals(List.of("N1 M110 N0*124", "N1 M105*38"), sent);
        assertTrue(sent.getLast().matches("N\\d+ M105\\*\\d+"),
                "the N field and the checksum must both be in it: " + sent.getLast());
    }

    /** Both directions on one seam, in the order they crossed, which is the whole of what a console
     * renders. */
    @Test
    void bothDirectionsReachTheOneObserverInWireOrder() {
        var out = sendWindowOf("M105");

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .then(() -> receive("ok"))
                .thenRequest(1)
                .expectNext("N1 M105*38\n")
                .then(() -> receive("ok T:45.26 /45.00 B:25.00 /0.00 @:3 B@:0"))
                .thenCancel()
                .verify(TIMEOUT);

        assertEquals(List.of(SENT, RECEIVED, SENT, RECEIVED),
                traffic.stream().map(TerminalTraffic::direction).toList());
        assertEquals(List.of("N1 M110 N0*124", "ok", "N1 M105*38",
                        "ok T:45.26 /45.00 B:25.00 /0.00 @:3 B@:0"),
                raw());
    }

    /**
     * The reply bus ending is the inbound half dying; the console's stream is not the reply bus. The
     * last lines of a session are the ones an operator is looking at when something went wrong, so a
     * traffic item may not be gated on {@code busEnded} - here the inbound flux completes before a
     * single block has been written, and the block that then goes out is still reported.
     */
    @Test
    void aLineThatCrossesTheWireAfterTheBusEndedIsStillReported() {
        var out = sendWindowOf("M105");

        wire.tryEmitComplete();

        StepVerifier.create(out, 0)
                .thenRequest(1)
                .expectNext("N1 M110 N0*124\n")
                .thenCancel()
                .verify(TIMEOUT);

        assertEquals(List.of("N1 M110 N0*124"), raw(),
                "the bus had already ended, and the console still has to show this");
    }
}

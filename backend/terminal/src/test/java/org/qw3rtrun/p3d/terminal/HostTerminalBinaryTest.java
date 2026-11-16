package org.qw3rtrun.p3d.terminal;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.BlocksAbandoned;
import org.qw3rtrun.p3d.g.marlin.binary.MbpControlType;
import org.qw3rtrun.p3d.g.marlin.binary.MbpFrame;
import org.qw3rtrun.p3d.g.marlin.binary.MbpPacket;
import org.qw3rtrun.p3d.g.marlin.binary.MbpPacketReader;
import org.qw3rtrun.p3d.g.marlin.binary.MbpProtocol;
import org.qw3rtrun.p3d.g.marlin.binary.MbpTransferType;
import org.qw3rtrun.p3d.g.marlin.event.SdWritingToFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Marlin Binary Protocol over a {@code Sinks} wire: no socket, no printer, no real clock.
 *
 * <p><strong>The script every happy-path test here replays is a capture, not an invention.</strong>
 * Taken 2026-11-16 against MarlinSim ({@code Marlin bugfix-2.1.x}, compiled Oct 29 2026) on
 * {@code localhost:8099} over a raw socket, with {@code M115} answering
 * {@code Cap:BINARY_FILE_TRANSFER:1}:
 *
 * <pre>
 * &gt;&gt; M28 B1                              &lt;&lt; echo:Switching to Binary Protocol
 *                                        &lt;&lt; ok P15 B3
 * &gt;&gt; AD B5 00 01 00 00 01 03  (SYNC)     &lt;&lt; ss1,96,0.1.0        (no ok for SYNC, ever)
 * &gt;&gt; QUERY  (sync=1)                     &lt;&lt; ok1
 *                                        &lt;&lt; PFT:version:0.1.0:compression:heatshrink,8,4
 * &gt;&gt; OPEN   (sync=2)                     &lt;&lt; ok2
 *                                        &lt;&lt; echo:SD card ok
 *                                        &lt;&lt; echo:Now fresh file: mbp.gco
 *                                        &lt;&lt; Writing to file: mbp.gco
 *                                        &lt;&lt; //action:notification mbp.gco
 *                                        &lt;&lt; PFT:success
 * &gt;&gt; WRITE block 0 [0,96)   (sync=3)     &lt;&lt; ok3    (and nothing else, ever)
 * &gt;&gt; WRITE block 1 [96,192) (sync=4)     &lt;&lt; ok4
 * &gt;&gt; WRITE block 2 [192,250)(sync=5)     &lt;&lt; ok5
 * &gt;&gt; PFT CLOSE (sync=6)                  &lt;&lt; ok6
 *                                        &lt;&lt; echo:SD card released
 *                                        &lt;&lt; PFT:success
 * &gt;&gt; CONTROL CLOSE (sync=7)              &lt;&lt; ok7
 * </pre>
 *
 * <p>Two measured facts the script carries that no reasoning would have produced: the firmware's
 * payload buffer is <strong>96 bytes, not the stock 512</strong>, and the sync id is
 * <strong>not reset</strong> by connecting or by {@code M28 B1} - three consecutive sessions
 * answered {@code ss1}, {@code ss8} and {@code ss0}, which is why the window adopts whatever the
 * {@code ss} names. Both are what {@link #theFirmwaresBufferSizeIsAdoptedFromTheHandshake()} and the
 * sync ids asserted here are about; a tidy-up that replaces them with round numbers undoes the
 * measurement.
 *
 * <p>The {@code ok P15 B3} above is unnumbered because that capture was taken over a raw socket,
 * with no {@code N} on the {@code M28 B1}. Through this transport the same line comes back as
 * {@code ok N4 P15 B3} - {@code MbpTransferProbe} measured it - and the point of the test that feeds
 * it is that <em>either</em> spelling falls through the binary decoder to {@code MarlinRsDecoder}
 * and credits the ASCII window. Both are real; neither is a typo to tidy.
 *
 * <p>Packets written are read back with {@code :gcode}'s {@code MbpPacketReader} rather than
 * compared against {@code MbpCodec.encode}, which would recompute the expectation the way the code
 * under test does. The one literal is the {@code SYNC} packet's eight bytes, which
 * {@code MbpSendWindow}'s own javadoc states independently.
 *
 * <p>Time is a {@link VirtualTimeScheduler} handed in, never the ambient one: the retry timeout is
 * two seconds and a test may not wait for it.
 */
class HostTerminalBinaryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** The retry timeout and attempt count the driver defaults to, restated so a test can advance
     * exactly one deadline. */
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(2);

    private static final int ATTEMPTS = 5;

    private final Sinks.Many<String> wire = Sinks.many().multicast().onBackpressureBuffer();

    /** Everything the terminal wrote, in order - ASCII blocks and packets both, because which of
     * the two went out and when is the thing most of these tests measure. */
    private final List<byte[]> wrote = Collections.synchronizedList(new ArrayList<>());

    private final List<Object> published = Collections.synchronizedList(new ArrayList<>());

    private final VirtualTimeScheduler clock = VirtualTimeScheduler.create();

    private HostTerminal terminal;

    /**
     * A terminal whose outbound side is drained with unbounded demand, as {@code reactor-netty}'s
     * send drains it. The G-code source is {@code gcode} and then silence: a source that
     * <em>completes</em> terminates the outbound stream, after which no packet could be written at
     * all, which is a fixture failure rather than the behaviour under test.
     */
    private void terminal(String... gcode) {
        terminal = new HostTerminal(wire::asFlux, out -> Flux.from(out).doOnNext(wrote::add).then());
        terminal.messageFlux().subscribe(published::add);
        terminal.start(Flux.concat(Flux.fromArray(gcode), Flux.never())).subscribe();
    }

    private Flux<Long> transfer(String name, byte[] data) {
        return transfer(name, data, Mono.never());
    }

    private Flux<Long> transfer(String name, byte[] data, Mono<Void> cancel) {
        return terminal.binaryTransfer(name, data, cancel, clock, REPLY_TIMEOUT, ATTEMPTS);
    }

    private void receive(String line) {
        wire.tryEmitNext(line + "\n");
    }

    /** A packet is the only thing on this wire that opens with the protocol's token. */
    private static boolean isPacket(byte[] written) {
        return written.length >= 2 && written[0] == (byte) 0xAD && written[1] == (byte) 0xB5;
    }

    /** What was written, as packets - decoded with {@code :gcode}'s reader, which is a different
     * piece of code from the encoder that produced them. */
    private List<MbpPacket> packets() {
        var out = new ArrayList<MbpPacket>();
        for (byte[] written : List.copyOf(wrote)) {
            if (!isPacket(written)) {
                continue;
            }
            for (MbpFrame frame : new MbpPacketReader(512).frames(written, 0, written.length)) {
                out.add(assertInstanceOf(MbpPacket.class, frame, "a whole packet"));
            }
        }
        return out;
    }

    /** What was written that was not a packet, as the ASCII it is. */
    private List<String> asciiBlocks() {
        var out = new ArrayList<String>();
        for (byte[] written : List.copyOf(wrote)) {
            if (!isPacket(written)) {
                out.add(new String(written, StandardCharsets.US_ASCII));
            }
        }
        return out;
    }

    private byte[] lastWritten() {
        var all = List.copyOf(wrote);
        return all.getLast();
    }

    /** A file whose every byte differs from its neighbours, so a block sliced at the wrong offset
     * cannot pass for the right one. */
    private static byte[] payload(int size) {
        var data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 7 + 1);
        }
        return data;
    }

    private static void assertPacket(MbpPacket packet, int sync, int protocol, int type, byte[] payload) {
        assertEquals(sync, packet.getSync(), "sync id of " + packet);
        assertEquals(protocol, packet.getProtocol(), "protocol of " + packet);
        assertEquals(type, packet.getType(), "type of " + packet);
        assertEquals(HostTerminal.hex(payload), HostTerminal.hex(packet.getPayload()), "payload of " + packet);
    }

    /** The {@code OPEN} payload the firmware answered {@code PFT:success} to: no dummy flag, no
     * compression flag, the name, a NUL. */
    private static byte[] openPayload(String name) {
        var bytes = new byte[2 + name.length() + 1];
        for (int i = 0; i < name.length(); i++) {
            bytes[2 + i] = (byte) name.charAt(i);
        }
        return bytes;
    }

    /** Everything from {@code echo:Switching to Binary Protocol} to the last {@code WRITE}'s
     * {@code ok}, exactly as the capture has it. Returns after the file is on the card and before
     * the mode has been left. */
    private void replayCaptureUpToTheFinalPftSuccess() {
        receive("echo:Switching to Binary Protocol");
        receive("ok P15 B3");
        receive("ss1,96,0.1.0");
        receive("ok1");
        receive("PFT:version:0.1.0:compression:heatshrink,8,4");
        receive("ok2");
        receive("echo:SD card ok");
        receive("echo:Now fresh file: mbp.gco");
        receive("Writing to file: mbp.gco");
        receive("//action:notification mbp.gco");
        receive("PFT:success");
        receive("ok3");
        receive("ok4");
        receive("ok5");
        receive("ok6");
        receive("echo:SD card released");
        receive("PFT:success");
    }

    /**
     * The capture, replayed: the exact packets in the exact order with the exact sync ids, and one
     * progress reading per acknowledged block.
     *
     * <p>Subscribed with <strong>no demand</strong> on purpose. The progress readings are buffered
     * until the caller asks for them and the transfer runs to the end regardless - a transfer whose
     * packets waited on a progress subscriber's demand would stall against a firmware that times a
     * silent stream out in ten seconds.
     */
    @Test
    void aTransferReplaysTheCapturedSessionPacketForPacket() {
        terminal();
        byte[] data = payload(250);

        StepVerifier.create(transfer("mbp.gco", data), 0)
                .then(this::replayCaptureUpToTheFinalPftSuccess)
                .then(() -> receive("ok7"))
                .thenRequest(3)
                .expectNext(96L, 192L, 250L)
                .expectComplete()
                .verify(TIMEOUT);

        var sent = packets();
        assertEquals(8, sent.size(), "the capture is eight packets: " + sent);
        assertPacket(sent.get(0), 0, MbpProtocol.CONTROL, MbpControlType.SYNC, new byte[0]);
        assertPacket(sent.get(1), 1, MbpProtocol.FILE_TRANSFER, MbpTransferType.QUERY, new byte[0]);
        assertPacket(sent.get(2), 2, MbpProtocol.FILE_TRANSFER, MbpTransferType.OPEN, openPayload("mbp.gco"));
        assertPacket(sent.get(3), 3, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE,
                Arrays.copyOfRange(data, 0, 96));
        assertPacket(sent.get(4), 4, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE,
                Arrays.copyOfRange(data, 96, 192));
        assertPacket(sent.get(5), 5, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE,
                Arrays.copyOfRange(data, 192, 250));
        assertPacket(sent.get(6), 6, MbpProtocol.FILE_TRANSFER, MbpTransferType.CLOSE, new byte[0]);
        assertPacket(sent.get(7), 7, MbpProtocol.CONTROL, MbpControlType.CLOSE, new byte[0]);
    }

    /** The {@code SYNC} packet's bytes, which {@code MbpSendWindow}'s javadoc states independently -
     * and the one packet whose whole wire form is short enough to pin as a literal. */
    @Test
    void theSyncPacketIsTheEightBytesTheProtocolSpecifies() {
        terminal();

        transfer("mbp.gco", payload(10)).subscribe();
        receive("echo:Switching to Binary Protocol");

        assertEquals("AD B5 00 01 00 00 01 03", HostTerminal.hex(lastWritten()));
    }

    /**
     * <strong>The construction-order test.</strong> {@code MbpTransfer} reads
     * {@code MbpSendWindow.blockSize} at construction and {@code MbpSendWindow.on(MbpStreamSync)} is
     * what clamps it down to the firmware's real buffer, so a transfer built before the handshake
     * slices 512-byte blocks and {@code window.send} throws
     * {@code payload of 512 bytes exceeds the negotiated block size of 96} on the very first write.
     * Measured, the simulator's buffer is 96.
     */
    @Test
    void theFirmwaresBufferSizeIsAdoptedFromTheHandshake() {
        terminal();
        byte[] data = payload(600);

        transfer("mbp.gco", data).subscribe();
        receive("echo:Switching to Binary Protocol");
        receive("ss0,96,0.1.0");
        receive("ok0");
        receive("PFT:version:0.1.0:compression:none");
        receive("ok1");
        receive("PFT:success");

        var write = packets().getLast();
        assertPacket(write, 2, MbpProtocol.FILE_TRANSFER, MbpTransferType.WRITE,
                Arrays.copyOfRange(data, 0, 96));
    }

    /**
     * Marlin reads serial into a command queue and runs {@code M28 B1} later, out of that queue, so
     * bytes written in between are eaten by the <em>ASCII</em> reader and become garbage commands.
     * {@code echo:Switching to Binary Protocol} is written from the execution stage, which makes it
     * the one signal that the mode really changed - and measured, it arrives <em>before</em> the
     * {@code ok}, which is why waiting on it beats waiting on the {@code ok}.
     */
    @Test
    void noPacketIsWrittenBeforeTheFirmwareSaysItSwitched() {
        terminal();

        transfer("mbp.gco", payload(10)).subscribe();
        receive("ok P15 B3");
        receive("echo:SD card ok");

        assertEquals(List.of(), packets(), "nothing may go out before the echo");

        receive("echo:Switching to Binary Protocol");

        assertEquals(1, packets().size(), "the SYNC, and only once the echo arrived");
        assertPacket(packets().getFirst(), 0, MbpProtocol.CONTROL, MbpControlType.SYNC, new byte[0]);
    }

    /**
     * A printer that cannot do this - no {@code Cap:BINARY_FILE_TRANSFER}, or a build that ignores
     * {@code M28 B1}'s flag - acknowledges the command and echoes nothing. The wait is timed for
     * that case, and it gives up <strong>without</strong> a {@code CONTROL CLOSE}: the firmware is
     * still reading ASCII, so eight bytes of packet would reach it as a garbage command.
     */
    @Test
    void aPrinterThatNeverSwitchesModeFailsRatherThanHanging() {
        terminal();

        StepVerifier.withVirtualTime(() -> transfer("mbp.gco", payload(10)), () -> clock, Long.MAX_VALUE)
                .then(() -> receive("ok P15 B3"))
                .thenAwait(REPLY_TIMEOUT.multipliedBy(ATTEMPTS))
                .expectErrorMessage("the printer did not switch to binary mode within 10000ms")
                .verify(TIMEOUT);

        assertEquals(List.of(), packets(), "nothing was written, so there is nothing to unwind");
    }

    /**
     * <strong>The ambiguity the mode gate exists to resolve.</strong> {@code rs7} is a valid line of
     * both protocols: {@code MarlinRsDecoder.decode("rs7")} answers {@code ResendRs(7)}, a
     * base-protocol <em>line</em> resend, while in binary mode it is a <em>packet</em> resend, and
     * only the host knows which mode it put the printer in.
     *
     * <p>Without the gate the packet resend reaches the ASCII send window, matches nothing it holds,
     * triggers {@code resynchronise}, restarts the line numbering and publishes a
     * {@link BlocksAbandoned} - a healthy ASCII session destroyed over a reply that was never about
     * it. So: nothing abandoned, and the next block still numbers on from where it was.
     */
    @Test
    void aPacketResendInBinaryModeLeavesTheAsciiSessionAlone() {
        terminal("G1 X1", "G1 X2");
        transfer("mbp.gco", payload(10)).subscribe();

        receive("echo:Switching to Binary Protocol");
        receive("ok P15 B3");
        receive("ss1,96,0.1.0");

        receive("rs7");

        assertEquals(List.of(), published.stream().filter(BlocksAbandoned.class::isInstance).toList(),
                "a packet resend abandons no G-code block: " + published);
        assertEquals(List.of("N1 M110 N0*124\n", "N1 G1 X1*96\n", "N2 G1 X2*96\n"), asciiBlocks(),
                "the numbering carried on: " + asciiBlocks());
    }

    /**
     * The lines the firmware emits <em>during</em> a transfer that are not MBP replies still reach
     * {@link HostTerminal#messageFlux()} through the {@code MarlinRsDecoder} fallback. Measured, all
     * of {@code echo:SD card ok}, {@code echo:Now fresh file:}, {@code Writing to file:},
     * {@code //action:notification} and {@code echo:SD card released} arrive mid-transfer; swallowing
     * them would blind the Storage tab for the length of every upload.
     */
    @Test
    void aLineThatIsNotAnMbpReplyStillReachesTheBusInBinaryMode() {
        terminal();
        transfer("mbp.gco", payload(10)).subscribe();
        receive("echo:Switching to Binary Protocol");

        receive("Writing to file: mbp.gco");

        assertEquals("mbp.gco", published.stream()
                .filter(SdWritingToFile.class::isInstance)
                .map(SdWritingToFile.class::cast)
                .map(SdWritingToFile::getFile)
                .findFirst()
                .orElse(null), "what reached the bus: " + published);
    }

    /**
     * {@code MbpSendWindow.retransmit()} hands back <strong>the exact bytes</strong> rather than
     * re-encoding them, which is its documented point, and the timer that calls it is this driver's.
     * Five attempts two seconds apart: the firmware times a stuck transfer out itself after ten
     * seconds, so the host's budget is lined up with the firmware's rather than fighting it.
     */
    @Test
    void anUnansweredPacketIsSentAgainAndThenGivenUpOn() {
        terminal();

        StepVerifier.withVirtualTime(() -> transfer("mbp.gco", payload(10)), () -> clock, Long.MAX_VALUE)
                .then(() -> receive("echo:Switching to Binary Protocol"))
                .then(() -> assertEquals(1, packets().size()))
                .thenAwait(REPLY_TIMEOUT)
                .then(() -> {
                    var sent = packets();
                    assertEquals(2, sent.size(), "the SYNC again: " + sent);
                    assertEquals(HostTerminal.hex(wrote.get(wrote.size() - 2)),
                            HostTerminal.hex(lastWritten()), "byte for byte the same packet");
                })
                .thenAwait(REPLY_TIMEOUT.multipliedBy(ATTEMPTS + 1))
                .expectErrorMessage("no reply to the packet in flight after 5 attempt(s) 2000ms apart")
                .verify(TIMEOUT);

        assertEquals(ATTEMPTS, packets().stream()
                        .filter(p -> p.getProtocol() == MbpProtocol.CONTROL
                                && p.getType() == MbpControlType.SYNC)
                        .count(),
                "five transmissions of the SYNC and no sixth: " + packets());
    }

    /**
     * {@code PFT:busy} is what an interrupted transfer leaves behind, and the recovery is
     * {@code ABORT} then {@code OPEN} again - once. {@code MbpTransfer} owns that policy and this
     * only has to carry it, including the giving up when the client stays busy.
     */
    @Test
    void aBusyClientIsAbortedAndReopenedExactlyOnce() {
        terminal();
        transfer("mbp.gco", payload(10)).subscribe();

        receive("echo:Switching to Binary Protocol");
        receive("ss10,96,0.1.0");
        receive("ok10");
        receive("PFT:version:0.1.0:compression:none");
        receive("ok11");
        receive("PFT:busy");

        var afterBusy = packets().getLast();
        assertPacket(afterBusy, 12, MbpProtocol.FILE_TRANSFER, MbpTransferType.ABORT, new byte[0]);

        receive("ok12");
        receive("PFT:success");

        var reopened = packets().getLast();
        assertPacket(reopened, 13, MbpProtocol.FILE_TRANSFER, MbpTransferType.OPEN, openPayload("mbp.gco"));
    }

    /** A second {@code PFT:busy} gives up with {@code MbpTransfer}'s own words, and leaves binary
     * mode on the way out. */
    @Test
    void aClientThatStaysBusyGivesUpWithTheReasonFromGcode() {
        terminal();

        StepVerifier.create(transfer("mbp.gco", payload(10)))
                .then(() -> {
                    receive("echo:Switching to Binary Protocol");
                    receive("ss10,96,0.1.0");
                    receive("ok10");
                    receive("PFT:version:0.1.0:compression:none");
                    receive("ok11");
                    receive("PFT:busy");
                    receive("ok12");
                    receive("PFT:success");
                    receive("ok13");
                    receive("PFT:busy");
                    receive("ok14");
                })
                .expectErrorMessage("the client stayed busy after an abort")
                .verify(TIMEOUT);

        var last = packets().getLast();
        assertPacket(last, 14, MbpProtocol.CONTROL, MbpControlType.CLOSE, new byte[0]);
    }

    /**
     * A cancellation <strong>completes</strong> rather than erroring, and still leaves binary mode.
     * The caller tells cancelled from failed with a flag it set before firing {@code cancel}, exactly
     * as the ASCII path does - an {@code onError} would show the operator a failure when they asked
     * for a stop.
     */
    @Test
    void aCancelledTransferCompletesAndStillLeavesBinaryMode() {
        terminal();
        var cancel = Sinks.<Void>empty();

        StepVerifier.create(transfer("mbp.gco", payload(250), cancel.asMono()), 0)
                .then(() -> {
                    receive("echo:Switching to Binary Protocol");
                    receive("ss1,96,0.1.0");
                    receive("ok1");
                    receive("PFT:version:0.1.0:compression:none");
                    receive("ok2");
                    receive("PFT:success");
                    receive("ok3");
                })
                .thenRequest(1)
                .expectNext(96L)
                .then(() -> {
                    cancel.tryEmitEmpty();
                    // The ABORT reuses the abandoned block's sync id, so the firmware is still
                    // waiting on the id it answers.
                    receive("ok4");
                    receive("PFT:success");
                    receive("ok5");
                })
                .expectComplete()
                .verify(TIMEOUT);

        var sent = packets();
        assertPacket(sent.get(sent.size() - 2), 4, MbpProtocol.FILE_TRANSFER, MbpTransferType.ABORT,
                new byte[0]);
        assertPacket(sent.getLast(), 5, MbpProtocol.CONTROL, MbpControlType.CLOSE, new byte[0]);
    }

    /** {@code MbpTransfer} closes straight from {@code OPENING} when the file has no blocks, so the
     * driver must not sit waiting for a write that is never sent. */
    @Test
    void anEmptyFileClosesWithoutAnyWriteAtAll() {
        terminal();

        StepVerifier.create(transfer("empty.gco", new byte[0]))
                .then(() -> {
                    receive("echo:Switching to Binary Protocol");
                    receive("ss1,96,0.1.0");
                    receive("ok1");
                    receive("PFT:version:0.1.0:compression:none");
                    receive("ok2");
                    receive("PFT:success");
                    receive("ok3");
                    receive("PFT:success");
                    receive("ok4");
                })
                .expectComplete()
                .verify(TIMEOUT);

        var sent = packets();
        assertTrue(sent.stream().noneMatch(p -> p.getType() == MbpTransferType.WRITE
                        && p.getProtocol() == MbpProtocol.FILE_TRANSFER),
                "nothing to write: " + sent);
        assertPacket(sent.get(sent.size() - 2), 3, MbpProtocol.FILE_TRANSFER, MbpTransferType.CLOSE,
                new byte[0]);
        assertPacket(sent.getLast(), 4, MbpProtocol.CONTROL, MbpControlType.CLOSE, new byte[0]);
    }

    /**
     * <strong>Why the mode flag may be set eagerly and cleared lazily.</strong>
     * {@code MbpRsDecoder} claims {@code ok<digits>} only - {@code MbpOk.decode("ok N1 P15 B3")}
     * declines, because {@code digitsEnd} hits a blank right after {@code ok} - and Marlin writes
     * its numbered ASCII acknowledgement {@code ok N1 P15 B3}, never {@code ok1}. So an ASCII
     * {@code ok} crossing the boundary in either direction still falls through to
     * {@code MarlinRsDecoder} and still widens the ASCII send window, which is what this measures:
     * three blocks go out on the {@code B3} that arrived while binary mode was on.
     *
     * <p>Measured by {@code MbpTransferProbe}: the {@code ok} for {@code M28 B1} arrives after the
     * echo and after the host has already written its {@code SYNC} packet, so this is the live
     * ordering and not a contrived one.
     */
    @Test
    void anAsciiAcknowledgementInBinaryModeStillCreditsTheAsciiWindow() {
        terminal("G1 X1", "G1 X2", "G1 X3");
        transfer("mbp.gco", payload(10)).subscribe();
        receive("echo:Switching to Binary Protocol");

        receive("ok N1 P15 B3");

        assertEquals(List.of("N1 M110 N0*124\n", "N1 G1 X1*96\n", "N2 G1 X2*96\n", "N3 G1 X3*96\n"),
                asciiBlocks(), "the B3 widened the window across the mode boundary");
    }

    /** Nothing happens until subscribe: the flux is cold, so a caller that built one and did not
     * subscribe has not switched the printer's mode and has not written a byte. */
    @Test
    void theTransferFluxIsCold() {
        terminal();

        transfer("mbp.gco", payload(250));
        receive("echo:Switching to Binary Protocol");

        assertEquals(List.of(), packets());
    }
}

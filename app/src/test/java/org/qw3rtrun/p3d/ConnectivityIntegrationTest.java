package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting a machine on the line, and knowing when it is - issue #43's {@code Connectivity} feature.
 *
 * <p>Driven entirely through {@code POST /api-printer/{id}/connect},
 * {@code GET /api-printer/{id}/connection} and the SSE stream, against mocked printers that differ
 * in <em>how they answer</em>: one that behaves like MarlinSim, one that acknowledges with a bare
 * {@code ok} and reports no buffer counts, one that accepts the socket and says nothing, and an
 * address with nothing behind it at all.
 *
 * <p>That last pair is the point of running this over several configurations. {@code ONLINE} and
 * {@code IDLE} look the same from a page and mean different things - a socket, against a firmware
 * that has taken something this session sent - and the only way to tell the distinction is real is
 * to put a machine at each end of it.
 *
 * <p><strong>The acknowledgement dialect is the same axis one level down, and #61 added it.</strong>
 * A machine that answers a numbered block with its number - {@code ok N2 P15 B3}, which is what
 * every Marlin built with {@code ADVANCED_OK} does and what this farm therefore sees on every block
 * it sends - lets the host match an {@code ok} to the block it answers. One that answers a bare
 * {@code ok} leaves it counting. The difference is invisible while the wire behaves and decides the
 * session when it does not, so the last two tests here put the same stray {@code ok} to each
 * dialect and assert the two different things that then reach the machine.
 */
class ConnectivityIntegrationTest extends FarmIntegrationTest {

    /** Registering a machine does not dial it, and the roster says so before anything happens. */
    @Test
    void aNewlyRegisteredMachineIsOffline() {
        var printer = machine(MockPrinter.marlin());
        var id = register("never dialled", printer);

        assertEquals("OFFLINE", connectionState(id));
        assertEquals(0, printer.sessions(), "registering a machine must not open a socket");
    }

    /**
     * Connecting reaches {@code IDLE}, and the session's opening handshake is on the wire.
     *
     * <p>Three blocks, in this order, and each one is load-bearing:
     *
     * <ul>
     *   <li>{@code M110 N0} - spec 7.2. Without it a reconnection to a printer that was never
     *       power-cycled is rejected from its first block onwards, which meant only the first
     *       session after a printer powered on had ever worked;</li>
     *   <li>{@code M115} - what the machine is, which the roster then keeps;</li>
     *   <li>{@code M155 S1} - start auto-reporting, which is what makes a healthy machine speak
     *       within a round trip of connecting.</li>
     * </ul>
     */
    @Test
    void connectingReachesIdleAndSendsTheOpeningHandshake() {
        var printer = machine(MockPrinter.marlin());
        var id = register("marlin", printer);

        connectAndWait(id);

        await("the handshake to be on the wire", () -> printer.sent("M155 S1"));
        assertEquals(List.of("M110 N0", "M115", "M155 S1"), printer.commands(),
                "the session must open with M110, then ask what the machine is, then start reports");
        assertTrue(printer.sessionOpen(), "the socket should still be open");
    }

    /**
     * A machine that takes the socket and says nothing stops at {@code ONLINE}.
     *
     * <p>A terminal server with nothing plugged into it. The arrow out of {@code ONLINE} used to be
     * "any reply arrived", and a live run disproved it - auto-report traffic left running by an
     * earlier session reaches a host whose outbound half is dead. It is a {@code BlockAcknowledged}
     * now, so a machine that never acknowledges never becomes usable, and this is what says so.
     *
     * <p><strong>It also pins the backpressure.</strong> Exactly one block reaches a machine that
     * never answers: the window starts at one outstanding block and will not pull another until
     * one comes back. {@code M115} and {@code M155} are queued behind it, not written.
     */
    @Test
    void aMachineThatNeverAnswersStopsAtOnline() {
        var printer = machine(MockPrinter.mute());
        var id = register("mute", printer);

        connect(id);
        await("the socket to open", () -> "ONLINE".equals(connectionState(id)));

        // Bounded, because the claim is that something does NOT happen. A machine that was going
        // to acknowledge would have done it in far less than this - the healthy case above takes
        // one round trip on loopback.
        quiet();

        assertEquals("ONLINE", connectionState(id),
                "a socket is not a firmware; nothing acknowledged a block, so this is not IDLE");
        assertEquals(List.of("M110 N0"), printer.commands(),
                "the send window must not pull a second block while the first is unacknowledged");
    }

    /**
     * A firmware that acknowledges with a bare {@code ok} gets there too.
     *
     * <p>{@code ok P15 B3} is Marlin reporting how much room it has; a plain {@code ok} is a
     * firmware that reports none, and the host then holds itself to one outstanding block. Slower,
     * and it must still work - which is the whole reason {@code AdvancedOkRs(1, 1, null)} is the
     * starting assumption rather than something larger.
     */
    @Test
    void aFirmwareThatReportsNoBufferCountsStillConnects() {
        var printer = machine(MockPrinter.legacyOk());
        var id = register("legacy", printer);

        connectAndWait(id);

        await("the handshake to be on the wire", () -> printer.sent("M155 S1"));
        assertEquals(List.of("M110 N0", "M115", "M155 S1"), printer.commands());
    }

    // ------------------------------------------------------- how an acknowledgement names a block

    /**
     * A stray {@code ok} moves nothing, because the acknowledgement names the block it answers.
     *
     * <p><strong>This is the test issue #61 exists for, and it is the only one in this module that
     * reaches {@code GSendWindow.acknowledge(number)}.</strong> Until the mock echoed the number it
     * was sent, every {@code ok} in this suite was {@code ok P15 B3} - a shape no real session ever
     * sees, since the farm numbers every block - so {@code CommandProcessor.applyWindowEvents} took
     * the FIFO {@code acknowledge()} branch throughout and the named branch was exercised by
     * nothing at all.
     *
     * <p><strong>Why the window has to be holding something.</strong> In a well-behaved exchange
     * the two branches agree: the numbered {@code ok} names exactly the block FIFO order would have
     * released, which is the whole reason this went unnoticed. They only part when something is out
     * of step, so the test has to put the session out of step - a block outstanding, and then an
     * {@code ok} that does not belong to it.
     *
     * <ul>
     *   <li><strong>named</strong> - the window is not holding {@code N103}, so it releases nothing
     *       and does not move. The {@code G28} is still outstanding, still has its bytes, and the
     *       {@code Resend:} is answered with them;</li>
     *   <li><strong>counted</strong> - {@code acknowledge()} releases whatever is at the front,
     *       which is the {@code G28} the machine has not answered. The correspondence is off by one
     *       from here on, the {@code Resend:} finds nothing held, and
     *       {@code CommandProcessor.resynchronise} starts the numbering again with a second
     *       {@code M110 N0} - abandoning the block the firmware just asked for.</li>
     * </ul>
     *
     * <p>So the two branches diverge into two different command lists at the machine, which is why
     * this can be asserted from out here at all - and the counted branch is not described but
     * <em>run</em>, by {@link #aStrayAcknowledgementResynchronisesAnUnnumberedSession()} below. The
     * two tests are the same stray against the two branches, and they are the mutation the issue
     * asks for, kept in the suite rather than performed once by hand.
     *
     * <p><strong>What proves the mock's numbering is load-bearing is not this test.</strong> The
     * stray below is pushed, so it would still be numbered even if {@link MockPrinter#ok()} went
     * back to {@code ok P15 B3}. What that change buys is the other eighty-odd tests: every
     * {@code ok} in this module now carries a number, so {@code applyWindowEvents} takes the named
     * branch throughout and {@code connectAndWait} reaching {@code IDLE} depends on it. Measured by
     * mutating the mock to answer {@code ok N<n+1000> P15 B3} - no block is ever released, no
     * {@code BlockAcknowledged} is emitted, and
     * {@link #connectingReachesIdleAndSendsTheOpeningHandshake()} fails with
     * {@code timed out after PT30S waiting for machine … to reach IDLE}. Before #61 that mutation
     * could not be written down, because there was no number to make wrong.
     *
     * <p><strong>The lines pushed are measured; the scenario is constructed.</strong>
     * {@code ok N<n> P15 B3} and {@code Resend: n} are both census lines - §4.2 - and a stray
     * acknowledgement is the hazard {@code acknowledge(number)} was added for by #30, after an
     * {@code ACK = -1} sentinel in a {@code Queue<Integer>} let one shift the window permanently.
     * Nothing waits on a clock: the machine is held silent, so the window cannot move under the
     * test between the two pushes.
     */
    @Test
    void aStrayAcknowledgementDoesNotShiftTheSendWindow() {
        var printer = machine(MockPrinter.marlin());
        var id = register("stray ok", printer);
        connectAndSettle(id, printer);

        // Held, so the G28 stays outstanding in the send window. A window with nothing in it
        // releases nothing on either branch, and the two cannot be told apart.
        printer.holdRepliesFrom("G28");
        command(id, "/home", "{}").expectStatus().isOk();
        await(() -> "G28 to reach the machine. It received " + printer.commands(),
                () -> printer.sent("G28"));

        var block = printer.blocks().getLast();
        var number = MockPrinter.number(block);
        assertNotNull(number, "the farm numbers every block it sends; this one is " + block);

        // An ok naming a line this session has not sent and will not send.
        printer.push("ok N" + (number + 100) + " P15 B3");
        // And then the firmware asking for the block that is genuinely still outstanding.
        printer.push("Resend: " + number);

        await(() -> "block " + block + " to be replayed byte for byte."
                        + " The machine received " + printer.commands() + " as " + printer.blocks()
                        + ". A second M110 N0 there means the stray ok released the G28 and the"
                        + " session was resynchronised instead of replayed",
                () -> Collections.frequency(printer.blocks(), block) == 2);
        assertEquals(List.of("G28", "G28"), printer.commands(),
                "a replay is the same block again and nothing else");
    }

    /**
     * The same stray, against a firmware that reports no number - and it costs the session.
     *
     * <p>The mirror of the test above and the reason {@code legacyOk()} is kept rather than
     * deleted. A bare {@code ok} carries nothing to match on, so the window has only its FIFO order
     * to go on and <em>cannot</em> tell a stray from the real thing. One arrives, the {@code G28}
     * is released although the machine never acknowledged it, and the {@code Resend:} that follows
     * finds nothing held - so the host abandons the block and starts the numbering again.
     *
     * <p>That is not a defect being frozen. It is what the un-numbered dialect is worth, stated
     * once rather than assumed, and it is the assertion that would fail if this profile ever
     * quietly started numbering: the two profiles must differ here or the matrix row is decoration.
     */
    @Test
    void aStrayAcknowledgementResynchronisesAnUnnumberedSession() {
        var printer = machine(MockPrinter.legacyOk());
        var id = register("legacy stray ok", printer);
        connectAndSettle(id, printer);

        printer.holdRepliesFrom("G28");
        command(id, "/home", "{}").expectStatus().isOk();
        await(() -> "G28 to reach the machine. It received " + printer.commands(),
                () -> printer.sent("G28"));

        var number = MockPrinter.number(printer.blocks().getLast());
        printer.push("ok");
        printer.push("Resend: " + number);

        await(() -> "the session to be resynchronised. The machine received " + printer.commands(),
                () -> printer.sent("M110 N0"));
        assertEquals(List.of("G28", "M110 N0"), printer.commands(),
                "a bare ok cannot be told from a stray one, so the block is released, the resend"
                        + " finds nothing held, and the numbering has to start again");
    }

    /** A machine at an address with nothing behind it keeps trying, and is never linked. */
    @Test
    void aMachineThatCannotBeReachedIsNeverLinked() {
        var id = register("unplugged", "localhost", MockPrinter.deadPort(), null);

        connect(id);

        await("the farm to start dialling", () -> "CONNECTING".equals(connectionState(id)));
        quiet();
        assertEquals("CONNECTING", connectionState(id),
                "a refused dial retries; it must not read as a connection");
    }

    /**
     * The state change reaches a browser, in the shape it reads.
     *
     * <p>{@code type} is the event's simple class name, so this is the same contract
     * {@code EventStreamContractTest} pins by serialising one - asserted here at the other end of
     * the wire, on a message produced by an actual connection.
     */
    @Test
    void theStateChangeReachesTheEventStream() {
        var printer = machine(MockPrinter.marlin());
        var id = register("watched", printer);
        var events = watch(id);

        connectAndWait(id);

        // payloadOf is null until the first message of that type arrives, so the condition has to
        // tolerate it rather than throw a NullPointerException out of the wait - which would report
        // as an error instead of as the timeout it is, and hide what the stream actually carried.
        await(() -> "IDLE to be announced on the stream (it carried " + events.types() + ")",
                () -> {
                    var latest = events.payloadOf("ConnectionStateChanged");
                    return latest != null && "IDLE".equals(latest.get("to"));
                });
        var announced = events.payloadOf("ConnectionStateChanged");
        assertEquals("IDLE", announced.get("to"));
        assertEquals("ONLINE", announced.get("from"),
                "a machine reaches IDLE from ONLINE - the socket first, then the acknowledgement");

        // The walk, in order, as a browser saw it. Only the tail is asserted: the sink replays the
        // machine's *current* state to a late subscriber, so the first element is wherever the
        // machine happened to be when this test attached, and that is not part of the claim.
        var walk = events.all().stream()
                .filter(m -> "ConnectionStateChanged".equals(m.get("type")))
                .map(m -> (String) ((Map<?, ?>) m.get("event")).get("to"))
                .toList();
        assertEquals(List.of("CONNECTING", "ONLINE", "IDLE"), walk.subList(walk.size() - 3, walk.size()),
                "dialling walks CONNECTING, ONLINE, IDLE, and a browser is told each step: " + walk);
    }

    /**
     * Disconnecting closes the socket, and that is asserted from the machine's end.
     *
     * <p>From the host, a disconnect that merely cancelled its two halves looked identical to one
     * that closed the connection - and the difference is a socket left {@code Established} on every
     * printer the farm has ever spoken to. Only the far end can tell them apart.
     */
    @Test
    void disconnectingClosesTheSocket() {
        var printer = machine(MockPrinter.marlin());
        var id = register("hung up on", printer);
        connectAndWait(id);

        disconnect(id);

        await("the machine to report OFFLINE", () -> "OFFLINE".equals(connectionState(id)));
        await("the socket to close at the machine", () -> !printer.sessionOpen());
    }

    /** And it can be dialled again afterwards, which is a second session at the machine. */
    @Test
    void aMachineCanBeDialledAgainAfterADisconnect() {
        var printer = machine(MockPrinter.marlin());
        var id = register("redialled", printer);
        connectAndWait(id);
        disconnect(id);
        await("the first session to end", () -> !printer.sessionOpen());
        printer.forget();

        connectAndWait(id);

        assertEquals(2, printer.sessions(), "a reconnection is a new session");
        await("the second handshake", () -> printer.sent("M155 S1"));
        assertEquals("M110 N0", printer.commands().getFirst(),
                "every session opens its own numbering, or the firmware rejects every block");
    }

    /**
     * What the runtime learned about the connection reaches the roster, and can be read back.
     *
     * <p>This is the fact that used to be write-only. {@code printer_entity.connection_state} is
     * written from {@code core}, because knowing whether a machine is connected means holding the
     * socket - and it is read at startup to decide what to dial again. A column with no reader was
     * the defect; this asserts the reader through the endpoint that exposes it.
     */
    @Test
    void theRosterLearnsThatTheMachineIsConnected() {
        var printer = machine(MockPrinter.marlin());
        var id = register("remembered", printer);

        connectAndWait(id);

        await(() -> "the roster to catch up. The machine received " + received(id)
                        + " and the roster says " + describe(id).get("state"),
                () -> {
                    var state = (String) describe(id).get("state");
                    return "IDLE".equals(state) || "ONLINE".equals(state);
                });
        assertFalse("OFFLINE".equals(describe(id).get("state")),
                "the roster must not still think the machine is offline");
    }
}

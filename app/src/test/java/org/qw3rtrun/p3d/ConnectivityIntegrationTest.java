package org.qw3rtrun.p3d;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        settle();

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

    /** A machine at an address with nothing behind it keeps trying, and is never linked. */
    @Test
    void aMachineThatCannotBeReachedIsNeverLinked() {
        var id = register("unplugged", "localhost", MockPrinter.deadPort(), null);

        connect(id);

        await("the farm to start dialling", () -> "CONNECTING".equals(connectionState(id)));
        settle();
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

        await("IDLE to be announced on the stream",
                () -> "IDLE".equals(events.payloadOf("ConnectionStateChanged").get("to")));
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

        await("the roster to catch up", () -> {
            var state = (String) describe(id).get("state");
            return "IDLE".equals(state) || "ONLINE".equals(state);
        });
        assertFalse("OFFLINE".equals(describe(id).get("state")),
                "the roster must not still think the machine is offline");
    }

    /**
     * A pause long enough to disprove "it just had not happened yet".
     *
     * <p>Used only where the claim is that something does <em>not</em> occur. Everything else waits
     * for a condition instead, because a test that sleeps for a result is a test that is slow when
     * it passes and flaky when it does not.
     */
    private static void settle() {
        try {
            Thread.sleep(Duration.ofMillis(750));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

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
 * in <em>how they answer</em>: MarlinSim-like, a bare-{@code ok} acknowledger reporting no buffer
 * counts, one that takes the socket and says nothing, and an address with nothing behind it.
 *
 * <p>The last pair is the point of running over several configurations: {@code ONLINE} and
 * {@code IDLE} look the same from a page and mean different things - a socket, against a firmware
 * that has taken something this session sent - and only a machine at each end proves the
 * distinction real.
 *
 * <p>#61 added the same axis one level down: the acknowledgement dialect. A numbered {@code ok}
 * (what every {@code ADVANCED_OK} Marlin sends) lets the host match it to the block it answers; a
 * bare {@code ok} leaves it counting. Invisible while the wire behaves, it decides the session when
 * it does not - so the last two tests here put the same stray {@code ok} to each dialect and assert
 * the two different things that then reach the machine.
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
     * Three blocks, each load-bearing: {@code M110 N0} (spec 7.2 - without it a reconnection to a
     * printer that was never power-cycled is rejected from its first block, so only the first
     * session after power-on ever worked), {@code M115} (what the machine is, which the roster
     * keeps), then {@code M155 S1} (auto-reporting, so a healthy machine speaks within a round trip).
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
     * The arrow out of {@code ONLINE} used to be "any reply arrived", and a live run disproved it -
     * auto-report traffic left running by an earlier session reaches a host whose outbound half is
     * dead. It is a {@code BlockAcknowledged} now, so this machine never becomes usable; it also
     * pins the backpressure, since exactly one block reaches it and {@code M115}/{@code M155} stay
     * queued.
     */
    @Test
    void aMachineThatNeverAnswersStopsAtOnline() {
        var printer = machine(MockPrinter.mute());
        var id = register("mute", printer);

        connect(id);
        await("the socket to open", () -> "ONLINE".equals(connectionState(id)));

        // Bounded, because the claim is a negative: a machine that was going to acknowledge would
        // have done it in far less than this - the healthy case above takes one round trip on loopback.
        quiet();

        assertEquals("ONLINE", connectionState(id),
                "a socket is not a firmware; nothing acknowledged a block, so this is not IDLE");
        assertEquals(List.of("M110 N0"), printer.commands(),
                "the send window must not pull a second block while the first is unacknowledged");
    }

    /**
     * {@code ok P15 B3} is Marlin reporting its room; a plain {@code ok} reports none, so the host
     * holds itself to one outstanding block - slower, and the reason {@code AdvancedOkRs(1, 1, null)}
     * is the starting assumption rather than something larger.
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
     * The only test here reaching {@code GSendWindow.acknowledge(number)} (#61): until the mock
     * echoed the block number, every {@code ok} was the unnumbered {@code ok P15 B3} shape, so
     * {@code CommandProcessor.applyWindowEvents} always took the FIFO branch and the named one was
     * never exercised. The two branches agree whenever the session is in step, so this holds a
     * block (the {@code G28}) outstanding and pushes an {@code ok} naming a different, never-sent
     * line: the named branch releases nothing, so the {@code G28} is still there to answer the
     * {@code Resend:} that follows; the FIFO branch (run as the mirror test below) releases the
     * {@code G28} anyway, finds nothing for the {@code Resend:}, and resynchronises with a second
     * {@code M110 N0}, abandoning the block. That the mock's numbering is load-bearing is measured,
     * not assumed: mutating it to answer {@code ok N<n+1000> P15 B3} releases no block anywhere and
     * fails {@link #connectingReachesIdleAndSendsTheOpeningHandshake()} with a 30-second IDLE
     * timeout. {@code ok N<n> P15 B3} and {@code Resend: n} are census lines, §4.2;
     * {@code acknowledge(number)} itself is #30, replacing an {@code ACK = -1} sentinel that let a
     * stray shift the window permanently. Nothing here waits on a clock - the machine is held
     * silent between the two pushes.
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
     * The mirror of the test above, and the reason {@code legacyOk()} is kept: a bare {@code ok}
     * carries nothing to match on, so FIFO order releases the {@code G28} regardless, the
     * {@code Resend:} finds nothing held, and the numbering restarts - not a defect being frozen,
     * but what the un-numbered dialect is worth, and the assertion that fails if this profile ever
     * quietly started numbering.
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
     * {@code type} is the event's simple class name, so this is the same contract
     * {@code EventStreamContractTest} pins by serialising one - asserted here on a message produced
     * by an actual connection.
     */
    @Test
    void theStateChangeReachesTheEventStream() {
        var printer = machine(MockPrinter.marlin());
        var id = register("watched", printer);
        var events = watch(id);

        connectAndWait(id);

        // payloadOf is null until the first message of that type arrives; tolerating that avoids a
        // NullPointerException reporting as an error instead of the timeout it actually is.
        await(() -> "IDLE to be announced on the stream (it carried " + events.types() + ")",
                () -> {
                    var latest = events.payloadOf("ConnectionStateChanged");
                    return latest != null && "IDLE".equals(latest.get("to"));
                });
        var announced = events.payloadOf("ConnectionStateChanged");
        assertEquals("IDLE", announced.get("to"));
        assertEquals("ONLINE", announced.get("from"),
                "a machine reaches IDLE from ONLINE - the socket first, then the acknowledgement");

        // Only the tail is asserted: the sink replays the machine's *current* state to a late
        // subscriber, so the first element is wherever it happened to be when this test attached.
        var walk = events.all().stream()
                .filter(m -> "ConnectionStateChanged".equals(m.get("type")))
                .map(m -> (String) ((Map<?, ?>) m.get("event")).get("to"))
                .toList();
        assertEquals(List.of("CONNECTING", "ONLINE", "IDLE"), walk.subList(walk.size() - 3, walk.size()),
                "dialling walks CONNECTING, ONLINE, IDLE, and a browser is told each step: " + walk);
    }

    // --------------------------------------------------------------- a link that drops and returns

    /**
     * The measurement that decided issue #94: <strong>a pulled cable never reads {@code OFFLINE}</strong>.
     *
     * <p>{@code ConnectionLifecycle.linkFailed} consults the intent rather than the socket - with a
     * connection still wanted it goes to {@code CONNECTING} and the reactor redials, and only a
     * {@code disconnectRequested} or a {@code gaveUp} reaches {@code OFFLINE}. So the journey a
     * browser is shown across a dropped link is {@code IDLE -> CONNECTING -> ONLINE -> IDLE}, and
     * anything a page keys off {@code to === 'OFFLINE'} - which {@code SettingsTab.vue} did, to
     * clear its table - fires on the deliberate disconnect and never on this. That is why #94's fix
     * is a stated boundary rather than a state the page infers.
     */
    @Test
    void aDroppedLinkThatComesBackNeverAnnouncesOffline() {
        var printer = machine(MockPrinter.marlin());
        var id = register("cable pulled", printer);
        var events = connectAndSettle(id, printer);
        var before = events.allOf("ConnectionStateChanged").size();

        dropTheLinkAndWait(id, printer);

        var walk = events.allOf("ConnectionStateChanged").stream()
                .skip(before)
                .map(event -> (String) event.get("to"))
                .toList();
        assertFalse(walk.contains("OFFLINE"),
                "a link that drops while a connection is still wanted is retried, not ended;"
                        + " a page waiting for OFFLINE waits forever: " + walk);
        assertTrue(walk.contains("CONNECTING") && walk.contains("ONLINE"),
                "it does go round again, which is what makes the previous assertion a finding"
                        + " rather than an absence of events: " + walk);
    }

    /**
     * The session boundary is the <em>aggregate's</em>, not either tab's - issue #94's "one fix in
     * the shared pattern rather than two".
     *
     * <p>Asserted on a machine with no EEPROM at all, whose Settings tab is never even rendered
     * ({@code monitor.html} gates it on {@code profile.hasEeprom()}): {@code SessionStarted} still
     * arrives, because {@code PrinterState.onOnline} states it beside the forgetting rather than
     * either control raising its own. That is what makes it hold for the next thing added to
     * {@code PrinterState.snapshot()} without a second patch.
     *
     * <p>Exactly one per established link, and before the {@code ONLINE} that announces the link:
     * a page applying them in order has forgotten the old session by the time it is told there is a
     * new one.
     */
    @Test
    void everyNewLinkStatesItsOwnBoundaryWhateverTheMachineCanDo() {
        var printer = machine(MockPrinter.withoutEeprom());
        var id = register("no eeprom", printer);
        var events = connectAndSettle(id, printer);
        // Counted rather than assumed to be one. This stream is opened before the dial, so it
        // should carry the first link's boundary too - but whether the server-side subscription
        // is live by the time onOnline runs is a race this test is not about, and the claim worth
        // making is the increment.
        var before = Collections.frequency(events.types(), "SessionStarted");

        dropTheLinkAndWait(id, printer);

        await(() -> "the new link's boundary (the stream carried " + events.types() + ")",
                () -> Collections.frequency(events.types(), "SessionStarted") > before);
        quiet();
        var after = events.types();
        assertEquals(before + 1, Collections.frequency(after, "SessionStarted"),
                "one link is one boundary and no more - a redial announcing one per attempt would"
                        + " blank a page every time a retry failed, and this machine's link was"
                        + " re-established once: " + after);

        var boundary = after.lastIndexOf("SessionStarted");
        var online = indexOfState(events, "ONLINE", boundary);
        assertTrue(online > boundary,
                "the boundary leads the link it belongs to, so a page is never told it is back"
                        + " before it is told to let go: " + after);
    }

    /** Where the first {@code ConnectionStateChanged} reaching {@code state} after {@code from} sits in {@link Events#types()}. */
    private static int indexOfState(Events events, String state, int from) {
        var messages = events.all();
        for (var i = from; i < messages.size(); i++) {
            var message = messages.get(i);
            if ("ConnectionStateChanged".equals(message.get("type"))
                    && state.equals(((Map<?, ?>) message.get("event")).get("to"))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * From the host, a disconnect that merely cancelled its two halves looked identical to one that
     * closed the connection - the difference being a socket left {@code Established} on every
     * printer ever spoken to. Only the far end can tell them apart.
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

    /**
     * {@link #disconnectingClosesTheSocket()} was making this claim by accident, as a timeout: it
     * awaited {@code OFFLINE} and took thirty seconds not getting it, because the machine reached
     * {@code OFFLINE} and was redialled straight back out of it - thirty "dialling it again" lines
     * and eleven {@code -> CONNECTING} transitions in one window. So the fact is stated at the far
     * boundary instead - the machine's own session count must stay at one, where
     * {@link #aMachineCanBeDialledAgainAfterADisconnect()} is the same counter reading two, on
     * purpose. The cause is {@code MRegisteredEvent} being republished about seven times per
     * connect while {@code PrinterReactorManager} dials anything announced as linked; since #54
     * queued those republications on the printer's own listener thread, a stale "it was linked"
     * copy can now arrive after a disconnect asked for in between. {@link #quiet()}, not an await,
     * per the spec's rule for a negative claim: the only honest way to make it is to give the thing
     * time to have happened.
     */
    @Test
    void aDisconnectedMachineIsNotRedialledWhenTheRosterCatchesUp() {
        var printer = machine(MockPrinter.marlin());
        var id = register("left alone", printer);
        connectAndWait(id);
        assertEquals(1, printer.sessions(), "one dial is one session; the claim below starts here");

        disconnect(id);
        await("the machine to report OFFLINE", () -> "OFFLINE".equals(connectionState(id)));
        quiet();

        assertEquals("OFFLINE", connectionState(id),
                "nothing asked this machine to reconnect, so nothing may have put it back on the line");
        assertEquals(1, printer.sessions(),
                "a redial is a second session at the machine, and the only thing that could have"
                        + " ordered one is a roster announcement that arrived after the disconnect");
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
     * Used to be write-only: {@code printer_entity.connection_state} is written from {@code core}
     * (knowing a machine is connected means holding the socket) and read at startup to decide what
     * to dial again - a column with no reader was the defect, and this asserts the reader through
     * the endpoint that exposes it.
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

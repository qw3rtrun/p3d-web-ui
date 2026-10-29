package org.qw3rtrun.p3d.core;

import org.junit.jupiter.api.Test;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.BUSY;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.CONNECTING;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.ERROR;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.IDLE;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.OFFLINE;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.ONLINE;

/**
 * Every arrow in the connection state machine, driven by hand.
 *
 * <p>No socket, no scheduler and no Reactor in any of it - which is the argument for the lifecycle
 * being a plain object rather than something woven into {@link PrinterReactor}. What B3 replaced
 * was two booleans on the aggregate and a column on the entity, and the reason nothing could be
 * asserted about them was that each was set in passing by code that was doing something else.
 *
 * <p>The timing half of the item is in {@code PrinterReactorRetryTest}, where the schedulers are.
 */
class ConnectionLifecycleTest {

    /** A clock a test can push forward, for the one transition that depends on time passing. */
    private static final class Hand extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Hand clock = new Hand();
    private final ConnectionLifecycle lifecycle = new ConnectionLifecycle(Duration.ofSeconds(10), clock);

    private static void moved(ConnectionStateChanged change, Object from, Object to, int attempt) {
        assertNotNull(change, "nothing moved, but the test expected " + from + " -> " + to);
        assertEquals(from, change.from());
        assertEquals(to, change.to());
        assertEquals(attempt, change.attempt());
    }

    /** A printer the process has only just heard of is offline, and nothing is in flight. */
    @Test
    void itStartsOffline() {
        assertEquals(OFFLINE, lifecycle.state());
        assertFalse(lifecycle.linked());
        assertFalse(lifecycle.wanted());
        assertEquals(0, lifecycle.status().attempt());
    }

    @Test
    void connectingThenTheSocketOpeningThenTheFirstAcknowledgement() {
        moved(lifecycle.connectRequested(), OFFLINE, CONNECTING, 1);
        assertTrue(lifecycle.wanted());
        assertFalse(lifecycle.linked(), "an attempt in flight is not a connection");

        moved(lifecycle.linkEstablished(), CONNECTING, ONLINE, 0);
        assertTrue(lifecycle.linked());

        moved(lifecycle.blockAcknowledged(), ONLINE, IDLE, 0);
    }

    /**
     * {@code ONLINE} and {@code IDLE} are not synonyms, and this is the difference.
     *
     * <p>A terminal server with nothing plugged into it accepts the TCP connection and then says
     * nothing at all. That machine is {@code ONLINE} and never reaches {@code IDLE}, which is worth
     * being able to see - the host had a socket and no printer, and the old pair of booleans called
     * that "online" with no qualification.
     */
    @Test
    void aSocketThatNobodyAnswersStaysOnline() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();

        assertEquals(ONLINE, lifecycle.state());
    }

    /**
     * A machine that talks but accepts nothing is {@code ONLINE}, not {@code IDLE}.
     *
     * <p>This is the hole B3 left open and recorded against itself. {@code M155 S1} left running by
     * a previous session goes on writing a temperature every second into whatever socket is open,
     * and a backlog the printer queued while nothing was listening arrives the moment one is - so
     * "a reply arrived" is satisfied by traffic that proves only that the firmware is powered. The
     * live run reached this state twice from two different causes, and both read as {@code IDLE}
     * while the outbound half of the session was dead.
     *
     * <p>The proof that is worth something is an acknowledgement of a block <em>this</em> session
     * sent, which is the one thing a dead outbound half cannot produce.
     */
    @Test
    void aMachineThatTalksButAcceptsNothingStaysOnline() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();

        assertNull(lifecycle.replyReceived(), "auto-report traffic is not proof that a block landed");
        assertEquals(ONLINE, lifecycle.state());

        moved(lifecycle.blockAcknowledged(), ONLINE, IDLE, 0);
    }

    @Test
    void askingToConnectTwiceDoesNotRestartAnythingOrResetTheBackoff() {
        moved(lifecycle.connectRequested(), OFFLINE, CONNECTING, 1);

        assertNull(lifecycle.connectRequested(), "an impatient second click must not restart the series");
        assertEquals(1, lifecycle.status().attempt());
    }

    @Test
    void askingToConnectAMachineThatAlreadyIsDoesNothing() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();

        assertNull(lifecycle.connectRequested());
        assertEquals(ONLINE, lifecycle.state());
    }

    /**
     * A drop nobody asked for goes back to {@code CONNECTING}.
     *
     * <p>The intent is what makes the difference, and the two booleans this replaced could not hold
     * it: a machine whose link had dropped was {@code connected && !online}, a combination with no
     * meaning and no event.
     */
    @Test
    void aDropWhileAConnectionIsWantedGoesBackToConnecting() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();

        moved(lifecycle.linkFailed("connection reset"), ONLINE, CONNECTING, 1);
        assertEquals("connection reset", lifecycle.status().reason());
        assertTrue(lifecycle.wanted());
    }

    /** The identical drop, a moment after Disconnect, rests instead of retrying. */
    @Test
    void aDropAfterADisconnectRests() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();
        moved(lifecycle.disconnectRequested(), ONLINE, OFFLINE, 0);
        assertFalse(lifecycle.wanted());

        assertNull(lifecycle.linkFailed("the socket closed"),
                "the machine was already resting, so the socket closing moved nothing");
        assertEquals(OFFLINE, lifecycle.state());
        assertFalse(lifecycle.wanted(), "nothing may retry after a disconnect");
    }

    /** Each failed attempt is counted, and each one says so rather than passing in silence. */
    @Test
    void everyFailedAttemptIsAnnouncedWithItsNumber() {
        lifecycle.connectRequested();

        moved(lifecycle.linkFailed("refused"), CONNECTING, CONNECTING, 2);
        moved(lifecycle.linkFailed("refused"), CONNECTING, CONNECTING, 3);
        moved(lifecycle.linkFailed("refused"), CONNECTING, CONNECTING, 4);
    }

    @Test
    void givingUpEndsTheIntentSoNothingRetriesAfterIt() {
        lifecycle.connectRequested();
        lifecycle.linkFailed("refused");

        moved(lifecycle.gaveUp("Retries exhausted"), CONNECTING, OFFLINE, 0);
        assertFalse(lifecycle.wanted());
        assertEquals("Retries exhausted", lifecycle.status().reason());

        assertNull(lifecycle.linkFailed("refused"), "a machine already given up on has nowhere to go");
    }

    /** A disconnect from any state ends at {@code OFFLINE}, including mid-backoff. */
    @Test
    void disconnectingFromAnyStateRests() {
        lifecycle.connectRequested();
        lifecycle.linkFailed("refused");

        moved(lifecycle.disconnectRequested(), CONNECTING, OFFLINE, 0);
        assertNull(lifecycle.disconnectRequested(), "already resting");
    }

    @Test
    void aBusyNoticeIsAStateAndRepeatsDoNotReAnnounceIt() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();
        lifecycle.blockAcknowledged();

        moved(lifecycle.busyReported("processing"), IDLE, BUSY, 0);
        assertNull(lifecycle.busyReported("processing"), "the firmware repeats this every two seconds");
    }

    /**
     * Busy survives the replies that arrive while the machine is working.
     *
     * <p>This is the whole reason leaving {@code BUSY} is timed rather than event-driven. The
     * handshake asks for {@code M155 S1}, so a temperature report lands once a second <em>between</em>
     * the busy notices; "busy until something else arrives" would flicker between the two states for
     * as long as the machine was working.
     */
    @Test
    void aTemperatureArrivingMidBusyDoesNotClearIt() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();
        lifecycle.busyReported("processing");

        clock.advance(Duration.ofSeconds(1));
        assertNull(lifecycle.replyReceived());
        assertEquals(BUSY, lifecycle.state());
    }

    /** Once the busy notices stop, the next reply is what notices. */
    @Test
    void busyEndsOnceTheNoticesHaveStopped() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();
        lifecycle.busyReported("processing");

        clock.advance(Duration.ofSeconds(11));

        moved(lifecycle.replyReceived(), BUSY, IDLE, 0);
    }

    /**
     * An error is a state the machine talks its way out of.
     *
     * <p>On a Marlin an {@code Error:} is routine traffic - a bad checksum is answered with
     * {@code Error:}, {@code Resend:} and then {@code ok} - so this has to clear itself. A sticky
     * {@code ERROR} would mean a farm where every printer eventually reads as faulted.
     */
    @Test
    void anErrorIsClearedByTheNextGoodReply() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();
        lifecycle.blockAcknowledged();

        moved(lifecycle.faulted("checksum mismatch, Last Line: 2"), IDLE, ERROR, 0);
        assertTrue(lifecycle.linked(), "a fault is not a lost connection");
        assertNull(lifecycle.faulted("checksum mismatch, Last Line: 3"), "already faulted");

        moved(lifecycle.replyReceived(), ERROR, IDLE, 0);
    }

    /** Nothing the machine says matters when there is no machine on the other end. */
    @Test
    void repliesFromNowhereAreIgnoredWhileOffline() {
        assertNull(lifecycle.replyReceived());
        assertNull(lifecycle.busyReported("processing"));
        assertNull(lifecycle.faulted("something"));
        assertEquals(OFFLINE, lifecycle.state());
    }

    /** What a late subscriber is handed: where the machine is, as an event it can read. */
    @Test
    void theCurrentStateIsAvailableAsAnEvent() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();

        var current = lifecycle.current();
        assertEquals(ONLINE, current.from());
        assertEquals(ONLINE, current.to());
    }
}

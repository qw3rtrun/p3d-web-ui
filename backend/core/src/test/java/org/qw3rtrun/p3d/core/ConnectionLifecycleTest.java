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
 * Every arrow in the connection state machine, driven by hand. No socket, scheduler or Reactor in
 * any of it, which is the argument for a plain object rather than something woven into
 * {@link PrinterReactor}: B3 replaced two booleans on the aggregate and a column on the entity, each
 * set in passing by code doing something else, so nothing could be asserted about them. The timing
 * half is in {@code PrinterReactorRetryTest}, where the schedulers are.
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

    // ONLINE and IDLE are not synonyms: a terminal server with nothing plugged in accepts the TCP
    // connection and says nothing at all - ONLINE forever, never IDLE - which the old pair of
    // booleans could not distinguish.
    @Test
    void aSocketThatNobodyAnswersStaysOnline() {
        lifecycle.connectRequested();
        lifecycle.linkEstablished();

        assertEquals(ONLINE, lifecycle.state());
    }

    /**
     * A machine that talks but accepts nothing is {@code ONLINE}, not {@code IDLE} - the hole B3
     * left open: a leftover {@code M155 S1} or a queued backlog arriving the moment something
     * listens both prove only that the firmware is powered, and both read as {@code IDLE} with a
     * dead outbound half. Only an acknowledgement of a block this session sent is proof, since a
     * dead outbound half cannot produce one.
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

    // the intent makes the difference - the two booleans this replaced could not hold it: a dropped
    // link was connected && !online, a combination with no meaning and no event.
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

    // the whole reason leaving BUSY is timed rather than event-driven: M155 S1 lands a temperature
    // report once a second between the busy notices, so "busy until something else arrives" would
    // flicker.
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

    // on a Marlin an Error: is routine traffic - a bad checksum gets Error:/Resend:/ok - so it must
    // clear itself, or every printer eventually reads as faulted.
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

package org.qw3rtrun.p3d.core;

import org.qw3rtrun.p3d.api.model.ConnectionStatus;
import org.qw3rtrun.p3d.api.model.MachineConnectionState;
import org.qw3rtrun.p3d.api.msg.ConnectionStateChanged;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.qw3rtrun.p3d.api.model.MachineConnectionState.BUSY;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.CONNECTING;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.ERROR;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.IDLE;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.OFFLINE;
import static org.qw3rtrun.p3d.api.model.MachineConnectionState.ONLINE;

/**
 * One machine's connection state machine: the whole of plan item B3's "one enum is the state".
 *
 * <p><strong>Plain, synchronous and with no Reactor in it.</strong> Every method is a transition
 * that returns the {@link ConnectionStateChanged} it caused, or {@code null} when nothing moved -
 * so this decides and the caller emits, and a test can drive every arrow in the diagram without a
 * socket, a scheduler or a clock tick. The timing half of B3 - backoff between attempts - belongs
 * to {@link PrinterReactor}, which is where the schedulers are.
 *
 * <p><strong>Intent is held separately from state, and that is the whole trick.</strong>
 * {@code wanted} is what the user asked for; the state is where the machine actually is. A link
 * that drops while a connection is still wanted goes back to {@code CONNECTING}; the identical
 * drop a moment after someone pressed Disconnect goes to {@code OFFLINE}. Without the distinction
 * the two are indistinguishable, which is why the code this replaces had no retry at all: there
 * was nothing to ask whether a reconnect would be welcome.
 *
 * <p><strong>Leaving {@code BUSY} is driven by replies and a clock, not by a timer.</strong>
 * Marlin repeats {@code echo:busy: processing} every couple of seconds while it works, and
 * interleaves the {@code M155} temperature auto-reports the handshake asked for. So "busy until
 * something else arrives" would flicker between {@code BUSY} and {@code IDLE} once a second, and a
 * timer would put a scheduler in the aggregate. Instead a busy notice stamps the clock and any
 * later reply asks whether the machine has gone quiet for the busy timeout - the steady stream of
 * temperature reports being the tick. If the printer stops talking altogether the link drops, and
 * that leaves {@code BUSY} by another door.
 */
public final class ConnectionLifecycle {

    /** Long enough to outlast Marlin's {@code busy:} interval, short enough to notice. */
    public static final Duration DEFAULT_BUSY_TIMEOUT = Duration.ofSeconds(10);

    private final Duration busyTimeout;
    private final Clock clock;

    private MachineConnectionState state = OFFLINE;

    /**
     * Whether a connection is wanted, which is not the same question as whether there is one.
     *
     * <p>Set by {@link #connectRequested()} and cleared by {@link #disconnectRequested()} or by
     * running out of attempts. It is what {@link #linkFailed(String)} consults to decide between
     * retrying and resting.
     */
    private boolean wanted;

    private int attempt;
    private String reason;
    private LocalDateTime since;
    private LocalDateTime lastBusyAt;

    public ConnectionLifecycle() {
        this(DEFAULT_BUSY_TIMEOUT, Clock.systemDefaultZone());
    }

    public ConnectionLifecycle(Duration busyTimeout, Clock clock) {
        this.busyTimeout = busyTimeout;
        this.clock = clock;
        this.since = now();
    }

    public MachineConnectionState state() {
        return state;
    }

    /** Whether there is a connection right now - what decides if a command can be sent. */
    public boolean linked() {
        return state.isLinked();
    }

    /** Whether a connection is wanted, which is what makes a dropped link worth retrying. */
    public boolean wanted() {
        return wanted;
    }

    public ConnectionStatus status() {
        return new ConnectionStatus(state, since, attempt, reason);
    }

    /**
     * The current state as the event that would have announced it.
     *
     * <p>What a late subscriber is handed: the replay sink behind {@code PrinterReactor.updates()}
     * is seeded with this, so a browser attaching to a printer learns where it is immediately
     * instead of waiting for the next thing to happen to it.
     */
    public ConnectionStateChanged current() {
        return new ConnectionStateChanged(state, state, attempt, reason);
    }

    /**
     * Someone asked for a connection.
     *
     * <p>Nothing happens if there already is one - this is an intent, and the intent is already
     * satisfied. An attempt already in flight is left alone too, rather than restarted, because
     * restarting it would reset the backoff and turn a user's impatient second click into a tight
     * reconnect loop.
     */
    public ConnectionStateChanged connectRequested() {
        wanted = true;
        if (state.isLinked() || state == CONNECTING) {
            return null;
        }
        attempt = 1;
        return moveTo(CONNECTING, null);
    }

    /** Someone asked to stop. Ends the intent, so nothing retries after it. */
    public ConnectionStateChanged disconnectRequested() {
        wanted = false;
        attempt = 0;
        if (state == OFFLINE) {
            return null;
        }
        return moveTo(OFFLINE, "disconnect was asked for");
    }

    /**
     * The socket is open.
     *
     * <p>It also sets the intent: a reactor that dialled did so because something wanted it to, and
     * the one path that reaches here without a {@link #connectRequested()} first - a test, or a
     * caller driving the aggregate directly - would otherwise hold a live link that nothing would
     * reconnect.
     */
    public ConnectionStateChanged linkEstablished() {
        wanted = true;
        attempt = 0;
        if (state == ONLINE) {
            return null;
        }
        return moveTo(ONLINE, null);
    }

    /**
     * An attempt failed, or an established link went away.
     *
     * <p>Deliberately one method for both. To the state machine they are the same event - the
     * connection is not there, and the next thing to decide is whether to try again - and the only
     * difference is the attempt number, which this keeps for itself.
     *
     * <p>It always returns an event, even from {@code CONNECTING} back to {@code CONNECTING}, so
     * that every retry is visible on the stream. A machine quietly retrying and a machine that has
     * given up look identical otherwise, which is the plan item's "says so on the event stream".
     */
    public ConnectionStateChanged linkFailed(String why) {
        if (!wanted) {
            return state == OFFLINE ? null : moveTo(OFFLINE, why);
        }
        attempt++;
        return moveTo(CONNECTING, why);
    }

    /** The attempts ran out. The intent ends with them, so nothing retries after this. */
    public ConnectionStateChanged gaveUp(String why) {
        wanted = false;
        attempt = 0;
        if (state == OFFLINE) {
            return null;
        }
        return moveTo(OFFLINE, why);
    }

    /**
     * The machine said something, which is the only proof the firmware is actually there.
     *
     * <p>{@code ONLINE} to {@code IDLE} is what separates "the socket opened" from "there is a
     * printer on the other end of it", and {@code ERROR} to {@code IDLE} is how a recoverable fault
     * clears itself. {@code BUSY} is left only once the busy notices have stopped for the busy
     * timeout - see the class note.
     */
    public ConnectionStateChanged replyReceived() {
        if (state == BUSY) {
            if (Duration.between(lastBusyAt, now()).compareTo(busyTimeout) < 0) {
                return null;
            }
            return moveTo(IDLE, null);
        }
        if (state == ONLINE || state == ERROR) {
            return moveTo(IDLE, null);
        }
        return null;
    }

    /** The machine said it is busy. Repeats refresh the deadline without re-announcing. */
    public ConnectionStateChanged busyReported(String why) {
        if (!state.isLinked()) {
            return null;
        }
        lastBusyAt = now();
        if (state == BUSY) {
            return null;
        }
        return moveTo(BUSY, why);
    }

    /** The machine reported a fault. Still linked - see {@link MachineConnectionState#ERROR}. */
    public ConnectionStateChanged faulted(String why) {
        if (!state.isLinked() || state == ERROR) {
            return null;
        }
        return moveTo(ERROR, why);
    }

    private ConnectionStateChanged moveTo(MachineConnectionState next, String why) {
        var previous = state;
        state = next;
        reason = why;
        since = now();
        return new ConnectionStateChanged(previous, next, attempt, why);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}

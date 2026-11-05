package org.qw3rtrun.p3d.api.model;

/**
 * Where a machine is in its connection lifecycle - the one answer to "what is this printer doing".
 *
 * <p>Replaces three booleans that could disagree: {@code PrinterState.connected/online},
 * {@code PrinterEntity.connected} (never written in production) and {@code PrinterReactor}'s
 * implicit {@code terminal == null} - any two of which could say different things, leaving the
 * browser to infer "online" from the fact that some event had arrived.
 *
 * <pre>
 *   OFFLINE   --connect asked for-->  CONNECTING
 *   CONNECTING--socket up--------->   ONLINE   --block acknowledged-->  IDLE
 *   CONNECTING--attempt failed---->   CONNECTING (backoff, attempt+1)
 *   CONNECTING--attempts spent---->   OFFLINE  (with the reason)
 *   IDLE      --busy notice------->   BUSY     --busy goes quiet-->  IDLE
 *   linked    --Error: reported--->   ERROR    --next good reply-->  IDLE
 *   linked    --link dropped------>   CONNECTING, if a connection is still wanted
 *   any       --disconnect asked-->   OFFLINE
 * </pre>
 *
 * <p>{@code ONLINE} and {@code IDLE} are a real distinction: {@code ONLINE} means a socket is
 * open, {@code IDLE} means the firmware has taken something this host sent. A terminal server
 * with nothing plugged into it stops at {@code ONLINE}.
 *
 * <p>The arrow out of {@code ONLINE} is an acknowledgement, not "any reply" - B3 shipped the
 * latter and a live run disproved it: {@code M155 S1} left running by an earlier session, and a
 * backlog queued while nothing was listening, both deliver replies to a host whose outbound half
 * is dead, and both used to read as a healthy machine. See
 * {@code org.qw3rtrun.p3d.api.msg.BlockAcknowledged}.
 */
public enum MachineConnectionState {

    /** Not connected, and not trying to be. The resting state, and where a process starts. */
    OFFLINE,

    /** Dialling, or waiting out a backoff before the next attempt. */
    CONNECTING,

    /**
     * The socket is open, and nothing this host sent has been accepted yet. A machine can sit
     * here while talking steadily - see the class note; replies alone do not leave this state.
     */
    ONLINE,

    /** The firmware has accepted something this session sent, and is not working on anything. */
    IDLE,

    /** The firmware said it is busy, and has not stopped saying so. */
    BUSY,

    /**
     * The machine reported a fault. Still linked: on a Marlin an {@code Error:} is routine
     * traffic the printer recovers from (a bad checksum is answered with {@code Error:},
     * {@code Resend:}, then {@code ok}), so this means "the last thing it said was an error", not
     * "the connection is gone" - the next good reply clears it.
     */
    ERROR;

    /**
     * Whether there is a connection to the machine right now - what the roster's persisted column
     * is derived from, and what decides whether a command can be sent. {@code CONNECTING} is
     * deliberately not linked; {@code ERROR} deliberately is.
     */
    public boolean isLinked() {
        return this == ONLINE || this == IDLE || this == BUSY || this == ERROR;
    }
}

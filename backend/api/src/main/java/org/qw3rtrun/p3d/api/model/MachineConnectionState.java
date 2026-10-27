package org.qw3rtrun.p3d.api.model;

/**
 * Where a machine is in its connection lifecycle - the one answer to "what is this printer doing".
 *
 * <p><strong>It replaces three booleans that could disagree.</strong> {@code PrinterState} held a
 * {@code connected} flag set by the connect command and an {@code online} flag set when the socket
 * came up; {@code PrinterEntity} held a third {@code connected} column that nothing in production
 * ever wrote; and {@code PrinterReactor}'s {@code terminal == null} was a fourth, implicit one. Any
 * two of them could say different things, and the browser was left inferring "online" from the fact
 * that <em>some</em> event had arrived.
 *
 * <p>The transitions, and what drives each:
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
 * <p>{@code ONLINE} and {@code IDLE} are a real distinction rather than a synonym: {@code ONLINE}
 * means a socket is open, {@code IDLE} means the firmware on the other end has taken something this
 * host sent. A printer that accepts TCP and then says nothing - a terminal server with nothing
 * plugged into it is the usual cause - stops at {@code ONLINE}, and that is worth being able to see.
 *
 * <p><strong>The arrow out of {@code ONLINE} was "any reply" and is now an acknowledgement.</strong>
 * B3 shipped it as the first, and a live run showed what that is worth: {@code M155 S1} left
 * running by an earlier session writes a temperature a second into whatever socket is open, and a
 * printer that queued output while nothing was listening delivers the backlog the moment something
 * is. Both are replies, both arrive at a host whose outbound half is dead, and both used to read as
 * a healthy machine. See {@code org.qw3rtrun.p3d.api.msg.BlockAcknowledged}.
 */
public enum MachineConnectionState {

    /** Not connected, and not trying to be. The resting state, and where a process starts. */
    OFFLINE,

    /** Dialling, or waiting out a backoff before the next attempt. */
    CONNECTING,

    /**
     * The socket is open, and nothing this host sent has been accepted yet.
     *
     * <p>A machine can sit here while talking steadily - see the class note. Replies are not what
     * leaves this state.
     */
    ONLINE,

    /** The firmware has accepted something this session sent, and is not working on anything. */
    IDLE,

    /** The firmware said it is busy, and has not stopped saying so. */
    BUSY,

    /**
     * The machine reported a fault.
     *
     * <p>Still linked: on a Marlin an {@code Error:} is routine traffic the printer recovers from -
     * a bad checksum is answered with {@code Error:}, {@code Resend:} and then {@code ok} - so this
     * is "the last thing it said was an error" and not "the connection is gone". The next good
     * reply clears it.
     */
    ERROR;

    /**
     * Whether there is a connection to the machine right now.
     *
     * <p>This is what the roster's persisted column is derived from, and what decides whether a
     * command can be sent at all. {@code CONNECTING} is deliberately not linked - an attempt in
     * flight is not a connection - and {@code ERROR} deliberately is.
     */
    public boolean isLinked() {
        return this == ONLINE || this == IDLE || this == BUSY || this == ERROR;
    }
}

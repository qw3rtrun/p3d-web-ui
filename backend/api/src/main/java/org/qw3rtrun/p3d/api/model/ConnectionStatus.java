package org.qw3rtrun.p3d.api.model;

import java.time.LocalDateTime;

/**
 * A machine's connection, as a health signal: where it is, since when, and why.
 *
 * <p>Answered without opening a stream, which is the difference between this and the
 * {@code ConnectionStateChanged} events carrying the same facts. A page that is about to render a
 * printer wants the current answer once; a page watching one wants every change.
 *
 * @param state    the one state - see {@link MachineConnectionState}
 * @param since    when the machine entered it
 * @param attempt  which connection attempt is in flight, counting from 1. Zero when none is
 * @param reason   why, in whatever words the cause used. Null when there is nothing to explain
 */
public record ConnectionStatus(MachineConnectionState state,
                               LocalDateTime since,
                               int attempt,
                               String reason) {

    /** A machine nothing has tried to reach. */
    public static ConnectionStatus offline(LocalDateTime since) {
        return new ConnectionStatus(MachineConnectionState.OFFLINE, since, 0, null);
    }

    /** Whether there is a connection to the machine right now. */
    public boolean linked() {
        return state.isLinked();
    }
}

package org.qw3rtrun.p3d.api.msg;

import org.qw3rtrun.p3d.api.model.MachineConnectionState;

/**
 * A machine moved from one connection state to another.
 *
 * <p>One event for the whole lifecycle, replacing {@code ConnectedEvent},
 * {@code DisconnectedEvent}, {@code MachineOnlineEvent} and {@code MachineOfflineEvent} - two
 * pairs of parallel truth (what the user asked for, what the socket did) that together said less
 * than this one event does.
 *
 * <p>{@code from} and {@code to} can be equal: a retry that fails and schedules another attempt
 * stays in {@code CONNECTING} and raises {@link #attempt}, which is what makes a machine quietly
 * retrying visible rather than indistinguishable from one that has given up.
 *
 * @param attempt which connection attempt this is, counting from 1; zero when none is in flight
 * @param reason  why it moved, in the words of whatever caused it; null when there is nothing to say
 */
public record ConnectionStateChanged(MachineConnectionState from,
                                     MachineConnectionState to,
                                     int attempt,
                                     String reason) {
}

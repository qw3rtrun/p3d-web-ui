package org.qw3rtrun.p3d.api.msg;

import org.qw3rtrun.p3d.api.model.MachineConnectionState;

/**
 * A machine moved from one connection state to another.
 *
 * <p><strong>One event for the whole lifecycle</strong>, replacing {@code ConnectedEvent},
 * {@code DisconnectedEvent}, {@code MachineOnlineEvent} and {@code MachineOfflineEvent} - four
 * records that between them said less than this one does. Two of them were a pair about what the
 * user asked for and two were a pair about what the socket did, which is the parallel truth this
 * event exists to end: a reader of the stream now learns one state rather than reconciling two
 * flags, and the UI's {@code online}/{@code connected} pair collapses with them.
 *
 * <p>A past-tense fact like every other event here. {@code from} and {@code to} are both carried
 * because a subscriber that missed earlier events still needs to know where the machine is, and a
 * subscriber that saw them wants to know what moved.
 *
 * <p><strong>{@code from} and {@code to} can be equal.</strong> A retry that fails and schedules
 * another attempt stays in {@code CONNECTING} and raises {@link #attempt}, and that is exactly the
 * thing the plan asked to be visible on the stream - a machine quietly retrying looks identical to
 * one that has given up unless each attempt says so.
 *
 * @param from    where the machine was
 * @param to      where it is now
 * @param attempt which connection attempt this is, counting from 1. Zero when none is in flight
 * @param reason  why it moved, in the words of whatever caused it. Null when there is nothing to say
 */
public record ConnectionStateChanged(MachineConnectionState from,
                                     MachineConnectionState to,
                                     int attempt,
                                     String reason) {
}

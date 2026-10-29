package org.qw3rtrun.p3d.api.msg

import org.qw3rtrun.p3d.api.model.MachineConnectionState
import org.qw3rtrun.p3d.api.model.MachineProfile
import org.qw3rtrun.p3d.api.model.MachineSpec
import java.time.LocalDateTime
import java.util.*

interface ManagementEvent {
    val machineId: UUID
    val timestamp: LocalDateTime
}

/**
 * A machine's connection moved, and the roster is the one that has to remember it.
 *
 * Replaces `MConnectedEvent` and `MDisconnectedEvent`, which were published by
 * `printer-management` **to itself** from `PrinterEntity.connect()`/`disconnect()` - a pair of
 * methods reached only by a test, since `PrinterController.connect` has always gone to the runtime
 * instead. So the persisted `connected` column was written `false` at registration and never again,
 * and the roster's own answer about a machine disagreed with the machine.
 *
 * It travels the other way now: only `core` can know this, because knowing it means holding the
 * socket, and the roster's column is **derived** from the [state] carried here rather than being a
 * second opinion about it. The same crossing as [MFirmwareReportedEvent] and the same reason - see
 * `doc/sub_boundaries.txt`, where live data is `core`'s and what is kept about a machine is the
 * roster's.
 */
data class MConnectionChangedEvent(
    override val machineId: UUID,
    val state: MachineConnectionState,
    val reason: String? = null,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : ManagementEvent

/**
 * A machine is part of the farm, and here is what is needed to reach it.
 *
 * Published when one is registered, and **again for every machine at startup**: a replica built
 * from events is empty when the process begins, and nothing else would fill it. That makes this
 * event a statement of current fact rather than of a change - "this machine exists, lives here, and
 * is this kind of thing" - so receiving it twice for the same machine is normal and must be
 * idempotent.
 *
 * It carries the [profile] because the announcement is what rebuilds a replica, and a replica that
 * knew where to dial a machine but not what it is would send `core` back to asking - which is the
 * query rule 2 exists to prevent. A machine nothing has ever connected to carries
 * [MachineProfile.unknown].
 *
 * It carries the [spec] for the same reason and travelling the opposite way. The profile is a fact
 * `core` learned and the roster kept; the spec is a fact a human declared to the roster and `core`
 * has to be told, because a declared extruder count is what decides whether a command for tool 1 is
 * refused. An edit re-publishes this event, so a replica learns the new spec without waiting for a
 * restart. A machine nobody has described carries [MachineSpec.undeclared].
 *
 * It carries the [state] for the same reason and one more: it is what a restart reads. The roster's
 * record of a machine's connection is written from [MConnectionChangedEvent] and would have no
 * reader at all if nothing acted on it - so `core` redials, at startup, every machine that was
 * connected when the process went down. That is the feature `PrinterReactorManager` used to
 * approximate by dialling inside a lookup, and plan item A3 took out for being a side effect of a
 * query rather than a reconnect.
 *
 * The state here is the roster's **last-known record**, not live truth: it is accurate at the
 * startup announcement, which is the moment it is read, and goes stale the instant `core` connects
 * to anything. A connection change deliberately does not re-announce this event - `core` is where
 * that fact came from and does not need it handed back.
 */
data class MRegisteredEvent(
    override val machineId: UUID,
    val name: String,
    val host: String,
    val port: Int,
    val profile: MachineProfile,
    val spec: MachineSpec,
    val state: MachineConnectionState = MachineConnectionState.OFFLINE,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : ManagementEvent

/**
 * A machine was removed from the farm.
 *
 * Published after the row is gone, and the reason `core` can release the runtime it was holding:
 * nothing else tells it that a printer it has an open socket to has stopped existing.
 */
data class MDeletedEvent(
    override val machineId: UUID,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : ManagementEvent

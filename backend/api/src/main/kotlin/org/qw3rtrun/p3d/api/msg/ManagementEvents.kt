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
 * Replaces `MConnectedEvent`/`MDisconnectedEvent`, published by `printer-management` **to
 * itself** from `PrinterEntity.connect()`/`disconnect()` - methods reached only by a test, since
 * `PrinterController.connect` has always gone to the runtime instead, leaving the persisted
 * `connected` column written `false` at registration and never again.
 *
 * It travels the other way now: only `core` can know this, since knowing it means holding the
 * socket, and the roster's column is **derived** from [state] rather than a second opinion about
 * it. Same crossing and reason as [MFirmwareReportedEvent] - see `doc/sub_boundaries.txt`.
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
 * from events starts empty, so this is a statement of current fact rather than a change, and
 * receiving it twice for the same machine must be idempotent.
 *
 * It carries [profile] because the announcement rebuilds a replica, and one that knew where to
 * dial a machine but not what it is would send `core` back to querying, which rule 2 forbids - a
 * machine never connected to carries [MachineProfile.unknown].
 *
 * It carries [spec] for the same reason travelling the other way: the profile is a fact `core`
 * learned and the roster kept, the spec is a fact a human declared and `core` must be told, since
 * a declared extruder count decides whether a command for tool 1 is refused. An edit re-publishes
 * this event. A machine nobody has described carries [MachineSpec.undeclared].
 *
 * It carries [state] because it is what a restart reads: `core` redials, at startup, every
 * machine that was connected when the process went down - the feature A3 took out of a lookup's
 * side effect. The state here is the roster's **last-known record**, not live truth, and goes
 * stale the instant `core` connects to anything; a connection change deliberately does not
 * re-announce this event, since `core` is where the fact came from.
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

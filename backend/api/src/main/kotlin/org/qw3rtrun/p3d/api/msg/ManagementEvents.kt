package org.qw3rtrun.p3d.api.msg

import java.time.LocalDateTime
import java.util.*

interface ManagementEvent {
    val machineId: UUID
    val timestamp: LocalDateTime
}

data class MConnectedEvent(
    override val machineId: UUID,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : ManagementEvent

data class MDisconnectedEvent(
    override val machineId: UUID,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : ManagementEvent

/**
 * A machine is part of the farm, and here is what is needed to reach it.
 *
 * Published when one is registered, and **again for every machine at startup**: a replica built
 * from events is empty when the process begins, and nothing else would fill it. That makes this
 * event a statement of current fact rather than of a change - "this machine exists and lives here" -
 * so receiving it twice for the same machine is normal and must be idempotent.
 */
data class MRegisteredEvent(
    override val machineId: UUID,
    val name: String,
    val host: String,
    val port: Int,
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

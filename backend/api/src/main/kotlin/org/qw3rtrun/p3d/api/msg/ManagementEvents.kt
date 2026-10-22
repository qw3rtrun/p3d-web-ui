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
 * A machine was removed from the farm.
 *
 * Published after the row is gone, and the reason `core` can release the runtime it was holding:
 * nothing else tells it that a printer it has an open socket to has stopped existing.
 */
data class MDeletedEvent(
    override val machineId: UUID,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : ManagementEvent

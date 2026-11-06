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

/**
 * A person declared what a machine is: [spec] is the whole of the declaration and replaces
 * whatever was declared before.
 *
 * Published by `MachineManagementImpl.update`, the one path an operator's edit takes, and acted on
 * by `PrinterReactorManager`, which hands [spec] to a machine that is **already running** - an
 * aggregate is otherwise seeded only at construction, so without this an edit would reach the
 * database and the replica but not the object that refuses a command for tool 1. A machine with no
 * runtime needs nothing: `CoreConfiguration.aggregates` builds a fresh aggregate from
 * `PrinterDirectory`'s entry, which takes its spec from [MRegisteredEvent], so the declaration is
 * already there the moment the machine is stood up. Registration publishes no [MSpecDeclaredEvent]
 * for the same reason - the id is minted in that transaction, so nothing can be running under it.
 *
 * ## Why this exists when [MRegisteredEvent] already carries a spec
 *
 * Because they are two different kinds of thing, and one event cannot be both. [MRegisteredEvent]
 * is an **announcement**: "here is a machine, as the roster currently has it", built to let a
 * replica be rebuilt from nothing, idempotent, and re-published about **seven times per connect** -
 * once per firmware report and once per capability line, each from a `@Transactional` roster
 * listener queued on that machine's own `p3d-listener-<uuid>` thread. This is a **command to a live
 * object**: singular, ordered, and meaningful exactly once.
 *
 * `PrinterReactorManager` used to learn an edit from the announcement, and each announcement
 * carries whatever the roster had when *it* was built - so an announcement whose read predates an
 * edit **silently undoes the edit** when it lands after it. Captured on machine `3b14c65e`, seven
 * milliseconds end to end: five announcements carrying `extruderCount=null`, then
 * `17:13:07.455540 [oundedElastic-2]` applying the `PUT`'s `extruderCount=2`, then
 * `17:13:07.457053 [32-580ccedeae22]` - the machine's own listener thread - applying a sixth
 * `extruderCount=null` 1.5 ms later and putting it back. The next command out was
 * `SetHotendTemperature(M104 S210 T1)` and the aggregate refused it: `This machine has only 1
 * extruders, but the command's hotend index is 1`. Nothing reached the wire.
 * `PrinterManagementIntegrationTest.clearingTheDeclarationFallsBackToTheFirmware` failed **4 runs
 * in 6** isolated and unloaded, timing out after thirty seconds. **A command is sent once and never
 * retried, so one stale read is permanent, not late** - the one refused command is the whole of the
 * operator's experience.
 *
 * None of the obvious repairs works, which is why the answer is a second event:
 * - **Not by timestamp.** The straggler's own timestamp is *later* than the edit's (`.457053`
 *   against `.455540`); it is built when the listener runs. New timestamp, stale content.
 * - **Not "ignore an announcement that changes nothing".** The clobbering announcement does change
 *   something - it changes it back.
 * - **Not `@TransactionalEventListener` on the edit.** That reorders the edit relative to its own
 *   commit, not relative to a straggler that is already behind it.
 */
data class MSpecDeclaredEvent(
    override val machineId: UUID,
    val spec: MachineSpec,
    override val timestamp: LocalDateTime = LocalDateTime.now()
) : ManagementEvent

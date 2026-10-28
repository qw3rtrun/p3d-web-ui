/**
 * The vocabulary, mirrored from `:backend:api`.
 *
 * These are not the browser's own model: every name here is a type the server already defines, and
 * the reason to restate them in TypeScript is so that a shape change becomes a type error at the
 * boundary in `client.ts` rather than an `undefined` at render time.
 *
 * **If something here stops matching `api`, this file is wrong, not the server.**
 */

/** Where a machine's connection is. One enum, server-owned - see `MachineConnectionState`. */
export type ConnectionState =
    | 'OFFLINE'
    | 'CONNECTING'
    | 'ONLINE'
    | 'IDLE'
    | 'BUSY'
    | 'ERROR'

/**
 * The states in which a socket is actually up.
 *
 * `ONLINE` means a socket; `IDLE` means a firmware that has acknowledged something this host sent.
 * They are deliberately not synonyms - a terminal server with nothing plugged into it stops at
 * `ONLINE` - so anything asking "can I drive this machine" should want {@link isUsable}, not this.
 */
const LINKED: readonly ConnectionState[] = ['ONLINE', 'IDLE', 'BUSY', 'ERROR']

export function isLinked(state: ConnectionState): boolean {
    return LINKED.includes(state)
}

/** A machine that has proved it takes commands: the firmware acknowledged a block we sent. */
export function isUsable(state: ConnectionState): boolean {
    return state === 'IDLE' || state === 'BUSY'
}

export interface ConnectionDetails {
    host: string
    port: number
}

export interface ConnectionStatus {
    state: ConnectionState
    attempt: number
    reason: string | null
}

export interface MachineProfile {
    firmware: Record<string, string>
    capabilities: Record<string, string>
    learnedAt: string | null
    known: boolean
}

export interface BedVolume {
    x: number
    y: number
    z: number
}

export interface MachineSpec {
    bedVolume: BedVolume | null
    extruderCount: number | null
    nozzleDiameters: number[]
    materials: string[]
    tags: string[]
    declared: boolean
}

/** One machine as the roster describes it - `api.model.Descriptor`. */
export interface Descriptor {
    uuid: string
    name: string
    connection: ConnectionDetails
    state: ConnectionState
    profile: MachineProfile
    spec: MachineSpec
}

/** One sensor - `api.msg.TemperatureReport`. `power` is 0..127, Marlin's PWM scale. */
export interface TemperatureReport {
    current: number
    target: number
    power: number
}

/**
 * The envelope both streams carry - `api.msg.EventMessage`.
 *
 * `type` is the event's simple class name, and those strings are a contract with code the compiler
 * cannot see: `EventStreamContractTest` pins them because this broke silently once already, when
 * the UI went on looking for `"TemperatureReported"` after the stream started carrying
 * `"OkTemperatureRs"`. Every reading fell through the `if` and the page just stopped updating.
 */
export interface EventMessage {
    id: string
    type: string
    event: unknown
}

/** `type: "ConnectionStateChanged"` */
export interface ConnectionStateChanged {
    from: ConnectionState
    to: ConnectionState
    attempt: number
    reason: string | null
}

/** `type: "TemperatureReported"` */
export interface TemperatureReported {
    hotend: TemperatureReport
    bed: TemperatureReport
}

/**
 * `type: "PositionReported"` - the answer to asking where the toolhead is.
 *
 * Keyed by the axis letter Marlin used, in millimetres. A map rather than named fields because
 * which axes exist is the machine's business - render what you were given.
 */
export interface PositionReported {
    axes: Record<string, number>
}

/** `type: "MachineJoinedFarm"` - a printer registered while the page was open. */
export interface MachineJoinedFarm {
    name: string
}

/** `type: "MachineLeftFarm"` - and one deleted. Carries nothing but the id in the envelope. */
export type MachineLeftFarm = Record<string, never>

/** The roles the filter chain enforces. The UI uses them to decide what to *offer*, never to guard. */
export type FarmRole = 'VIEWER' | 'OPERATOR' | 'ADMIN'

export interface Account {
    username: string
    role: FarmRole
    enabled: boolean
}

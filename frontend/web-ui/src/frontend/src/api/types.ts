/**
 * The vocabulary, mirrored from the server.
 *
 * These are not the browser's own model: every name here is a type the server already defines, and
 * the reason to restate them in TypeScript is so that a shape change becomes a type error at the
 * boundary in `client.ts` rather than an `undefined` at render time.
 *
 * **If something here stops matching the server, this file is wrong, not the server.**
 *
 * Two sources now, and the names say which: the farm's own facts come from `:backend:api`
 * (`ConnectionStateChanged`, `MachineJoinedFarm`), and anything a printer *said* comes from
 * `:gcode` under the protocol's own name (`TemperatureRs`, `PositionRs`, `ErrorRs`). The server
 * stopped keeping a parallel model of the second kind, so these are the only remaining copies -
 * and `EventStreamContractTest` is what holds them to it.
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

/**
 * One sensor - `:gcode`'s `HeaterReading`. `power` is 0..127, Marlin's PWM scale.
 *
 * **`target` and `power` are nullable, and null is not zero.** A firmware that reports no target
 * has not been asked for one; a target of zero is a heater on its way off. The record this replaced
 * flattened the first into the second, so a page could not tell them apart - and the server no
 * longer does the flattening, because the protocol's own reply keeps the distinction.
 */
export interface HeaterReading {
    current: number
    target: number | null
    power: number | null
}

/**
 * The envelope both streams carry - `api.msg.EventMessage`.
 *
 * `type` is the event's simple class name, and those strings are a contract with code the compiler
 * cannot see: `EventStreamContractTest` pins them because this broke silently once already, when
 * the UI went on looking for `"TemperatureReported"` after the stream started carrying
 * `"OkTemperatureRs"`. Every reading fell through the `if` and the page just stopped updating.
 * That is why a reply family is matched with a helper such as {@link isTemperature} rather than by
 * writing one of its names.
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

/**
 * A temperature line, as `:gcode` decoded it.
 *
 * **Two type strings, because `ok` is a type and not a flag.** `OkTemperatureRs` is the answer to
 * an `M105` somebody asked for and `BareTemperatureRs` is an unsolicited `M155` auto-report; they
 * are the same reading and a page wants both, so match with {@link isTemperature} rather than
 * writing one of the names and silently missing the other.
 *
 * Every sensor is nullable - a machine with no chamber reports no chamber - and so is `bed`, which
 * a bedless machine never sends.
 */
export interface TemperatureRs {
    hotend: HeaterReading | null
    bed: HeaterReading | null
    chamber: HeaterReading | null
    probe: HeaterReading | null
    cooler: HeaterReading | null
    board: HeaterReading | null
    redundant: HeaterReading | null
    hotends: Record<string, HeaterReading>
    ok: boolean
}

/** The concrete replies a temperature line decodes to - see {@link TemperatureRs}. */
const TEMPERATURES: readonly string[] = ['BareTemperatureRs', 'OkTemperatureRs']

/**
 * Whether an `EventMessage.type` is a temperature reading.
 *
 * This is the drift that broke the page once already: the UI matched `"TemperatureReported"` while
 * the stream carried `"OkTemperatureRs"`, every reading fell through the `if`, and nothing in
 * either build said a word. `EventStreamContractTest` pins both strings on the server side.
 */
export function isTemperature(type: string): boolean {
    return TEMPERATURES.includes(type)
}

/**
 * `type: "PositionRs"` - the answer to asking where the toolhead is.
 *
 * Keyed by the axis letter Marlin used, in millimetres. A map rather than named fields because
 * which axes exist is the machine's business - render what you were given.
 *
 * `counts` is the firmware's internal stepper position. It differs from the planner's while moves
 * are still queued, so it is deliberately not what the move controls display.
 */
export interface PositionRs {
    axes: Record<string, number>
    counts: Record<string, number>
}

/** `type: "ErrorRs"` - the firmware's own words for something that went wrong. */
export interface ErrorRs {
    message: string
    lastLine: number | null
}

/**
 * One entry in a card listing - `:gcode`'s `SdFile`.
 *
 * `name` is **the card's spelling, not the user's**: a build without long-filename support reports
 * DOS 8.3 in upper case, so a file uploaded as `benchy.gco` is listed as `BENCHY.GCO`. That is also
 * the name `M23` and `M30` take, which is why delete and print send back whatever the listing gave
 * rather than what was typed.
 */
export interface SdFile {
    name: string
    size: number
}

/**
 * `type: "SdFileList"` - the whole of `M20`'s answer, in one event.
 *
 * **Assembled by the server, and it has to be.** The firmware writes a listing as three lines or
 * more - `Begin file list`, an entry each, `End file list` - and an entry on its own is
 * `<word> <number>`, far too common a shape for the reply decoder to claim. So the transport keeps
 * the open listing and publishes one of these on the terminator.
 *
 * **An interrupted listing produces nothing at all**, rather than a short list. A host cannot tell a
 * card holding two files from a card holding two hundred whose listing was cut off, so a page that
 * asked for a listing and received no event should say it did not get an answer - never show an
 * empty table.
 *
 * `files` is empty for a card with nothing printable on it, and that *is* an answer: `M20` lists
 * only `.gcode`, `.gco` and `.g`, so a file uploaded under another name is on the card and will
 * never appear here.
 */
export interface SdFileList {
    files: SdFile[]
}

/**
 * `type: "SdFileOpened"` - `M23`'s answer, naming the file and its size in bytes.
 *
 * The size is the firmware's own measure of what is on the card, which is where a progress bar gets
 * its denominator: `SdPrinting` reports a position against it.
 */
export interface SdFileOpened {
    file: string
    size: number
}

/**
 * `type: "SdCardOk"` - `M21` mounted the card and it answered. Carries nothing.
 *
 * **This is the only way to learn that a card is present.** No command asks, so the Storage tab
 * mounts and reads the answer: this event means a card, and anything else means none. The failure
 * text is deliberately not matched on - the simulator always has a card, so it has never been
 * captured, and guessing a reply format from the wiki is how this module got them wrong before.
 */
export type SdCardOk = Record<string, never>

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

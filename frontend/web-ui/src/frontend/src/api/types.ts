/**
 * The vocabulary, mirrored from the server - not the browser's own model. Every name here is a type
 * the server already defines, restated so a shape change becomes a type error at the boundary in
 * `client.ts` rather than an `undefined` at render time. If something here stops matching the
 * server, this file is wrong, not the server.
 *
 * Two sources: the farm's own facts come from `:backend:api` (`ConnectionStateChanged`,
 * `MachineJoinedFarm`); anything a printer said comes from `:gcode` under the protocol's own name
 * (`TemperatureRs`, `PositionRs`, `ErrorRs`) - the server keeps no parallel model of the second kind,
 * so these are the only remaining copies, and `EventStreamContractTest` holds them to it.
 */

/** Where a machine's connection is. One enum, server-owned - see `MachineConnectionState`. */
export type ConnectionState =
    | 'OFFLINE'
    | 'CONNECTING'
    | 'ONLINE'
    | 'IDLE'
    | 'BUSY'
    | 'ERROR'

// The states in which a socket is actually up. ONLINE means a socket; IDLE means a firmware that
// has acknowledged something this host sent - a terminal server with nothing plugged into it stops
// at ONLINE, so "can I drive this machine" should ask isUsable, not this.
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

/**
 * What a machine turned out to be - `api.model.MachineProfile`.
 *
 * `capabilities` is a map of booleans, not of strings: the server's record is `Map<String,
 * Boolean>`, so `capabilities[x]` compared against `'1'` would type-check and be false on every
 * machine there is - this file said `Record<string, string>` undetected until something here read it.
 *
 * `known` is the server's `isKnown()` getter, written by Jackson as a property - four fields on the
 * wire, three components in the record. A template must call `isKnown()`, since Thymeleaf reads the
 * record and not the payload.
 */
export interface MachineProfile {
    firmware: Record<string, string>
    capabilities: Record<string, boolean>
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
 * `target` and `power` are nullable, and null is not zero: a firmware reporting no target has not
 * been asked for one, while a target of zero is a heater on its way off - a distinction the server
 * used to flatten away and no longer does.
 */
export interface HeaterReading {
    current: number
    target: number | null
    power: number | null
}

/**
 * The envelope both streams carry - `api.msg.EventMessage`.
 *
 * `type` is the event's simple class name, a contract with code the compiler cannot see -
 * `EventStreamContractTest` pins it, because this broke silently once already when the UI kept
 * looking for `"TemperatureReported"` after the stream switched to `"OkTemperatureRs"`. Match a
 * reply family with a helper such as {@link isTemperature}, not by writing one of its names.
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
 * `type: "SessionStarted"` - a new link to this machine is up, and everything the server knew about
 * it from the previous session has been forgotten. Carries nothing: the boundary is the whole
 * content, and the envelope's `id` already says which machine.
 *
 * Its scope is the server's, not a list kept here - exactly what the per-printer snapshot holds
 * (today the settings report and the card state), so a panel added to that snapshot inherits it.
 *
 * It does **not** mean the link is up; `ConnectionStateChanged` says that and arrives immediately
 * after, so a page applying messages in order has let go of the old session before it is told there
 * is a new one. Exactly one per established link, including the first.
 *
 * `useSessionScope` in `src/session.ts` is the only thing that should read this - see it for why a
 * panel marks rather than blanks, and why no `OFFLINE` ever arrives across a reconnect.
 */
export type SessionStarted = Record<string, never>

/** The `type` string {@link SessionStarted} arrives under - matched in one place, `session.ts`. */
export const SESSION_STARTED = 'SessionStarted'

/**
 * A temperature line, as `:gcode` decoded it.
 *
 * Two type strings, because `ok` is a type and not a flag: `OkTemperatureRs` answers an `M105`,
 * `BareTemperatureRs` is an unsolicited `M155` auto-report, and a page wants both - match with
 * {@link isTemperature} rather than one name.
 *
 * Every sensor is nullable, including `bed` - a machine with no chamber or no bed reports none.
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

// Whether an EventMessage.type is a temperature reading - the drift that broke the page once
// already (see above); EventStreamContractTest pins both strings on the server side.
export function isTemperature(type: string): boolean {
    return TEMPERATURES.includes(type)
}

/**
 * `type: "PositionRs"` - the answer to asking where the toolhead is.
 *
 * Keyed by the axis letter Marlin used, in millimetres - a map, not named fields, since which axes
 * exist is the machine's business. `counts` is the firmware's internal stepper position, differing
 * from the planner's while moves are still queued, so it is deliberately not what move controls show.
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
 * `name` is the card's spelling, not the user's: a build without long-filename support reports DOS
 * 8.3 in upper case, so `benchy.gco` is listed as `BENCHY.GCO` - also the name `M23`/`M30` take, so
 * delete and print send back whatever the listing gave rather than what was typed.
 */
export interface SdFile {
    name: string
    size: number
}

/**
 * `type: "SdFileList"` - the whole of `M20`'s answer, in one event.
 *
 * Assembled by the server: the firmware writes `Begin file list`, an entry each, `End file list`,
 * and an entry alone is too common a shape (`<word> <number>`) for the reply decoder to claim - the
 * transport keeps the open listing and publishes one of these on the terminator.
 *
 * An interrupted listing produces nothing at all, not a short list, since a host cannot tell two
 * files from two hundred cut short - a page with no event should say so, never show an empty table.
 *
 * `files` is empty for a card with nothing printable, which is still an answer: `M20` lists only
 * `.gcode`/`.gco`/`.g`, so a file uploaded under another name is on the card but never appears here.
 */
export interface SdFileList {
    files: SdFile[]
}

/**
 * `type: "SdFileOpened"` - `M23`'s answer, naming the file and its size in bytes.
 *
 * The firmware's own measure of what is on the card - a progress bar's denominator, which
 * `SdPrinting` reports a position against.
 */
export interface SdFileOpened {
    file: string
    size: number
}

/**
 * `type: "SdCardOk"` - `M21` mounted the card and it answered. Carries nothing.
 *
 * The only way to learn a card is present, since no command asks directly: this event means a card,
 * anything else means none. The failure text is not matched on - never captured, since the
 * simulator always has a card.
 */
export type SdCardOk = Record<string, never>

/**
 * What the slicer said about a stored file - `api.files.SliceInfo`.
 *
 * All four values are nullable by design, and null is not zero: a file from an unrecognised slicer
 * carries none of them - `SliceInfo.unknown()` is a real value, not a failure, and the file is still
 * printable. Rendering a missing estimate as "0 min" would claim the print takes no time.
 *
 * `known` is the server's `isKnown()` getter, serialised as a property - five fields on the wire,
 * not four - true when any one of the others was read. `slice` itself is never absent or null:
 * `GcodeFile`'s compact constructor substitutes `SliceInfo.unknown()`, so render by checking `known`.
 *
 * On the wire: `estimatedSeconds` is a JSON number in seconds (`3661`, from Java's `Long`), never
 * formatted. `filamentMillimetres` keeps `BigDecimal`'s scale (`1234.5000` in the response text,
 * `1234.5` after `JSON.parse`) and is always millimetres whatever unit the file used - Cura's
 * `;Filament used: 1.2345m` is stored as `1234.5000`; never render it raw. `layerHeight` is
 * millimetres (`0.2`). `format.ts` turns the first two into something a person reads.
 */
export interface SliceInfo {
    slicer: string | null
    estimatedSeconds: number | null
    filamentMillimetres: number | null
    layerHeight: number | null
    known: boolean
}

/**
 * One file the server holds - `api.files.GcodeFile`. `sizeBytes` is an upload's denominator.
 *
 * Seven fields, matching the record's seven components - this file carried six for a while, missing
 * `slice`, so everything the slicer said was parsed and stored and then discarded in the browser.
 * `GcodeFileContractTest` is what notices now.
 */
export interface GcodeFile {
    id: string
    name: string
    contentHash: string
    sizeBytes: number
    lineCount: number
    uploadedAt: string
    slice: SliceInfo
}

/**
 * How a transfer to the card ended, which decides what is on the card - `SdUploadState`.
 *
 * `CANCELLED` sent its `M29`, so the file is short but closed (safe to delete, not to print);
 * `FAILED` may not have, since an emergency stop preempts the transfer with no close behind it,
 * leaving the file open until the next reset. Showing both as "did not finish" hides that difference.
 */
export type SdUploadState = 'UPLOADING' | 'DONE' | 'CANCELLED' | 'FAILED'

/**
 * `type: "SdUploadProgressed"` - how far a transfer to the card has got.
 *
 * Two counts, not interchangeable: `bytesSent`/`bytesTotal` is the exact progress bar, since the
 * stored file's size is known up front. `blocksSent` has no total on purpose - sliced files drop
 * comments and blank lines, so `lineCount` is only an upper bound; show the number, never divide by
 * `lineCount`.
 *
 * `file` is the name on the card, which may differ from what was asked for if the server had to add
 * a printable extension (`M20` lists nothing else) - this is how the page learns what it became.
 *
 * Arrives about once a second while running, and once more, exact, when it ends.
 */
export interface SdUploadProgressed {
    file: string
    state: SdUploadState
    bytesSent: number
    bytesTotal: number
    blocksSent: number
    reason: string | null
}

/**
 * `type: "SettingsSection"` - a heading in `M503`'s report, naming the group of values under it.
 *
 * Sections and values are interleaved on the stream in firmware order, with nothing nesting one in
 * the other, so a value belongs to the most recent section ahead of it - grouping means tracking
 * that as it comes in.
 *
 * `title` can contain its own punctuation - `Acceleration (units/s2) (P<print-accel>
 * R<retract-accel> T<travel-accel>)` is one real section name - so it is rendered as text, never
 * parsed. Eleven sections on the measured build, one of which ("Material heatup parameters") holds
 * two values.
 */
export interface SettingsSection {
    title: string
}

/**
 * `type: "SettingsValue"` - one setting, as the G-code that would restore it.
 *
 * The report is G-code, deliberately: `command` is the firmware's own line - `M92 X80.00 Y80.00
 * Z400.00 E500.00` - with no parsed model on the server, since the report already carries Marlin's
 * command set and a second number model would be a second place for scale rules to drift. A control
 * wanting editable fields tokenises the text here, in the browser.
 *
 * `code` is the head (`M92`, `G21`) in the firmware's own casing, what a view switches on - not
 * unique within a report, since both `M145` preset lines carry the same code, so it is not a key.
 *
 * A trailing comment stays in `command` verbatim (`G21 ; (mm)`), so tokenising it must stop at `;`.
 */
export interface SettingsValue {
    code: string
    command: string
}

/**
 * `type: "EepromReport"` - `M504`'s verdict on the stored settings.
 *
 * A message, not a boolean, and a page must render it as one: `echo:EEPROM OK` is what MarlinSim
 * writes and the only answer ever measured - the failure text is unknown, since the simulator has
 * no way to be asked for a corrupt EEPROM, so there is nothing to derive a tick or cross from.
 */
export interface EepromReport {
    message: string
}

/**
 * `type: "UnknownCommand"` - the firmware refusing something it does not have.
 *
 * `command` is what it did not recognise, quoted back. `command === 'M503'` is how a page learns
 * this build has no settings report: Marlin's `DISABLE_M503` removes it from a firmware that still
 * stores settings fine, and `M115` says nothing about it under any spelling (checked against the
 * simulator's 38 `Cap:` lines) - unlike the EEPROM capability, this can only be learned by asking
 * and being refused.
 */
export interface UnknownCommand {
    command: string
}

/**
 * `type: "ActionRs"` - an `//action:` line, the firmware asking the host for something.
 *
 * `M500`'s confirmation arrives as one of these: `action: 'notification'`, `argument: 'Settings
 * Stored'`. `argument` is nullable, since plenty of actions carry no payload.
 */
export interface ActionRs {
    action: string
    argument: string | null
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

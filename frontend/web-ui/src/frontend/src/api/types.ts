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

/**
 * What a machine turned out to be - `api.model.MachineProfile`.
 *
 * **`capabilities` is a map of booleans, not of strings.** The server's record declares
 * `Map<String, Boolean>` and Jackson serialises `{"EEPROM": true}`; this file said
 * `Record<string, string>` for as long as nothing in TypeScript read it, so the mismatch was
 * invisible - a `capabilities[x]` compared against `'1'` would have type-checked and been false on
 * every machine there is. The Settings tab is the first thing on this side to care, and the
 * capability it cares about is read by Thymeleaf through `hasEeprom()` rather than from here, so
 * nothing broke when this was corrected.
 *
 * `known` is the server's `isKnown()` getter, which Jackson writes as a property - so there are
 * four fields on the wire and the record has three components. A **template** must call
 * `isKnown()`, because Thymeleaf reads the record and not the payload.
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

/**
 * What the slicer said about a stored file - `api.files.SliceInfo`.
 *
 * **All four values are nullable by design, and null is not zero.** A slicer writes what it feels
 * like in comments it invented and there is no standard for any of it, so a file produced by
 * something this host has never heard of carries none of them - `SliceInfo.unknown()` is a real
 * value rather than a failure, and the file is still perfectly printable. Rendering a missing
 * estimate as "0 min" would state that the print takes no time, which is a worse answer than
 * saying nobody said.
 *
 * `known` is the discriminator and it is **the server's `isKnown()` getter**, which Jackson
 * serialises as a property - so there are five fields on the wire, not four. It is true when any
 * one of the others was read. **`slice` itself is never absent and never null**: `GcodeFile`'s
 * compact constructor substitutes `SliceInfo.unknown()`, so a renderer checks `known`, not the
 * object.
 *
 * Units and types, as Jackson actually puts them on the wire:
 *
 * - `estimatedSeconds` is a JSON number - `3661`, from Java's `Long`. Seconds, never formatted.
 * - `filamentMillimetres` is a JSON number carrying the scale `BigDecimal` kept - `1234.5000` in
 *   the response text, which `JSON.parse` gives back as `1234.5`. **Millimetres**, whatever unit
 *   the file used: Cura's `;Filament used: 1.2345m` is stored as `1234.5000`. Never render it raw.
 * - `layerHeight` is a JSON number in millimetres - `0.2`.
 *
 * `format.ts` turns the first two into something a person reads.
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
 * Seven fields, matching the record's seven components. The browser's copy carried six for a
 * while, and the one it dropped was `slice` - so everything the slicer said was parsed, stored,
 * serialised and then discarded in the browser. `GcodeFileContractTest` is what notices now.
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
 * How a transfer to the card ended, which decides **what is on the card** - `SdUploadState`.
 *
 * - `CANCELLED` sent its `M29`, so the file is short but **closed**: safe to delete, not to print.
 * - `FAILED` may not have. An emergency stop preempts a transfer with no close behind it, so the
 *   file can be left **open** until the next reset.
 *
 * Showing both as "did not finish" hides the difference that matters.
 */
export type SdUploadState = 'UPLOADING' | 'DONE' | 'CANCELLED' | 'FAILED'

/**
 * `type: "SdUploadProgressed"` - how far a transfer to the card has got.
 *
 * **Two counts, and they are not interchangeable.**
 *
 * - `bytesSent / bytesTotal` is the progress bar. Exact, because the stored file's size is known
 *   before a single line goes out.
 * - `blocksSent` has **no total** on purpose. A tenth of a sliced file is comments and blank lines
 *   that are dropped rather than sent, so the file's `lineCount` is an upper bound - a bar driven
 *   by it would stop short of the end every time. Show the number; never divide by `lineCount`.
 *
 * `file` is the name on the **card**, which may not be the name that was asked for: the server
 * adds a printable extension to one that lacks it, because `M20` lists nothing else. This is how
 * the page learns what it became.
 *
 * Arrives about once a second while running, and once more - exact - when it ends.
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
 * **Sections and values are interleaved on the stream**, in the order the firmware wrote them, so a
 * value belongs to the most recent section that arrived before it. There is no nesting on the wire
 * and nothing numbers the lines: grouping the report means tracking that as it comes in.
 *
 * `title` is the heading without its punctuation, and it can contain plenty of its own -
 * `Acceleration (units/s2) (P<print-accel> R<retract-accel> T<travel-accel>)` is one real section
 * name - so it is rendered as text and never parsed.
 *
 * Eleven sections on the measured build, one of which ("Material heatup parameters") holds **two**
 * values rather than one.
 */
export interface SettingsSection {
    title: string
}

/**
 * `type: "SettingsValue"` - one setting, as the G-code that would restore it.
 *
 * **The report *is* G-code, and that is deliberate.** `command` is the firmware's own line -
 * `M92 X80.00 Y80.00 Z400.00 E500.00` - and there is no parsed model of it on the server, now or
 * later: the report already carries Marlin's command set, so a second number model would be a
 * second place for the scale rules to drift. A control that wants editable fields tokenises the
 * text here, in the browser.
 *
 * `code` is the head - `M92`, `G21` - in the firmware's own casing, and it is what a view switches
 * on to decide which setting a line is about. **It is not unique within a report**: both
 * `M145` preset lines carry the same code, so it is not a key.
 *
 * A trailing comment stays in `command` verbatim - `G21 ; (mm)` is what the firmware said - so
 * anything tokenising it has to stop at the `;`.
 */
export interface SettingsValue {
    code: string
    command: string
}

/**
 * `type: "EepromReport"` - `M504`'s verdict on the stored settings.
 *
 * **A message, not a boolean, and a page must render it as one.** `echo:EEPROM OK` is what MarlinSim
 * writes and the only answer anyone here has measured; the *failure* text is unknown, because the
 * simulator has no way to be asked for a corrupt EEPROM. So there is nothing to derive a green tick
 * or a red cross from without inventing the rule, and inventing reply formats is how this
 * repository got reply formats wrong before.
 */
export interface EepromReport {
    message: string
}

/**
 * `type: "UnknownCommand"` - the firmware refusing something it does not have.
 *
 * `command` is what it did not recognise, as the firmware quoted it back. **`command === 'M503'` is
 * how a page learns that this build has no settings report**: Marlin's `DISABLE_M503` removes it
 * from a firmware that still stores settings perfectly well, and `M115` reports nothing about it
 * under any spelling - checked against the simulator's 38 `Cap:` lines. So unlike the EEPROM
 * capability, which the server decides before rendering, this one can only be learned by asking
 * once and being refused.
 */
export interface UnknownCommand {
    command: string
}

/**
 * `type: "ActionRs"` - an `//action:` line, the firmware asking the host for something.
 *
 * `M500`'s confirmation arrives as one of these: `action: 'notification'`,
 * `argument: 'Settings Stored'`. **`argument` is nullable** - plenty of actions carry no payload -
 * so a view that renders it has to cope.
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

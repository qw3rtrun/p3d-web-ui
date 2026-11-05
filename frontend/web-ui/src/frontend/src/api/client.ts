import type {Account, ConnectionStatus, Descriptor, EventMessage, GcodeFile} from './types'

/**
 * The one place the browser talks to the server.
 *
 * Everything else in the UI goes through here, which is what keeps the three contracts below from
 * having to be remembered at every call site. All three were established by plan item D3 and all
 * three fail in misleading ways when they are missed.
 */

// ---------------------------------------------------------------- CSRF

/**
 * The token the server issued, read back out of the cookie it set.
 *
 * The farm authenticates with a **session cookie**, because `EventSource` cannot set an
 * `Authorization` header and a bearer token would therefore mean credentials in a query string.
 * Cookie authentication needs CSRF protection, and the mechanism is exactly this asymmetry: a page
 * on another origin can make the browser *send* the cookie, but cannot *read* it to build the
 * header.
 */
function csrfToken(): string | null {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
    return match ? decodeURIComponent(match[1]) : null
}

// ---------------------------------------------------------------- the session

/**
 * A 401 means the session has gone - a restart, or a timeout. Send the browser to sign in again.
 *
 * **Never render the body of a 401.** The server answers machine callers (`/api-*`, `/actuator`)
 * with a bare 401 rather than a redirect precisely so that a `fetch` does not follow the redirect
 * and receive *200 and a page of HTML*, which it would then try to parse as JSON - turning "you
 * are logged out" into an unexplained parse error.
 */
function signedOut(response: Response): boolean {
    if (response.status === 401) {
        window.location.assign('/login')
        return true
    }
    return false
}

/**
 * Thrown for a server refusal the caller is expected to show - a 403, a 400, a 404, a 409.
 *
 * The `message` is whatever `describe` could make of the body; for this application's own
 * refusals that is the server's `reason` sentence, written to be read by an operator.
 */
export class RequestFailed extends Error {
    constructor(readonly status: number, message: string) {
        super(message)
        this.name = 'RequestFailed'
    }
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
    const method = (init.method ?? 'GET').toUpperCase()
    const headers = new Headers(init.headers)

    if (method !== 'GET' && method !== 'HEAD') {
        const token = csrfToken()
        if (token) {
            headers.set('X-XSRF-TOKEN', token)
        }
    }

    const response = await fetch(path, {...init, headers, credentials: 'same-origin'})

    if (signedOut(response)) {
        // The navigation above is already under way; nothing downstream should run.
        throw new RequestFailed(401, 'signed out')
    }
    if (!response.ok) {
        throw new RequestFailed(response.status, await describe(response))
    }
    return response
}

/**
 * A server error as something worth putting in front of a person.
 *
 * **`reason` is this application's own field for a refusal, and it is read first.** A print queue
 * declining a job (`JobOutcome.Refused`) and an SD upload answering 404 or 409 both carry a
 * sentence meant for the operator - *"printer 6f0b... is not connected, so nothing can be written
 * to its card"* - under `reason`. `message` and `error` are Spring's generic fields and stay as
 * the fallback: when a body carries both, the generic one only restates the status (*"Conflict"*)
 * while `reason` says what the farm actually refused and why, so reading `message` first would
 * throw away the only part worth showing.
 */
async function describe(response: Response): Promise<string> {
    if (response.status === 403) {
        // The filter chain refused it. On a correct page this should be unreachable: it means the
        // UI offered an action this role cannot perform.
        return 'You are not allowed to do that.'
    }
    try {
        const body = await response.json() as { reason?: string; message?: string; error?: string }
        return body.reason ?? body.message ?? body.error ?? `Request failed (${response.status})`
    } catch {
        return `Request failed (${response.status})`
    }
}

async function getJson<T>(path: string): Promise<T> {
    const response = await request(path)
    return await response.json() as T
}

async function send(path: string, body: unknown, method = 'POST'): Promise<void> {
    await request(path, {
        method,
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body),
    })
}

// ---------------------------------------------------------------- the roster

export const farm = {
    /** Every machine the roster knows. */
    all: () => getJson<Descriptor[]>('/api-manager'),

    one: (uuid: string) => getJson<Descriptor>(`/api-manager/${uuid}`),

    /** ADMIN only - the server refuses anybody else. */
    add: (machine: { name: string; host: string; port: number }) =>
        send('/api-manager', machine),

    remove: (uuid: string) =>
        request(`/api-manager/${uuid}`, {method: 'DELETE'}).then(() => undefined),
}

// ---------------------------------------------------------------- one machine

/**
 * The commands one printer accepts.
 *
 * Every one of these posts a small JSON body that the server turns into G-code - the browser never
 * sends a Marlin line itself. There is deliberately no "send raw G-code" escape hatch: it would
 * hand any operator arbitrary control of a heater past every check the endpoints exist to make, so
 * a control that needs something not listed here needs a backend change rather than a workaround.
 */
export function printer(uuid: string) {
    const at = (path: string) => `/api-printer/${uuid}${path}`
    return {
        connection: () => getJson<ConnectionStatus>(at('/connection')),

        connect: () => send(at('/connect'), {connect: true}),
        disconnect: () => send(at('/connect'), {connect: false}),

        /** `index` is the hotend; this farm has one today, so it is 0. */
        setHotendTarget: (index: number, temp: number) =>
            send(at('/set-temp'), {index, temp}),

        setBedTarget: (temp: number) => send(at('/set-bed-temp'), {temp}),

        /**
         * Ask the firmware to report temperatures every `period` seconds (M155).
         *
         * The host already sends this when a socket opens, so a page does not have to - it is here
         * for changing the rate, and for a machine an earlier session left reporting at some other
         * one.
         */
        autoReportTemperature: (period: number) =>
            send(at('/auto-report-temp'), {period}),

        /** One reading, now (M105). */
        reportTemperature: () => send(at('/report-temp'), {}),

        /**
         * Send axes home (G28). Naming none homes everything, which is Marlin's own rule.
         *
         * Homing is a slow move that drags the nozzle across whatever is on the bed, so it is
         * something an operator asks for - never something another control does on their behalf.
         */
        home: (axes: { x?: boolean; y?: boolean; z?: boolean } = {}) =>
            send(at('/home'), axes),

        /**
         * Move the toolhead. `relative` is what a jog button means.
         *
         * An omitted axis is **not** zero: null is an axis the move does not mention, while zero
         * in relative mode means "do not move this one" and in absolute mode means "go to the
         * origin". Pass only the axes being moved.
         *
         * `feedRate` is Marlin's F, in millimetres per **minute**.
         */
        move: (move: {
            x?: number; y?: number; z?: number; e?: number
            feedRate?: number; relative?: boolean
        }) => send(at('/move'), move),

        /**
         * Set a part-cooling fan on Marlin's **0..255** scale, not a percentage.
         *
         * A UI shows a percentage because that is what a person reads; convert at the control, so
         * there is only ever one unit travelling to a machine that understands one.
         */
        setFanSpeed: (speed: number, index?: number) =>
            send(at('/fan'), {index: index ?? null, speed}),

        /**
         * Write the machine's settings to its EEPROM (M500).
         *
         * The one command here whose effect outlives the connection: a wrong value stored is one
         * the machine keeps after a power cycle.
         */
        storeSettings: () => send(at('/store-settings'), {}),

        /**
         * Ask what the machine's settings are (M503).
         *
         * The report arrives on the event stream as a run of `SettingsSection` and `SettingsValue`
         * events, interleaved in the order the firmware wrote them - **not** from this call. The
         * aggregate keeps the run and hands it to a late subscriber, so a tab opened after somebody
         * else pressed Read is given the report rather than an empty table; that is also why a tab
         * should not fire this on mount if anything has already arrived, or the report renders
         * twice.
         *
         * A build with `DISABLE_M503` refuses it, and the refusal is the only notice there is:
         * `UnknownCommand` with `command: 'M503'`.
         */
        readSettings: () => send(at('/settings/read'), {}),

        /**
         * Load the EEPROM's copy back over live memory (M501) - the undo for this session's edits.
         *
         * **Recoverable, so no confirmation is warranted.** What is stored stays stored; the only
         * thing lost is whatever was changed and not saved.
         *
         * M501 answers a bare `ok` and announces nothing it changed, so the server re-reads behind
         * it and the reverted values arrive on the stream by themselves. Do **not** prompt the
         * operator to press Read afterwards.
         */
        revertSettings: () => send(at('/settings/revert'), {}),

        /**
         * Load the values the firmware was compiled with (M502).
         *
         * **The destructive one, and the page must say so.** It discards everything in live memory
         * including anything just typed, and followed by `storeSettings()` it overwrites the only
         * record of every calibration the machine has ever been given. The defaults and the stored
         * settings really do differ on a measured machine, so this is no no-op.
         *
         * Like M501 it announces nothing and the server re-reads.
         */
        resetSettingsToDefaults: () => send(at('/settings/defaults'), {}),

        /**
         * Ask whether what is in the EEPROM is readable (M504).
         *
         * The verdict arrives as `EepromReport`, carrying the firmware's own message. `OK` is the
         * only text anybody has measured, so **render the message** - there is nothing to build a
         * tick or a cross out of.
         */
        validateSettings: () => send(at('/settings/validate'), {}),

        /**
         * Steps per millimetre (M92). **Live memory only** - `storeSettings()` is what persists it.
         *
         * Every setter below takes the same shape and the same rule: an omitted axis is left alone,
         * and that is not the same as zero. Send only what is being changed.
         */
        setStepsPerUnit: (axes: { x?: number; y?: number; z?: number; e?: number }) =>
            send(at('/settings/steps-per-unit'), axes),

        /** Per-axis speed ceiling (M203), in mm per **second** - not a move's mm per minute. */
        setMaxFeedrates: (axes: { x?: number; y?: number; z?: number; e?: number }) =>
            send(at('/settings/max-feedrate'), axes),

        /** Per-axis acceleration ceiling (M201), mm/s². What the hardware can take. */
        setMaxAccelerations: (axes: { x?: number; y?: number; z?: number; e?: number }) =>
            send(at('/settings/max-acceleration'), axes),

        /**
         * What a move is asked to accelerate at (M204 P/R/T), mm/s².
         *
         * Clamped by `setMaxAccelerations` - Marlin applies the lower of the two - so a value above
         * that ceiling changes nothing an operator can see.
         */
        setAccelerations: (values: { print?: number; retract?: number; travel?: number }) =>
            send(at('/settings/acceleration'), values),

        /**
         * The planner's own limits (M205 B/S/T/J).
         *
         * `minSegmentTimeUs` is microseconds and an integer; the two feedrates are mm/s;
         * `junctionDeviation` is millimetres. The jerk words the same Marlin command carries are
         * deliberately absent - a build using junction deviation ignores them while appearing to
         * take them.
         */
        setAdvancedMotion: (values: {
            minSegmentTimeUs?: number; minFeedrate?: number
            minTravelFeedrate?: number; junctionDeviation?: number
        }) => send(at('/settings/advanced'), values),

        /**
         * Where the machine thinks its origin is (M206), in millimetres.
         *
         * Added to every position after homing, so `z` is what decides how close the nozzle comes
         * to the bed - and an `M206` naming one axis must not zero the other two, which is why an
         * omitted axis is left alone here as everywhere in this group.
         */
        setHomeOffset: (axes: { x?: number; y?: number; z?: number }) =>
            send(at('/settings/home-offset'), axes),

        /**
         * Ask where the toolhead is (M114).
         *
         * The answer arrives on the event stream as `PositionRs`, not from this call. There
         * is no auto-report for position the way M155 auto-reports temperature, so ask on demand
         * and after a move - never on a timer, which fills a busy printer's command queue with
         * questions.
         */
        requestPosition: () => send(at('/position'), {}),

        /**
         * Mount the SD card (M21) - and the only way to find out whether there is one.
         *
         * No command asks "is a card present", so this is the question: a machine with a card
         * answers `SdCardOk` on the event stream, and anything else means there is none. The
         * failure text is deliberately not matched - it has never been captured, because the
         * simulator always has a card.
         */
        mountCard: () => send(at('/sd/mount'), {}),

        /**
         * Release the card (M22) so it can be pulled out.
         *
         * **Ends a print running from the card**, because the reader is the job's source. Disable
         * the control while the last `SdPrinting` seen says the machine is printing - the server
         * does not second-guess it, and Marlin will not refuse.
         */
        releaseCard: () => send(at('/sd/release'), {}),

        /**
         * Ask for the card's contents (M20).
         *
         * The listing arrives on the event stream as one `SdFileList`, assembled server-side from
         * the several lines the firmware writes. **No event means no answer** - an interrupted
         * listing produces nothing rather than a short list, so a view that asked and heard nothing
         * should say so and never render an empty table, which means a card with no files.
         *
         * Only `.gcode`, `.gco` and `.g` are listed. A file uploaded under another name is on the
         * card, is printable by exact name, and will never appear here.
         */
        listFiles: () => send(at('/sd/list'), {}),

        /**
         * Delete a file from the card (M30). Irreversible, and the machine does not confirm.
         *
         * `name` must be **the card's spelling** - what the listing reported, which on a build
         * without long filenames is DOS 8.3 in upper case - because that is what the firmware
         * matches on.
         */
        deleteFile: (name: string) => send(at('/sd/delete'), {name}),

        /**
         * Start printing a file already on the card (M23 then M24, sent as one unit).
         *
         * `name` is the card's spelling, as for `deleteFile`. Watching the print is a different
         * screen; this only starts it.
         */
        printFile: (name: string) => send(at('/sd/print'), {name}),

        /**
         * Write a file the server already holds onto the card (M28, the file, M29).
         *
         * **It resolves when the transfer has started, not when it has finished.** Every line is a
         * round trip, so a real file takes minutes. Watch the event stream for `SdUploadProgressed`
         * and treat a `state` other than `UPLOADING` as the end.
         *
         * `fileId` names something uploaded to `/api-files` first - see {@link files.upload}. An
         * extension is not required: the server adds one if it is missing, because `M20` lists only
         * printable extensions, and the first progress event reports the name it actually used.
         *
         * **The machine takes nothing else until it ends.** Disable the other controls while it
         * runs; the server enforces it anyway, but a control that appears to work and does nothing
         * is worse than one that is visibly unavailable.
         */
        uploadToCard: (fileId: string, name: string) =>
            send(at('/sd/upload'), {fileId, name}),

        /**
         * Stop a transfer that is running.
         *
         * **A stop, not a cancel.** The content ends and `M29` still goes out, so the card keeps a
         * short but closed file - which the next listing will show, and which is safe to delete and
         * not safe to print. Expect `state: 'CANCELLED'`.
         */
        cancelCardUpload: () => send(at('/sd/upload/cancel'), {}),
    }
}

// ---------------------------------------------------------------- stored files

/**
 * The server's own G-code store, which is where a file goes before it can go to a card.
 *
 * Deliberately **not multipart**: the body is the bytes and the name travels in a header, so
 * nothing has to decide where to put a part - see `GcodeFileController`. The same stored file can
 * be sent to several machines without being uploaded again.
 */
export const files = {
    all: () => getJson<GcodeFile[]>('/api-files'),

    one: (id: string) => getJson<GcodeFile>(`/api-files/${id}`),

    /**
     * Stream a file to the server and get back what it recorded - including `sizeBytes`, which is
     * the denominator an SD transfer's progress bar divides by.
     */
    upload: async (file: File): Promise<GcodeFile> => {
        const response = await request('/api-files', {
            method: 'POST',
            headers: {'X-File-Name': file.name, 'Content-Type': 'application/octet-stream'},
            body: file,
        })
        return await response.json() as GcodeFile
    },

    remove: (id: string) =>
        request(`/api-files/${id}`, {method: 'DELETE'}).then(() => undefined),
}

// ---------------------------------------------------------------- people

export const users = {
    /** ADMIN only. Never carries a password hash - see `UserController.Account`. */
    all: () => getJson<Account[]>('/api-users'),
    add: (account: { username: string; password: string; role: string }) =>
        send('/api-users', account),
    changePassword: (username: string, password: string) =>
        send(`/api-users/${encodeURIComponent(username)}/password`, {password}, 'PUT'),
    remove: (username: string) =>
        request(`/api-users/${encodeURIComponent(username)}`, {method: 'DELETE'})
            .then(() => undefined),
}

// ---------------------------------------------------------------- streams

type Listener = (message: EventMessage) => void

/**
 * A subscription that can be closed, which is the only kind this UI hands out.
 *
 * A component that subscribes must release it in `onUnmounted` - the same rule the backend applies
 * to sockets and reactors.
 */
export interface Subscription {
    close(): void
}

function subscribe(url: string, onMessage: Listener): Subscription {
    const source = new EventSource(url, {withCredentials: true})

    const listener = (e: MessageEvent) => {
        try {
            onMessage(JSON.parse(e.data) as EventMessage)
        } catch (error) {
            console.warn(`{${url}} unreadable message`, error)
        }
    }
    source.addEventListener('message', listener)
    source.addEventListener('error', () => {
        // EventSource reconnects by itself; a closed one means the server refused us, which after
        // a session expiry is what a logged-out browser gets.
        if (source.readyState === EventSource.CLOSED) {
            console.warn(`{${url}} closed by the server`)
        }
    })

    return {
        close() {
            source.removeEventListener('message', listener)
            source.close()
        },
    }
}

/**
 * Every machine's events on **one** subscription - plan item D2.
 *
 * Opening one of these per printer instead is the mistake that item exists to prevent:
 * `/api-printer/{id}/stream` instantiates a reactor as a side effect, so forty rows on a dashboard
 * would dial forty machines nobody asked to connect. This one opens nothing.
 *
 * It begins with a **snapshot** - the current state of every machine - so a page can draw itself
 * from the stream alone rather than listing first and subscribing after, which leaves a gap where
 * an event goes missing.
 */
export function watchFarm(onMessage: Listener): Subscription {
    return subscribe('/api-farm/stream', onMessage)
}

/** One machine, for a page that is looking at exactly that machine. */
export function watchPrinter(uuid: string, onMessage: Listener): Subscription {
    return subscribe(`/api-printer/${uuid}/stream`, onMessage)
}

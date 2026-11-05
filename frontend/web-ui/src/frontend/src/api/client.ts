import type {Account, ConnectionStatus, Descriptor, EventMessage, GcodeFile} from './types'

/**
 * The one place the browser talks to the server - everything else in the UI goes through here, so
 * the three contracts below (plan item D3) need remembering only once:
 *
 * - **CSRF**: the farm authenticates with a session cookie, since `EventSource` cannot set an
 *   `Authorization` header. A page on another origin can make the browser send that cookie but
 *   cannot read it to build the `X-XSRF-TOKEN` header, which is the whole of why that header exists.
 * - **401**: never render its body. The server answers machine callers (`/api-*`, `/actuator`) with
 *   a bare 401 rather than a redirect, specifically so a `fetch` does not follow it to 200 and a
 *   page of HTML it would try to parse as JSON.
 * - **One stream for the farm** (plan item D2): `watchFarm` below opens one `EventSource`, never one
 *   per printer - `/api-printer/{id}/stream` instantiates a reactor server-side as a side effect.
 */

// ---------------------------------------------------------------- CSRF

function csrfToken(): string | null {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
    return match ? decodeURIComponent(match[1]) : null
}

// ---------------------------------------------------------------- the session

// A 401 means the session has gone - a restart or a timeout. Send the browser to sign in again.
function signedOut(response: Response): boolean {
    if (response.status === 401) {
        window.location.assign('/login')
        return true
    }
    return false
}

// Thrown for a server refusal the caller is expected to show - 403, 400, 404, 409. `message` is
// whatever `describe` made of the body, which for this application's own refusals is `reason`.
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

// `reason` is this application's own field for a refusal and is read first: a print queue
// declining a job or an SD upload answering 404/409 both carry an operator-facing sentence there.
// `message`/`error` are Spring's generic fallback fields, which otherwise only restate the status
// ("Conflict") and would throw away the part worth showing.
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

// The commands one printer accepts. Every one posts a small JSON body the server turns into
// G-code - the browser never sends a Marlin line itself, and deliberately has no "send raw G-code"
// escape hatch: a control needing something not listed here needs a backend change, not a workaround.
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

        // M155. The host already sends this when a socket opens; this is for changing the rate.
        autoReportTemperature: (period: number) =>
            send(at('/auto-report-temp'), {period}),

        // One reading, now (M105).
        reportTemperature: () => send(at('/report-temp'), {}),

        // G28. Naming no axis homes everything (Marlin's own rule) - a slow move that drags the
        // nozzle across the bed, so it is the operator's call, never issued on their behalf.
        home: (axes: { x?: boolean; y?: boolean; z?: boolean } = {}) =>
            send(at('/home'), axes),

        // relative is what a jog button means. An omitted axis is not zero - pass only the axes
        // being moved. feedRate is Marlin's F, in mm per minute.
        move: (move: {
            x?: number; y?: number; z?: number; e?: number
            feedRate?: number; relative?: boolean
        }) => send(at('/move'), move),

        // Marlin's 0..255 scale, not a percentage - convert at the control.
        setFanSpeed: (speed: number, index?: number) =>
            send(at('/fan'), {index: index ?? null, speed}),

        // M500. The one command here whose effect outlives the connection.
        storeSettings: () => send(at('/store-settings'), {}),

        // M503. The report arrives on the event stream as interleaved SettingsSection/SettingsValue
        // events, not from this call - a tab opened late still gets it from the aggregate's kept
        // run, so a tab must not fire this again if a report has already arrived. DISABLE_M503
        // refuses with UnknownCommand({command: 'M503'}).
        readSettings: () => send(at('/settings/read'), {}),

        // M501, the undo for this session's edits - recoverable, so no confirmation is warranted.
        // Answers a bare ok with no detail, so the server re-reads and the reverted values arrive
        // on the stream; do not prompt the operator to press Read afterwards.
        revertSettings: () => send(at('/settings/revert'), {}),

        // M502, the destructive one: discards live memory, and followed by storeSettings()
        // overwrites every stored calibration - the defaults and stored settings really do differ
        // on a measured machine. Like M501 it announces nothing and the server re-reads.
        resetSettingsToDefaults: () => send(at('/settings/defaults'), {}),

        // M504. The verdict arrives as EepromReport carrying the firmware's own message; "OK" is
        // the only text ever measured, so render the message rather than a tick/cross.
        validateSettings: () => send(at('/settings/validate'), {}),

        // M92, live memory only (storeSettings() persists it). Every setter below shares this
        // shape: an omitted axis is left alone, not zeroed - send only what is being changed.
        setStepsPerUnit: (axes: { x?: number; y?: number; z?: number; e?: number }) =>
            send(at('/settings/steps-per-unit'), axes),

        // M203, mm/second - not a move's mm per minute.
        setMaxFeedrates: (axes: { x?: number; y?: number; z?: number; e?: number }) =>
            send(at('/settings/max-feedrate'), axes),

        // M201, mm/s^2 - what the hardware can take.
        setMaxAccelerations: (axes: { x?: number; y?: number; z?: number; e?: number }) =>
            send(at('/settings/max-acceleration'), axes),

        // M204 P/R/T, mm/s^2 - clamped by setMaxAccelerations (Marlin applies the lower of the two).
        setAccelerations: (values: { print?: number; retract?: number; travel?: number }) =>
            send(at('/settings/acceleration'), values),

        // M205 B/S/T/J. minSegmentTimeUs is microseconds (integer); the feedrates are mm/s;
        // junctionDeviation is millimetres. The jerk words the same command carries are deliberately
        // absent - a junction-deviation build ignores them while appearing to take them.
        setAdvancedMotion: (values: {
            minSegmentTimeUs?: number; minFeedrate?: number
            minTravelFeedrate?: number; junctionDeviation?: number
        }) => send(at('/settings/advanced'), values),

        // M206, millimetres, added to every position after homing - z decides how close the nozzle
        // comes to the bed. An omitted axis is left alone, as everywhere in this group.
        setHomeOffset: (axes: { x?: number; y?: number; z?: number }) =>
            send(at('/settings/home-offset'), axes),

        // M114. The answer arrives on the stream as PositionRs, not from this call - ask on demand
        // and after a move, never on a timer, which fills a busy printer's queue with questions.
        requestPosition: () => send(at('/position'), {}),

        // M21, also the only way to learn whether a card exists: success is SdCardOk on the stream,
        // anything else means none. The failure text is unmatched since it has never been
        // captured - the simulator always has a card.
        mountCard: () => send(at('/sd/mount'), {}),

        // M22. Ends a print running from the card, since the reader is the job's source - disable
        // the control while the last status seen was SdPrinting; the server does not second-guess it.
        releaseCard: () => send(at('/sd/release'), {}),

        // M20. The listing arrives on the stream as one SdFileList. No event means no answer - an
        // interrupted listing produces nothing, not a short list, so render that as unknown rather
        // than an empty card. Only .gcode/.gco/.g are listed; other names are on the card but hidden.
        listFiles: () => send(at('/sd/list'), {}),

        // M30, irreversible and unconfirmed by the machine. name must be the card's spelling (DOS
        // 8.3 upper case without long-filename support) - what the listing reported.
        deleteFile: (name: string) => send(at('/sd/delete'), {name}),

        // M23 then M24, sent as one unit. name is the card's spelling, as for deleteFile.
        printFile: (name: string) => send(at('/sd/print'), {name}),

        // M28, the file, M29. Resolves when the transfer has started, not finished - watch the
        // stream for SdUploadProgressed and treat any state but UPLOADING as the end. fileId names
        // something uploaded to /api-files first (see files.upload); an extension is not required,
        // since the server adds one if missing and the first progress event names what it became.
        // The machine takes nothing else until M29 - disable other controls while it runs.
        uploadToCard: (fileId: string, name: string) =>
            send(at('/sd/upload'), {fileId, name}),

        // A stop, not a cancel: the content ends but M29 still goes out, leaving a short closed file
        // (safe to delete, not to print). Expect state: 'CANCELLED'.
        cancelCardUpload: () => send(at('/sd/upload/cancel'), {}),
    }
}

// ---------------------------------------------------------------- stored files

// The server's own G-code store, where a file goes before it can go to a card. Deliberately not
// multipart: the body is the bytes and the name travels in a header (see GcodeFileController), so
// the same stored file can be sent to several machines without being uploaded again.
export const files = {
    all: () => getJson<GcodeFile[]>('/api-files'),

    one: (id: string) => getJson<GcodeFile>(`/api-files/${id}`),

    // sizeBytes in the response is the denominator an SD transfer's progress bar divides by.
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

// A component that subscribes must release this in onUnmounted - the same rule the backend applies
// to sockets and reactors.
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

// Every machine's events on one subscription (plan item D2) - opening one per printer instead is
// the mistake this prevents, since /api-printer/{id}/stream instantiates a reactor as a side
// effect. Begins with a snapshot of every machine, so a page draws itself from the stream alone
// rather than listing first and subscribing after, which leaves a gap where an event goes missing.
export function watchFarm(onMessage: Listener): Subscription {
    return subscribe('/api-farm/stream', onMessage)
}

/** One machine, for a page that is looking at exactly that machine. */
export function watchPrinter(uuid: string, onMessage: Listener): Subscription {
    return subscribe(`/api-printer/${uuid}/stream`, onMessage)
}

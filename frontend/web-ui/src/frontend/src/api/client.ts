import type {Account, ConnectionStatus, Descriptor, EventMessage} from './types'

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

/** Thrown for a server refusal the caller is expected to show - a 403, a 400, a 409. */
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

/** A server error as something worth putting in front of a person. */
async function describe(response: Response): Promise<string> {
    if (response.status === 403) {
        // The filter chain refused it. On a correct page this should be unreachable: it means the
        // UI offered an action this role cannot perform.
        return 'You are not allowed to do that.'
    }
    try {
        const body = await response.json() as { message?: string; error?: string }
        return body.message ?? body.error ?? `Request failed (${response.status})`
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
 * **This is the whole list, and it is temperature only.** Homing, moving an axis, fan speed and
 * saving settings exist in `:gcode` but have no application command yet, so there is nothing here
 * to call. They want a `PrinterCommand` record in `api::msg`, an endpoint, and an arm in
 * `PrinterState.onCommand` - a backend change. There is deliberately no "send raw G-code" escape
 * hatch: it would hand any operator arbitrary control of a heater past every check the command
 * model exists to make.
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
    }
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

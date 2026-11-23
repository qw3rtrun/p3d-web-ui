import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import type {EventMessage} from './types'

/**
 * `client.ts`'s two per-uuid pools (#166): `watchPrinter`'s shared `EventSource` and
 * `printer(uuid).connection()`'s shared in-flight request. Both exist because a Monitor page is
 * several islands watching the same machine, mounting at once (Bootstrap keeps every tab pane in
 * the DOM from load) and each independently calling the same client function.
 *
 * `EventSource` does not exist in this test's `node` environment, so a minimal stand-in is stubbed
 * onto the global before each test - just enough of the real API (`addEventListener`,
 * `removeEventListener`, `close`, `readyState`) for the pool to drive, plus an `emit` helper a test
 * uses to play a server message. `vi.resetModules()` between tests is what gives each one a fresh
 * pool: the real module keeps its maps at module scope for the lifetime of the page, which a test
 * run must not let leak from one `it` into the next.
 */

class MockEventSource {
    static readonly CLOSED = 2
    static instances: MockEventSource[] = []

    readyState = 1
    private readonly listeners = new Map<string, Set<(event: MessageEvent) => void>>()

    constructor(readonly url: string | URL, readonly init?: EventSourceInit) {
        MockEventSource.instances.push(this)
    }

    addEventListener(type: string, listener: (event: MessageEvent) => void): void {
        let forType = this.listeners.get(type)
        if (!forType) {
            forType = new Set()
            this.listeners.set(type, forType)
        }
        forType.add(listener)
    }

    removeEventListener(type: string, listener: (event: MessageEvent) => void): void {
        this.listeners.get(type)?.delete(listener)
    }

    close(): void {
        this.readyState = MockEventSource.CLOSED
    }

    // Test-only: play a message event to every listener this mock has, exactly as a real
    // EventSource would if the server sent one.
    emit(message: EventMessage): void {
        const event = {data: JSON.stringify(message)} as MessageEvent
        for (const listener of this.listeners.get('message') ?? []) {
            listener(event)
        }
    }
}

beforeEach(() => {
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    vi.resetModules()
})

afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
})

describe('watchPrinter', () => {
    it('shares one EventSource across two subscribers to the same uuid', async () => {
        const {watchPrinter} = await import('./client')
        const received1: EventMessage[] = []
        const received2: EventMessage[] = []

        watchPrinter('printer-1', m => received1.push(m))
        watchPrinter('printer-1', m => received2.push(m))

        // The mock was constructed exactly once across both subscribe calls.
        expect(MockEventSource.instances).toHaveLength(1)

        const message: EventMessage = {id: 'printer-1', type: 'ConnectionStateChanged', event: {}}
        MockEventSource.instances[0].emit(message)

        expect(received1).toEqual([message])
        expect(received2).toEqual([message])
    })

    it('leaves the connection open and the other subscriber receiving until the last one closes', async () => {
        const {watchPrinter} = await import('./client')
        const received1: EventMessage[] = []
        const received2: EventMessage[] = []

        const sub1 = watchPrinter('printer-1', m => received1.push(m))
        const sub2 = watchPrinter('printer-1', m => received2.push(m))
        const source = MockEventSource.instances[0]

        sub1.close()
        expect(source.readyState).not.toBe(MockEventSource.CLOSED)

        const message: EventMessage = {id: 'printer-1', type: 'ConnectionStateChanged', event: {}}
        source.emit(message)

        // The closed subscriber's own listener is gone; the other one, still open, still gets it.
        expect(received1).toHaveLength(0)
        expect(received2).toEqual([message])

        sub2.close()
        expect(source.readyState).toBe(MockEventSource.CLOSED)
    })

    it('opens a separate connection per uuid', async () => {
        const {watchPrinter} = await import('./client')

        watchPrinter('printer-a', () => {
        })
        watchPrinter('printer-b', () => {
        })

        expect(MockEventSource.instances).toHaveLength(2)
    })

    it('opens a fresh connection if a later subscriber arrives after the last one closed', async () => {
        const {watchPrinter} = await import('./client')

        const first = watchPrinter('printer-1', () => {
        })
        first.close()
        expect(MockEventSource.instances[0].readyState).toBe(MockEventSource.CLOSED)

        watchPrinter('printer-1', () => {
        })
        expect(MockEventSource.instances).toHaveLength(2)
    })
})

describe('printer(uuid).connection()', () => {
    it('coalesces two concurrent callers into one request', async () => {
        const fetchMock = vi.fn(async () =>
            new Response(JSON.stringify({state: 'ONLINE', attempt: 0, reason: null}), {status: 200}))
        vi.stubGlobal('fetch', fetchMock)
        // client.ts reads the CSRF cookie unconditionally; absent on GET it is simply not sent.
        vi.stubGlobal('document', {cookie: ''})

        const {printer} = await import('./client')
        const api = printer('printer-1')

        const [a, b] = await Promise.all([api.connection(), api.connection()])

        expect(fetchMock).toHaveBeenCalledTimes(1)
        expect(a).toEqual(b)
    })

    it('makes a fresh request once the in-flight one has settled', async () => {
        const fetchMock = vi.fn(async () =>
            new Response(JSON.stringify({state: 'ONLINE', attempt: 0, reason: null}), {status: 200}))
        vi.stubGlobal('fetch', fetchMock)
        vi.stubGlobal('document', {cookie: ''})

        const {printer} = await import('./client')
        const api = printer('printer-1')

        await api.connection()
        await api.connection()

        expect(fetchMock).toHaveBeenCalledTimes(2)
    })
})

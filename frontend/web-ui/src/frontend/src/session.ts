import {computed, ref, type ComputedRef} from 'vue'
import {SESSION_STARTED, type EventMessage} from './api/types'

/**
 * One rule for every panel whose contents came out of the printer's *last session*: the settings
 * report, the card listing, and whatever is added to `PrinterState.snapshot()` next.
 *
 * The server forgets everything it knew about a machine the moment a new link comes up, and now
 * says so - `SessionStarted` on `/api-printer/{uuid}/stream`, exactly once per established link,
 * ahead of the `ConnectionStateChanged` that announces the link itself. Why that matters here:
 * **what comes back on the same address may be a different firmware** (a reflash, a cable moved),
 * so values held from before are not merely old, they may describe a machine that no longer exists.
 *
 * It replaces a guess this layer used to make. `SettingsTab.vue` cleared its table on
 * `ConnectionStateChanged.to === 'OFFLINE'`, but a link that drops while a connection is still
 * wanted is retried rather than ended - `ConnectionLifecycle.linkFailed` consults the intent, not
 * the socket - so the journey across a pulled cable is `IDLE -> CONNECTING -> ONLINE -> IDLE` with
 * no `OFFLINE` in it (pinned by `ConnectivityIntegrationTest
 * .aDroppedLinkThatComesBackNeverAnnouncesOffline`). The guard fired only on a deliberate
 * disconnect, which is the case that does not matter. **Infer a session boundary from nothing but
 * this event.**
 *
 * What a panel does with the mark is settled: it **keeps showing the values, saying they are from
 * before**, and it **never re-reads by itself**. Blanking a table on a momentary bounce is jarring
 * and throws away the last true thing the machine said; auto-reading would be worse - `POST
 * /api-printer/**` is `OPERATOR`/`ADMIN`, so it would 403 for the viewer who most needs the mark,
 * and N open tabs would put N `M503`/`M20` bursts on one serial line. The existing Read / Refresh
 * button is already role- and liveness-gated; a person presses it.
 *
 * Usage, which is the whole of what a third panel has to write - destructured, so `stale` is a
 * top-level binding and unwraps itself in the template:
 *
 * ```ts
 * const {stale, observe, renewed} = useSessionScope(props.uuid, () => edits.clear())
 * function onMessage(message: EventMessage) {
 *     observe(message)                   // marks on SessionStarted for this uuid, ignores the rest
 *     ...
 *     case 'SdFileList': renewed(); ...  // data for the session now running - drop the mark
 * }
 * ```
 *
 * ```html
 * <PreviousSession v-if="stale" what="This listing"/>
 * ```
 *
 * The callback is for what is *not* last-known truth and so cannot be marked and kept - an
 * operator's half-typed edit is an instruction aimed at a session that is over, a pending read is a
 * question nobody will now answer. Each panel knows its own; the rule above is not restated.
 */
export interface SessionScope {
    /** Whether what the panel is holding came from a session that has since ended. */
    stale: ComputedRef<boolean>

    /** Every message off the printer stream goes through here; it filters by uuid itself. */
    observe(message: EventMessage): void

    /** Data for the session now running has arrived (or been asked for afresh) - drop the mark. */
    renewed(): void
}

export function useSessionScope(uuid: string, onEnded?: () => void): SessionScope {
    const stale = ref(false)

    return {
        stale: computed(() => stale.value),

        observe(message: EventMessage) {
            if (message.id !== uuid || message.type !== SESSION_STARTED) {
                return
            }
            stale.value = true
            onEnded?.()
        },

        renewed() {
            stale.value = false
        },
    }
}

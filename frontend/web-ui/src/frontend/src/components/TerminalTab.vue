<script setup lang="ts">
import {computed, nextTick, onMounted, onUnmounted, ref} from 'vue'
import {printer, RequestFailed, watchTerminal, type Subscription} from '../api/client'
import type {EventMessage, FarmRole, TerminalDirection, TerminalTraffic} from '../api/types'
import {TERMINAL_TRAFFIC} from '../api/types'
import {wireTime} from '../format'

/**
 * The machine's console: every line that crossed the wire, and a box to type one into (#135).
 *
 * Unlike Storage and Settings this tab is gated on no capability - every machine has a wire, so
 * `monitor.html` always renders it and always mounts this island. It is the only tab that opens
 * `/api-printer/{uuid}/terminal/stream`, a genuinely different endpoint from the monitor stream
 * rather than a second copy of it: it carries the lines no decoder claimed and the acknowledgements
 * the monitor stream withholds as flow control, at a volume that has no business on a stream the
 * dashboard fans out.
 *
 * **Mounted is not connected** (#162). The island still mounts with the page, unchanged; the
 * `EventSource` now opens lazily, the first time the Terminal pane is actually shown, and stays open
 * for the rest of the page's life. Opening it at mount meant a Monitor page held two permanently-open
 * SSE connections - this one and `PrinterMonitor.vue`'s - so two browser tabs on the same printer held
 * four, forever, since an SSE connection never frees its slot. This app serves plain HTTP/1.1 (no TLS,
 * no h2c anywhere in it), where a browser's own per-origin connection cap is about six in Chrome, and
 * four plus a second tab's page load exceeds it: whatever queues behind them queues for good, which is
 * the hang. Deferring is always safe because `#overview` is `monitor.html`'s `show active` pane and
 * `#terminal` is never the default.
 *
 * It is deliberately not closed again when the operator switches away. The server's terminal sink is a
 * `replay().limit(256)`, so every resubscription replays up to 256 lines that `lines` already holds and
 * the transcript would duplicate on each revisit; reconciling that buys one connection back while the
 * pane is hidden, which #162 does not need.
 *
 * Three decisions a later reader will otherwise take for defects and "fix":
 *
 * **Nothing is persisted and nothing is paged** (#135 point 3). The live session is the whole of it;
 * the server replays its last 256 lines on connect - the one stream in this farm that replays
 * anything - which is why every line shows its time: the first burst after connecting is history,
 * and without a clock on it a replayed banner reads as a machine that just booted.
 *
 * **The DOM buffer is capped and the oldest lines are dropped** ({@link KEEP}). An eight-hour print
 * is millions of lines, so an uncapped transcript is an unbounded leak in a tab somebody left open.
 *
 * **Auto-scroll stops while the operator has scrolled up.** A console that yanks the view back to
 * the end every time a line arrives cannot be read backwards, which is the one thing a protocol log
 * is for - so following the tail is a mode (`pinned`), entered by scrolling to the bottom and left
 * by scrolling away from it, with a button to come back. The pane is also hidden at mount, where a
 * zero-height box cannot be scrolled at all, so Bootstrap's `shown.bs.tab` is the other moment the
 * tail is caught up with.
 *
 * `decodedAs` being null is **normal, not a failure**: 10 of 106 received lines in the measured
 * session (the simulator's whole boot banner) reach no decoder. Such a line is shown with no badge -
 * never as "null" or "unknown" - because the raw line is the content and the decode is an annotation.
 *
 * The input is offered to an OPERATOR or ADMIN only, from `data-role` as every other write control on
 * this page reads it; the server is the authority and answers a VIEWER's POST with 403. It is not
 * gated on the connection state, which this stream does not carry: a line sent with no link comes
 * back as a 409 whose `reason` says exactly that, and a second guess here would go stale. A refusal
 * keeps what was typed, since a malformed line is something to correct rather than retype.
 */

const props = withDefaults(defineProps<{
    uuid: string
    role?: FarmRole
}>(), {role: 'VIEWER'})

const mayType = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')

// Four times the server's replay, so a live session has room beyond its own scrollback, and small
// enough that the transcript stays a plain `v-for` rather than wanting virtual scrolling.
const KEEP = 1000

// How many recalled lines Up/Down walks back through.
const RECALL = 50

// How close to the end counts as "at the end". A couple of lines' slack, because a browser's
// fractional scroll heights mean an exact comparison is false on a view that looks pinned.
const AT_THE_END_PX = 24

// How each direction reads at a glance, which is the single most important thing about a protocol
// log. Said twice on purpose: an arrow alone does not survive scanning a hundred lines, and a colour
// alone does not survive a colour-blind reader.
// `text` is the line's own colour and is empty for RECEIVED deliberately: received lines are the
// wall, so tinting the far rarer sent ones is what makes them findable in it.
const LOOK: Record<TerminalDirection, { arrow: string; tone: string; text: string; what: string }> = {
    SENT: {arrow: '→', tone: 'text-primary', text: 'text-primary', what: 'Sent to the machine'},
    RECEIVED: {arrow: '←', tone: 'text-success', text: '', what: 'Received from the machine'},
}

// One rendered line. `key` is a counter because nothing on the wire is unique - two lines can share
// a millisecond, and the same text recurs all session.
interface Line extends TerminalTraffic {
    key: number
}

const lines = ref<Line[]>([])
let counter = 0

const scroller = ref<HTMLElement | null>(null)
const pinned = ref(true)

const draft = ref('')
const sending = ref(false)
const failure = ref<string | null>(null)

const api = printer(props.uuid)

const recalled = ref<string[]>([])
// Where Up/Down has walked to; -1 is a fresh line rather than a recalled one.
let walked = -1

function atTheEnd(box: HTMLElement): boolean {
    return box.scrollHeight - box.scrollTop - box.clientHeight <= AT_THE_END_PX
}

function onScroll() {
    const box = scroller.value
    if (box) {
        pinned.value = atTheEnd(box)
    }
}

async function toTheEnd() {
    pinned.value = true
    await nextTick()
    const box = scroller.value
    if (box) {
        box.scrollTop = box.scrollHeight
    }
}

function append(traffic: TerminalTraffic) {
    lines.value.push({...traffic, key: counter++})
    if (lines.value.length > KEEP) {
        lines.value.splice(0, lines.value.length - KEEP)
    }
    if (pinned.value) {
        void toTheEnd()
    }
}

function onMessage(message: EventMessage) {
    if (message.id === props.uuid && message.type === TERMINAL_TRAFFIC) {
        append(message.event as TerminalTraffic)
    }
}

function submit() {
    const line = draft.value.trim()
    if (line === '' || sending.value) {
        return
    }
    sending.value = true
    void (async () => {
        try {
            await api.sendLine(line)
            // What went out arrives on the stream, framed - nothing is echoed here.
            draft.value = ''
            walked = -1
            if (recalled.value[0] !== line) {
                recalled.value.unshift(line)
                recalled.value.splice(RECALL)
            }
            failure.value = null
            // Typing is following the conversation, so the transcript jumps back to the tail.
            void toTheEnd()
        } catch (error) {
            // The draft is deliberately left alone: 400 names the offending character and its
            // position, which is an instruction to fix the line, not to type it again.
            if (error instanceof RequestFailed && error.status !== 401) {
                failure.value = error.message
            }
        } finally {
            sending.value = false
        }
    })()
}

// Up walks back through what was sent, Down returns towards the empty line - the shell behaviour,
// since a console's commands are mostly re-sent with one character changed.
function recall(steps: number) {
    const history = recalled.value
    if (history.length === 0) {
        return
    }
    const next = walked + steps
    if (next < 0) {
        walked = -1
        draft.value = ''
        return
    }
    walked = Math.min(next, history.length - 1)
    draft.value = history[walked]
}

let subscription: Subscription | null = null

// Bootstrap fires this on the tab's anchor and it bubbles; the pane is `display: none` until then,
// and a box with no height has nowhere to scroll, so a replayed scrollback would sit at the top.
// This is also where the stream is opened, once - see the note on mounted versus connected above.
function onTabShown(event: Event) {
    const shown = (event.target as HTMLElement | null)?.getAttribute('href')
    if (shown !== '#terminal') {
        return
    }
    if (subscription === null) {
        subscription = watchTerminal(props.uuid, onMessage)
    }
    if (pinned.value) {
        void toTheEnd()
    }
}

onMounted(() => {
    document.addEventListener('shown.bs.tab', onTabShown)
})

onUnmounted(() => {
    subscription?.close()
    subscription = null
    document.removeEventListener('shown.bs.tab', onTabShown)
})
</script>

<template>
  <div class="pt-3">
    <div class="d-sm-flex justify-content-between align-items-start mb-3">
      <div>
        <h4 class="card-title mb-1">Terminal</h4>
        <p class="card-subtitle mb-0">
          Every line that crossed the wire, in both directions. This session only - nothing here is
          stored, and closing the page loses it.
        </p>
      </div>
      <div class="d-flex gap-2">
        <button v-if="!pinned" type="button" class="btn btn-sm btn-outline-primary"
                @click="toTheEnd()">Jump to latest
        </button>
        <span v-else class="badge bg-light text-dark align-self-center">following</span>
      </div>
    </div>

    <div v-if="failure" class="alert alert-warning py-2" role="alert">{{ failure }}</div>

    <!-- `pre-wrap` on the text, so a long line wraps without losing the firmware's own spacing. -->
    <div ref="scroller" class="border rounded bg-light p-2 overflow-auto font-monospace small"
         style="height: 26rem;" role="log" aria-label="Printer console" @scroll.passive="onScroll">
      <p v-if="lines.length === 0" class="text-muted mb-0">
        Nothing has crossed the wire yet. A connected machine replays its last lines here as soon as
        there are any.
      </p>
      <div v-for="line in lines" :key="line.key" class="d-flex gap-2">
        <span class="text-muted flex-shrink-0">{{ wireTime(line.at) }}</span>
        <span class="flex-shrink-0 fw-bold" :class="LOOK[line.direction].tone"
              :title="LOOK[line.direction].what">{{ LOOK[line.direction].arrow }}</span>
        <span class="flex-grow-1" :class="LOOK[line.direction].text"
              style="white-space: pre-wrap; word-break: break-word;">{{ line.raw }}</span>
        <!-- No badge at all when nothing claimed the line: see the script's note on `decodedAs`. -->
        <span v-if="line.decodedAs" class="badge bg-white text-muted fw-normal flex-shrink-0 align-self-start"
              title="What the host decoded this line as">{{ line.decodedAs }}</span>
      </div>
    </div>

    <!-- A SENT line comes back framed (`N12 M105*31`), which is what went out rather than what was
         typed here - the N word and the checksum are the send window's. -->
    <form v-if="mayType" class="mt-3" @submit.prevent="submit()">
      <div class="input-group">
        <span class="input-group-text font-monospace">&gt;</span>
        <input v-model="draft" type="text" class="form-control font-monospace"
               placeholder="M105" aria-label="A line of G-code to send"
               autocomplete="off" spellcheck="false" :disabled="sending"
               @keydown.up.prevent="recall(1)" @keydown.down.prevent="recall(-1)"/>
        <button type="submit" class="btn btn-primary text-white"
                :disabled="sending || draft.trim() === ''">Send
        </button>
      </div>
      <p class="text-muted small mt-1 mb-0">
        One line, as typed - no line number and no checksum, which the host adds. Enter sends it; Up
        and Down walk back through this session's lines. A line the host will not put on a wire is
        refused with the reason above, and what is typed is kept so it can be corrected.
      </p>
    </form>
    <p v-else class="text-muted small mt-3 mb-0">
      Your role can watch this console but not type into it.
    </p>
  </div>
</template>

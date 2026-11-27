<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import {printer, RequestFailed, watchPrinter, type Subscription} from '../api/client'
import type {
    ConnectionState,
    ConnectionStateChanged,
    EventMessage,
    FarmRole,
    PositionRs,
} from '../api/types'
import {isUsable} from '../api/types'
import {useSessionScope} from '../session'

/**
 * Manual bed levelling: home, then send the nozzle to each bed corner at a height where a person can
 * feel the gap and turn the screw under it.
 *
 * **Not auto-levelling.** `G29`, `M420` and `M290` all answer `echo:Unknown command` on the firmware
 * this repository measures against, so there is nothing to call and nothing is stubbed for them
 * (issue #174 owns that). In particular the Z jog below is an ordinary relative `move`, not
 * babystepping: a real axis move is what judging a nozzle-to-bed gap needs, and it is also the only
 * one this firmware has.
 *
 * **Homing is enforced, because the machine cannot be asked whether it is homed.** `M114` answers a
 * plausible-looking position before any `G28`, with no flag saying it is untrusted -
 *
 * ```
 * >> M114        (before any G28)
 * << X:0.00 Y:0.00 Z:5.00 E:0.00 Count X:0 Y:0 Z:2000
 * ```
 *
 * - so an absolute move against that origin goes somewhere nobody chose. What *is* observable is
 * that `G28` answers a `PositionRs` of its own immediately before its `ok`:
 *
 * ```
 * >> G28
 * << echo:busy: processing
 * << echo:busy: processing
 * << echo:busy: processing
 * << X:0.00 Y:0.00 Z:5.00 E:0.00 Count X:0 Y:0 Z:2000
 * << ok P15 B3
 * ** 5 line(s) in 6778 ms; ended with an ok: true
 * ```
 *
 * So `homed` turns true on the first `PositionRs` after the operator pressed Home, and the sequence
 * controls are dead until then. 6.8 s is the measured duration of that one command, hence the
 * "Homing…" state rather than a button that looks stuck. `homed` is this host's belief rather than a
 * firmware fact - the badge's own `title` says as much to the operator - and it is dropped again the
 * moment the link stops being usable, since a machine that halted or lost its link part-way through a
 * `G28` is not homed.
 *
 * **The 30 mm indent is UNMEASURED.** It is Marlin's own `LEVEL_CORNERS_INSET_LFRB` default,
 * `{30,30,30,30}` in `Configuration_adv.h` - documentation, not a measurement. Nothing in this
 * repository declares a reachable area or a probe margin (searched: there is no indent convention to
 * reuse), and nothing on the wire says where a bed's levelling screws are. A percentage of bed size
 * was considered and rejected: it would put a 120 mm bed's corners 12 mm in - off the screw and near
 * the edge - and a 350 mm bed's 35 mm in, while screw spacing barely varies with bed size. So it is
 * an operator input with a documented default, clamped only so the two corners on an axis cannot
 * cross.
 *
 * **Check Z defaults to 0.2 mm and that deliberately deviates from Marlin's `LEVEL_CORNERS_HEIGHT`
 * of 0.0.** Marlin's wizard is driven by somebody standing at the machine; this one is driven from a
 * browser that may be nowhere near it, and on a machine whose Z home offset is wrong 0 mm digs into
 * the bed while 0.2 mm scrapes. 0 is still reachable by typing it.
 *
 * One stop is one request (see `moveToBedCorner` in `client.ts` for why the three moves it carries
 * must not be three calls). The answer to `requestPosition` arrives on the stream as `PositionRs`,
 * asked after each move and never on a timer - Marlin has no position auto-report, and a timer would
 * compete with a print for the planner. Those axes are the planner's position, so a reading can name
 * the corner before the nozzle has finished travelling to it.
 *
 * Z moves at 600 mm/min where XY moves at 3000: a Z leadscrew is geared far lower than the belts, and
 * driving it at an XY feedrate asks for steps the motor cannot take, which it loses silently.
 *
 * Across a reconnect `homed` and the sequence position are **discarded**, which is why this is the
 * one panel using `useSessionScope` with no `PreviousSession` badge: a home is a gate on absolute
 * moves rather than a value to show, so marking it and keeping it would leave this sequence enabled
 * against an origin the machine has lost - a power cycle loses the home, and what comes back on the
 * same address may be a different firmware (`session.ts` owns that rule). With nothing kept there is
 * nothing to label as being from before, and the Home step's own "Home first" state is the message.
 * The operator's indent and Z figures *are* kept: those are their intent, not the machine's state.
 */

const props = withDefaults(defineProps<{
    uuid: string
    role?: FarmRole
    // `data-` attributes, so strings: `main.ts` hands every island a Record<string, string>, exactly
    // as `data-role` arrives. Parsed below rather than trusted - an absent or non-numeric bed size
    // disables the wizard instead of putting NaN on the wire.
    bedX?: string
    bedY?: string
    bedZ?: string
}>(), {role: 'VIEWER'})

const DEFAULT_INDENT = 30
const DEFAULT_TRAVEL_Z = 5
const DEFAULT_CHECK_Z = 0.2

const XY_FEEDRATE = 3000
const Z_FEEDRATE = 600

// Steps for judging a gap, not for crossing a bed - MotionControl.vue has the coarse ones.
const Z_STEPS = [0.02, 0.05, 0.1]

// Measured at 6778 ms for the whole of G28; this is the point at which a silent machine is reported
// as such rather than left showing "Homing…" for the life of the page.
const HOMING_MS = 60_000

const mayControl = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')

const api = printer(props.uuid)

const state = ref<ConnectionState>('OFFLINE')
const live = computed(() => isUsable(state.value))
const controlsEnabled = computed(() => mayControl.value && live.value)

const failure = ref<string | null>(null)

// ---------------------------------------------------------------- the bed

function millimetres(raw: string | undefined): number | null {
    if (raw === undefined || raw.trim() === '') {
        return null
    }
    const value = Number(raw)
    return Number.isFinite(value) && value > 0 ? value : null
}

const bed = computed(() => {
    const x = millimetres(props.bedX)
    const y = millimetres(props.bedY)
    return x === null || y === null ? null : {x, y}
})

const height = computed(() => millimetres(props.bedZ))

// ---------------------------------------------------------------- the operator's figures

// What was typed, which is not always what can be sent: each is sanitised into the computed below
// it, since a cleared number input hands back an empty string rather than a number.
const indentTyped = ref(DEFAULT_INDENT)
const travelTyped = ref(DEFAULT_TRAVEL_Z)
const checkTyped = ref(DEFAULT_CHECK_Z)

// Half the shorter axis is where that axis's two corners meet, and past it they have swapped sides,
// so the clamp sits a millimetre inside it - a visible gap rather than two stops at one point.
const indentLimit = computed(() =>
    bed.value === null ? 0 : Math.max(0, Math.min(bed.value.x, bed.value.y) / 2 - 1))

const indent = computed(() => {
    const wanted = Number.isFinite(indentTyped.value) ? indentTyped.value : DEFAULT_INDENT
    return Math.max(0, Math.min(wanted, indentLimit.value))
})

function withinHeight(typed: number, fallback: number): number {
    const wanted = Number.isFinite(typed) ? typed : fallback
    const top = height.value
    return Math.max(0, top === null ? wanted : Math.min(wanted, top))
}

const travelZ = computed(() => withinHeight(travelTyped.value, DEFAULT_TRAVEL_Z))
const checkZ = computed(() => withinHeight(checkTyped.value, DEFAULT_CHECK_Z))

const clamped = computed(() =>
    indent.value !== indentTyped.value
    || travelZ.value !== travelTyped.value
    || checkZ.value !== checkTyped.value)

// ---------------------------------------------------------------- the stops

interface Stop {
    label: string
    x: number
    y: number
}

// Travel order, so consecutive stops are adjacent corners rather than diagonals across the bed.
const stops = computed<Stop[]>(() => {
    const size = bed.value
    if (size === null) {
        return []
    }
    const i = indent.value
    return [
        {label: 'Front Left', x: i, y: i},
        {label: 'Front Right', x: size.x - i, y: i},
        {label: 'Back Right', x: size.x - i, y: size.y - i},
        {label: 'Back Left', x: i, y: size.y - i},
        {label: 'Center', x: size.x / 2, y: size.y / 2},
    ]
})

const at = ref(0)
const current = computed<Stop | null>(() => stops.value[at.value] ?? null)

// ---------------------------------------------------------------- homing

const homing = ref(false)
const homed = ref(false)
const position = ref<Record<string, number>>({})

const axes = computed(() => ['X', 'Y', 'Z', 'E']
    .filter(axis => position.value[axis] !== undefined)
    .map(axis => ({axis, value: position.value[axis]})))

const sending = ref(false)

const sequenceEnabled = computed(() =>
    controlsEnabled.value && homed.value && !homing.value && !sending.value && bed.value !== null)

// Only the callback is taken: there is no `stale` badge here because nothing survives to wear one -
// see the class doc. A home in flight is also a question the previous session will never answer.
const {observe} = useSessionScope(props.uuid, () => {
    homed.value = false
    homing.value = false
    sending.value = false
    at.value = 0
    position.value = {}
})

function onMessage(message: EventMessage) {
    if (message.id !== props.uuid) {
        return
    }
    observe(message)
    switch (message.type) {
        case 'ConnectionStateChanged':
            state.value = (message.event as ConnectionStateChanged).to
            // A halt (`Error:`/`!!`) faults the connection and arrives here, as does a lost link, so
            // this is where the gate closes: a machine that stopped part-way through its G28 is not
            // homed, and leaving `homed` true would re-arm the sequence the moment the link returned.
            // `isUsable` is IDLE or BUSY, so the busy stretch of a home does not trip this.
            if (!live.value) {
                homed.value = false
                homing.value = false
            }
            break

        case 'PositionRs':
            position.value = {...(message.event as PositionRs).axes}
            // G28 answers one of these ahead of its own `ok` and it is the only homed signal this
            // firmware offers (see the class doc) - but the stream carries no marker saying which
            // command a position answers, so this cannot be attributed to our own G28: a PositionRs
            // another tab asked for (MotionControl's Read position, or a second operator's M114)
            // inside the measured 6778 ms homing window will satisfy the gate. The consequence is
            // bounded - the corner moves queue behind the G28 on the same serial line either way, so
            // an early unlock reorders nothing physically. What it cannot cover is a G28 that never
            // completes; HOMING_MS is the only answer to that.
            if (homing.value) {
                homing.value = false
                homed.value = true
            }
            break

        default:
            break
    }
}

// ---------------------------------------------------------------- commands

async function guarded(work: () => Promise<unknown>) {
    try {
        await work()
        failure.value = null
    } catch (error) {
        if (error instanceof RequestFailed && error.status !== 401) {
            failure.value = error.message
        }
    }
}

// Pressed by a person, never issued on their behalf: homing drags the nozzle across the bed.
function home() {
    homing.value = true
    homed.value = false
    void guarded(async () => {
        try {
            await api.home({})
        } catch (error) {
            homing.value = false
            throw error
        }
        window.setTimeout(() => {
            if (homing.value) {
                homing.value = false
                failure.value = 'The machine did not report a position, so it is not known to be homed.'
            }
        }, HOMING_MS)
    })
}

// Microns are finer than any screw adjustment, and rounding here is what keeps a whole number whole:
// 220 - 30 can arrive as 189.99999999999997, which would be stringified in full and reach the
// firmware that way.
const mm = (value: number) => Math.round(value * 1000) / 1000

// One request carrying the hop, the traverse and the descent - see `moveToBedCorner` in client.ts.
function goTo() {
    const stop = current.value
    if (stop === null) {
        return
    }
    sending.value = true
    void guarded(async () => {
        try {
            await api.moveToBedCorner({
                x: mm(stop.x),
                y: mm(stop.y),
                travelZ: mm(travelZ.value),
                checkZ: mm(checkZ.value),
                xyFeedRate: XY_FEEDRATE,
                zFeedRate: Z_FEEDRATE,
            })
            // Nothing reports a position unasked, and this is what shows the move landed.
            await api.requestPosition()
        } finally {
            sending.value = false
        }
    })
}

function step(to: number) {
    if (to < 0 || to >= stops.value.length) {
        return
    }
    at.value = to
    goTo()
}

function jogZ(direction: 1 | -1, size: number) {
    sending.value = true
    void guarded(async () => {
        try {
            await api.move({z: mm(size * direction), feedRate: Z_FEEDRATE, relative: true})
            await api.requestPosition()
        } finally {
            sending.value = false
        }
    })
}

let subscription: Subscription | null = null

onMounted(async () => {
    subscription = watchPrinter(props.uuid, onMessage)
    // The stream announces transitions; this asks where the machine is now, which is the only way to
    // learn the state of one that has been quietly idle since before this row was expanded.
    await guarded(async () => {
        state.value = (await api.connection()).state
    })
})

onUnmounted(() => {
    subscription?.close()
    subscription = null
})
</script>

<template>
  <div>
    <!-- Nothing to compute corners from. The pane is already gated on a declared bed server-side;
         this covers a mount point that forgot the attributes. -->
    <p v-if="bed === null" class="text-muted mb-0">
      This machine's bed size is not known, so there are no corner positions to move to. Declare it
      on the printer's Edit page and reload.
    </p>

    <template v-else>
      <div v-if="failure" class="alert alert-warning py-2" role="alert">{{ failure }}</div>

      <p class="text-muted">
        Home the machine, then send the nozzle to each corner and turn the screw under it until the
        gap matches at every one. This moves the axes; it is not automatic bed levelling.
      </p>

      <!-- 1. Home -->
      <div class="d-flex align-items-center gap-3 flex-wrap mb-3">
        <button type="button" class="btn btn-sm btn-primary text-white"
                :disabled="!controlsEnabled || homing" @click="home()">
          {{ homing ? 'Homing…' : 'Home all axes' }}
        </button>
        <span v-if="homing" class="text-muted">
          Homing takes a few seconds - this one measured about 6.8 s.
        </span>
        <span v-else-if="homed" class="badge bg-success"
              title="The firmware reports no homed flag, so this is tracked by this host since the last G28.">homed</span>
        <span v-else class="text-muted">
          Home first: the firmware answers a position whether or not it has been homed, so an
          unhomed machine would travel to somewhere nobody chose.
        </span>
      </div>

      <!-- 2. Where in the sequence -->
      <div class="d-flex align-items-center gap-3 flex-wrap mb-2 pt-3 border-top">
        <h5 v-if="current" class="mb-0">
          {{ at + 1 }} of {{ stops.length }} — {{ current.label }}
        </h5>
        <span v-if="current" class="text-muted">
          X {{ current.x.toFixed(2) }}, Y {{ current.y.toFixed(2) }},
          Z {{ checkZ.toFixed(2) }} (travelling at Z {{ travelZ.toFixed(2) }})
        </span>
      </div>

      <div class="d-flex gap-2 flex-wrap mb-3">
        <button type="button" class="btn btn-sm btn-primary text-white"
                :disabled="!sequenceEnabled" @click="goTo()">Go to this corner
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!sequenceEnabled || at === 0" @click="step(at - 1)">Previous
        </button>
        <button type="button" class="btn btn-sm btn-outline-primary"
                :disabled="!sequenceEnabled || at >= stops.length - 1"
                @click="step(at + 1)">Next corner
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!sequenceEnabled || at === 0"
                title="Back to the first corner, and move there"
                @click="step(0)">Restart
        </button>
      </div>

      <!-- 3. Confirming the gap. Relative, so nothing here needs to know where the machine is. -->
      <div v-if="mayControl" class="mb-3">
        <label class="form-label mb-1">Nudge Z</label>
        <div class="d-flex gap-3 flex-wrap">
          <div v-for="size in Z_STEPS" :key="size" class="btn-group btn-group-sm">
            <button type="button" class="btn btn-outline-dark"
                    :disabled="!sequenceEnabled" @click="jogZ(1, size)">&uarr; {{ size }}
            </button>
            <button type="button" class="btn btn-outline-dark"
                    :disabled="!sequenceEnabled" @click="jogZ(-1, size)">&darr; {{ size }}
            </button>
          </div>
        </div>
      </div>

      <!-- 4. Where the machine says it is -->
      <div class="d-flex align-items-center gap-3 flex-wrap mb-3">
        <template v-if="axes.length > 0">
          <div v-for="a in axes" :key="a.axis">
            <span class="text-muted">{{ a.axis }}</span>
            <strong class="ms-1">{{ a.value.toFixed(2) }}</strong>
          </div>
        </template>
        <span v-else class="text-muted">Position unknown — home the machine.</span>
      </div>

      <!-- The figures, last: the defaults are the answer most of the time. -->
      <div class="row g-3 pt-3 border-top">
        <div class="col-auto">
          <label for="corner-indent" class="form-label mb-1">Indent (mm)</label>
          <input id="corner-indent" v-model.number="indentTyped" type="number" min="0"
                 :max="indentLimit" step="1" class="form-control form-control-sm"
                 :disabled="!mayControl"/>
        </div>
        <div class="col-auto">
          <label for="corner-travel-z" class="form-label mb-1">Travel Z (mm)</label>
          <input id="corner-travel-z" v-model.number="travelTyped" type="number" min="0"
                 :max="height ?? undefined" step="0.1" class="form-control form-control-sm"
                 :disabled="!mayControl"/>
        </div>
        <div class="col-auto">
          <label for="corner-check-z" class="form-label mb-1">Check Z (mm)</label>
          <input id="corner-check-z" v-model.number="checkTyped" type="number" min="0"
                 :max="height ?? undefined" step="0.05" class="form-control form-control-sm"
                 :disabled="!mayControl"/>
        </div>
        <div class="col-12">
          <p class="text-muted small mb-0">
            The indent is how far in from each edge a corner sits - 30 mm is Marlin's own default and
            is a starting point, not a measurement of your bed's screws. Check Z is where the nozzle
            stops above the bed; Marlin's own wizard uses 0, which digs in on a machine whose Z
            offset is wrong.
          </p>
          <p v-if="clamped" class="text-warning small mb-0">
            Using indent {{ indent.toFixed(2) }} mm, travel Z {{ travelZ.toFixed(2) }} mm and check Z
            {{ checkZ.toFixed(2) }} mm - a figure past the bed's own size was brought back inside it.
          </p>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import ConnectionBadge from './ConnectionBadge.vue'
import FanControl from './FanControl.vue'
import MotionControl from './MotionControl.vue'
import TemperatureControl, {type Preset} from './TemperatureControl.vue'
import {printer, RequestFailed, watchPrinter, type Subscription} from '../api/client'
import type {
    ConnectionState,
    ConnectionStateChanged,
    EventMessage,
    FarmRole,
    HeaterReading,
    PositionRs,
    SdPrinting,
    TemperatureRs,
} from '../api/types'
import {isTemperature, isUsable} from '../api/types'
import {clock} from '../format'

/**
 * One machine: where its connection is, what it is reading, and the controls that change that.
 *
 * This page is the only place a per-printer stream is opened. Opening the same stream from a list
 * is what plan item D2 forbids - each subscription instantiates a reactor server-side - but here the
 * page is that one machine, so the cost is the point.
 *
 * Commands are offered only once the machine has proved it takes them (`IDLE`/`BUSY`, not merely
 * `ONLINE` - a socket with nothing plugged in also satisfies that). `target`/`power` are nullable in
 * a reading, so a control renders "-" rather than a zero the firmware never claimed. `M500` and the
 * rest of the EEPROM operations live on the Settings tab now, gated on `Cap:EEPROM`; the button
 * moved rather than being copied, and a test asserts the string is absent from here.
 *
 * A print running from the card is *observed* here rather than owned (#147): `SdPrinting` is `M27`'s
 * answer, which the farm asks for on a timer while connected, so a print started from the Storage
 * tab, from another browser or from the printer's own screen all look the same from here. That is
 * what the progress figures below are honest about and what the controls are gated on.
 */

const props = withDefaults(defineProps<{
    uuid: string
    name: string
    role?: FarmRole
}>(), {role: 'VIEWER'})

const mayControl = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')

const state = ref<ConnectionState>('OFFLINE')
const attempt = ref(0)
const reason = ref<string | null>(null)

// The last reading of each sensor, or nulls until the machine reports one.
const unknown: HeaterReading = {current: 0, target: null, power: null}
const hotend = ref<HeaterReading>(unknown)
const bed = ref<HeaterReading>(unknown)
const position = ref<Record<string, number>>({})

// What this page last asked the fan for - Marlin does not report fan speed (see FanControl). Reset
// when the link drops, since a remembered value would describe a machine since power-cycled.
const fanSpeed = ref<number | null>(null)

// The last `M27` that said something is printing, or null for the machine's other answer. Reset when
// the link drops, for the same reason `position` and `fanSpeed` above are.
const sdPrinting = ref<SdPrinting | null>(null)

// The print's own clock and the last position sample it was measured against, exactly as
// StorageTab.vue measures an upload. `rate` is bytes a second, null until two samples exist to
// divide.
const startedAt = ref<number | null>(null)
const sample = ref<{ bytes: number; at: number } | null>(null)
const rate = ref<number | null>(null)

const failure = ref<string | null>(null)
const api = printer(props.uuid)

const live = computed(() => isUsable(state.value))
const controlsEnabled = computed(() => mayControl.value && live.value)

// Pause and Stop need something to act on; Resume deliberately does not. `M27` says how far into the
// file the firmware has read and nothing about whether it is still moving, so no event here means
// "paused" - do not invent the distinction, and do not gate the one control that undoes a pause on a
// signal the wire never sends.
const printControlsEnabled = computed(() => controlsEnabled.value && sdPrinting.value !== null)

const hotendPresets: Preset[] = [
    {label: 'PLA', value: 210},
    {label: 'PETG', value: 230},
    {label: 'ABS', value: 250},
]
const bedPresets: Preset[] = [
    {label: 'PLA', value: 60},
    {label: 'PETG', value: 75},
    {label: 'ABS', value: 80},
]

const percent = computed(() => {
    const progress = sdPrinting.value
    if (!progress || progress.size <= 0) {
        return 0
    }
    return Math.min(100, Math.round((progress.position / progress.size) * 100))
})

/**
 * Elapsed since this tab started watching, which is **not** elapsed since the print started: a print
 * may already have been under way when the page opened, including one begun from the printer's own
 * screen, and nothing on the stream says when it began. So this counts from the first `SdPrinting`
 * seen here, and `remaining` divides what is left of the file by the last two samples' rate - the
 * same arithmetic StorageTab.vue does for an upload, where the figure is exact because the click that
 * started it was in that component.
 *
 * The honest fix is not here: it needs Marlin's `M31`, whose `Print time` line reaches the host as an
 * unstructured `EchoMessage` (HandshakeProbe's capture) with no typed reply behind it - so a correct
 * answer begins with a `ReportPrintTime` reply in `:gcode`, not with more arithmetic in this file.
 */
const elapsed = computed(() => {
    const started = startedAt.value
    const last = sample.value
    return started === null || last === null ? null : clock((last.at - started) / 1000)
})

// Only while bytes are still moving: a rate measured before a pause stopped being true, and dividing
// by it would count a stalled print down to zero.
const remaining = computed(() => {
    const progress = sdPrinting.value
    const perSecond = rate.value
    if (!progress || perSecond === null || perSecond <= 0) {
        return null
    }
    const left = progress.size - progress.position
    return left <= 0 ? null : clock(left / perSecond)
})

function forgetProgress() {
    startedAt.value = null
    sample.value = null
    rate.value = null
}

function track(progress: SdPrinting) {
    const at = Date.now()
    if (startedAt.value === null) {
        startedAt.value = at
    }
    const previous = sample.value
    if (previous !== null) {
        const seconds = (at - previous.at) / 1000
        const bytes = progress.position - previous.bytes
        // A sample that moved nothing says nothing about the rate - a paused print answers the same
        // position every three seconds - so the previous reading stands rather than becoming 0 B/s.
        if (seconds > 0 && bytes > 0) {
            rate.value = bytes / seconds
        }
    }
    sample.value = {bytes: progress.position, at}
}

function onMessage(message: EventMessage) {
    if (message.id !== props.uuid) {
        return
    }
    if (isTemperature(message.type)) {
        const event = message.event as TemperatureRs
        hotend.value = event.hotend ?? unknown
        bed.value = event.bed ?? unknown
        return
    }
    switch (message.type) {
        case 'ConnectionStateChanged': {
            const event = message.event as ConnectionStateChanged
            state.value = event.to
            attempt.value = event.attempt
            reason.value = event.reason
            if (event.to === 'OFFLINE') {
                // A machine that went away kept none of this. Showing a position from before the
                // disconnection would be worse than showing none: it reads as current.
                position.value = {}
                fanSpeed.value = null
                sdPrinting.value = null
                forgetProgress()
            }
            break
        }
        case 'PositionRs': {
            position.value = {...(message.event as PositionRs).axes}
            break
        }
        case 'SdPrinting': {
            // A fresh sequence starts its own clock: the figures are relative to when this tab began
            // watching this print, and a previous print's rate says nothing about this one.
            if (sdPrinting.value === null) {
                forgetProgress()
            }
            const event = message.event as SdPrinting
            sdPrinting.value = event
            track(event)
            break
        }
        case 'SdNotPrinting': {
            sdPrinting.value = null
            forgetProgress()
            break
        }
        default:
            break
    }
}

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

const connect = () => guarded(() => api.connect())
const disconnect = () => guarded(() => api.disconnect())
const setHotend = (temp: number) => guarded(() => api.setHotendTarget(0, temp))
const setBed = (temp: number) => guarded(() => api.setBedTarget(temp))
const readNow = () => guarded(() => api.reportTemperature())
const readPosition = () => guarded(() => api.requestPosition())

const home = (axes: { x?: boolean; y?: boolean; z?: boolean }) =>
    // Ask where it ended up: homing moves the machine, and nothing reports position unasked.
    guarded(async () => {
        await api.home(axes)
        await api.requestPosition()
    })

const jog = (move: { x?: number; y?: number; z?: number; feedRate: number }) =>
    guarded(async () => {
        await api.move({...move, relative: true})
        await api.requestPosition()
    })

const pausePrint = () => guarded(() => api.pauseSdPrint())
const resumePrint = () => guarded(() => api.resumeSdPrint())

function stopPrint() {
    if (!window.confirm('Stop the print on the card?\n\nThe part is lost and the print cannot be '
            + 'continued. The heaters are left as they are.')) {
        return
    }
    void guarded(() => api.stopSdPrint())
}

// No confirmation, deliberately: an emergency stop is pressed when something is already going wrong,
// and a dialog in front of it would be a second thing to get past. The machine needs a reset
// afterwards - M112 halts the firmware, it does not pause it.
const emergencyStop = () => guarded(() => api.emergencyStop())

const setFan = (speed: number) => guarded(async () => {
    await api.setFanSpeed(speed)
    fanSpeed.value = speed
})

let subscription: Subscription | null = null

onMounted(async () => {
    subscription = watchPrinter(props.uuid, onMessage)
    // The stream announces transitions; this asks where the machine is *now*, which matters when
    // the page is opened against a machine that has been quietly idle for an hour.
    await guarded(async () => {
        const status = await api.connection()
        state.value = status.state
        attempt.value = status.attempt
        reason.value = status.reason
    })
})

onUnmounted(() => {
    subscription?.close()
    subscription = null
})
</script>

<template>
  <div class="card-body">
    <div class="d-sm-flex justify-content-between align-items-start">
      <div>
        <h4 class="card-title card-title-dash mb-1">{{ name }}</h4>
        <p class="card-subtitle card-subtitle-dash mb-0">{{ uuid }}</p>
      </div>
      <div class="d-flex align-items-center gap-2">
        <ConnectionBadge :state="state" :attempt="attempt" :reason="reason"/>
        <template v-if="mayControl">
          <button type="button" class="btn btn-sm btn-primary text-white"
                  :disabled="live" @click="connect()">Connect
          </button>
          <button type="button" class="btn btn-sm btn-outline-danger"
                  :disabled="state === 'OFFLINE'" @click="disconnect()">Disconnect
          </button>
          <!-- Solid rather than outlined, and set apart from the print controls below: this is not
               the gentle end of a print. Gated on the link and the role only - never on a print
               being believed to be running, since that belief is what an emergency stop is for. -->
          <button type="button" class="btn btn-sm btn-danger text-white ms-2"
                  :disabled="!controlsEnabled" @click="emergencyStop()"
                  title="M112. Halts the firmware at once, ahead of anything queued. The machine needs a reset afterwards.">
            Emergency stop
          </button>
        </template>
      </div>
    </div>

    <div v-if="failure" class="alert alert-warning mt-3" role="alert">{{ failure }}</div>

    <div v-if="!live" class="alert alert-secondary mt-3" role="alert">
      <span v-if="state === 'ONLINE'">
        A socket is open, but the firmware has not acknowledged anything yet — controls stay off
        until it does.
      </span>
      <span v-else-if="state === 'CONNECTING'">Connecting…</span>
      <span v-else>This machine is not connected.</span>
    </div>

    <!-- The bar shows only while the card says something is printing; the buttons stay, because a
         pause is not something this view can see (see printControlsEnabled). The bar's markup is
         StorageTab.vue's upload bar - the only progress bar this theme is used for here. -->
    <div v-if="mayControl || sdPrinting !== null" class="card card-rounded mt-3">
      <div class="card-body py-3">
        <div class="d-flex justify-content-between align-items-center"
             :class="sdPrinting !== null ? 'mb-2' : ''">
          <strong>
            <template v-if="sdPrinting !== null">Printing from the card</template>
            <template v-else>Card print</template>
          </strong>
          <div v-if="mayControl" class="btn-group btn-group-sm">
            <button type="button" class="btn btn-outline-secondary"
                    :disabled="!printControlsEnabled" @click="pausePrint()">Pause
            </button>
            <button type="button" class="btn btn-outline-secondary"
                    :disabled="!controlsEnabled" @click="resumePrint()">Resume
            </button>
            <button type="button" class="btn btn-outline-danger"
                    :disabled="!printControlsEnabled" @click="stopPrint()">Stop
            </button>
          </div>
        </div>

        <template v-if="sdPrinting !== null">
          <div class="progress" style="height: 1rem;">
            <div class="progress-bar" role="progressbar" :style="{width: percent + '%'}"
                 :aria-valuenow="percent" aria-valuemin="0" aria-valuemax="100">{{ percent }}%
            </div>
          </div>

          <!-- "Watched" and not "elapsed": see the elapsed computed for why this view cannot say
               when the print began. -->
          <p v-if="elapsed" class="text-muted small mb-0 mt-2">
            {{ elapsed }} watched<template v-if="remaining">, ~{{ remaining }} remaining</template>
            <template v-else>, estimating what is left…</template>
          </p>
        </template>
        <p v-else class="text-muted small mb-0">
          Nothing is reported as printing from the card. Resume stays available anyway — nothing the
          machine reports says a print is paused rather than finished.
        </p>
      </div>
    </div>

    <TemperatureControl
        name="Nozzle"
        :current="hotend.current"
        :target="hotend.target"
        :power="hotend.power"
        :presets="hotendPresets"
        :max="350"
        :can-control="controlsEnabled"
        @change="setHotend"
    />

    <TemperatureControl
        name="Bed"
        :current="bed.current"
        :target="bed.target"
        :power="bed.power"
        :presets="bedPresets"
        :max="120"
        :can-control="controlsEnabled"
        @change="setBed"
    />

    <div class="row pt-3">
      <div class="col-auto">
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!controlsEnabled" @click="readNow()">
          Read temperatures now
        </button>
      </div>
    </div>

    <FanControl
        :speed="fanSpeed"
        :can-control="controlsEnabled"
        @change="setFan"
    />

    <MotionControl
        :position="position"
        :can-control="controlsEnabled"
        @home="home"
        @jog="jog"
        @read-position="readPosition"
    />
  </div>
</template>

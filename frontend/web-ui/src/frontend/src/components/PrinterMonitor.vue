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
    PositionReported,
    TemperatureReported,
} from '../api/types'
import {isUsable} from '../api/types'

/**
 * One machine: where its connection is, what it is reading, and the controls that change that.
 *
 * This page is the only place a per-printer stream is opened. Opening the same stream from a list
 * is what plan item D2 forbids - each subscription instantiates a reactor server-side - but here
 * the page *is* that one machine, so the cost is the point.
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

const hotend = ref({current: 0, target: 0, power: 0})
const bed = ref({current: 0, target: 0, power: 0})
const position = ref<Record<string, number>>({})

/**
 * What this page last asked the fan for.
 *
 * Marlin does not report fan speed, so there is nothing to mirror - see FanControl. It is reset
 * when the link drops, because a value remembered across a reconnection would describe a machine
 * that has since been power-cycled.
 */
const fanSpeed = ref<number | null>(null)

const failure = ref<string | null>(null)
const api = printer(props.uuid)

/**
 * Commands are offered only when the machine has proved it takes them.
 *
 * `IDLE` and `BUSY` mean the firmware acknowledged a block this host sent; `ONLINE` means only
 * that a socket is open, which a terminal server with nothing plugged into it also satisfies.
 * Offering a heat command in that state produces a command that goes nowhere.
 */
const live = computed(() => isUsable(state.value))
const controlsEnabled = computed(() => mayControl.value && live.value)

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

function onMessage(message: EventMessage) {
    if (message.id !== props.uuid) {
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
            }
            break
        }
        case 'TemperatureReported': {
            const event = message.event as TemperatureReported
            hotend.value = {...event.hotend}
            bed.value = {...event.bed}
            break
        }
        case 'PositionReported': {
            position.value = {...(message.event as PositionReported).axes}
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

const setFan = (speed: number) => guarded(async () => {
    await api.setFanSpeed(speed)
    fanSpeed.value = speed
})

/**
 * Writing the settings to EEPROM, behind a confirmation.
 *
 * It is the one control on this page whose effect outlives the connection: a wrong value stored is
 * one the machine keeps after a power cycle, and undoing it means `M502` and re-entering whatever
 * was right. A click that cannot be taken back deserves to be asked about.
 */
const STORE_WARNING = [
    'Write this machine\'s current settings to its EEPROM?',
    'They survive a power cycle, and undoing it means resetting the firmware to defaults.',
].join('\n\n')

const storeSettings = () => {
    if (!window.confirm(STORE_WARNING)) {
        return
    }
    return guarded(() => api.storeSettings())
}

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

    <div class="row pt-4 border-top mt-3">
      <div class="col">
        <h4 class="card-title">Settings</h4>
        <p class="card-subtitle">
          Write what the firmware currently holds to its EEPROM, so it survives a power cycle.
        </p>
      </div>
      <div class="col-auto align-self-center">
        <button type="button" class="btn btn-sm btn-outline-warning"
                :disabled="!controlsEnabled" @click="storeSettings()">
          Store settings (M500)
        </button>
      </div>
    </div>
  </div>
</template>

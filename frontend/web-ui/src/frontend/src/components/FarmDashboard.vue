<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import ConnectionBadge from './ConnectionBadge.vue'
import {farm, printer, RequestFailed, watchFarm, type Subscription} from '../api/client'
import type {
    ConnectionStateChanged,
    ConnectionState,
    Descriptor,
    EventMessage,
    FarmRole,
    MachineJoinedFarm,
    TemperatureRs,
} from '../api/types'
import {isTemperature, isUsable} from '../api/types'

/**
 * Every printer in the farm, live, on **one** subscription - plan item D2.
 *
 * The obvious build is one `EventSource` per row. That is the thing D2 exists to prevent:
 * `/api-printer/{id}/stream` instantiates a reactor as a side effect, so forty rows would open
 * forty sockets to render a page that is only looking. `/api-farm/stream` opens nothing.
 */

const props = withDefaults(defineProps<{ role?: FarmRole }>(), {role: 'VIEWER'})

const mayControl = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')
const mayAdminister = computed(() => props.role === 'ADMIN')

interface Row {
    uuid: string
    name: string
    host: string
    port: number
    state: ConnectionState
    attempt: number
    reason: string | null
    hotend: number | null
    bed: number | null
}

const rows = ref<Record<string, Row>>({})
const failure = ref<string | null>(null)
const loading = ref(true)

/** Alphabetical, so a row does not move when a printer happens to speak. */
const ordered = computed(() =>
    Object.values(rows.value).sort((a, b) => a.name.localeCompare(b.name)))

const anyMachines = computed(() => ordered.value.length > 0)

function blank(uuid: string, name = 'unknown'): Row {
    return {
        uuid, name, host: '', port: 0,
        state: 'OFFLINE', attempt: 0, reason: null,
        hotend: null, bed: null,
    }
}

function rowFor(uuid: string): Row {
    const existing = rows.value[uuid]
    if (existing) {
        return existing
    }
    const created = blank(uuid)
    rows.value[uuid] = created
    return created
}

function adopt(machine: Descriptor) {
    const row = rowFor(machine.uuid)
    row.name = machine.name
    row.host = machine.connection.host
    row.port = machine.connection.port
    // The roster's `state` is its own record of the last transition core announced. The stream is
    // more current, so it only fills in a row the stream has not spoken about yet.
    if (row.state === 'OFFLINE' && machine.state) {
        row.state = machine.state
    }
}

function onMessage(message: EventMessage) {
    if (isTemperature(message.type)) {
        // A machine with no bed sensor reports none, so this is the one place that has to say
        // "unknown" rather than read `.current` off a null - which is what the old flattened
        // record hid by sending zeroes.
        const event = message.event as TemperatureRs
        const row = rowFor(message.id)
        row.hotend = event.hotend?.current ?? null
        row.bed = event.bed?.current ?? null
        return
    }
    switch (message.type) {
        case 'ConnectionStateChanged': {
            const event = message.event as ConnectionStateChanged
            const row = rowFor(message.id)
            row.state = event.to
            row.attempt = event.attempt
            row.reason = event.reason
            break
        }
        case 'MachineJoinedFarm': {
            // A printer registered while this page was open - D2's done-when.
            const event = message.event as MachineJoinedFarm
            rowFor(message.id).name = event.name
            // The join carries a name but not an address; the roster has the rest.
            void refresh()
            break
        }
        case 'MachineLeftFarm': {
            delete rows.value[message.id]
            break
        }
        default:
            // Most events are not this page's business - job progress, firmware reports. Ignoring
            // them is correct; logging each one would drown the console on a busy farm.
            break
    }
}

async function refresh() {
    try {
        (await farm.all()).forEach(adopt)
        failure.value = null
    } catch (error) {
        if (error instanceof RequestFailed && error.status !== 401) {
            failure.value = error.message
        }
    }
}

/**
 * Removes a machine from the farm, behind a confirmation.
 *
 * <strong>The card does not need removing here.</strong> Deleting publishes `MachineLeftFarm`, and
 * the farm stream this page is already holding carries it - so the row disappears for *every* open
 * dashboard, not just the one that pressed the button. Removing it locally as well would mean two
 * mechanisms for one fact, and the local one would be the one that went wrong when a delete failed.
 */
async function remove(row: Row) {
    const warning = [
        `Remove ${row.name} from the farm?`,
        'It stops being monitored and any queued job for it is left without a machine. '
        + 'The printer itself is not touched.',
    ].join('\n\n')

    if (!window.confirm(warning)) {
        return
    }
    try {
        await farm.remove(row.uuid)
        failure.value = null
    } catch (error) {
        if (error instanceof RequestFailed && error.status !== 401) {
            failure.value = error.message
        }
    }
}

async function act(uuid: string, action: 'connect' | 'disconnect') {
    try {
        await (action === 'connect' ? printer(uuid).connect() : printer(uuid).disconnect())
        failure.value = null
    } catch (error) {
        if (error instanceof RequestFailed && error.status !== 401) {
            failure.value = error.message
        }
    }
}

let subscription: Subscription | null = null

onMounted(async () => {
    // Subscribe *before* listing. The other order leaves a gap between the two calls in which an
    // event is simply lost, and the row it belonged to stays stale until that printer next speaks -
    // which for an idle machine could be never.
    subscription = watchFarm(onMessage)
    await refresh()
    loading.value = false
})

onUnmounted(() => {
    subscription?.close()
    subscription = null
})
</script>

<template>
  <div>
    <div class="d-sm-flex align-items-center justify-content-between mb-3">
      <h3 class="mb-0">Farm</h3>
      <a v-if="mayAdminister" class="btn btn-primary text-white" href="/printers/add.html">
        Add a printer
      </a>
    </div>

    <div v-if="failure" class="alert alert-warning" role="alert">{{ failure }}</div>

    <div v-if="loading" class="text-muted">Loading the farm…</div>

    <div v-else-if="!anyMachines" class="card">
      <div class="card-body">
        <h4 class="card-title">No printers yet</h4>
        <p class="card-subtitle">
          <span v-if="mayAdminister">Add one to start watching it.</span>
          <span v-else>An administrator has not registered any machines.</span>
        </p>
      </div>
    </div>

    <div v-else class="row">
      <div v-for="row in ordered" :key="row.uuid" class="col-md-6 col-xl-4 grid-margin stretch-card">
        <div class="card card-rounded">
          <div class="card-body">
            <div class="d-flex justify-content-between align-items-start">
              <div>
                <h4 class="card-title card-title-dash mb-1">
                  <a :href="`/printers/${row.uuid}`">{{ row.name }}</a>
                </h4>
                <p class="card-subtitle card-subtitle-dash mb-0">
                  {{ row.host }}<span v-if="row.port">:{{ row.port }}</span>
                </p>
              </div>
              <ConnectionBadge :state="row.state" :attempt="row.attempt" :reason="row.reason"/>
            </div>

            <div class="row mt-3">
              <div class="col">
                <p class="mb-0 text-muted">Nozzle</p>
                <h5 class="mb-0">
                  <span v-if="row.hotend !== null">{{ row.hotend.toFixed(1) }} °C</span>
                  <span v-else class="text-muted">—</span>
                </h5>
              </div>
              <div class="col">
                <p class="mb-0 text-muted">Bed</p>
                <h5 class="mb-0">
                  <span v-if="row.bed !== null">{{ row.bed.toFixed(1) }} °C</span>
                  <span v-else class="text-muted">—</span>
                </h5>
              </div>
            </div>

            <div v-if="mayControl" class="mt-3 d-flex gap-2 flex-wrap align-items-center">
              <button type="button" class="btn btn-sm btn-primary text-white"
                      :disabled="isUsable(row.state)" @click="act(row.uuid, 'connect')">
                Connect
              </button>
              <button type="button" class="btn btn-sm btn-outline-danger"
                      :disabled="row.state === 'OFFLINE'" @click="act(row.uuid, 'disconnect')">
                Disconnect
              </button>

              <template v-if="mayAdminister">
                <a class="btn btn-sm btn-outline-secondary ms-auto"
                   :href="`/printers/${row.uuid}/edit.html`">Edit</a>
                <button type="button" class="btn btn-sm btn-outline-danger"
                        @click="remove(row)">Remove</button>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

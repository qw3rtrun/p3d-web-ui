<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import {files as fileStore, printer, RequestFailed, watchPrinter, type Subscription} from '../api/client'
import type {EventMessage, FarmRole, GcodeFile, SdFile, SdFileList, SdUploadProgressed} from '../api/types'
import {estimate, fileSize, filamentUsed, sliceDetail} from '../format'

/**
 * The machine's SD card: what is on it, and what can be done to it.
 *
 * **Whether this is offered at all was decided by the server.** `monitor.html` renders the tab
 * disabled, and this island not at all, when the firmware reports no card reader - a capability is
 * true at request time, so asking again from here would be re-learning what the page already knew.
 *
 * **Whether a card is actually *in* the reader is a different question, and nothing asks it.**
 * There is no command for "is a card present", so this mounts one (`M21`) and reads the answer:
 * `SdCardOk` means yes, and silence means no. The failure text is deliberately not matched -
 * the simulator always has a card, so it has never been captured, and guessing a reply format from
 * the wiki is how this repository got reply formats wrong before. A timeout is the honest version
 * of a question the protocol cannot answer directly.
 */

const props = withDefaults(defineProps<{
    uuid: string
    role?: FarmRole
}>(), {role: 'VIEWER'})

const mayControl = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')

/** How long to wait for an answer before concluding there is not one. */
const ANSWER_MS = 4000

type Card = 'unknown' | 'mounted' | 'absent'

const card = ref<Card>('unknown')

/**
 * The card's contents, or null for "no answer".
 *
 * **Null and empty are different and the page must not blur them.** An empty array is a card with
 * nothing printable on it; null is a listing that never arrived, which an interrupted one produces
 * on purpose rather than reporting a short list. Rendering both as an empty table would tell an
 * operator their card is empty when the truth is that nobody knows.
 */
const listed = ref<SdFile[] | null>(null)
const waitingForList = ref(false)

/**
 * The three things a listing can mean, named rather than reached through a nullable in the
 * template - which also keeps the distinction between "empty" and "no answer" impossible to lose
 * in a chain of `v-else-if`.
 */
const unknownContents = computed(() => listed.value === null && !waitingForList.value)
const emptyCard = computed(() => listed.value !== null && listed.value.length === 0)
const onTheCard = computed<SdFile[]>(() => listed.value ?? [])

/**
 * What the **server** holds, which is a different store from the card above it.
 *
 * Every upload lands here first - `/api-files` takes the bytes, the machine takes them afterwards -
 * and a stored file can be written to any number of machines without being uploaded again. Until
 * this list existed the store was invisible: the only way to put a file on a card was to send its
 * bytes over the network a second time, and the only `GcodeFile` the UI ever held was the one
 * `upload()` handed back, which it read an id out of and threw away.
 *
 * `storedKnown` is false until the server has actually answered, so an empty table reads as
 * "nothing stored" only when that is what it said - the same distinction the card listing draws
 * between an empty card and a machine that never replied.
 */
const stored = ref<GcodeFile[]>([])
const storedKnown = ref(false)

const upload = ref<SdUploadProgressed | null>(null)
const chosen = ref<File | null>(null)
const sending = ref(false)

const failure = ref<string | null>(null)
const api = printer(props.uuid)

const transferring = computed(() => upload.value?.state === 'UPLOADING' || sending.value)

/** Everything that drives the machine is unavailable while the wire belongs to a transfer. */
const canAct = computed(() => mayControl.value && !transferring.value)

const percent = computed(() => {
    const progress = upload.value
    if (!progress || progress.bytesTotal <= 0) {
        return 0
    }
    return Math.min(100, Math.round((progress.bytesSent / progress.bytesTotal) * 100))
})

function onMessage(message: EventMessage) {
    if (message.id !== props.uuid) {
        return
    }
    switch (message.type) {
        case 'SdCardOk':
            card.value = 'mounted'
            break

        case 'SdFileList': {
            // A listing arriving at all is proof of a card, which saves the mount answer being the
            // only evidence - and covers a machine that was already mounted when the page opened.
            card.value = 'mounted'
            listed.value = (message.event as SdFileList).files
            waitingForList.value = false
            break
        }

        case 'SdUploadProgressed': {
            const progress = message.event as SdUploadProgressed
            upload.value = progress
            if (progress.state !== 'UPLOADING') {
                sending.value = false
                // What is on the card changed, whichever way it ended: a cancel leaves a short but
                // closed file that the operator will want to see and probably delete.
                refresh()
            }
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

/**
 * Ask whether there is a card, and give up on an answer rather than waiting forever.
 *
 * The timeout is the mechanism, not a safety net: no reply means no card, because there is no
 * reply that means "no card" that this host has ever seen written down.
 */
function mount() {
    card.value = 'unknown'
    void guarded(async () => {
        await api.mountCard()
        window.setTimeout(() => {
            if (card.value === 'unknown') {
                card.value = 'absent'
            }
        }, ANSWER_MS)
    })
}

/**
 * Ask for the contents, and say so when nothing comes back.
 *
 * A listing that is interrupted produces no event at all - see `SdFileList` - so the only way to
 * tell "the card is empty" from "the machine did not answer" is to notice the silence.
 */
function refresh() {
    waitingForList.value = true
    void guarded(async () => {
        await api.listFiles()
        window.setTimeout(() => {
            if (waitingForList.value) {
                waitingForList.value = false
                listed.value = null
                failure.value = 'The machine did not answer with a listing.'
            }
        }, ANSWER_MS)
    })
}

/** The server's own store. A plain GET; nothing is asked of the machine. */
const loadStored = () => guarded(async () => {
    stored.value = await fileStore.all()
    storedKnown.value = true
})

const release = () => guarded(async () => {
    await api.releaseCard()
    card.value = 'absent'
    listed.value = null
})

const print = (name: string) => guarded(() => api.printFile(name))

function remove(name: string) {
    if (!window.confirm(`Delete ${name} from the card?\n\nThis cannot be undone.`)) {
        return
    }
    void guarded(async () => {
        await api.deleteFile(name)
        refresh()
    })
}

function choose(event: Event) {
    const input = event.target as HTMLInputElement
    chosen.value = input.files?.[0] ?? null
}

/**
 * Store the file on the server, then ask the machine to write it to its card.
 *
 * **Two requests, and the first is the one that carries the bytes.** `/api-files` streams them into
 * the server's store and records the size, which is what the progress bar divides by - and it means
 * sending the same file to a second machine costs no second upload. The second request only names
 * the result.
 *
 * `sending` covers the gap between them: until the first progress event arrives there is nothing on
 * the stream to say a transfer is under way, and the controls have to be unavailable from the click.
 */
function send() {
    const file = chosen.value
    if (!file) {
        return
    }
    sending.value = true
    upload.value = null
    void guarded(async () => {
        try {
            const held = await fileStore.upload(file)
            // The store has one more file in it, and its summary is worth showing even if the
            // transfer below fails. Awaited rather than fired off, so this cannot clear the
            // failure the next line may be about to report.
            await loadStored()
            await api.uploadToCard(held.id, file.name)
        } catch (error) {
            sending.value = false
            throw error
        }
    })
}

/**
 * Write a file the server already holds to this machine's card.
 *
 * The same second request `send()` makes, without the first: the bytes are on the server already,
 * so sending a file to a second printer costs nothing but the wire to that printer.
 */
function sendStored(file: GcodeFile) {
    sending.value = true
    upload.value = null
    void guarded(async () => {
        try {
            await api.uploadToCard(file.id, file.name)
        } catch (error) {
            sending.value = false
            throw error
        }
    })
}

const cancelUpload = () => guarded(() => api.cancelCardUpload())

let subscription: Subscription | null = null

onMounted(() => {
    subscription = watchPrinter(props.uuid, onMessage)
    mount()
    refresh()
    void loadStored()
})

onUnmounted(() => {
    subscription?.close()
    subscription = null
})
</script>

<template>
  <div class="pt-3">
    <div class="d-sm-flex justify-content-between align-items-center mb-3">
      <div>
        <h4 class="card-title mb-1">
          SD card
          <span v-if="card === 'mounted'" class="badge bg-success ms-2">mounted</span>
          <span v-else-if="card === 'absent'" class="badge bg-secondary ms-2">not mounted</span>
          <span v-else class="badge bg-light text-dark ms-2">asking…</span>
        </h4>
        <p class="card-subtitle mb-0">Files the machine can print on its own.</p>
      </div>

      <div v-if="mayControl" class="d-flex gap-2">
        <button v-if="card !== 'mounted'" type="button" class="btn btn-sm btn-primary text-white"
                :disabled="!canAct" @click="mount()">Mount
        </button>
        <button v-else type="button" class="btn btn-sm btn-outline-danger"
                :disabled="!canAct" @click="release()">Release
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!canAct" @click="refresh()">Refresh
        </button>
      </div>
    </div>

    <div v-if="failure" class="alert alert-warning py-2" role="alert">{{ failure }}</div>

    <!--
      The transfer panel. The sentence about the machine taking nothing else is not decoration -
      it is what M28 does to the wire, and it is why every other control on this page is disabled
      while it runs.
    -->
    <div v-if="upload || sending" class="card card-rounded mb-3">
      <div class="card-body py-3">
        <div class="d-flex justify-content-between align-items-center mb-2">
          <strong>
            <template v-if="transferring">Uploading</template>
            <template v-else-if="upload?.state === 'DONE'">Uploaded</template>
            <template v-else-if="upload?.state === 'CANCELLED'">Stopped</template>
            <template v-else>Failed</template>
            {{ upload?.file ?? chosen?.name }}
          </strong>
          <button v-if="transferring && mayControl" type="button"
                  class="btn btn-sm btn-outline-danger" @click="cancelUpload()">Cancel upload
          </button>
        </div>

        <div class="progress" style="height: 1rem;">
          <div class="progress-bar" role="progressbar" :style="{width: percent + '%'}"
               :aria-valuenow="percent" aria-valuemin="0" aria-valuemax="100">{{ percent }}%
          </div>
        </div>

        <p v-if="upload" class="text-muted small mb-0 mt-2">
          {{ fileSize(upload.bytesSent) }} of {{ fileSize(upload.bytesTotal) }},
          {{ upload.blocksSent.toLocaleString() }} blocks sent
        </p>

        <p v-if="transferring" class="text-muted small mb-0">
          The printer takes no other commands until this finishes.
        </p>
        <p v-else-if="upload?.state === 'CANCELLED'" class="text-muted small mb-0">
          The file was closed where it stopped, so what is on the card is incomplete. Delete it
          rather than printing it.
        </p>
        <p v-else-if="upload?.state === 'FAILED'" class="text-warning small mb-0">
          {{ upload.reason }} The file may have been left open on the card.
        </p>
      </div>
    </div>

    <!-- Nothing is known about the card yet, or there is none in the slot. -->
    <p v-if="card === 'absent'" class="text-muted">
      No card is mounted. Insert one and press Mount.
    </p>

    <template v-else>
      <p v-if="waitingForList" class="text-muted">Reading the card…</p>
      <p v-else-if="unknownContents" class="text-muted">
        The card's contents are unknown - nothing answered the last listing.
      </p>
      <p v-else-if="emptyCard" class="text-muted">
        There is nothing printable on this card.
      </p>

      <table v-else class="table table-sm align-middle">
        <thead>
        <tr>
          <th scope="col">Name</th>
          <th scope="col">Size</th>
          <th scope="col" class="text-end"></th>
        </tr>
        </thead>
        <tbody>
        <tr v-for="file in onTheCard" :key="file.name">
          <td>{{ file.name }}</td>
          <td class="text-muted">{{ fileSize(file.size) }}</td>
          <td class="text-end">
            <div v-if="mayControl" class="btn-group btn-group-sm">
              <button type="button" class="btn btn-outline-primary"
                      :disabled="!canAct" @click="print(file.name)">Print
              </button>
              <button type="button" class="btn btn-outline-danger"
                      :disabled="!canAct" @click="remove(file.name)">Delete
              </button>
            </div>
          </td>
        </tr>
        </tbody>
      </table>
    </template>

    <!--
      The server's own store, which is where every upload lands before a machine sees it.

      This is the one place a GcodeFile is shown, so it is where what the slicer said belongs. Two
      columns earn the space - how long it takes and how much filament it eats - because those are
      the figures somebody picking a file to print is actually choosing between. The program that
      sliced it and the layer height are true but are detail, and ride in the row's title.

      A file nothing could be read from says "not stated" in both columns. Not "0 min", which would
      claim the print is instant, and not an error: a file with no slicer comments in it is a
      perfectly printable file, and `slice.known` is how it says so.
    -->
    <div class="pt-3 border-top">
      <h5 class="mb-1">On the server</h5>
      <p class="text-muted small">
        Stored once and sendable to any machine - a file here needs no second upload.
      </p>

      <p v-if="!storedKnown" class="text-muted">Reading the server's files…</p>
      <p v-else-if="stored.length === 0" class="text-muted">
        No files are stored yet. One uploaded below is kept here too.
      </p>

      <table v-else class="table table-sm align-middle">
        <thead>
        <tr>
          <th scope="col">Name</th>
          <th scope="col">Size</th>
          <th scope="col">Estimated time</th>
          <th scope="col">Filament</th>
          <th scope="col" class="text-end"></th>
        </tr>
        </thead>
        <tbody>
        <tr v-for="file in stored" :key="file.id" :title="sliceDetail(file.slice)">
          <td>{{ file.name }}</td>
          <td class="text-muted">{{ fileSize(file.sizeBytes) }}</td>
          <td :class="file.slice.estimatedSeconds === null ? 'text-muted fst-italic' : ''">
            {{ estimate(file.slice) }}
          </td>
          <td :class="file.slice.filamentMillimetres === null ? 'text-muted fst-italic' : ''">
            {{ filamentUsed(file.slice) }}
          </td>
          <td class="text-end">
            <!-- There is nowhere to send it with no card in the slot, so the control says so. -->
            <button v-if="mayControl" type="button" class="btn btn-sm btn-outline-primary"
                    :disabled="!canAct || card === 'absent'"
                    :title="card === 'absent' ? 'Mount a card first.' : 'Write this file to the card'"
                    @click="sendStored(file)">Send to card
            </button>
          </td>
        </tr>
        </tbody>
      </table>
    </div>

    <!--
      Upload. No extension is required of the operator: the server adds a printable one when it is
      missing, because M20 lists nothing else and a file without one would be on the card and
      invisible. The name that was actually used comes back with the first progress event.
    -->
    <div v-if="mayControl" class="row align-items-end pt-3 border-top">
      <div class="col-md-6">
        <label for="storage-upload" class="form-label mb-1">Upload a file to the card</label>
        <input id="storage-upload" type="file" class="form-control form-control-sm"
               accept=".gcode,.gco,.g" :disabled="!canAct" @change="choose"/>
      </div>
      <div class="col-auto">
        <button type="button" class="btn btn-sm btn-primary text-white"
                :disabled="!canAct || !chosen" @click="send()">Upload
        </button>
      </div>
    </div>
  </div>
</template>

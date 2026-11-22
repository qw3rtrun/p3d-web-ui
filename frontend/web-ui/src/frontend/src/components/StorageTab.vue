<script setup lang="ts">
import {computed, onMounted, onUnmounted, ref} from 'vue'
import {Tab} from 'bootstrap'
import {files as fileStore, printer, RequestFailed, watchPrinter, type Subscription} from '../api/client'
import type {EventMessage, FarmRole, GcodeFile, SdFile, SdFileList, SdUploadProgressed} from '../api/types'
import {clock, estimate, fileSize, filamentUsed, sliceDetail, transferRate} from '../format'
import {useSessionScope} from '../session'
import PreviousSession from './PreviousSession.vue'

/**
 * The machine's SD card: what is on it, and what can be done to it.
 *
 * Whether this is offered at all was decided by the server: `monitor.html` disables the tab and
 * skips this island when the firmware reports no card reader (a capability true at request time).
 * Whether a card is actually in the reader is a different question nothing asks directly, so this
 * mounts one (`M21`) and reads the answer: `SdCardOk` means yes, silence means no (the failure text
 * is unmatched, since the simulator always has a card and has never produced one) - a timeout is the
 * honest version of a question the protocol cannot answer directly.
 *
 * Two stores, two kinds of "empty": a null listing is a listing that never arrived (an interrupted
 * one produces nothing, not a short list), distinct from an empty array, which is a card with
 * nothing printable - rendering both as the same empty table would claim knowledge nobody has. The
 * same distinction applies to the server's own file store (`storedKnown`) below it.
 *
 * Uploading is two requests: `/api-files` takes the bytes first, so the same stored file can reach a
 * second machine without a second upload, and the second request only names the result to the card.
 * "Send to card now" decides whether the second one is made at all, and is ticked, since both at
 * once is what this control has always done; unticked, the bytes stop on the server and the stored
 * table's own button makes the second request whenever the operator is ready. Unticked also means no
 * progress event is coming, so the busy state the click raised has to be dropped where the first
 * request returns - waiting for a transfer nobody asked for would leave the whole tab disabled under
 * "Uploading" until a reload.
 *
 * Which protocol that second request asks for is the Binary Transfer checkbox, enabled from
 * `data-binary-transfer` - the firmware's `BINARY_FILE_TRANSFER` capability, rendered into the mount
 * point by the server, since props here come from `data-` attributes rather than a fetch for
 * something the page already knew when it rendered (`main.ts`), exactly as `data-role` arrives.
 * Unlike the `SDCARD` gate above, this capability does **not** decide whether the island mounts: a
 * machine without it uploads perfectly well over ASCII, so the island always mounts and only the
 * checkbox goes dead - the two gates look alike and are not. And like every gate in this layer the
 * attribute is a hint: the server refuses a binary request from a machine that never reported the
 * capability (409, naming it) whatever the browser sends, which is why the checkbox may be wrong
 * about a machine and may not be wrong about the wire.
 *
 * "Trim GCode" is unticked, and that default is the point: binary transfer's promise is a byte-exact
 * copy of the file, so dropping anything out of it is asked for rather than discovered. It decides
 * nothing for an ASCII transfer, which strips comments and blank lines whatever the box says - which
 * is why the box carries a tooltip saying so rather than a label that would have to lie in one
 * direction or the other, and why the progress card shows the event's `trimmed` instead of echoing
 * the tick back at whoever made it.
 *
 * Elapsed, speed and time remaining are arithmetic over the progress events and the clock, done here
 * because no part of the protocol knows them: each event is one (`bytesSent`, now) sample, speed is
 * the last two samples rather than the average since the start - a transfer that has slowed down
 * should read as one instead of being diluted by however long it was healthy - and remaining divides
 * what is left of the file by it. The stream's own cadence is the clock, so there is no interval to
 * own and release: a reading that has stopped moving is a transfer whose events have, which the
 * Interrupted notice below is already there to explain.
 *
 * Across a reconnect the card state and the listing are kept and marked rather than blanked, and
 * nothing is re-listed automatically - `useSessionScope` in `src/session.ts` owns that rule for
 * every panel that shows what the server holds about a session.
 */

const props = withDefaults(defineProps<{
    uuid: string
    role?: FarmRole
    // A `data-` attribute, so a string: Thymeleaf writes a Java boolean as `true`/`false` and the
    // comparison below is exact. A truthiness check would read the string 'false' as yes.
    binaryTransfer?: string
}>(), {role: 'VIEWER', binaryTransfer: 'false'})

const mayControl = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')

// Defaulting to off rather than on: a page that forgot the attribute must not offer a mode the
// machine may not have.
const mayUseBinary = computed(() => props.binaryTransfer === 'true')

// How long to wait for an answer before concluding there is not one.
const ANSWER_MS = 4000

type Card = 'unknown' | 'mounted' | 'absent'

const card = ref<Card>('unknown')

// The card's contents, or null for "no answer" - see the class doc above.
const listed = ref<SdFile[] | null>(null)
const waitingForList = ref(false)

// Named rather than reached through a nullable in the template.
const unknownContents = computed(() => listed.value === null && !waitingForList.value)
const emptyCard = computed(() => listed.value !== null && listed.value.length === 0)
const onTheCard = computed<SdFile[]>(() => listed.value ?? [])

// What the server holds, a different store from the card above it - see the class doc above.
const stored = ref<GcodeFile[]>([])
const storedKnown = ref(false)

const upload = ref<SdUploadProgressed | null>(null)
const chosen = ref<File | null>(null)
const sending = ref(false)

// Binary is the better transfer where it exists, so it is ticked by default - but the tick is only
// half the answer. What gets sent is this computed, never the ref: the input is disabled on an
// incapable machine while the ref underneath it stays true, and a call site reading the ref would
// ask for binary anyway. There are two such call sites, which is one more than wants remembering.
const binary = ref(true)
const useBinary = computed(() => mayUseBinary.value && binary.value)

// Both unguarded by any capability, so both are read straight at the call sites - see the class doc
// for why one defaults on and the other off.
const toCard = ref(true)
const trim = ref(false)

// The transfer's own clock and the last progress sample it was measured against (see the class doc).
// `rate` is bytes a second, null until two samples exist to divide.
const startedAt = ref<number | null>(null)
const sample = ref<{ bytes: number; at: number } | null>(null)
const rate = ref<number | null>(null)

const failure = ref<string | null>(null)
const api = printer(props.uuid)

// A transfer whose link went away. The server's last word was UPLOADING and no terminal event is
// coming - the session that would have sent one is over - so without this the progress card stays
// at "Uploading", `transferring` stays true, and every control on the tab is disabled until a
// reload. Not written as a FAILED progress event: the states are the server's vocabulary, and
// inventing one here would claim the server said something it did not.
const abandoned = ref(false)

const transferring = computed(() =>
    !abandoned.value && (upload.value?.state === 'UPLOADING' || sending.value))

// Everything that drives the machine is unavailable while the wire belongs to a transfer.
const canAct = computed(() => mayControl.value && !transferring.value)

// The card state and its listing are kept across a reconnect and marked instead of blanked
// (`session.ts` holds the rule and the reasoning). What cannot be kept is a transfer: `sending`
// covers a request whose answer will never come, and anything still showing as in flight was cut
// off by the drop - see `abandoned` above. Nothing is re-listed here; the Refresh button is.
const {stale, observe, renewed} = useSessionScope(props.uuid, () => {
    if (transferring.value) {
        abandoned.value = true
    }
    sending.value = false
    waitingForList.value = false
})

const percent = computed(() => {
    const progress = upload.value
    if (!progress || progress.bytesTotal <= 0) {
        return 0
    }
    return Math.min(100, Math.round((progress.bytesSent / progress.bytesTotal) * 100))
})

const elapsed = computed(() => {
    const started = startedAt.value
    const last = sample.value
    return started === null || last === null ? null : clock((last.at - started) / 1000)
})

const speed = computed(() => transferRate(rate.value))

// Only while something is still arriving: a finished transfer has nothing remaining, and a stalled
// one divides by a rate that stopped being true, so it says nothing rather than counting down.
const remaining = computed(() => {
    const progress = upload.value
    const perSecond = rate.value
    if (!progress || progress.state !== 'UPLOADING' || perSecond === null || perSecond <= 0) {
        return null
    }
    const left = progress.bytesTotal - progress.bytesSent
    return left <= 0 ? null : clock(left / perSecond)
})

// Clears the previous transfer's figures. The clock is deliberately not started here: send() has a
// request of its own to make first, and time spent putting the bytes on the server is not time on
// the wire to the card.
function forgetProgress() {
    startedAt.value = null
    sample.value = null
    rate.value = null
}

function track(progress: SdUploadProgressed) {
    const at = Date.now()
    // A transfer started from somewhere else - another tab, another operator - has no click here to
    // have started the clock, so its first event does.
    if (startedAt.value === null) {
        startedAt.value = at
    }
    const previous = sample.value
    if (previous !== null) {
        const seconds = (at - previous.at) / 1000
        const bytes = progress.bytesSent - previous.bytes
        // A sample that moved nothing says nothing about the rate - two events in the same
        // millisecond, or the closing event repeating the last count - so the previous reading
        // stands. A bar that is not moving is what shows a stall; a speed of 0.0 B/s would only
        // claim to have measured one.
        if (seconds > 0 && bytes > 0) {
            rate.value = bytes / seconds
        }
    }
    sample.value = {bytes: progress.bytesSent, at}
}

function onMessage(message: EventMessage) {
    if (message.id !== props.uuid) {
        return
    }
    observe(message)
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
            // Both of the above belong to the session now running.
            renewed()
            break
        }

        case 'SdUploadProgressed': {
            const progress = message.event as SdUploadProgressed
            upload.value = progress
            track(progress)
            // Progress is proof a transfer is running now, which a transfer cut off by a drop is
            // not - this is how the card recovers when somebody starts a new one from elsewhere.
            abandoned.value = false
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

// The timeout is the mechanism, not a safety net: no reply means no card (see the class doc above).
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

// An interrupted listing produces no event at all, so the only way to tell "empty" from "no
// answer" is to notice the silence.
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

// The server's own store. A plain GET; nothing is asked of the machine.
const loadStored = () => guarded(async () => {
    stored.value = await fileStore.all()
    storedKnown.value = true
})

const release = () => guarded(async () => {
    await api.releaseCard()
    card.value = 'absent'
    listed.value = null
})

// #147 item 4: pressing Print moves the operator to the Monitor tab, which is where the progress of
// what they just started shows up. Inside the guarded call, so a refused print leaves them here with
// the failure in front of them instead of on a tab that will never show a bar.
const print = (name: string) => guarded(async () => {
    await api.printFile(name)
    const monitor = document.getElementById('home-tab')
    if (monitor) {
        Tab.getOrCreateInstance(monitor).show()
    }
})

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

// One or two requests, as the checkbox says (see the class doc above). `sending` covers the gap
// between them: until the first progress event arrives there is nothing on the stream to say a
// transfer is under way, so the controls must be unavailable from the click.
function send() {
    const file = chosen.value
    if (!file) {
        return
    }
    sending.value = true
    upload.value = null
    abandoned.value = false
    forgetProgress()
    void guarded(async () => {
        try {
            const held = await fileStore.upload(file)
            // Awaited rather than fired off, so this cannot clear the failure the next line may
            // be about to report.
            await loadStored()
            if (!toCard.value) {
                // The only request asked for has returned, and no progress event is coming to end
                // this - see the class doc. The stored row's button is the second half now.
                sending.value = false
                return
            }
            startedAt.value = Date.now()
            await api.uploadToCard(held.id, file.name, useBinary.value, trim.value)
        } catch (error) {
            sending.value = false
            throw error
        }
    })
}

// The second request on its own - the bytes are already on the server, whether send() put them
// there a moment ago with the box unticked or a different page did yesterday.
function sendStored(file: GcodeFile) {
    sending.value = true
    upload.value = null
    abandoned.value = false
    forgetProgress()
    startedAt.value = Date.now()
    void guarded(async () => {
        try {
            await api.uploadToCard(file.id, file.name, useBinary.value, trim.value)
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

    <!-- Nothing to call stale before anything is known about the card. -->
    <PreviousSession v-if="stale && (listed || card !== 'unknown')"
                     what="This card and its listing"/>

    <!-- "Takes no other commands" is not decoration - it is what M28 does to the wire. -->
    <div v-if="upload || sending || abandoned" class="card card-rounded mb-3">
      <div class="card-body py-3">
        <div class="d-flex justify-content-between align-items-center mb-2">
          <strong>
            <template v-if="abandoned">Interrupted</template>
            <template v-else-if="transferring">Uploading</template>
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

        <!-- Which protocol the server actually ran and whether it actually trimmed, neither of
             which is what was asked for. -->
        <p v-if="upload" class="text-muted small mb-0 mt-2">
          {{ fileSize(upload.bytesSent) }} of {{ fileSize(upload.bytesTotal) }},
          {{ upload.blocksSent.toLocaleString() }} blocks sent
          <span class="badge ms-1" :class="upload.binary ? 'bg-info' : 'bg-light text-dark'">
            {{ upload.binary ? 'binary' : 'ASCII' }}
          </span>
          <span class="badge bg-light text-dark ms-1"
                :title="upload.trimmed
                        ? 'Comments and blank lines were left out of what reached the card.'
                        : 'The file was copied to the card byte for byte.'">
            {{ upload.trimmed ? 'trimmed' : 'verbatim' }}
          </span>
        </p>

        <!-- Measured here, not reported - see the class doc. Nothing is shown before the first
             sample, rather than a zero that would read as a measurement. -->
        <p v-if="elapsed" class="text-muted small mb-0 mt-1">
          {{ elapsed }} elapsed<template v-if="speed">, {{ speed }}</template>
          <template v-if="remaining">, ~{{ remaining }} remaining</template>
          <template v-else-if="transferring">, estimating what is left…</template>
        </p>

        <p v-if="abandoned" class="text-warning small mb-0">
          The link dropped while this was transferring, so it stopped where the bar left it and
          nothing will say how it ended. No end-of-file was sent, so the file is most likely still
          open on the card: reset the machine before deleting it, and do not print it.
        </p>
        <p v-else-if="transferring" class="text-muted small mb-0">
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

    <!-- The one place a GcodeFile is shown, so where what the slicer said belongs: estimated time
         and filament earn columns, since those are what somebody picking a file actually compares;
         slicer name and layer height are detail and ride in the row's title. A file with nothing
         read says "not stated", not "0 min" (which would claim the print is instant). -->
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

    <!-- No extension required: the server adds a printable one if missing, since M20 lists nothing
         else; the name actually used comes back with the first progress event. -->
    <div v-if="mayControl" class="row align-items-end pt-3 border-top">
      <div class="col-md-6">
        <!-- Not "to the card": where it ends up is the third checkbox's business. -->
        <label for="storage-upload" class="form-label mb-1">Upload a file</label>
        <input id="storage-upload" type="file" class="form-control form-control-sm"
               accept=".gcode,.gco,.g" :disabled="!canAct" @change="choose"/>
      </div>
      <!-- Each title hangs on the wrapper, not the input: a disabled control fires no events, the
           same reason the monitor page's dead tab wraps its anchor. Native rather than a Bootstrap
           tooltip, since startTheme() constructs those once before any island has rendered. -->
      <div class="col-auto">
        <div class="d-flex flex-wrap gap-3">
          <div class="form-check mb-2"
               :title="mayUseBinary ? undefined
                       : 'Unavailable: this firmware did not report BINARY_FILE_TRANSFER.'">
            <input id="storage-binary" v-model="binary" class="form-check-input" type="checkbox"
                   :disabled="!canAct || !mayUseBinary"/>
            <label class="form-check-label" for="storage-binary">Binary Transfer</label>
          </div>
          <!-- Not gated on the binary capability, though it only changes a binary transfer: the
               tooltip says which, since a label that fits in the space cannot. -->
          <div class="form-check mb-2"
               title="A binary transfer copies the file byte for byte unless this is ticked. An ASCII transfer leaves comments out either way.">
            <input id="storage-trim" v-model="trim" class="form-check-input" type="checkbox"
                   :disabled="!canAct"/>
            <label class="form-check-label" for="storage-trim">Trim GCode</label>
          </div>
          <div class="form-check mb-2"
               title="Unticked, the file is only kept on the server - send it to the card from the table above whenever the machine is free.">
            <input id="storage-to-card" v-model="toCard" class="form-check-input" type="checkbox"
                   :disabled="!canAct"/>
            <label class="form-check-label" for="storage-to-card">Send to card now</label>
          </div>
        </div>
      </div>
      <div class="col-auto">
        <button type="button" class="btn btn-sm btn-primary text-white"
                :disabled="!canAct || !chosen" @click="send()">Upload
        </button>
      </div>
    </div>
  </div>
</template>

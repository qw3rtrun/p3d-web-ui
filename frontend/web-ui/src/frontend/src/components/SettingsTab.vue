<script setup lang="ts">
import {computed, onMounted, onUnmounted, reactive, ref} from 'vue'
import {printer, RequestFailed, watchPrinter, type Subscription} from '../api/client'
import type {
    ActionRs,
    ConnectionState,
    ConnectionStateChanged,
    EepromReport,
    EventMessage,
    FarmRole,
    SettingsSection,
    SettingsValue,
    UnknownCommand,
} from '../api/types'
import {isUsable} from '../api/types'

/**
 * The machine's settings: what the firmware holds, and the operations that can be done to it.
 *
 * Whether this is offered at all was decided by the server: `monitor.html` disables the tab and
 * skips this island entirely when the firmware reports no `Cap:EEPROM` (true at request time, the
 * same arrangement as the Storage tab). The wire name is `EEPROM`; issue #88's `EEPROM_SETTINGS` is
 * Marlin's build macro and never leaves the compiler.
 *
 * Read (`M503`) asks what is live; a setter (`M92`, `M203`, …) changes live memory only and is lost
 * on a power cycle; Revert (`M501`) loads the EEPROM back over live memory, undoing this session;
 * Save (`M500`) writes live memory into the EEPROM; Reset to defaults (`M502`) loads the compiled
 * defaults, discarding every calibration, made permanent by a `M500` behind it; Validate (`M504`)
 * asks whether the stored copy is readable and changes nothing.
 *
 * `M501`/`M502` answer a bare `ok` with no detail, so the server sends its own `M503` behind each -
 * nothing here prompts for a Read afterwards, the new values simply arrive on the stream.
 *
 * The report is G-code with no parsed model on the server, so it is tokenised here in the browser
 * (see `wordsOf`); reading a value means finding its letter, while a setter's request body is the
 * browser's own shape, naming fields rather than letters - the two never collapse into one lookup.
 *
 * Only six of the reported settings (`EDITABLE` below) are editable. The rest are shown read-only on
 * purpose: `M301` hotend PID needs the autotune cycle (`M303`), not a text box, since a bad thermal
 * loop is how a heater runs away; `M200` volumetric extrusion reports disabled on this firmware, so
 * setting it has never been measured; `M145` material presets are indexed (two lines, `S0`/`S1`,
 * under one heading, so `code` is not a key); `M149`/`G21` units reinterpret every command already in
 * flight, including a running print's.
 *
 * An empty table can mean three different things - nothing read yet, read and genuinely empty, or
 * this firmware has no `M503` at all (`UnknownCommand` naming it, Marlin's `DISABLE_M503`) - and
 * they are tracked separately (`StorageTab.vue` draws the same distinction for a card listing),
 * since collapsing them would tell an operator something about their machine nobody established.
 */

const props = withDefaults(defineProps<{
    uuid: string
    role?: FarmRole
}>(), {role: 'VIEWER'})

const mayControl = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')

// How long to wait for a report before concluding there is not one coming.
const ANSWER_MS = 6000

const api = printer(props.uuid)

// Derived the way PrinterMonitor.vue derives it: ONLINE is only a socket, not yet a firmware that
// has acknowledged anything, and offering M500 in that state would send a command that goes nowhere.
const state = ref<ConnectionState>('OFFLINE')
const live = computed(() => isUsable(state.value))
const controlsEnabled = computed(() => mayControl.value && live.value)

const failure = ref<string | null>(null)
const notice = ref<string | null>(null)
const verdict = ref<string | null>(null)

// One line of the report, under the section it arrived beneath.
interface Value {
    code: string
    command: string
}

interface Group {
    // null for values that arrived before any heading
    title: string | null
    values: Value[]
}

const groups = ref<Group[]>([])
const reading = ref(false)
// Set when the report arrives or a read gives up waiting - an empty table means "answered with
// nothing" only once something asked.
const answered = ref(false)
// Driven by UnknownCommand naming M503 and nothing else; the server stops asking once this is true.
const noReport = ref(false)

// Named rather than left to a chain of v-else-if; see the class doc above.
const nothingReadYet = computed(() =>
    !noReport.value && !answered.value && groups.value.length === 0)
const readAndEmpty = computed(() =>
    !noReport.value && answered.value && groups.value.length === 0)

// ---------------------------------------------------------------- the stream

// Sections and values are separate events, interleaved in firmware order with nothing numbering the
// lines, so a value belongs to the most recent section ahead of it, tracked as the stream arrives.
function openSection(title: string) {
    // A heading already used means a second report has begun (another Read, or a server re-read
    // behind M501) - start over rather than appending, so a fresh report does not render twice.
    // Titles are distinct within one report; "Material heatup parameters" holds two M145 lines under
    // one heading rather than appearing twice.
    if (groups.value.some(group => group.title === title)) {
        groups.value = []
    }
    groups.value.push({title, values: []})
}

function addValue(value: Value) {
    if (groups.value.length === 0) {
        groups.value.push({title: null, values: []})
    }
    groups.value[groups.value.length - 1].values.push(value)
    answered.value = true
    reading.value = false
}

function onMessage(message: EventMessage) {
    if (message.id !== props.uuid) {
        return
    }
    switch (message.type) {
        case 'ConnectionStateChanged': {
            const event = message.event as ConnectionStateChanged
            state.value = event.to
            if (event.to === 'OFFLINE') {
                // What comes back on the same address may be a different firmware (a reflash, a
                // cable moved); holding the old report would show values this machine does not have.
                groups.value = []
                answered.value = false
                reading.value = false
                noReport.value = false
                verdict.value = null
                edits.clear()
            }
            break
        }

        case 'SettingsSection':
            openSection((message.event as SettingsSection).title)
            break

        case 'SettingsValue': {
            const value = message.event as SettingsValue
            addValue({code: value.code, command: value.command})
            break
        }

        case 'EepromReport':
            verdict.value = (message.event as EepromReport).message
            break

        case 'UnknownCommand':
            if ((message.event as UnknownCommand).command === 'M503') {
                noReport.value = true
                reading.value = false
            }
            break

        case 'ActionRs': {
            // M500's only confirmation (//action:notification Settings Stored) - the firmware says
            // nothing else about having stored anything.
            const action = message.event as ActionRs
            if (action.action === 'notification' && action.argument) {
                notice.value = action.argument
            }
            break
        }

        default:
            break
    }
}

// ---------------------------------------------------------------- editing

// One field of an editable setting: the letter it is reported under, and the name it is sent under
// (see the class doc above for why the two differ).
interface Field {
    letter: string
    field: string
    label: string
}

interface Editable {
    fields: Field[]
    apply: (body: Record<string, number>) => Promise<void>
}

// The six settings this tab may change, and what each one posts - see the class doc above for why
// the rest are read-only.
const EDITABLE: Record<string, Editable> = {
    M92: {
        fields: [
            {letter: 'X', field: 'x', label: 'X'},
            {letter: 'Y', field: 'y', label: 'Y'},
            {letter: 'Z', field: 'z', label: 'Z'},
            {letter: 'E', field: 'e', label: 'E'},
        ],
        apply: body => api.setStepsPerUnit(body),
    },
    M203: {
        fields: [
            {letter: 'X', field: 'x', label: 'X'},
            {letter: 'Y', field: 'y', label: 'Y'},
            {letter: 'Z', field: 'z', label: 'Z'},
            {letter: 'E', field: 'e', label: 'E'},
        ],
        apply: body => api.setMaxFeedrates(body),
    },
    M201: {
        fields: [
            {letter: 'X', field: 'x', label: 'X'},
            {letter: 'Y', field: 'y', label: 'Y'},
            {letter: 'Z', field: 'z', label: 'Z'},
            {letter: 'E', field: 'e', label: 'E'},
        ],
        apply: body => api.setMaxAccelerations(body),
    },
    M204: {
        fields: [
            {letter: 'P', field: 'print', label: 'Print'},
            {letter: 'R', field: 'retract', label: 'Retract'},
            {letter: 'T', field: 'travel', label: 'Travel'},
        ],
        apply: body => api.setAccelerations(body),
    },
    M205: {
        fields: [
            {letter: 'B', field: 'minSegmentTimeUs', label: 'Min segment µs'},
            {letter: 'S', field: 'minFeedrate', label: 'Min feedrate'},
            {letter: 'T', field: 'minTravelFeedrate', label: 'Min travel'},
            {letter: 'J', field: 'junctionDeviation', label: 'Junction dev.'},
        ],
        apply: body => api.setAdvancedMotion(body),
    },
    M206: {
        fields: [
            {letter: 'X', field: 'x', label: 'X'},
            {letter: 'Y', field: 'y', label: 'Y'},
            {letter: 'Z', field: 'z', label: 'Z'},
        ],
        apply: body => api.setHomeOffset(body),
    },
}

// The words of one reported line, by letter. Split on whitespace; the first token is the code.
// A trailing comment is part of the line verbatim (`G21 ; (mm)` is real), so the walk stops at `;`
// rather than reading `(mm)` as a word. A flag with no value (`M149 C`) yields an empty string,
// which is not a number and is never offered as a field.
function wordsOf(command: string): Record<string, string> {
    const words: Record<string, string> = {}
    for (const token of command.trim().split(/\s+/).slice(1)) {
        if (token.startsWith(';')) {
            break
        }
        const letter = token.charAt(0).toUpperCase()
        if (letter >= 'A' && letter <= 'Z') {
            words[letter] = token.slice(1)
        }
    }
    return words
}

// What the operator has typed, keyed by row and field - kept apart from the report so an incoming
// value (a revert, another operator's read) does not silently rewrite a half-typed number.
const edits = reactive(new Map<string, string>())

const editKey = (row: number, field: string) => `${row}:${field}`

const typed = (row: number, field: string) => edits.get(editKey(row, field)) ?? ''

function onType(row: number, field: string, text: string) {
    if (text.trim() === '') {
        edits.delete(editKey(row, field))
    } else {
        edits.set(editKey(row, field), text)
    }
}

// Every row of the report, flattened and numbered because `code` is not a key - both M145 preset
// lines carry M145, and an edit has to belong to a row rather than a code.
const rows = computed(() => {
    const flat: {
        row: number
        title: string | null
        first: boolean
        code: string
        command: string
        words: Record<string, string>
        editable: Editable | null
    }[] = []
    let row = 0
    for (const group of groups.value) {
        group.values.forEach((value, index) => {
            flat.push({
                row: row++,
                title: group.title,
                first: index === 0,
                code: value.code,
                command: value.command,
                words: wordsOf(value.command),
                editable: EDITABLE[value.code] ?? null,
            })
        })
    }
    return flat
})

// ---------------------------------------------------------------- the commands

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

// Groups are not cleared here: a repeated section heading is what starts a fresh report (see
// openSection) - clearing here too would blank the table while a machine answers with the same thing.
function read() {
    reading.value = true
    void guarded(async () => {
        await api.readSettings()
        window.setTimeout(() => {
            if (reading.value) {
                reading.value = false
                answered.value = true
            }
        }, ANSWER_MS)
    })
}

// Moved here from PrinterMonitor.vue with its warning: the one control whose effect outlives the
// connection.
const STORE_WARNING = [
    'Write this machine\'s current settings to its EEPROM?',
    'They survive a power cycle, and undoing it means resetting the firmware to defaults.',
].join('\n\n')

// A different sentence from STORE_WARNING on purpose: M502 discards live memory (including anything
// just typed) rather than writing it, and names its own escape (Revert) since the EEPROM is
// untouched until a Save - "are you sure" alone would not tell an operator they are one button from
// being fine.
const DEFAULTS_WARNING = [
    'Load this firmware\'s compiled defaults over everything in live memory?',
    'Every value above is discarded, including anything you have just typed.',
    'The EEPROM is untouched until you Save, so "Revert from EEPROM" undoes this.',
].join('\n\n')

const save = () => {
    if (!window.confirm(STORE_WARNING)) {
        return
    }
    notice.value = null
    return guarded(() => api.storeSettings())
}

const revert = () => guarded(() => api.revertSettings())

const resetToDefaults = () => {
    if (!window.confirm(DEFAULTS_WARNING)) {
        return
    }
    edits.clear()
    return guarded(() => api.resetSettingsToDefaults())
}

const validate = () => {
    verdict.value = null
    return guarded(() => api.validateSettings())
}

// Sends only the fields actually touched: an omitted word leaves the firmware's value alone, and
// zero does not (an axis with no steps per millimetre cannot move). A non-numeric field refuses the
// whole row rather than sending NaN.
function apply(row: number, editable: Editable | null) {
    if (!editable) {
        return
    }
    const body: Record<string, number> = {}
    for (const field of editable.fields) {
        const text = typed(row, field.field)
        if (text.trim() === '') {
            continue
        }
        const value = Number(text)
        if (!Number.isFinite(value)) {
            failure.value = `${field.label} is not a number.`
            return
        }
        body[field.field] = value
    }
    if (Object.keys(body).length === 0) {
        return
    }
    void guarded(async () => {
        await editable.apply(body)
        for (const field of editable.fields) {
            edits.delete(editKey(row, field.field))
        }
        // Nothing announces a successful set, so ask - the answer re-draws the report, the only
        // way to see the firmware took the value and in what scale.
        await api.readSettings()
    })
}

let subscription: Subscription | null = null

onMounted(async () => {
    subscription = watchPrinter(props.uuid, onMessage)
    // The stream announces transitions; this asks where the machine is *now*, which is what a tab
    // opened against a machine that has been quietly idle for an hour needs.
    await guarded(async () => {
        const status = await api.connection()
        state.value = status.state
    })
})

onUnmounted(() => {
    subscription?.close()
    subscription = null
})
</script>

<template>
  <div class="pt-3">
    <div class="d-sm-flex justify-content-between align-items-start mb-3">
      <div>
        <h4 class="card-title mb-1">Settings</h4>
        <p class="card-subtitle mb-0">
          What the firmware is using. A change here is in the machine's memory only - it survives a
          power cycle only once it is saved to the EEPROM.
        </p>
      </div>

      <div v-if="mayControl" class="d-flex flex-wrap gap-2">
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!controlsEnabled || noReport" @click="read()">Read (M503)
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!controlsEnabled" @click="revert()">Revert from EEPROM (M501)
        </button>
        <button type="button" class="btn btn-sm btn-outline-warning"
                :disabled="!controlsEnabled" @click="save()">Save to EEPROM (M500)
        </button>
        <button type="button" class="btn btn-sm btn-outline-danger"
                :disabled="!controlsEnabled" @click="resetToDefaults()">
          Reset to firmware defaults (M502)
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!controlsEnabled" @click="validate()">Validate EEPROM (M504)
        </button>
      </div>
    </div>

    <div v-if="failure" class="alert alert-warning py-2" role="alert">{{ failure }}</div>
    <div v-if="notice" class="alert alert-success py-2" role="alert">{{ notice }}</div>

    <!-- M504's answer, as text - no tick or cross, since only "echo:EEPROM OK" has ever been measured. -->
    <div v-if="verdict" class="alert alert-info py-2" role="alert">
      The firmware says of its EEPROM: <strong>{{ verdict }}</strong>
    </div>

    <div v-if="!live" class="alert alert-secondary py-2" role="alert">
      <span v-if="state === 'ONLINE'">
        A socket is open, but the firmware has not acknowledged anything yet - the controls stay off
        until it does.
      </span>
      <span v-else-if="state === 'CONNECTING'">Connecting…</span>
      <span v-else>This machine is not connected, so nothing can be read or changed.</span>
    </div>

    <!-- The three states an empty table can be in. -->
    <p v-if="noReport" class="text-muted">
      This firmware has no settings report - it refused <code>M503</code>, which is Marlin's
      <code>DISABLE_M503</code>. It still stores settings, so saving, reverting and resetting all
      work; there is simply nothing to list, and nothing asks again.
    </p>
    <template v-else>
      <p v-if="reading" class="text-muted">Reading the settings…</p>
      <p v-else-if="nothingReadYet" class="text-muted">
        Nothing has been read yet. Press Read to ask the machine what it is using.
      </p>
      <p v-else-if="readAndEmpty" class="text-muted">
        The machine reported no settings at all. That is not an empty machine - it is an answer
        nobody got, so ask again.
      </p>

      <!-- Grouped by section, firmware order; a section with two lines (M145 presets) shows both. -->
      <table v-else class="table table-sm align-middle">
        <thead>
        <tr>
          <th scope="col">Setting</th>
          <th scope="col">What the machine reports</th>
          <th scope="col">Change it</th>
          <th scope="col" class="text-end"></th>
        </tr>
        </thead>
        <tbody>
        <template v-for="entry in rows" :key="entry.row">
          <tr v-if="entry.first && entry.title" class="table-light">
            <th colspan="4" class="fw-semibold" scope="colgroup">{{ entry.title }}</th>
          </tr>
          <tr>
            <td><code>{{ entry.code }}</code></td>
            <td class="text-muted"><code>{{ entry.command }}</code></td>
            <td>
              <!-- The placeholder is the machine's own value, visible without an edit starting from it. -->
              <div v-if="entry.editable && mayControl" class="d-flex flex-wrap gap-2">
                <div v-for="field in (entry.editable?.fields ?? [])" :key="field.field"
                     class="input-group input-group-sm" style="width: 9rem;">
                  <span class="input-group-text">{{ field.label }}</span>
                  <input type="text" inputmode="decimal"
                         class="form-control"
                         :disabled="!controlsEnabled"
                         :placeholder="entry.words[field.letter] ?? '-'"
                         :aria-label="entry.code + ' ' + field.label"
                         :value="typed(entry.row, field.field)"
                         @input="onType(entry.row, field.field,
                                        ($event.target as HTMLInputElement).value)"/>
                </div>
              </div>
              <span v-else-if="entry.editable" class="text-muted small">
                Read-only for your role.
              </span>
              <span v-else class="text-muted small">Not editable here.</span>
            </td>
            <td class="text-end">
              <button v-if="entry.editable && mayControl" type="button"
                      class="btn btn-sm btn-outline-primary"
                      :disabled="!controlsEnabled" @click="apply(entry.row, entry.editable)">
                Apply
              </button>
            </td>
          </tr>
        </template>
        </tbody>
      </table>

      <!-- Why four reported settings have no fields - written on the page, not only in the source,
           since the obvious reading of a read-only row is that somebody ran out of time. -->
      <p v-if="rows.length" class="text-muted small">
        The hotend PID (<code>M301</code>), volumetric extrusion (<code>M200</code>), the material
        presets (<code>M145</code>) and the units (<code>M149</code>, <code>G21</code>) are reported
        and not editable here. PID is a thermal-safety change and needs the autotune cycle rather
        than a text box; <code>M200</code> reports as disabled on this firmware, so setting it has
        never been measured; a preset is indexed, so a control has to say which one; and changing
        the units mid-session reinterprets every command already in flight, including a running
        print's.
      </p>
    </template>
  </div>
</template>

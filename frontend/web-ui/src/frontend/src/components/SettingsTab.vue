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
 * The machine's settings: what the firmware holds, and the five things that can be done to it.
 *
 * **Whether this is offered at all was decided by the server.** `monitor.html` renders the tab
 * disabled, and this island not at all, when the firmware reports no `Cap:EEPROM`. The capability
 * is true at request time, so asking again from here would be re-learning what the page already
 * knew - the same arrangement the Storage tab has.
 *
 * Note that the capability's name on the wire is `EEPROM`. Issue #88 calls it `EEPROM_SETTINGS`,
 * which is Marlin's **build macro** and never leaves the compiler.
 *
 * **Five operations, and the differences between them are what this view exists to make visible:**
 *
 * - *Read* (`M503`) asks what is live.
 * - A setter (`M92`, `M203`, …) changes **live memory only**. Power-cycle the machine and it is
 *   gone.
 * - *Revert* (`M501`) loads the EEPROM back over live memory - the undo for this session.
 * - *Save* (`M500`) writes live memory into the EEPROM, where it survives a power cycle.
 * - *Reset to defaults* (`M502`) loads what the firmware was compiled with, discarding every
 *   calibration the machine has - and a `M500` behind it makes that permanent.
 *
 * Plus *Validate* (`M504`), which asks whether the stored copy is readable and changes nothing.
 *
 * **Nothing here prompts for a Read after a revert or a reset.** `M501` and `M502` answer a bare
 * `ok` and announce none of the dozen values they just changed, so the server sends its own `M503`
 * behind each of them: the new values simply arrive on the stream.
 */

const props = withDefaults(defineProps<{
    uuid: string
    role?: FarmRole
}>(), {role: 'VIEWER'})

const mayControl = computed(() => props.role === 'OPERATOR' || props.role === 'ADMIN')

/** How long to wait for a report before concluding there is not one coming. */
const ANSWER_MS = 6000

const api = printer(props.uuid)

/**
 * The connection, derived the way `PrinterMonitor.vue` derives it.
 *
 * `IDLE` and `BUSY` mean the firmware acknowledged a block this host sent; `ONLINE` means only that
 * a socket is open, which a terminal server with nothing plugged into it also satisfies. Offering
 * `M500` in that state produces a command that goes nowhere.
 */
const state = ref<ConnectionState>('OFFLINE')
const live = computed(() => isUsable(state.value))
const controlsEnabled = computed(() => mayControl.value && live.value)

const failure = ref<string | null>(null)
const notice = ref<string | null>(null)
const verdict = ref<string | null>(null)

/** One line of the report, under the section it arrived beneath. */
interface Value {
    code: string
    command: string
}

interface Group {
    /** The section heading, or null for values that arrived before any heading. */
    title: string | null
    values: Value[]
}

const groups = ref<Group[]>([])
const reading = ref(false)

/**
 * Whether a read has actually completed, which is what separates two of the three states below.
 *
 * Set when the report arrives and when a read gives up waiting, so an empty table means "the
 * machine answered with nothing" only once something asked.
 */
const answered = ref(false)

/**
 * Whether this firmware has no `M503` at all.
 *
 * Driven by `UnknownCommand` naming `M503` and by nothing else. Marlin's `DISABLE_M503` strips the
 * report out of a build that stores settings perfectly well, and `M115` says nothing about it under
 * any spelling - so the refusal is the only notice there is, and the server stops asking once it
 * has one.
 */
const noReport = ref(false)

/**
 * The three things an empty table can mean, named rather than left to a chain of `v-else-if`.
 *
 * `StorageTab.vue` draws the same distinction for a card listing and for the same reason: showing
 * "nothing read yet" and "read, and the machine reported nothing" as the same empty table tells an
 * operator a fact about their machine that nobody has established. The third is a fact about the
 * *firmware* and is not an empty report at all.
 */
const nothingReadYet = computed(() =>
    !noReport.value && !answered.value && groups.value.length === 0)
const readAndEmpty = computed(() =>
    !noReport.value && answered.value && groups.value.length === 0)

// ---------------------------------------------------------------- the stream

/**
 * A value belongs to the most recent section that arrived before it.
 *
 * Sections and values are separate events, interleaved, in the order the firmware wrote them -
 * there is no nesting on the wire and nothing numbers the lines, so the grouping has to be tracked
 * as the stream arrives. A value with no section ahead of it gets a group with no title, which is
 * what an `M503 S0`-shaped report would be in its entirety.
 */
function openSection(title: string) {
    // A heading this report has already used means a *second* report has begun - somebody pressed
    // Read, or the server re-read behind an M501. Start over rather than appending, which is what
    // keeps a snapshot followed by a fresh report from rendering the settings twice. The section
    // titles within one report are distinct; "Material heatup parameters" holds two M145 lines
    // under one heading rather than appearing twice.
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
                // A machine that went away kept none of this, and what comes back up on the same
                // address may be a different firmware - a reflash, or a cable moved. The server
                // forgets the session's report for exactly this reason; holding it here would show
                // values this machine does not have.
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
            // M500's only confirmation: `//action:notification Settings Stored`. The firmware says
            // nothing else about having stored anything, so this is what tells an operator it took.
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

/**
 * One field of an editable setting: the letter it is reported under and the name it is sent under.
 *
 * **The two are different on purpose.** The report is G-code - `M204 P500.00 R1000.00 T1000.00` -
 * so reading a value out means finding the word by letter; the request body is the browser's own
 * shape, so sending it means naming the field the server declared. Deriving one from the other
 * would tie the HTTP contract to Marlin's letters, which is what the command records exist to
 * avoid.
 */
interface Field {
    letter: string
    field: string
    label: string
}

interface Editable {
    fields: Field[]
    apply: (body: Record<string, number>) => Promise<void>
}

/**
 * The six settings this tab may change, and what each one posts.
 *
 * **The others are shown and not editable, deliberately, and this is the comment that says why** -
 * because somebody will otherwise "finish" the tab:
 *
 * - `M301` hotend PID. Changing a thermal control loop badly is how a heater runs away; it needs a
 *   control that understands the autotune cycle (`M303`) and its own issue, not a text box.
 * - `M200` volumetric extrusion. This build reports it **disabled**, so the set path has never been
 *   measured on a machine that would act on it - and guessing at a reply or a unit is how this
 *   repository got protocol details wrong before.
 * - `M145` material presets. Indexed - two lines under one heading, `S0` and `S1` - so a control
 *   has to carry which preset it is editing, and the report's `code` is not a key.
 * - `M149`/`G21` units. Changing the unit mid-session reinterprets every command already in
 *   flight, including a running print's; it is not a setting so much as a change of language.
 */
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

/**
 * The words of one reported line, by letter.
 *
 * Tokenised **here, in the browser**, because the report is G-code and there is no parsed model of
 * it on the server - deliberately, since the report already carries Marlin's own command set and a
 * second number model would be a second place for the scale rules to drift.
 *
 * Split on whitespace; the first token is the code and the rest are letter-and-value words. A
 * trailing comment is part of the reported line verbatim - `G21 ; (mm)` and
 * `M149 C ; Units in Celsius` are real - so the walk stops at the `;` rather than reading `(mm)` as
 * a word. A flag with no value (`M149 C`) yields an empty string, which is not a number and is
 * never offered as a field.
 */
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

/**
 * What the operator has typed, keyed by the row and the field.
 *
 * Kept apart from the report rather than editing it in place, so an incoming value - a revert, or
 * another operator's read - does not silently rewrite a half-typed number, and so the field can
 * show the machine's current value beside it. A row with nothing typed in it sends nothing.
 */
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

/**
 * Every row of the report, flattened with its section, its words and whether it can be edited.
 *
 * Flattened for the template's benefit and numbered, because `code` is **not** a key: both `M145`
 * preset lines carry `M145`, and an edit has to belong to a row rather than to a code.
 */
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

/**
 * Ask for the report, and say so when nothing comes back.
 *
 * The groups are *not* cleared here: a repeated section heading is what starts a fresh report (see
 * `openSection`), which also covers a report somebody else asked for and a server-side re-read
 * behind an `M501`. Clearing on the click as well would blank the table for a machine that is about
 * to answer with the same thing.
 */
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

/**
 * Writing live memory to the EEPROM, behind a confirmation.
 *
 * Moved here from `PrinterMonitor.vue` with its warning: this is the one control whose effect
 * outlives the connection, and undoing it means `M502` and re-entering whatever was right.
 */
const STORE_WARNING = [
    'Write this machine\'s current settings to its EEPROM?',
    'They survive a power cycle, and undoing it means resetting the firmware to defaults.',
].join('\n\n')

/**
 * And the other confirmation, which has to be a *different* sentence.
 *
 * `M502` is the more destructive of the two and in the less obvious way. Save writes what is there;
 * this **discards** it - including whatever the operator has just typed into the fields above - and
 * replaces it with what the firmware was compiled with. On a measured machine the defaults and the
 * stored settings genuinely differ, so it is not the no-op it can look like.
 *
 * The sentence names the escape, because there is one and it is not obvious: the EEPROM is
 * untouched until a Save, so Revert undoes this. Saying "are you sure" instead would leave an
 * operator who clicked it by mistake with no idea that they are one button from being fine.
 */
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

/**
 * Send one row's typed values, and only those.
 *
 * An untouched field sends nothing at all rather than echoing the machine's own number back at it:
 * an omitted word leaves the firmware's value alone, and zero does not - an axis with no steps per
 * millimetre cannot move, and an `M206` naming one axis must not zero the other two.
 *
 * A field that is not a number refuses the whole row rather than being sent as `NaN`.
 *
 * `editable` is nullable because a row's is - the template only offers the button on a row that has
 * one, and this checks rather than asserting, so the narrowing is in the code and not in a `!`.
 */
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
        // Nothing announces a successful set, so ask - and the answer re-draws the whole report,
        // which is the only way to see that the firmware took the value and in what scale.
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

    <!--
      M504's answer, as text. There is deliberately no tick and no cross: `echo:EEPROM OK` is the
      only verdict anybody has measured, the failure text is unknown, and a colour derived from
      guessing at it would be a claim this farm cannot make.
    -->
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

      <!--
        The report, grouped by the section each value arrived under and in the order the firmware
        wrote them. A section with two lines in it - "Material heatup parameters" has two M145
        presets - shows both.
      -->
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
              <!--
                Editable for the six in EDITABLE and read-only for the rest. The field shows what
                was typed, and the machine's own value as the placeholder, so the current number is
                visible without an edit having to start from it.
              -->
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

      <!--
        Why four of the reported settings have no fields. Written on the page and not only in the
        source, because the obvious reading of a read-only row is that somebody ran out of time.
      -->
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

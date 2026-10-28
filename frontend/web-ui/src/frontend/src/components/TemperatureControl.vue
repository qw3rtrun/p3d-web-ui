<script setup lang="ts">
import {computed, ref, watch} from 'vue'

/**
 * One heater: what it reads, what it is aiming at, and the controls to change that.
 *
 * Ported from `static/js/component/temperature-control.js`. Two things changed beyond the syntax:
 *
 * - **The target input no longer fights the machine.** The POC bound the slider straight to a ref
 *   that a `watch` on the incoming target also wrote, so a reading arriving mid-drag yanked the
 *   slider out from under the operator. The input is now only adopted from the machine while the
 *   operator is not touching it - see `editing`.
 * - **`presets` is typed and non-optional.** The POC read `presets[presetIndex]` in a computed that
 *   ran before anything set the index, which is an undefined dereference on the first render for
 *   any caller that passed an empty list.
 */

export interface Preset {
    label: string
    value: number
}

const props = withDefaults(defineProps<{
    name: string
    /** What the sensor reads now. */
    current: number
    /** What the machine is aiming at. 0 means off. */
    target: number
    /** Marlin's PWM scale, 0..127. */
    power?: number
    presets?: Preset[]
    max?: number
    /** An operator may heat; a viewer may only watch. The server enforces this regardless. */
    canControl?: boolean
}>(), {
    power: 0,
    presets: () => [],
    max: 350,
    canControl: false,
})

const emit = defineEmits<{ change: [target: number] }>()

const input = ref(props.target)
const editing = ref(false)

/**
 * Adopt the machine's target, but not while somebody is setting one.
 *
 * Without the guard, a temperature report once a second rewrites the number under the operator's
 * cursor - which in the POC made the slider impossible to drag on a connected printer.
 */
watch(() => props.target, (next) => {
    if (!editing.value) {
        input.value = next
    }
})

const heating = computed(() => props.target > 0)

/** Marlin reports 0..127; a percentage is what a person reads. */
const powerPercent = computed(() => Math.round((props.power / 127) * 100))

function apply(value: number) {
    const clamped = Math.min(Math.max(Math.round(value), 0), props.max)
    input.value = clamped
    editing.value = false
    emit('change', clamped)
}

function off() {
    apply(0)
}
</script>

<template>
  <div class="row pt-2 align-items-center">
    <div class="col-12 col-md-3">
      <h4 class="card-title mb-0">
        {{ current.toFixed(1) }} °C
        <small v-if="heating" class="text-muted">&rarr; {{ target }} °C</small>
      </h4>
      <p class="card-subtitle mb-0">
        {{ name }}
        <span v-if="power > 0" class="badge bg-warning text-dark ms-1">{{ powerPercent }}%</span>
      </p>
    </div>

    <div class="col">
      <div class="row justify-content-between">
        <div class="col">
          <label :for="`${name}-target`" class="form-label mb-0">Target</label>
        </div>
        <div class="col-auto">
          <input
              :id="`${name}-target`"
              v-model.number="input"
              type="number"
              class="form-control form-control-sm d-inline-block"
              style="width: 6rem"
              min="0"
              :max="max"
              :disabled="!canControl"
              @focus="editing = true"
              @change="apply(input)"
          />
        </div>
      </div>
      <input
          v-model.number="input"
          type="range"
          class="form-range"
          min="0"
          :max="max"
          step="5"
          :disabled="!canControl"
          @mousedown="editing = true"
          @touchstart="editing = true"
          @change="apply(input)"
      />
    </div>

    <div class="col-auto">
      <button type="button" class="btn btn-sm btn-outline-secondary"
              :disabled="!canControl" @click="off()">
        Off
      </button>
    </div>

    <div v-if="presets.length" class="col-auto">
      <div class="btn-group">
        <button
            v-for="preset in presets"
            :key="preset.label"
            type="button"
            class="btn btn-sm btn-outline-danger"
            :disabled="!canControl"
            @click="apply(preset.value)"
        >
          {{ preset.label }} {{ preset.value }}°
        </button>
      </div>
    </div>
  </div>
</template>

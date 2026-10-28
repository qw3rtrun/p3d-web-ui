<script setup lang="ts">
import {computed, ref, watch} from 'vue'

/**
 * The part-cooling fan.
 *
 * <strong>The unit conversion is the whole job of this component.</strong> Marlin's fan speed is a
 * 0..255 PWM value and a person thinks in percent, so the percentage lives here and only one unit
 * ever travels to the machine. Putting the conversion in `client.ts` instead would mean every
 * future caller choosing a unit again.
 *
 * There is no reported fan speed to mirror: Marlin does not report one the way it reports a
 * temperature, so what is shown is what this page last asked for. That is honest for a control
 * nobody else is touching and wrong the moment a print's own `M106` changes it - which is why the
 * label says "requested" rather than pretending to be a reading.
 */
const props = withDefaults(defineProps<{
    /** 0..255, Marlin's scale. Null when nothing has asked for a speed this session. */
    speed?: number | null
    canControl?: boolean
}>(), {speed: null, canControl: false})

const emit = defineEmits<{ change: [speed: number] }>()

const MAX = 255

const percent = ref(toPercent(props.speed))
const editing = ref(false)

function toPercent(speed: number | null): number {
    return speed === null ? 0 : Math.round((speed / MAX) * 100)
}

watch(() => props.speed, (next) => {
    if (!editing.value) {
        percent.value = toPercent(next)
    }
})

const running = computed(() => (props.speed ?? 0) > 0)

function apply(value: number) {
    const clamped = Math.min(Math.max(Math.round(value), 0), 100)
    percent.value = clamped
    editing.value = false
    // Round-trip through the machine's own scale so the number sent is the number meant.
    emit('change', Math.round((clamped / 100) * MAX))
}
</script>

<template>
  <div class="row pt-2 align-items-center">
    <div class="col-12 col-md-3">
      <h4 class="card-title mb-0">
        {{ percent }} %
        <small v-if="running" class="text-muted">requested</small>
      </h4>
      <p class="card-subtitle mb-0">Part fan</p>
    </div>

    <div class="col">
      <label for="fan-speed" class="form-label mb-0">Speed</label>
      <input
          id="fan-speed"
          v-model.number="percent"
          type="range"
          class="form-range"
          min="0"
          max="100"
          step="5"
          :disabled="!canControl"
          @mousedown="editing = true"
          @touchstart="editing = true"
          @change="apply(percent)"
      />
    </div>

    <div class="col-auto">
      <div class="btn-group">
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!canControl" @click="apply(0)">Off
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!canControl" @click="apply(50)">50%
        </button>
        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!canControl" @click="apply(100)">Full
        </button>
      </div>
    </div>
  </div>
</template>

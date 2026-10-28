<script setup lang="ts">
import {computed, ref} from 'vue'

/**
 * Homing, jogging, and where the machine says it is.
 *
 * <strong>Jogging is relative, which is what the buttons mean.</strong> An operator pressing "X
 * +10" knows nothing about the current position, and a host that computed an absolute target would
 * be wrong whenever its idea of the position was stale - after a manual move, after a reset, and
 * always before the first home. The server sends the `G91`/`G90` mode switch around the move, so
 * nothing here has to know the machine has modes.
 *
 * <strong>Only the axis being jogged is sent.</strong> An omitted axis is not zero: zero in
 * relative mode means "do not move this axis", and a request naming all three would move all three.
 *
 * The position is not polled. Marlin has no auto-report for it the way it has `M155` for
 * temperature, so asking on a timer would put an `M114` in the command queue every tick - competing
 * with a print for the planner. This asks once after a move and whenever a person presses refresh.
 */
const props = withDefaults(defineProps<{
    /** Keyed by axis letter, from `PositionReported`. Empty until the machine is asked. */
    position?: Record<string, number>
    canControl?: boolean
}>(), {position: () => ({}), canControl: false})

const emit = defineEmits<{
    home: [axes: { x?: boolean; y?: boolean; z?: boolean }]
    jog: [move: { x?: number; y?: number; z?: number; feedRate: number }]
    readPosition: []
}>()

/** Millimetres per press. 0.1 for levelling, 100 to cross the bed. */
const STEPS = [0.1, 1, 10, 100]
const step = ref(10)

/**
 * Z moves slower than X and Y, and the difference is not cosmetic.
 *
 * A Z axis is usually a leadscrew geared far lower than the belts; driving it at an XY feedrate
 * asks for steps the motor cannot take, and the machine answers by losing them silently - so the
 * position it reports afterwards is wrong and the next print starts at the wrong height.
 */
const XY_FEEDRATE = 3000
const Z_FEEDRATE = 600

const axes = computed(() => ['X', 'Y', 'Z', 'E']
    .filter(axis => props.position[axis] !== undefined)
    .map(axis => ({axis, value: props.position[axis]})))

const known = computed(() => axes.value.length > 0)

function jog(axis: 'x' | 'y' | 'z', direction: 1 | -1) {
    emit('jog', {
        [axis]: step.value * direction,
        feedRate: axis === 'z' ? Z_FEEDRATE : XY_FEEDRATE,
    })
}
</script>

<template>
  <div class="row pt-3">
    <div class="col-12">
      <h4 class="card-title">Motion</h4>
    </div>

    <!-- Where it is -->
    <div class="col-12 mb-3">
      <div class="d-flex align-items-center gap-3 flex-wrap">
        <template v-if="known">
          <div v-for="a in axes" :key="a.axis">
            <span class="text-muted">{{ a.axis }}</span>
            <strong class="ms-1">{{ a.value.toFixed(2) }}</strong>
          </div>
        </template>
        <span v-else class="text-muted">Position unknown — ask the machine.</span>

        <button type="button" class="btn btn-sm btn-outline-secondary"
                :disabled="!canControl" @click="emit('readPosition')">
          Read position
        </button>
      </div>
    </div>

    <!-- Homing -->
    <div class="col-12 mb-3">
      <label class="form-label mb-1">Home</label>
      <div class="btn-group d-block">
        <button type="button" class="btn btn-sm btn-primary text-white"
                :disabled="!canControl" @click="emit('home', {})">All
        </button>
        <button type="button" class="btn btn-sm btn-outline-primary"
                :disabled="!canControl" @click="emit('home', {x: true})">X
        </button>
        <button type="button" class="btn btn-sm btn-outline-primary"
                :disabled="!canControl" @click="emit('home', {y: true})">Y
        </button>
        <button type="button" class="btn btn-sm btn-outline-primary"
                :disabled="!canControl" @click="emit('home', {z: true})">Z
        </button>
      </div>
    </div>

    <!-- Step size -->
    <div class="col-12 mb-3">
      <label class="form-label mb-1">Step</label>
      <div class="btn-group d-block">
        <button
            v-for="size in STEPS"
            :key="size"
            type="button"
            class="btn btn-sm"
            :class="step === size ? 'btn-secondary text-white' : 'btn-outline-secondary'"
            @click="step = size"
        >
          {{ size }} mm
        </button>
      </div>
    </div>

    <!-- Jog -->
    <div class="col-auto">
      <label class="form-label mb-1 d-block">X / Y</label>
      <div class="d-flex gap-2 align-items-center">
        <button type="button" class="btn btn-sm btn-outline-dark"
                :disabled="!canControl" @click="jog('x', -1)">&larr; X
        </button>
        <div class="d-flex flex-column gap-1">
          <button type="button" class="btn btn-sm btn-outline-dark"
                  :disabled="!canControl" @click="jog('y', 1)">&uarr; Y
          </button>
          <button type="button" class="btn btn-sm btn-outline-dark"
                  :disabled="!canControl" @click="jog('y', -1)">&darr; Y
          </button>
        </div>
        <button type="button" class="btn btn-sm btn-outline-dark"
                :disabled="!canControl" @click="jog('x', 1)">X &rarr;
        </button>
      </div>
    </div>

    <div class="col-auto">
      <label class="form-label mb-1 d-block">Z</label>
      <div class="d-flex flex-column gap-1">
        <button type="button" class="btn btn-sm btn-outline-dark"
                :disabled="!canControl" @click="jog('z', 1)">&uarr; Z
        </button>
        <button type="button" class="btn btn-sm btn-outline-dark"
                :disabled="!canControl" @click="jog('z', -1)">&darr; Z
        </button>
      </div>
    </div>
  </div>
</template>

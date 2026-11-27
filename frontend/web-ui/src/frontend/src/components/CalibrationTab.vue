<script setup lang="ts">
import {ref} from 'vue'
import type {FarmRole} from '../api/types'
import LevelBedCorners from './LevelBedCorners.vue'

/**
 * The Calibration tab: a list of the calibrations this farm can drive, one row each.
 *
 * Today there is exactly one row. The accordion is the shape because more are coming (issue #174's
 * `G29`, and whatever follows), and adding one must mean adding a row and a child component rather
 * than rearranging this file. The rows are written out rather than driven from a registry on
 * purpose: each calibration takes the props it needs - this one takes a bed size - and a registry
 * would force every future one into a single prop shape.
 *
 * **A row's body mounts only while that row is open** - `v-if`, not `v-show`. A calibration panel is
 * not inert markup: `LevelBedCorners` subscribes to the printer's stream and asks the machine where
 * it is, so a collapsed one must open no stream and run no logic. That is also why Bootstrap's own
 * `Collapse` is not used here and the open row is a `ref` instead: `Collapse` animates by measuring
 * the element it is opening, and with `v-if` the body does not exist when that measurement is taken,
 * so the two would fight over the same element. The `ref` is needed regardless, since one row open
 * at a time is a decision about state rather than about markup - what is left is Bootstrap's
 * accordion classes, driven by `:class` and a click handler.
 *
 * Note that this island itself mounts on page load whatever tab is showing, because Bootstrap keeps
 * every tab pane in the DOM from the start - which is the whole reason the `v-if` above matters.
 */

const props = withDefaults(defineProps<{
    uuid: string
    role?: FarmRole
    // `data-` attributes, so strings - passed straight down and parsed by the child that needs them.
    bedX?: string
    bedY?: string
    bedZ?: string
}>(), {role: 'VIEWER'})

const open = ref<string | null>(null)

const toggle = (row: string) => open.value = open.value === row ? null : row
</script>

<template>
  <div class="pt-3">
    <h4 class="card-title mb-1">Calibration</h4>
    <p class="card-subtitle mb-3">Procedures that move the machine. Open one to use it.</p>

    <div class="accordion" id="calibration-accordion">
      <div class="accordion-item">
        <h2 class="accordion-header">
          <button class="accordion-button" type="button"
                  :class="{collapsed: open !== 'bed-corners'}"
                  :aria-expanded="open === 'bed-corners'" aria-controls="calibration-bed-corners"
                  @click="toggle('bed-corners')">
            Level Bed Corners
          </button>
        </h2>
        <div id="calibration-bed-corners" class="accordion-collapse collapse"
             :class="{show: open === 'bed-corners'}">
          <div class="accordion-body">
            <LevelBedCorners v-if="open === 'bed-corners'"
                             :uuid="props.uuid" :role="props.role"
                             :bed-x="props.bedX" :bed-y="props.bedY" :bed-z="props.bedZ"/>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

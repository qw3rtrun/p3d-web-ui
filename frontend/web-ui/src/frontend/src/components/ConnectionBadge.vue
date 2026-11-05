<script setup lang="ts">
import {computed} from 'vue'
import type {ConnectionState} from '../api/types'
import {isLinked, isUsable} from '../api/types'

/**
 * Where one machine's connection is, as a badge.
 *
 * One state from one event: the POC kept `connected` and `online` as separate booleans set from four
 * event names, with `online` turned on by any event arriving, so a connected-but-quiet printer read
 * as offline. The server owns a single state now and announces every transition as one
 * `ConnectionStateChanged`.
 *
 * Subscribes to nothing itself - whoever owns the page owns the stream and passes the state down,
 * which is what keeps a dashboard of forty rows on one subscription.
 */
const props = withDefaults(defineProps<{
    state: ConnectionState
    attempt?: number
    reason?: string | null
}>(), {attempt: 0, reason: null})

// ONLINE is a socket, IDLE is a firmware that acknowledged something this host sent - different
// colours for different facts, since a terminal server with nothing plugged in stops at ONLINE.
const tone = computed(() => {
    if (props.state === 'ERROR') return 'bg-danger'
    if (isUsable(props.state)) return 'bg-success'
    if (isLinked(props.state)) return 'bg-info text-dark'
    if (props.state === 'CONNECTING') return 'bg-warning text-dark'
    return 'bg-secondary'
})

const label = computed(() => {
    if (props.state === 'CONNECTING' && props.attempt > 1) {
        return `CONNECTING (attempt ${props.attempt})`
    }
    return props.state
})
</script>

<template>
  <span class="badge" :class="tone" :title="reason ?? undefined">{{ label }}</span>
</template>

<script setup lang="ts">
import {ref} from 'vue'
import {custom} from "../client.ts";

const name = ref("Wait...")
custom().then(data => name.value = data.name)
</script>

<template>

  <div class="card">
    <p>
      {{ name }}
    </p>
  </div>

</template>

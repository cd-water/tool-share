<script setup lang="ts">
import { useId } from 'vue'

withDefaults(defineProps<{ size?: number }>(), { size: 34 })

const gradientId = `bm-copper-${useId().replace(/[:]/g, '')}`
</script>

<template>
  <svg class="brand-mark" :width="size" :height="size" viewBox="0 0 64 64" aria-hidden="true">
    <defs>
      <linearGradient :id="gradientId" x1="8" y1="4" x2="56" y2="44" gradientUnits="userSpaceOnUse">
        <stop stop-color="#F2C672" />
        <stop offset=".55" stop-color="#D89B43" />
        <stop offset="1" stop-color="#A96A12" />
      </linearGradient>
    </defs>
    <path
      d="M32 3.2 57.4 17.8v28.4L32 60.8 6.6 46.2V17.8Z"
      fill="#26302F"
      stroke="#FFF9EC"
      stroke-opacity=".52"
      stroke-width="1.2"
      stroke-linejoin="round"
    />
    <path d="M32 3.2 57.4 17.8v5.6C44 23.4 44 42 32 42S20 23.4 6.6 23.4v-5.6Z" :fill="`url(#${gradientId})`" />
    <path
      d="M6.6 23.4C20 23.4 20 42 32 42s12-18.6 25.4-18.6"
      fill="none"
      stroke="#FFF9EC"
      stroke-width="2.4"
      stroke-linecap="round"
    />
    <circle cx="32" cy="42" r="3.1" fill="#FFF9EC" />
  </svg>
</template>

<style scoped>
.brand-mark {
  display: block;
  flex-shrink: 0;
}
</style>

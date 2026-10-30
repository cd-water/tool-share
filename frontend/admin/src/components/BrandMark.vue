<script setup lang="ts">
withDefaults(defineProps<{ size?: number }>(), { size: 34 })
</script>

<template>
  <svg
    class="brand-mark"
    :width="size"
    :height="size"
    viewBox="0 0 64 64"
    aria-hidden="true"
  >
    <rect width="64" height="64" rx="16" fill="#1E4FD6" />
    <circle cx="32" cy="32" r="23" fill="#FFFFFF" />
    <circle cx="23.5" cy="27" r="3.4" fill="#1E4FD6" />
    <circle cx="40.5" cy="27" r="3.4" fill="#1E4FD6" />
    <path
      d="M21.5 36.5C24.5 41 27.9 43.2 32 43.2C36.1 43.2 39.5 41 42.5 36.5"
      stroke="#1E4FD6"
      stroke-width="4"
      stroke-linecap="round"
      fill="none"
    />
  </svg>
</template>

<style scoped>
.brand-mark {
  display: block;
  flex-shrink: 0;
}
</style>

<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <div class="placeholder">
    <el-empty :description="`${route.meta.title ?? ''} · 页面开发中，敬请期待`" :image-size="120" />
  </div>
</template>

<style scoped>
.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
</style>

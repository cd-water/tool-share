<script setup lang="ts">
</script>

<template>
  <div class="placeholder">
    <el-card class="placeholder-card" shadow="never">
      <el-empty description="页面开发中，敬请期待" :image-size="120" />
    </el-card>
  </div>
</template>

<style scoped>
.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
}

.placeholder-card {
  width: 100%;
  max-width: 560px;
}
</style>

<script setup>
import { useRoute } from "vue-router";
const route = useRoute();
</script>
<template>
  <div class="page-heading">
    <p class="eyebrow">业务模块</p>
    <h1>{{ route.meta.title }}</h1>
    <p class="muted">{{ route.meta.description }}</p>
  </div>
  <section class="panel">
    <el-empty
      :description="
        route.meta.stage
          ? `此功能将在阶段 ${route.meta.stage} 开放`
          : '此功能将在后续业务增强阶段开放'
      "
      ><p class="muted">开放后，你可以在这里使用相应功能。</p>
      <el-button @click="$router.push('/')">返回工作台</el-button></el-empty
    >
  </section>
</template>

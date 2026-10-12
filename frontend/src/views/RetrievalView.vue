<script setup>
import { ref, onUnmounted } from 'vue';
import { api } from '../api/client';
const query = ref(''), hits = ref([]), loading = ref(false), error = ref(''), searched = ref(false);
let sequence = 0;
onUnmounted(() => { ++sequence; });
async function search() {
  const current = ++sequence;
  hits.value = []; error.value = ''; searched.value = false;
  if (!query.value.trim()) { error.value = '请输入要查找的制度问题'; return; }
  loading.value = true;
  try {
    const result = await api.post('/retrieval/search', { query: query.value.trim(), topK: 5, minScore: 0.5 }, { quiet: true, timeout: 195000 });
    if (current === sequence) { hits.value = result; searched.value = true; }
  } catch (e) { if (current === sequence) error.value = e.userMessage || '检索失败，请稍后重试'; }
  finally { if (current === sequence) loading.value = false; }
}
async function download(hit) {
  try {
    const response = await api.get(`/documents/${hit.documentId}/versions/${hit.documentVersionId}/file`, { responseType: 'blob' });
    const url = URL.createObjectURL(response.data), anchor = document.createElement('a');
    const filename = response.headers['content-disposition']?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
    anchor.href = url; anchor.download = filename ? decodeURIComponent(filename) : `${hit.title}-${hit.versionNo}`; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch {}
}
</script>
<template>
  <section class="panel">
    <h1>制度原文检索</h1>
    <p class="muted">查找当前适用于你的企业制度条款，核对版本与来源。检索结果为原文，请结合上下文阅读。</p>
    <el-form @submit.prevent="search">
      <el-form-item label="查找问题"><el-input v-model="query" aria-label="查找问题" maxlength="500" placeholder="例如：出差前需要办理什么手续？" :disabled="loading" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading">检索原文</el-button>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="feedback" />
    <el-empty v-if="searched && !hits.length" description="当前授权的有效制度中未找到足够相关的原文，请调整问题或联系法务核对资料。" />
    <article v-for="hit in hits" :key="hit.chunkId" class="result">
      <h2>{{ hit.title }} <el-tag>{{ hit.versionNo }}</el-tag></h2>
      <p class="muted">{{ hit.location }}<template v-if="hit.sectionTitle"> · {{ hit.sectionTitle }}</template> · 文本块 {{ hit.chunkId }}</p>
      <pre>{{ hit.text }}</pre>
      <el-button link type="primary" @click="download(hit)">下载来源原文件</el-button>
      <router-link :to="'/documents'">查看适用制度</router-link>
    </article>
  </section>
</template>
<style scoped>
.feedback, .result { margin-top: 24px; }
.result { border-top: 1px solid var(--el-border-color); padding-top: 16px; }
.result pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.8; }
.result a { margin-left: 16px; }
</style>

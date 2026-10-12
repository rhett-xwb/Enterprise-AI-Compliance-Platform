<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { api } from '../api/client';
const question = ref(''), result = ref(null), history = ref([]), loading = ref(false), error = ref(''), historyError = ref(''), more = ref(true), historyLoading = ref(false), source = ref(null);
const names = { ANSWERED: '有依据的回答', INSUFFICIENT: '依据不足', CONFLICT: '条款存在冲突，请法务核对', FAILED: '问答未完成', RESTRICTED: '来源不可访问' };
let sequence = 0, active = true;
onUnmounted(() => { active = false; ++sequence; });
async function loadHistory(reset = false) {
  if (historyLoading.value) return;
  historyLoading.value = true; historyError.value = '';
  try {
    const before = reset || !history.value.length ? undefined : history.value.at(-1).id;
    const rows = await api.get('/questions', { params: { before, size: 20 }, quiet: true });
    if (active) { history.value = reset ? rows : [...history.value, ...rows]; more.value = rows.length === 20; }
  } catch (e) { if (active) historyError.value = e.userMessage || '历史记录加载失败'; }
  finally { if (active) historyLoading.value = false; }
}
async function ask() {
  if (loading.value) return;
  const current = ++sequence; error.value = ''; result.value = null; source.value = null;
  if (!question.value.trim()) { error.value = '请输入制度问题'; return; }
  loading.value = true;
  try {
    const reply = await api.post('/questions', { question: question.value.trim() }, { quiet: true, timeout: 320000 });
    if (current === sequence) { result.value = reply; await loadHistory(true); }
  } catch (e) { if (current === sequence) error.value = e.userMessage || '问答失败，请稍后重试'; }
  finally { if (current === sequence) loading.value = false; }
}
async function open(id) {
  if (loading.value) return;
  const current = ++sequence; error.value = ''; result.value = null; source.value = null; loading.value = true;
  try {
    const reply = await api.get(`/questions/${id}`, { quiet: true });
    if (current === sequence) { result.value = reply; question.value = reply.question; }
  } catch (e) { if (current === sequence) error.value = e.userMessage; }
  finally { if (current === sequence) loading.value = false; }
}
async function view(citation) {
  const id = result.value.id;
  try {
    const reply = await api.get(`/questions/${id}/citations/${citation.id}`, { quiet: true });
    if (active && result.value?.id === id) source.value = reply;
  } catch (e) { if (active && result.value?.id === id) { source.value = null; await open(id); error.value = e.userMessage; } }
}
async function download() {
  const hit = source.value.source;
  try {
    const reply = await api.get(`/documents/${hit.documentId}/versions/${hit.documentVersionId}/file`, { responseType: 'blob' });
    const url = URL.createObjectURL(reply.data), a = document.createElement('a');
    const name = reply.headers['content-disposition']?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
    a.href = url; a.download = name ? decodeURIComponent(name) : `${hit.title}-${hit.versionNo}`; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch {}
}
onMounted(() => loadHistory(true));
</script>
<template>
  <section class="panel">
    <h1>制度问答</h1>
    <p class="muted">根据当前适用于你的制度回答。请核对引用原文；依据不足或条款冲突时联系法务。历史回答保留当时版本。</p>
    <el-form @submit.prevent="ask">
      <el-form-item label="制度问题"><el-input v-model="question" aria-label="制度问题" maxlength="500" :disabled="loading" placeholder="例如：出差前需要办理什么手续？" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading">提交问题</el-button>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="feedback" />
    <article v-if="result" class="result" aria-label="问答结果">
      <h2>{{ names[result.status] }}</h2>
      <pre>{{ result.answer }}</pre>
      <p v-if="result.status === 'FAILED'" class="muted">可以再次提交问题。记录编号 {{ result.id }}</p>
      <div v-for="citation in result.citations" :key="citation.id" class="citation">
        <h3>[{{ citation.id }}] {{ citation.source.title }} · {{ citation.source.versionNo }}</h3>
        <el-tag v-if="citation.state === 'OUTDATED'" type="warning">历史版本，当前不再适用</el-tag>
        <p class="muted">{{ citation.source.location }}<template v-if="citation.source.sectionTitle"> · {{ citation.source.sectionTitle }}</template> · 文本块 {{ citation.source.chunkId }}</p>
        <pre>{{ citation.source.text }}</pre>
        <el-button link type="primary" @click="view(citation)">查看引用来源 [{{ citation.id }}]</el-button>
      </div>
    </article>
    <section class="history">
      <h2>我的问答历史</h2>
      <el-button :disabled="historyLoading" @click="loadHistory(true)">刷新历史</el-button>
      <el-alert v-if="historyError" :title="historyError" type="error" :closable="false" />
      <el-empty v-if="!history.length && !historyLoading && !historyError" description="还没有问答记录" />
      <ul><li v-for="item in history" :key="item.id"><el-button link type="primary" :disabled="loading" @click="open(item.id)">{{ item.question }}</el-button> <span class="muted">{{ item.createdAt }} · {{ names[item.status] }}</span></li></ul>
      <el-button v-if="more && history.length" :loading="historyLoading" @click="loadHistory()">加载更多</el-button>
    </section>
    <el-dialog :model-value="!!source" title="引用来源" width="min(680px, 95vw)" @close="source = null">
      <template v-if="source">
        <h3>{{ source.source.title }} · {{ source.source.versionNo }}</h3>
        <el-alert v-if="source.state === 'OUTDATED'" title="这是历史依据，当前不再适用。请查看现行制度。" type="warning" :closable="false" />
        <p>{{ source.source.location }} · 文本块 {{ source.source.chunkId }}</p>
        <pre>{{ source.source.text }}</pre>
        <el-button type="primary" @click="download">下载来源原文件</el-button>
      </template>
    </el-dialog>
  </section>
</template>
<style scoped>
.feedback, .result, .history { margin-top: 24px; }
.citation, .history { border-top: 1px solid var(--el-border-color); padding-top: 16px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.8; }
li { margin: 12px 0; overflow-wrap: anywhere; }
li .el-button { white-space: normal; text-align: left; height: auto; }
</style>

<script setup>
import { ref, onMounted } from 'vue';
import { api } from '../api/client';
const rows = ref([]), detail = ref(null), materials = ref(null), history = ref(false), opinion = ref(''), busy = ref(false), error = ref(''), more = ref(false);
async function run(work) { if (busy.value) return; busy.value = true; error.value = ''; try { await work(); } catch (e) { error.value = e.userMessage || '操作失败，请刷新后重试'; } finally { busy.value = false; } }
async function load(reset = true) { const next = await api.get('/workflows/tasks', { params: { history: history.value, before: reset ? undefined : rows.value.at(-1)?.id, size: 20 } }); rows.value = reset ? next : [...rows.value, ...next]; more.value = next.length === 20; }
async function open(id) { await run(async () => { detail.value = await api.get(`/workflows/tasks/${id}`); materials.value = await api.get(`/workflows/requests/${detail.value.task.request_id}/materials`); opinion.value = ''; }); }
async function download(a) { await run(async () => { const reply = await api.get(`/workflows/requests/${detail.value.task.request_id}/attachments/${a.id}`, { responseType: 'blob' }); const url = URL.createObjectURL(reply.data); const link = document.createElement('a'); link.href = url; link.download = a.filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }); }
async function decide(action) { await run(async () => { detail.value = await api.post(`/workflows/tasks/${detail.value.task.id}/decisions`, { action, opinion: opinion.value, lockVersion: detail.value.task.lock_version }); opinion.value = ''; await load(); }); }
function snapshot() { const value = detail.value?.task.request_snapshot; return typeof value === 'string' ? JSON.parse(value) : value; }
onMounted(() => run(() => load()));
</script>
<template>
  <section class="panel">
    <h1>审批中心</h1>
    <p class="muted">仅显示分配给你的任务。核对提交材料后填写意见，人工决定申请结果。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-switch v-model="history" active-text="已处理记录" inactive-text="当前待办" :disabled="busy" @change="run(async () => { detail = null; await load(); })" />
    <el-button :disabled="busy" @click="run(() => load())">刷新待办</el-button>
    <p v-if="!rows.length" class="muted">暂无任务。</p>
    <el-table :data="rows"><el-table-column prop="title" label="申请" /><el-table-column prop="amount" label="金额（CNY）" /><el-table-column prop="node_key" label="审批节点" /><el-table-column prop="status" label="任务状态" /><el-table-column label="操作"><template #default="{ row }"><el-button link :disabled="busy" @click="open(row.id)">查看材料</el-button></template></el-table-column></el-table>
    <el-button v-if="more" :disabled="busy" @click="run(() => load(false))">加载更多</el-button>
    <template v-if="detail">
      <h2>申请 #{{ detail.task.request_id }} · {{ detail.task.node_key }}</h2>
      <p>流程版本 ID {{ detail.task.definition_id }} · 内容版本 {{ detail.task.request_content_version }}</p>
      <dl><template v-for="(value, key) in snapshot().form" :key="key"><dt>{{ ({ title: '标题', destination: '目的地', startDate: '开始日期', endDate: '结束日期', purpose: '事由', amount: '金额（CNY）' })[key] }}</dt><dd>{{ value }}</dd></template></dl>
      <p>提交关联预审 #{{ snapshot().reviewId }}</p>
      <p v-for="a in snapshot().attachments" :key="a.id">附件：{{ a.filename }} · {{ a.size }} 字节 <el-button link :disabled="busy" @click="download(a)">下载附件</el-button></p>
      <article v-for="r in (materials?.reviews || []).filter(r => r.id === snapshot().reviewId)" :key="r.id"><p>预审状态：{{ r.status }} · {{ r.riskLevel }}</p><pre>{{ r.advice }}</pre><template v-for="c in r.citations" :key="c"><p>[{{ c }}] {{ r.evidence[c - 1].title }} · {{ r.evidence[c - 1].location }}</p><pre>{{ r.evidence[c - 1].text }}</pre></template></article>
      <p class="muted">AI 预审不替代人工决定。制度建议仅在当前仍有来源权限时展示。</p>
      <template v-if="detail.task.status === 'PENDING'">
        <el-input v-model="opinion" type="textarea" aria-label="审批意见" placeholder="填写审批意见（必填）" maxlength="3000" :disabled="busy" />
        <div class="actions"><el-button type="primary" :disabled="busy || !opinion.trim()" @click="decide('APPROVE')">同意</el-button><el-button type="danger" :disabled="busy || !opinion.trim()" @click="decide('REJECT')">拒绝</el-button><el-button :disabled="busy || !opinion.trim()" @click="decide('RETURN')">退回修改</el-button></div>
      </template>
      <h2>处理历史</h2>
      <p v-for="a in detail.history.actions" :key="a.id">{{ a.display_name }} · {{ a.action }} · {{ a.before_status }} → {{ a.after_status }} · {{ a.opinion }} · {{ new Date(a.created_at).toLocaleString('zh-CN') }}</p>
    </template>
  </section>
</template>
<style scoped>dl { display: grid; grid-template-columns: 100px 1fr; gap: 12px; } dd { margin: 0; overflow-wrap: anywhere; } .actions { margin-top: 16px; } p { overflow-wrap: anywhere; } pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; }</style>

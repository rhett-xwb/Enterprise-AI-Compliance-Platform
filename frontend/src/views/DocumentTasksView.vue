<script setup>
import { ref, onMounted } from 'vue';
import { api } from '../api/client';
const tasks = ref([]), metrics = ref(null), busy = ref(false), error = ref(''), success = ref('');
const names = { PENDING: '等待处理', PROCESSING: '处理中', SUCCEEDED: '已完成', FAILED: '失败待处理' };
async function load() {
  busy.value = true; error.value = '';
  try { [tasks.value, metrics.value] = await Promise.all([api.get('/document-tasks'), api.get('/document-tasks/metrics')]); }
  catch (e) { error.value = e.userMessage || '任务加载失败'; }
  finally { busy.value = false; }
}
async function retry(task) {
  busy.value = true; error.value = ''; success.value = '';
  try { await api.post(`/document-tasks/${task.id}/retry`); success.value = `任务 #${task.id} 已重新排队`; await load(); }
  catch (e) { error.value = e.userMessage || '重试失败'; }
  finally { busy.value = false; }
}
onMounted(load);
</script>
<template>
  <section class="panel">
    <div class="page-heading"><div><h1>文档处理任务</h1><p class="muted">查看最近 100 个解析与索引任务。临时故障最多自动尝试三次，人工重试后累计最多五次。</p></div><el-button :loading="busy" @click="load">刷新任务</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="success" :title="success" type="success" :closable="false" />
    <el-alert v-if="metrics && !metrics.asyncEnabled" title="异步任务尚未启用，当前沿用原有处理方式。" type="info" :closable="false" />
    <p v-if="metrics">解析结果缓存：{{ metrics.cache.enabled ? '已启用' : '关闭' }} · 命中 {{ metrics.cache.hits }} · 未命中 {{ metrics.cache.misses }} · 故障降级 {{ metrics.cache.errors }}</p>
    <p v-if="metrics?.jobs.length" class="muted">{{ metrics.jobs.map(row => `${row.task_type === 'PARSE' ? '解析' : '索引'}${names[row.status]} ${row.count}`).join(' · ') }}</p>
    <el-table :data="tasks" v-loading="busy" empty-text="暂无异步处理任务">
      <el-table-column prop="id" label="任务" width="80" /><el-table-column prop="document_id" label="制度" width="80" /><el-table-column prop="document_version_id" label="版本" width="80" />
      <el-table-column label="类型" width="80"><template #default="{ row }">{{ row.task_type === 'PARSE' ? '解析' : '索引' }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{ row }">{{ names[row.status] }}</template></el-table-column>
      <el-table-column prop="attempts" label="尝试次数" width="100" /><el-table-column prop="error_code" label="最近故障" min-width="190" /><el-table-column prop="updated_at" label="更新时间" min-width="190" />
      <el-table-column label="操作" width="110"><template #default="{ row }"><el-button v-if="row.status === 'FAILED' && row.attempts < 5 && metrics?.asyncEnabled" link type="primary" :disabled="busy" @click="retry(row)">重新排队</el-button><span v-else-if="row.attempts >= 5 && row.status === 'FAILED'" class="muted">次数已用尽</span></template></el-table-column>
    </el-table>
  </section>
</template>

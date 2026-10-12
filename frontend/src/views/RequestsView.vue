<script setup>
import { ref, computed, onMounted } from 'vue';
import { api } from '../api/client';
const rows = ref([]), detail = ref(null), busy = ref(false), error = ref(''), more = ref(false), dirty = ref(false);
const empty = () => ({ title: '', destination: '', startDate: null, endDate: null, purpose: '', amount: null });
const form = ref(empty());
const history = ref(null);
const statuses = { DRAFT: '草稿', SUBMITTED: '已提交', IN_REVIEW: '审批中', APPROVED: '已通过', REJECTED: '已拒绝', RETURNED: '退回修改', WITHDRAWN: '已撤回' };
const current = computed(() => detail.value?.reviews[0]);
const draft = computed(() => !detail.value || detail.value.status === 'DRAFT');
const names = { REVIEWED: '预审建议待人工核对', UNKNOWN: '制度风险无法判断', FAILED: '预审失败，可重试', STALE: '内容已修改，预审失效', RESTRICTED: '制度来源无访问权限', SOURCE_OUTDATED: '制度依据已失效', CONFLICT: '条款冲突，交由法务核对' };
function accept(value) { detail.value = value; form.value = { ...value.form }; dirty.value = false; history.value = null; }
async function flow(action) { await run(async () => { await api.post(`/workflows/requests/${detail.value.id}/${action}`, { contentVersion: detail.value.contentVersion }); accept(await api.get(`/travel-requests/${detail.value.id}`)); await load(); }); }
async function showHistory() { await run(async () => { history.value = await api.get(`/workflows/requests/${detail.value.id}/history`); }); }
async function load(reset = true) {
  const next = await api.get('/travel-requests', { params: { before: reset ? undefined : rows.value.at(-1)?.id, size: 20 } });
  rows.value = reset ? next : [...rows.value, ...next]; more.value = next.length === 20;
}
async function run(work) {
  if (busy.value) return;
  busy.value = true; error.value = '';
  try { await work(); } catch (e) { error.value = e.userMessage || '操作失败，请稍后重试'; }
  finally { busy.value = false; }
}
function fresh() { detail.value = null; form.value = empty(); dirty.value = false; error.value = ''; }
async function open(id) { await run(async () => accept(await api.get(`/travel-requests/${id}`))); }
async function save() {
  await run(async () => {
    accept(detail.value ? await api.put(`/travel-requests/${detail.value.id}`, { contentVersion: detail.value.contentVersion, form: form.value }) : await api.post('/travel-requests', form.value));
    await load();
  });
}
async function review() {
  await run(async () => accept(await api.post(`/travel-requests/${detail.value.id}/reviews`, { contentVersion: detail.value.contentVersion }, { timeout: 320000 })));
}
async function submit() {
  await run(async () => {
    accept(await api.post(`/travel-requests/${detail.value.id}/submit`, { contentVersion: detail.value.contentVersion, reviewId: current.value.id }));
    await load();
  });
}
async function attach(event) {
  const file = event.target.files[0]; event.target.value = '';
  if (!file) return;
  await run(async () => { const data = new FormData(); data.append('file', file); data.append('contentVersion', detail.value.contentVersion); accept(await api.post(`/travel-requests/${detail.value.id}/attachments`, data)); await load(); });
}
async function remove(id) {
  await run(async () => { accept(await api.delete(`/travel-requests/${detail.value.id}/attachments/${id}`, { params: { contentVersion: detail.value.contentVersion } })); await load(); });
}
async function download(a) {
  await run(async () => { const reply = await api.get(`/travel-requests/${detail.value.id}/attachments/${a.id}`, { responseType: 'blob' }); const url = URL.createObjectURL(reply.data); const link = document.createElement('a'); link.href = url; link.download = a.filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); });
}
onMounted(() => run(() => load()));
</script>
<template>
  <section class="panel">
    <h1>我的差旅申请</h1>
    <p class="muted">保存草稿 → 添加附件 → 预审并核对依据 → 提交 → 人工审批。金额单位为人民币。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button :disabled="busy || dirty" @click="fresh">新建差旅申请</el-button>
    <el-button :disabled="busy || dirty" @click="run(() => load())">刷新列表</el-button>
    <p v-if="!rows.length" class="muted">暂无申请，可填写下方表单保存草稿。</p>
    <el-table :data="rows">
      <el-table-column prop="title" label="标题" />
      <el-table-column label="状态"><template #default="{ row }">{{ statuses[row.status] || row.status }}</template></el-table-column>
      <el-table-column prop="contentVersion" label="内容版本" />
      <el-table-column label="操作"><template #default="{ row }"><el-button link :disabled="busy || dirty" @click="open(row.id)">查看申请</el-button></template></el-table-column>
    </el-table>
    <el-button v-if="more" :disabled="busy" @click="run(() => load(false))">加载更多</el-button>
    <h2>{{ detail ? `申请 #${detail.id} · 内容版本 ${detail.contentVersion}` : '新申请' }}</h2>
    <el-form label-position="top" :disabled="busy || !draft" @input.capture="dirty = true" @submit.prevent="save">
      <el-form-item label="申请标题"><el-input v-model="form.title" aria-label="申请标题" maxlength="255" @input="dirty = true" /></el-form-item>
      <el-form-item label="目的地"><el-input v-model="form.destination" aria-label="目的地" maxlength="200" @input="dirty = true" /></el-form-item>
      <div class="dates">
        <el-form-item label="开始日期"><el-date-picker v-model="form.startDate" aria-label="开始日期" value-format="YYYY-MM-DD" @change="dirty = true" /></el-form-item>
        <el-form-item label="结束日期"><el-date-picker v-model="form.endDate" aria-label="结束日期" value-format="YYYY-MM-DD" @change="dirty = true" /></el-form-item>
      </div>
      <el-form-item label="差旅事由"><el-input v-model="form.purpose" aria-label="差旅事由" type="textarea" maxlength="3000" @input="dirty = true" /></el-form-item>
      <el-form-item label="申请金额（CNY）"><el-input-number v-model="form.amount" aria-label="申请金额" :min="0" :precision="2" :max="999999999999.99" @input="dirty = true" @change="dirty = true" /></el-form-item>
      <el-button v-if="draft" type="primary" native-type="submit" :loading="busy">保存草稿</el-button>
    </el-form>
    <template v-if="detail">
      <p v-if="dirty" class="muted">有未保存修改，请先保存。保存后原预审结果会失效。</p>
      <h2>附件</h2>
      <p class="muted">每笔申请最多 10 个附件，单个不超过 10 MiB。支持 PDF、DOCX、PNG、JPG。当前预审不解析附件正文，请人工核对。</p>
      <input v-if="draft" type="file" aria-label="上传申请附件" accept=".pdf,.docx,.png,.jpg,.jpeg" :disabled="busy || dirty" @change="attach" />
      <p v-for="a in detail.attachments" :key="a.id">{{ a.filename }} · {{ a.size }} 字节 <el-button link :disabled="busy" @click="download(a)">下载附件</el-button><el-button v-if="draft" link type="danger" :disabled="busy || dirty" @click="remove(a.id)">移除附件</el-button></p>
      <h2>预审结果</h2>
      <el-button v-if="draft" :disabled="busy || dirty" :loading="busy" @click="review">{{ current ? '再次预审' : '开始预审' }}</el-button>
      <p class="muted">AI 建议需要人工复核。依据不足也可提交，但会保留“无法判断”的记录。</p>
      <article v-for="r in detail.reviews" :key="r.id" class="review">
        <h3>{{ names[r.status] }} · 版本 {{ r.contentVersion }}</h3>
        <p>风险标记：{{ ({ HIGH: '表单存在问题', UNKNOWN: '无法判断', REVIEW_REQUIRED: '需人工核对' })[r.riskLevel] }} · {{ r.analyzedAt }}</p>
        <ul v-if="r.issues.length"><li v-for="i in r.issues" :key="i.field + i.code">{{ i.message }}</li></ul>
        <pre>{{ r.advice }}</pre>
        <template v-for="id in r.citations" :key="id"><p>[{{ id }}] {{ r.evidence[id - 1].title }} · {{ r.evidence[id - 1].versionNo }} · {{ r.evidence[id - 1].location }}</p><pre>{{ r.evidence[id - 1].text }}</pre></template>
      </article>
      <el-button v-if="draft" type="primary" :disabled="busy || dirty || !current || !['REVIEWED', 'UNKNOWN', 'CONFLICT'].includes(current.status) || current.issues.length > 0" @click="submit">确认当前内容并提交申请</el-button>
      <el-alert v-else :title="`申请已提交，当前状态：${statuses[detail.status]}。提交材料与流程记录已保存。`" type="info" :closable="false" />
      <el-button v-if="['IN_REVIEW', 'SUBMITTED'].includes(detail.status)" :disabled="busy" @click="flow('withdraw')">撤回申请</el-button>
      <el-button v-if="['RETURNED', 'WITHDRAWN'].includes(detail.status)" :disabled="busy" @click="flow('reopen')">重新编辑并预审</el-button>
      <el-button :disabled="busy" @click="showHistory">查看审批历史</el-button>
      <template v-if="history"><h2>审批历史</h2><p v-for="a in history.actions" :key="a.id">{{ a.display_name }} · {{ a.action }} · {{ a.before_status }} → {{ a.after_status }} · {{ a.opinion }} · {{ new Date(a.created_at).toLocaleString('zh-CN') }}</p></template>
      <p v-if="detail.submission" class="muted">提交记录 #{{ detail.submission.id }} · 内容版本 {{ detail.submission.content_version }} · {{ new Date(detail.submission.submitted_at).toLocaleString('zh-CN') }}</p>
    </template>
  </section>
</template>
<style scoped>
.dates { display: flex; gap: 24px; flex-wrap: wrap; }
.review { padding: 16px 0; border-bottom: 1px solid #e4e7ed; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.7; }
h2 { margin-top: 28px; }
</style>

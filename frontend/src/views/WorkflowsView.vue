<script setup>
import { ref, onMounted } from 'vue';
import { api } from '../api/client';
const name = ref('差旅审批'), routes = ref([{ minimum: 0, maximum: null, nodes: [{ name: '主管审批', assigneeId: null }] }]), assignees = ref([]), definitions = ref([]), busy = ref(false), error = ref(''), success = ref('');
async function run(work) { if (busy.value) return; busy.value = true; error.value = ''; success.value = ''; try { await work(); } catch (e) { error.value = e.userMessage || '操作失败'; } finally { busy.value = false; } }
async function load() { [assignees.value, definitions.value] = await Promise.all([api.get('/workflows/assignees'), api.get('/workflows/definitions')]); }
function split() { const last = routes.value.at(-1); last.maximum = Number(last.minimum) + 1000; routes.value.push({ minimum: last.maximum, maximum: null, nodes: [{ name: '审批', assigneeId: null }] }); }
async function publish() { await run(async () => { const result = await api.post('/workflows/definitions', { name: name.value, routes: routes.value }); await load(); success.value = `已发布版本 ${result.version}，后续新提交使用此版本。`; }); }
function edit(d) { const c = typeof d.config_json === 'string' ? JSON.parse(d.config_json) : d.config_json; name.value = c.name; routes.value = structuredClone(c.routes); }
onMounted(() => run(load));
</script>
<template>
  <section class="panel"><h1>审批流程配置</h1><p class="muted">金额区间为下限包含、上限不包含，从零连续覆盖至无上限。节点按顺序逐一审批。发布创建新版本，运行中的申请沿用原版本。金额示例可按实际业务调整。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert v-if="success" :title="success" type="success" :closable="false" />
    <el-form label-position="top" :disabled="busy"><el-form-item label="流程名称"><el-input v-model="name" maxlength="100" /></el-form-item>
      <article v-for="(r, i) in routes" :key="i" class="route"><h2>金额区间 {{ i + 1 }}</h2><el-form-item label="金额下限（CNY）"><el-input-number v-model="r.minimum" :min="0" :precision="2" /></el-form-item><el-form-item :label="i === routes.length - 1 ? '最后区间无上限' : '金额上限（CNY）'"><el-input-number v-if="i < routes.length - 1" v-model="r.maximum" :min="0" :precision="2" /></el-form-item>
        <div v-for="(n, j) in r.nodes" :key="j" class="node"><el-input v-model="n.name" placeholder="节点名称" maxlength="100" /><el-select v-model="n.assigneeId" aria-label="审批人" placeholder="选择审批人"><el-option v-for="u in assignees" :key="u.id" :label="`${u.display_name} (#${u.id})`" :value="u.id" /></el-select><el-button :disabled="r.nodes.length === 1" @click="r.nodes.splice(j, 1)">移除节点</el-button></div>
        <el-button :disabled="r.nodes.length >= 10" @click="r.nodes.push({ name: '审批', assigneeId: null })">添加节点</el-button><el-button v-if="i === routes.length - 1 && i > 0" @click="routes.pop(); routes.at(-1).maximum = null">移除末区间</el-button>
      </article><el-button :disabled="routes.length >= 20" @click="split">添加金额区间</el-button><el-button type="primary" @click="publish">发布新版本</el-button>
    </el-form><h2>已发布版本</h2><p v-if="!definitions.length">尚无已发布流程，员工提交会提示先配置审批流程。</p><p v-for="d in definitions" :key="d.id">版本 {{ d.version_no }} · {{ d.name }} · {{ d.active ? '当前发布版本' : '历史版本' }} <el-button link :disabled="busy" @click="edit(d)">复制配置</el-button></p>
  </section>
</template>
<style scoped>.route { border-bottom: 1px solid #e4e7ed; padding-bottom: 20px; margin-bottom: 20px; } .node { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; } .node > .el-input, .node > .el-select { width: min(100%, 280px); }</style>

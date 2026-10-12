<script setup>
import { onMounted, ref } from "vue";
import { api } from "../api/client";
const rows = ref([]),
  total = ref(0),
  page = ref(1),
  busy = ref(false),
  error = ref("");
let version = 0;
async function load() {
  const current = ++version;
  busy.value = true;
  error.value = "";
  try {
    const result = await api.get("/users", {
      params: { page: page.value, size: 20 },
      quiet: true,
    });
    if (current === version) {
      rows.value = result.records;
      total.value = result.total;
    }
  } catch (e) {
    if (current === version) {
      rows.value = [];
      error.value = e.userMessage || "加载失败";
    }
  } finally {
    if (current === version) busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <p class="eyebrow">系统管理</p>
    <h1>用户管理</h1>
    <p class="muted">查看平台账号及其所属部门、启用状态。</p>
  </div>
  <section class="panel">
    <div class="panel-toolbar">
      <h2>账号列表</h2>
      <el-button :disabled="busy" @click="load">刷新</el-button>
    </div>
    <el-skeleton v-if="busy" :rows="6" animated />
    <div v-else-if="error" role="alert">
      <el-alert
        :title="error"
        type="error"
        :closable="false"
        show-icon
      /><el-button class="retry" @click="load">重新加载</el-button>
    </div>
    <el-empty v-else-if="!rows.length" description="暂无用户记录" />
    <el-table v-else :data="rows" stripe
      ><el-table-column prop="id" label="编号" width="80" /><el-table-column
        prop="username"
        label="账号"
        min-width="160"
      /><el-table-column
        prop="displayName"
        label="姓名"
        min-width="140"
      /><el-table-column
        prop="email"
        label="邮箱"
        min-width="200"
      /><el-table-column label="部门" width="100"
        ><template #default="{ row }">{{
          row.departmentId ?? "未分配"
        }}</template></el-table-column
      ><el-table-column label="状态" width="100"
        ><template #default="{ row }"
          ><el-tag disable-transitions :type="row.enabled ? 'success' : 'info'">{{
            row.enabled ? "已启用" : "已停用"
          }}</el-tag></template
        ></el-table-column
      ></el-table
    >
    <el-pagination
      v-if="!error && total"
      v-model:current-page="page"
      :page-size="20"
      :total="total"
      layout="prev, pager, next, total"
      :disabled="busy"
      @current-change="load"
    />
  </section>
</template>

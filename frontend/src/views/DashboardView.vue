<script setup>
import { computed } from "vue";
import { session } from "../auth/session";
import { visibleFeatures, roleNames } from "../router/access";
const entries = computed(() => visibleFeatures(session.user));
</script>
<template>
  <div class="page-heading">
    <p class="eyebrow">我的工作空间</p>
    <h1>{{ session.user?.username }}，欢迎回来</h1>
    <p class="muted">从这里进入你有权限访问的业务模块。</p>
  </div>
  <section class="panel">
    <h2>当前身份</h2>
    <el-descriptions :column="1" border
      ><el-descriptions-item label="账号">{{
        session.user?.username
      }}</el-descriptions-item
      ><el-descriptions-item label="角色">{{
        session.user?.roles.map((r) => roleNames[r] || r).join("、")
      }}</el-descriptions-item
      ><el-descriptions-item label="部门编号">{{
        session.user?.departmentId ?? "尚未分配部门"
      }}</el-descriptions-item></el-descriptions
    >
  </section>
  <section class="panel">
    <h2>业务入口</h2>
    <div class="entry-list">
      <router-link v-for="item in entries" :key="item.path" :to="item.path"
        ><div>
          <strong>{{ item.title }}</strong>
          <p>{{ item.description || "查询平台用户与账号状态。" }}</p>
        </div>
        <span
          >{{
            item.path === "/users" || item.available ? "进入" : "待开放"
          }}
          →</span
        ></router-link
      >
    </div>
    <el-empty
      v-if="!entries.length"
      description="当前账号尚未分配业务权限，请联系管理员"
    />
  </section>
</template>

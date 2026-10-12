<script setup>
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api/client";
import { session, clearSession } from "../auth/session";
import { visibleFeatures, roleNames } from "../router/access";
const route = useRoute(),
  router = useRouter(),
  busy = ref(false);
const menu = computed(() => visibleFeatures(session.user));
async function logout() {
  busy.value = true;
  try {
    await api.post("/auth/logout");
    clearSession();
    await router.replace("/login");
  } catch {
    /* 保留会话以便用户重试服务端撤销。 */
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="workspace">
    <aside class="sidebar">
      <router-link class="brand" to="/"
        >企业合规平台<small>COMPLIANCE WORKSPACE</small></router-link
      >
      <nav aria-label="主导航">
        <el-menu :default-active="route.path" router
          ><el-menu-item index="/">工作台</el-menu-item
          ><el-menu-item
            v-for="item in menu"
            :key="item.path"
            :index="item.path"
            >{{ item.title }}</el-menu-item
          ></el-menu
        >
      </nav>
      <p class="sidebar-note">制度有来源<br />审批有记录</p>
    </aside>
    <div class="workspace-body">
      <header class="workspace-header">
        <span>{{ route.meta.title }}</span>
        <div class="account">
          <span>{{ session.user?.username }}</span
          ><el-tag
            disable-transitions
            v-for="role in session.user?.roles"
            :key="role"
            type="info"
            >{{ roleNames[role] || role }}</el-tag
          ><el-button :loading="busy" @click="logout">退出登录</el-button>
        </div>
      </header>
      <main class="workspace-main"><router-view :key="route.path" /></main>
    </div>
  </div>
</template>

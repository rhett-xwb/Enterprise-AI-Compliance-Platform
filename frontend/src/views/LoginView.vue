<script setup>
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, loadIdentity } from "../api/client";
import { setToken, clearSession, session } from "../auth/session";
import { safeRedirect } from "../router";
const route = useRoute(),
  router = useRouter();
const username = ref(""),
  password = ref(""),
  busy = ref(false),
  error = ref("");
async function login() {
  if (busy.value) return;
  if (!username.value.trim() || !password.value) {
    error.value = "请输入账号和密码";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const result = await api.post(
      "/auth/login",
      { username: username.value.trim(), password: password.value },
      { quiet: true },
    );
    setToken(result.accessToken);
    try {
      await loadIdentity(true);
    } catch (e) {
      clearSession();
      throw e;
    }
    password.value = "";
    await router.replace(safeRedirect(route.query.redirect));
  } catch (e) {
    error.value = e.userMessage || "登录失败，请重试";
  } finally {
    busy.value = false;
  }
}
async function retryIdentity() {
  busy.value = true;
  error.value = "";
  try {
    await loadIdentity(true);
    await router.replace(safeRedirect(route.query.redirect));
  } catch (e) {
    error.value = e.userMessage || "连接失败，请重试";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="login-page">
    <section class="login-intro">
      <p class="eyebrow">企业合规平台</p>
      <h1>让每一步业务<br />都有据可依。</h1>
      <p>
        连接企业制度、员工申请与人工审批，<br />为合规决策保留清晰的依据与记录。
      </p>
      <span class="intro-footer">企业内部工作空间</span>
    </section>
    <section class="login-panel">
      <div class="login-form">
        <p class="eyebrow">欢迎回来</p>
        <h2>登录工作空间</h2>
        <p class="muted">使用管理员分配的企业账号登录。</p>
        <el-alert
          v-if="route.query.reason"
          :title="
            route.query.reason === 'expired'
              ? '登录已失效，请重新登录'
              : '暂时无法获取身份，请重试连接'
          "
          type="warning"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
          show-icon
          role="alert"
        />
        <el-form label-position="top" @submit.prevent="login">
          <el-form-item label="账号"
            ><el-input
              v-model="username"
              aria-label="账号"
              autocomplete="username"
              maxlength="64"
              :disabled="busy"
          /></el-form-item>
          <el-form-item label="密码"
            ><el-input
              v-model="password"
              aria-label="密码"
              type="password"
              show-password
              autocomplete="current-password"
              maxlength="72"
              :disabled="busy"
          /></el-form-item>
          <el-button
            class="login-submit"
            type="primary"
            native-type="submit"
            :loading="busy"
            >登录</el-button
          >
        </el-form>
        <el-button
          v-if="session.token && route.query.reason === 'connection'"
          :loading="busy"
          @click="retryIdentity"
          >重试连接</el-button
        >
        <p class="login-help muted">
          忘记密码或需要开通账号？请联系系统管理员。
        </p>
      </div>
    </section>
  </main>
</template>

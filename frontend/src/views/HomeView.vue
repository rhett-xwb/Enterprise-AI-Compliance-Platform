<script setup>
import { ElMessage } from 'element-plus'

function showMessage() {
  ElMessage.success('Element Plus 已接入，可以继续开发业务页面。')
}
</script>

<template>
  <el-card>
    <template #header>
      <h1>欢迎使用企业合规平台</h1>
    </template>
    <p>前端基础工程已准备就绪，后续将在这里接入制度管理、知识问答与审批功能。</p>
    <div class="page-actions">
      <el-button type="primary" @click="showMessage">体验消息提示</el-button>
      <el-button @click="$router.push('/about')">查看项目说明</el-button>
    </div>
  </el-card>
</template>

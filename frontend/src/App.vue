<script setup>
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <el-container class="app-layout">
    <el-header class="app-header">
      <span class="app-title">企业智能法律合规与审批平台</span>
      <el-menu :default-active="route.path" mode="horizontal" router :ellipsis="false">
        <el-menu-item index="/">首页</el-menu-item>
        <el-menu-item index="/about">项目说明</el-menu-item>
      </el-menu>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

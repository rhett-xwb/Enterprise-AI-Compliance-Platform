<template>
  <el-result icon="warning" title="404" sub-title="页面不存在，请返回工作台。">
    <template #extra>
      <el-button type="primary" @click="$router.replace('/')">返回工作台</el-button>
    </template>
  </el-result>
</template>

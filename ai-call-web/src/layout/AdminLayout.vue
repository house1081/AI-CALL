<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">AI外呼总后台</div>
      <el-menu :default-active="$route.path" router background-color="#001529" text-color="#fff" active-text-color="#409eff">
        <el-menu-item index="/dashboard">控制台</el-menu-item>
        <el-menu-item index="/line">线路管理</el-menu-item>
        <el-menu-item index="/tenant">商户管理</el-menu-item>
        <el-menu-item index="/price">费率配置</el-menu-item>
        <el-menu-item index="/ai-prompt">AI话术</el-menu-item>
        <el-menu-item index="/ai-model">模型配置</el-menu-item>
        <el-menu-item index="/prerecord-outbound">智能预录外呼</el-menu-item>
        <el-menu-item index="/ai-chat-lab">对话训练</el-menu-item>
        <el-menu-item index="/dialog-training">训练知识库</el-menu-item>
        <el-menu-item index="/risk">风控配置</el-menu-item>
        <el-menu-item index="/call-record">通话记录</el-menu-item>
        <el-menu-item index="/profit">利润统计</el-menu-item>
        <el-menu-item index="/recharge">充值审核</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ username }}</span>
        <el-button type="danger" link @click="logout">退出</el-button>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
const router = useRouter()
const username = ref(localStorage.getItem('admin_username') || 'admin')
const logout = () => {
  localStorage.removeItem('admin_token')
  localStorage.removeItem('admin_username')
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #001529; }
.logo { color: #fff; font-size: 18px; font-weight: bold; padding: 20px; text-align: center; }
.header { display: flex; justify-content: flex-end; align-items: center; gap: 16px; background: #fff; border-bottom: 1px solid #eee; }
</style>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">AI外呼商户端</div>
      <el-menu :default-active="$route.path" router background-color="#1565c0" text-color="#fff">
        <el-menu-item index="/home">首页</el-menu-item>
        <el-menu-item index="/customer">客户管理</el-menu-item>
        <el-menu-item index="/task">外呼任务</el-menu-item>
        <el-menu-item index="/call-record">通话记录</el-menu-item>
        <el-menu-item index="/intent">意向客户</el-menu-item>
        <el-menu-item index="/recharge">在线充值</el-menu-item>
        <el-menu-item index="/bill">消费明细</el-menu-item>
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
const username = ref(localStorage.getItem('tenant_username') || '')
const logout = () => {
  localStorage.removeItem('tenant_token')
  localStorage.removeItem('tenant_username')
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #1565c0; }
.logo { color: #fff; font-size: 16px; font-weight: bold; padding: 20px 12px; text-align: center; }
.header { display: flex; justify-content: flex-end; align-items: center; gap: 16px; background: #fff; }
</style>

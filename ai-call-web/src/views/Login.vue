<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2>AI外呼总后台</h2>
      <el-form :model="form" @submit.prevent="onLogin">
        <el-form-item><el-input v-model="form.username" placeholder="账号" /></el-form-item>
        <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" show-password /></el-form-item>
        <el-button type="primary" style="width:100%" @click="onLogin" :loading="loading">登录</el-button>
      </el-form>
      <p class="tip">默认账号 admin / admin123</p>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '../api/request'

const router = useRouter()
const loading = ref(false)
const form = ref({ username: 'admin', password: 'admin123' })

const onLogin = async () => {
  loading.value = true
  try {
    const data = await request.post('/admin/login', {
      username: (form.value.username || '').trim(),
      password: (form.value.password || '').trim()
    })
    localStorage.setItem('admin_token', data.token)
    localStorage.setItem('admin_username', data.username)
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #1a237e, #0d47a1); }
.login-card { width: 400px; padding: 20px; }
h2 { text-align: center; margin-bottom: 24px; }
.tip { text-align: center; color: #999; font-size: 12px; margin-top: 12px; }
</style>

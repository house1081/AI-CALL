<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2>AI外呼商户端</h2>
      <el-form :model="form">
        <el-form-item><el-input v-model="form.username" placeholder="商户账号" /></el-form-item>
        <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" show-password /></el-form-item>
        <el-button type="primary" style="width:100%" @click="onLogin" :loading="loading">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '../api/request'

const router = useRouter()
const loading = ref(false)
const form = ref({ username: '', password: '' })

const onLogin = async () => {
  loading.value = true
  try {
    const data = await request.post('/tenant/login', form.value)
    localStorage.setItem('tenant_token', data.token)
    localStorage.setItem('tenant_username', data.username)
    router.push('/home')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #1565c0, #42a5f5); }
.login-card { width: 380px; padding: 20px; }
h2 { text-align: center; margin-bottom: 24px; }
</style>

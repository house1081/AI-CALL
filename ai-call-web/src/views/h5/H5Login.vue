<template>
  <div class="h5-login">
    <div class="login-box">
      <h1>对话训练</h1>
      <p class="sub">手机端 H5 · 模拟外呼对话</p>
      <el-form @submit.prevent="onLogin">
        <el-input v-model="form.username" placeholder="账号" size="large" class="field" />
        <el-input
          v-model="form.password"
          type="password"
          placeholder="密码"
          show-password
          size="large"
          class="field"
        />
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="onLogin">
          登录
        </el-button>
      </el-form>
      <p class="tip">与总后台相同账号；需 HTTPS 或 localhost 才能使用麦克风</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import request from '../../api/request.js'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = ref({ username: '', password: '' })

const onLogin = async () => {
  loading.value = true
  try {
    const data = await request.post('/admin/login', {
      username: (form.value.username || '').trim(),
      password: (form.value.password || '').trim()
    })
    localStorage.setItem('admin_token', data.token)
    localStorage.setItem('admin_username', data.username)
    const redirect = route.query.redirect || '/h5/training'
    router.replace(redirect)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.h5-login {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  padding-bottom: calc(24px + env(safe-area-inset-bottom));
  background: linear-gradient(160deg, #0d1b2a 0%, #1e3a5f 50%, #0d47a1 100%);
}
.login-box {
  width: 100%;
  max-width: 360px;
  background: #fff;
  border-radius: 16px;
  padding: 28px 22px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}
h1 { margin: 0 0 4px; font-size: 22px; text-align: center; color: #1a1a1a; }
.sub { margin: 0 0 24px; text-align: center; font-size: 13px; color: #909399; }
.field { margin-bottom: 14px; }
.submit { width: 100%; margin-top: 8px; height: 44px; }
.tip { margin: 16px 0 0; font-size: 11px; color: #909399; text-align: center; line-height: 1.5; }
</style>

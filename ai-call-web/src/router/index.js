import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
  { path: '/training', redirect: '/h5/training' },
  { path: '/h5', redirect: '/h5/training' },
  {
    path: '/h5/login',
    component: () => import('../views/h5/H5Login.vue'),
    meta: { public: true, standalone: true, title: '对话训练登录' }
  },
  {
    path: '/h5/training',
    component: () => import('../views/h5/VoiceTrainingH5.vue'),
    meta: { public: true, standalone: true, h5: true, title: '对话训练' }
  },
  // 旧路径统一进独立 H5，不再嵌在总后台侧边栏里
  { path: '/voice-chat-lab', redirect: '/h5/training' },
  { path: '/ai-chat-lab', redirect: '/h5/training' },
  {
    path: '/',
    component: () => import('../layout/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('../views/Dashboard.vue') },
      { path: 'line', component: () => import('../views/Line.vue') },
      { path: 'tenant', component: () => import('../views/Tenant.vue') },
      { path: 'price', component: () => import('../views/Price.vue') },
      { path: 'ai-prompt', component: () => import('../views/AiPrompt.vue') },
      { path: 'ai-model', component: () => import('../views/AiModel.vue') },
      { path: 'dialog-training', component: () => import('../views/DialogTraining.vue') },
      { path: 'main-flow', component: () => import('../views/MainFlowConfig.vue') },
      { path: 'risk', component: () => import('../views/Risk.vue') },
      { path: 'call-record', component: () => import('../views/CallRecord.vue') },
      { path: 'profit', component: () => import('../views/ProfitReport.vue') },
      { path: 'recharge', component: () => import('../views/Recharge.vue') }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to, from, next) => {
  if (to.meta.public) {
    next()
    return
  }
  const token = localStorage.getItem('admin_token')
  if (to.path !== '/login' && !token) {
    next('/login')
  } else {
    next()
  }
})

router.afterEach(to => {
  document.title = to.meta.title ? `${to.meta.title} - AI外呼` : 'AI外呼'
  document.documentElement.classList.toggle('h5-standalone', !!to.meta.standalone)
  document.body.classList.toggle('h5-standalone', !!to.meta.standalone)
})

export default router

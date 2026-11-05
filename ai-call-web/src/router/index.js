import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
  // 对话训练默认进总后台（左侧菜单 + 右侧训练页），不再全屏
  { path: '/training', redirect: '/voice-training' },
  { path: '/voice-chat-lab', redirect: '/voice-training' },
  { path: '/ai-chat-lab', redirect: '/voice-training' },
  { path: '/h5', redirect: '/h5/training' },
  {
    path: '/h5/login',
    component: () => import('../views/h5/H5Login.vue'),
    meta: { public: true, standalone: true, title: '对话训练登录' }
  },
  {
    // 手机端独立页保留；PC 总后台请用 /voice-training
    path: '/h5/training',
    component: () => import('../views/h5/VoiceTrainingH5.vue'),
    meta: { public: true, standalone: true, h5: true, title: '对话训练(手机)' }
  },
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
      {
        path: 'voice-training',
        component: () => import('../views/VoiceChatLab.vue'),
        meta: { title: '对话训练' }
      },
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

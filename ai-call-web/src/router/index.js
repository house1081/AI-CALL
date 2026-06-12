import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
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
      { path: 'ai-chat-lab', component: () => import('../views/AiChatLab.vue') },
      { path: 'dialog-training', component: () => import('../views/DialogTraining.vue') },
      { path: 'risk', component: () => import('../views/Risk.vue') },
      { path: 'call-record', component: () => import('../views/CallRecord.vue') },
      { path: 'profit', component: () => import('../views/ProfitReport.vue') },
      { path: 'recharge', component: () => import('../views/Recharge.vue') }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to, from, next) => {
  if (to.path !== '/login' && !localStorage.getItem('admin_token')) {
    next('/login')
  } else {
    next()
  }
})

export default router

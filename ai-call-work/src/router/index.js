import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    component: () => import('../layout/WorkLayout.vue'),
    redirect: '/home',
    children: [
      { path: 'home', component: () => import('../views/Home.vue') },
      { path: 'customer', component: () => import('../views/Customer.vue') },
      { path: 'task', component: () => import('../views/Task.vue') },
      { path: 'call-record', component: () => import('../views/CallRecord.vue') },
      { path: 'intent', component: () => import('../views/Intent.vue') },
      { path: 'recharge', component: () => import('../views/Recharge.vue') },
      { path: 'bill', component: () => import('../views/Bill.vue') }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to, from, next) => {
  if (to.path !== '/login' && !localStorage.getItem('tenant_token')) {
    next('/login')
  } else {
    next()
  }
})

export default router

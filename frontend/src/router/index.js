import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/tasks', name: 'tasks', component: () => import('../views/TasksView.vue') },
    { path: '/users', name: 'users', component: () => import('../views/UsersView.vue'), meta: { permission: 'user:manage' } }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public) {
    return auth.isLoggedIn.value && to.name === 'login'
      ? { name: 'home' }
      : true
  }
  if (!auth.isLoggedIn.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.permission && !auth.hasPermission(to.meta.permission)) {
    return { name: 'home' }
  }
  return true
})

export default router

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    router.push(route.query.redirect || '/')
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card card">
      <div class="login-mark">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M12 1.5 L13.8 9.2 L21.5 11 L13.8 12.8 L12 20.5 L10.2 12.8 L2.5 11 L10.2 9.2 Z"
            fill="currentColor"
          />
        </svg>
      </div>
      <p class="caption-uppercase">IT Workbench</p>
      <h1 class="display-sm">登录工作台</h1>
      <p class="body-sm login-sub">使用管理员分配的账号登录</p>

      <form class="login-form" @submit.prevent="submit">
        <div class="form-field">
          <label class="field-label" for="username">用户名</label>
          <input
            id="username"
            v-model.trim="form.username"
            class="input"
            autocomplete="username"
            required
          />
        </div>
        <div class="form-field">
          <label class="field-label" for="password">密码</label>
          <input
            id="password"
            v-model="form.password"
            class="input"
            type="password"
            autocomplete="current-password"
            required
          />
        </div>
        <p v-if="error" class="login-error">{{ error }}</p>
        <button class="btn btn-primary login-submit" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: var(--sp-lg);
  background:
    radial-gradient(circle at 15% 20%, rgba(204, 120, 92, 0.12), transparent 32%),
    var(--canvas);
}
.login-card {
  width: min(420px, 100%);
  padding: var(--sp-xxl);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.login-mark {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--surface-cream-strong);
  color: var(--primary);
  margin-bottom: var(--sp-lg);
}
.login-mark svg {
  width: 22px;
  height: 22px;
}
.login-sub {
  margin-top: var(--sp-xs);
  margin-bottom: var(--sp-xl);
  color: var(--muted);
}
.login-form {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--sp-md);
}
.login-error {
  color: var(--error);
  font-size: 13px;
}
.login-submit {
  width: 100%;
  margin-top: var(--sp-xs);
}
</style>

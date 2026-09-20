<script setup>
import { useRoute } from 'vue-router'
import { computed } from 'vue'

const route = useRoute()
const activeName = computed(() => route.name)
</script>

<template>
  <div class="app-shell">
    <header class="top-nav">
      <div class="nav-inner">
        <router-link to="/" class="brand">
          <!-- Anthropic 风格四辐星标 -->
          <svg class="spike" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M12 1.5 L13.8 9.2 L21.5 11 L13.8 12.8 L12 20.5 L10.2 12.8 L2.5 11 L10.2 9.2 Z"
              fill="currentColor"
            />
          </svg>
          <span class="brand-name">IT 工作台</span>
        </router-link>
        <nav class="nav-links">
          <router-link to="/" class="nav-link" :class="{ active: activeName === 'home' }">首页</router-link>
          <router-link to="/tasks" class="nav-link" :class="{ active: activeName === 'tasks' }">任务列表</router-link>
        </nav>
      </div>
    </header>

    <main class="main-container">
      <router-view />
    </main>

    <footer class="app-footer">
      <span class="caption">IT 工作台 · 内部工具</span>
    </footer>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* 顶部导航：奶油色 64px */
.top-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  height: 64px;
  background: var(--canvas);
  border-bottom: 1px solid var(--hairline-soft);
}
.nav-inner {
  max-width: 1200px;
  margin: 0 auto;
  height: 100%;
  padding: 0 var(--sp-lg);
  display: flex;
  align-items: center;
  gap: var(--sp-xl);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--ink);
}
.brand:hover {
  text-decoration: none;
}
.spike {
  width: 20px;
  height: 20px;
  color: var(--ink);
}
.brand-name {
  font-family: var(--font-display);
  font-size: 22px;
  letter-spacing: -0.3px;
}
.nav-links {
  display: flex;
  gap: var(--sp-xs);
}
.nav-link {
  padding: 8px 14px;
  border-radius: var(--r-md);
  font-size: 14px;
  font-weight: 500;
  color: var(--muted);
}
.nav-link:hover {
  color: var(--ink);
  text-decoration: none;
}
.nav-link.active {
  background: var(--surface-card);
  color: var(--ink);
}

.main-container {
  flex: 1;
  width: 100%;
  max-width: 1200px;
  margin: 0 auto;
  padding: var(--sp-xxl) var(--sp-lg) var(--sp-xxl);
}

/* 页脚：深色 */
.app-footer {
  background: var(--surface-dark);
  color: var(--on-dark-soft);
  padding: var(--sp-lg);
  text-align: center;
}
.app-footer .caption {
  color: var(--on-dark-soft);
}
</style>

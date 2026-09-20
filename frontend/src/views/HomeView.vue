<script setup>
import { onMounted, ref, computed } from 'vue'
import { statsApi, newsApi } from '../api'
import TaskBadge from '../components/TaskBadge.vue'

const stats = ref(null)
const news = ref([])
const loading = ref(true)
const newsLoading = ref(true)

const today = new Date()
const dateStr = today.toLocaleDateString('zh-CN', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
  weekday: 'long'
})

const rateInt = computed(() => (stats.value ? Math.round(stats.value.completionRate || 0) : 0))

async function loadStats() {
  try {
    stats.value = await statsApi.today()
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

async function loadNews() {
  try {
    news.value = (await newsApi.hardware()) || []
  } catch (e) {
    console.error(e)
  } finally {
    newsLoading.value = false
  }
}

onMounted(() => {
  loadStats()
  loadNews()
})
</script>

<template>
  <div class="home">
    <!-- 页头：衬线大标题 -->
    <section class="page-head">
      <p class="caption-uppercase">Today · {{ dateStr }}</p>
      <h1 class="display-md">今日概览</h1>
      <p class="body-md head-sub">当日任务完成情况与 PC 硬件资讯</p>
    </section>

    <!-- 完成率 -->
    <section class="rate-band">
      <div class="card rate-card">
        <div class="rate-main">
          <div class="rate-number">
            <span class="display-sm rate-value">{{ loading ? '—' : rateInt }}</span>
            <span class="rate-unit">%</span>
          </div>
          <div class="rate-label caption-uppercase">当日完成率</div>
        </div>
        <div class="rate-progress">
          <div class="rate-progress-track">
            <div class="rate-progress-fill" :style="{ width: rateInt + '%' }" />
          </div>
          <div class="rate-legend">
            <span class="body-sm">已完成 {{ stats?.completedCount ?? 0 }} 项</span>
            <span class="body-sm">未完成 {{ stats?.uncompletedCount ?? 0 }} 项</span>
            <span class="body-sm">共 {{ stats?.total ?? 0 }} 项</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 当日任务：已完成 / 未完成 两模块 -->
    <section class="task-modules">
      <div class="card module-card">
        <div class="module-head">
          <h2 class="title-md">
            <span class="badge badge-success module-badge"><span class="badge-dot-status" />已完成</span>
          </h2>
          <span class="caption">{{ stats?.completedList?.length ?? 0 }} 项</span>
        </div>
        <ul v-if="stats && stats.completedList.length" class="task-list">
          <li v-for="t in stats.completedList" :key="t.id" class="task-item">
            <div class="task-item-main">
              <TaskBadge :type="t.taskType" />
              <span class="task-desc body-sm">{{ t.description || '（无描述）' }}</span>
            </div>
            <p v-if="t.summary" class="task-summary caption">{{ t.summary }}</p>
          </li>
        </ul>
        <p v-else class="empty caption">今日暂无已完成任务</p>
      </div>

      <div class="card module-card">
        <div class="module-head">
          <h2 class="title-md">
            <span class="badge badge-warning module-badge"><span class="badge-dot-status" />未完成</span>
          </h2>
          <span class="caption">{{ stats?.uncompletedList?.length ?? 0 }} 项（含延期）</span>
        </div>
        <ul v-if="stats && stats.uncompletedList.length" class="task-list">
          <li v-for="t in stats.uncompletedList" :key="t.id" class="task-item">
            <div class="task-item-main">
              <TaskBadge :type="t.taskType" />
              <TaskBadge :status="t.status" />
              <span class="task-desc body-sm">{{ t.description || '（无描述）' }}</span>
            </div>
            <p v-if="t.summary" class="task-summary caption">{{ t.summary }}</p>
          </li>
        </ul>
        <p v-else class="empty caption">今日暂无未完成任务</p>
      </div>
    </section>

    <!-- PC 硬件新闻：深色面板 -->
    <section>
      <div class="card-dark news-card">
        <div class="news-head">
          <h2 class="display-sm news-title">PC 硬件资讯</h2>
          <span class="news-source caption">来自公开 RSS 源 · 每 30 分钟更新</span>
        </div>
        <ul v-if="news.length" class="news-list">
          <li v-for="(n, i) in news" :key="i" class="news-item">
            <a :href="n.link" target="_blank" rel="noopener" class="news-link">{{ n.title }}</a>
            <span v-if="n.pubDate" class="news-date">{{ n.pubDate }}</span>
          </li>
        </ul>
        <p v-else-if="newsLoading" class="news-empty">加载中…</p>
        <p v-else class="news-empty">暂无新闻数据</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  flex-direction: column;
  gap: var(--sp-xxl);
}

.page-head {
  display: flex;
  flex-direction: column;
  gap: var(--sp-xs);
}

/* 完成率卡片 */
.rate-card {
  display: flex;
  align-items: center;
  gap: var(--sp-xxl);
  padding: var(--sp-xl) var(--sp-xxl);
}
.rate-main {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 180px;
  gap: var(--sp-xs);
}
.rate-number {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.rate-value {
  font-size: 56px;
  color: var(--ink);
}
.rate-unit {
  font-family: var(--font-display);
  font-size: 24px;
  color: var(--muted);
}
.rate-label {
  color: var(--muted);
}
.rate-progress {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--sp-sm);
}
.rate-progress-track {
  height: 10px;
  border-radius: var(--r-pill);
  background: var(--surface-cream-strong);
  overflow: hidden;
}
.rate-progress-fill {
  height: 100%;
  border-radius: var(--r-pill);
  background: var(--primary);
  transition: width 0.4s ease;
}
.rate-legend {
  display: flex;
  gap: var(--sp-lg);
  color: var(--muted);
}

/* 任务模块 */
.task-modules {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-lg);
}
.module-card {
  padding: var(--sp-lg) var(--sp-xl);
}
.module-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sp-md);
}
.module-badge {
  font-size: 14px;
}
.task-list {
  list-style: none;
  display: flex;
  flex-direction: column;
}
.task-item {
  padding: var(--sp-sm) 0;
  border-bottom: 1px solid var(--hairline-soft);
}
.task-item:last-child {
  border-bottom: none;
}
.task-item-main {
  display: flex;
  align-items: center;
  gap: var(--sp-sm);
  flex-wrap: wrap;
}
.task-desc {
  flex: 1;
  min-width: 200px;
  color: var(--body);
}
.task-summary {
  margin-top: 4px;
  margin-left: 2px;
  color: var(--muted);
}
.empty {
  padding: var(--sp-lg) 0;
  text-align: center;
}

/* 新闻：深色卡片 */
.news-card {
  padding: var(--sp-xl) var(--sp-xxl);
}
.news-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--sp-md);
  gap: var(--sp-md);
  flex-wrap: wrap;
}
.news-title {
  font-size: 24px;
}
.news-source {
  color: var(--on-dark-soft);
}
.news-list {
  list-style: none;
}
.news-item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-lg);
  padding: 10px 0;
  border-bottom: 1px solid var(--surface-dark-elevated);
}
.news-item:last-child {
  border-bottom: none;
}
.news-link {
  color: var(--on-dark);
  font-size: 14px;
  line-height: 1.55;
}
.news-link:hover {
  color: var(--primary);
}
.news-date {
  color: var(--on-dark-soft);
  font-size: 12px;
  white-space: nowrap;
}
.news-empty {
  color: var(--on-dark-soft);
  font-size: 14px;
  padding: var(--sp-md) 0;
}

@media (max-width: 900px) {
  .task-modules {
    grid-template-columns: 1fr;
  }
  .rate-card {
    flex-direction: column;
    gap: var(--sp-lg);
  }
  .rate-progress {
    width: 100%;
  }
}
</style>

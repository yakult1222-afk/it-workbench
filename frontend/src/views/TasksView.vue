<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, computed } from 'vue'
import { taskApi, dailySummaryApi, userApi } from '../api'
import { dailySummaryStageCopy, pollDailySummary } from '../api/dailySummaryPolling'
import { auth } from '../stores/auth'
import TaskBadge from '../components/TaskBadge.vue'
import AppModal from '../components/AppModal.vue'

const TASK_TYPES = ['审批', '硬件问题', '软件问题', '网络问题']
const STATUSES = ['完成', '未完成', '延期']
const canViewAll = computed(() => auth.hasPermission('task:view:all'))
const canManageAll = computed(() => auth.hasPermission('task:manage:all'))
const owners = ref([])

// ---- 列表与筛选 ----
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const filters = reactive({ taskDate: '', taskType: '', status: '', ownerId: '' })

async function load() {
  loading.value = true
  try {
    const params = {
      page: page.value,
      size: size.value,
      taskDate: filters.taskDate || undefined,
      taskType: filters.taskType || undefined,
      status: filters.status || undefined,
      ownerId: canViewAll.value && filters.ownerId ? Number(filters.ownerId) : undefined
    }
    const data = await taskApi.page(params)
    list.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    alert(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function reset() {
  filters.taskDate = ''
  filters.taskType = ''
  filters.status = ''
  filters.ownerId = ''
  search()
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

function prevPage() {
  if (page.value > 1) {
    page.value--
    load()
  }
}
function nextPage() {
  if (page.value < totalPages.value) {
    page.value++
    load()
  }
}

// ---- 新增 / 编辑 ----
const editorOpen = ref(false)
const editingId = ref(null)
const saving = ref(false)
const form = reactive({
  taskDate: new Date().toISOString().slice(0, 10),
  taskType: '',
  description: '',
  status: '未完成',
  summary: '',
  ownerId: ''
})
const formError = ref('')

function openCreate() {
  editingId.value = null
  form.taskDate = new Date().toISOString().slice(0, 10)
  form.taskType = ''
  form.description = ''
  form.status = '未完成'
  form.summary = ''
  form.ownerId = canManageAll.value ? auth.user.value?.id || '' : ''
  formError.value = ''
  editorOpen.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.taskDate = row.taskDate
  form.taskType = row.taskType
  form.description = row.description || ''
  form.status = row.status
  form.summary = row.summary || ''
  form.ownerId = row.ownerId || ''
  formError.value = ''
  editorOpen.value = true
}

async function save() {
  if (!form.taskDate) return (formError.value = '请选择任务日期')
  if (!form.taskType) return (formError.value = '请选择任务类型')
  if (!form.status) return (formError.value = '请选择任务状态')
  saving.value = true
  try {
    const payload = { ...form }
    if (!canManageAll.value) delete payload.ownerId
    if (editingId.value) {
      await taskApi.update(editingId.value, payload)
    } else {
      await taskApi.create(payload)
    }
    editorOpen.value = false
    load()
  } catch (e) {
    formError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  if (!window.confirm(`确认删除该任务？\n${row.description || row.taskType}`)) return
  try {
    await taskApi.remove(row.id)
    load()
  } catch (e) {
    alert(e.message || '删除失败')
  }
}

// ---- 日报总结弹窗（AI 汇总当日任务，未配置 AI 时为模板汇总）----
const dailyOpen = ref(false)
const dailyLoading = ref(false)
const dailyError = ref('')
const dailyData = ref(null)
const dailyStatus = ref('')
const dailyElapsed = ref(0)
const copyState = ref('') // '' | 'ok'
let dailyPollController = null
let dailyClock = null
let dailyRunToken = 0

const dailyWaitCopy = computed(() => dailySummaryStageCopy(dailyStatus.value, dailyElapsed.value))
const dailyWaitHint = computed(() => {
  if (dailyStatus.value === 'RETRYING') {
    return '当前任务记录已经保留，稍后会继续整理。'
  }
  if (dailyElapsed.value >= 45000) {
    return '后台任务仍在继续，关闭弹窗不会中断。'
  }
  return '日报只基于你今天的任务记录生成。'
})

async function openDaily() {
  stopDailyPolling()
  dailyOpen.value = true
  dailyLoading.value = true
  dailyError.value = ''
  dailyData.value = null
  dailyStatus.value = 'QUEUED'
  dailyElapsed.value = 0
  copyState.value = ''
  const startedAt = Date.now()
  const runToken = ++dailyRunToken
  const controller = new AbortController()
  dailyPollController = controller
  dailyClock = setInterval(() => {
    dailyElapsed.value = Date.now() - startedAt
  }, 1000)
  try {
    let supersededRetries = 0
    while (true) {
      let job = await dailySummaryApi.create()
      if (controller.signal.aborted || runToken !== dailyRunToken) {
        throw Object.assign(new Error('已停止等待日报'), {
          code: 'DAILY_SUMMARY_POLL_ABORTED'
        })
      }
      dailyStatus.value = job.status
      if (job.status === 'SUPERSEDED' && supersededRetries < 1) {
        supersededRetries++
        continue
      }
      if (!['SUCCEEDED', 'DEGRADED', 'FAILED', 'SUPERSEDED'].includes(job.status)) {
        job = await pollDailySummary({
          jobId: job.jobId,
          load: dailySummaryApi.status,
          onUpdate: (update) => {
            if (!controller.signal.aborted && runToken === dailyRunToken) {
              dailyStatus.value = update.status
            }
          },
          signal: controller.signal
        })
        if (controller.signal.aborted || runToken !== dailyRunToken) {
          throw Object.assign(new Error('已停止等待日报'), {
            code: 'DAILY_SUMMARY_POLL_ABORTED'
          })
        }
        dailyStatus.value = job.status
      }
      if (job.status === 'SUPERSEDED' && supersededRetries < 1) {
        supersededRetries++
        continue
      }
      if (job.status === 'FAILED' || job.status === 'SUPERSEDED') {
        throw new Error(job.errorMessage || '日报生成失败，请稍后重试')
      }
      dailyData.value = job
      break
    }
  } catch (e) {
    if (e.code === 'DAILY_SUMMARY_POLL_ABORTED' || runToken !== dailyRunToken) return
    dailyError.value = e.message || '日报生成失败'
  } finally {
    if (runToken === dailyRunToken) {
      if (!controller.signal.aborted) stopDailyPolling()
      dailyLoading.value = false
    }
  }
}

function closeDaily() {
  dailyOpen.value = false
  dailyLoading.value = false
  stopDailyPolling()
}

function stopDailyPolling() {
  dailyRunToken++
  dailyPollController?.abort()
  dailyPollController = null
  if (dailyClock) {
    clearInterval(dailyClock)
    dailyClock = null
  }
}

async function copyDaily() {
  const text = dailyData.value?.content || ''
  if (!text) return
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      // 非安全上下文降级方案
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    copyState.value = 'ok'
    setTimeout(() => (copyState.value = ''), 2000)
  } catch (e) {
    copyState.value = ''
    alert('复制失败，请手动复制')
  }
}

async function loadOwners() {
  if (!canViewAll.value) return
  try {
    owners.value = await userApi.options()
  } catch (e) {
    console.error(e)
  }
}

onMounted(() => {
  loadOwners()
  load()
})

onBeforeUnmount(stopDailyPolling)
</script>

<template>
  <div class="tasks-page">
    <!-- 页头 -->
    <section class="page-head">
      <div class="page-head-text">
        <h1 class="display-md">任务列表</h1>
        <p class="body-md head-sub">IT 日常任务登记与跟踪</p>
      </div>
      <div class="page-head-actions">
        <button class="btn btn-secondary" @click="openDaily">日报总结</button>
        <button class="btn btn-primary" @click="openCreate">+ 新建任务</button>
      </div>
    </section>

    <!-- 筛选栏 -->
    <section class="card-canvas filter-bar">
      <div class="filter-item">
        <label class="field-label">日期</label>
        <input v-model="filters.taskDate" type="date" class="input" />
      </div>
      <div class="filter-item">
        <label class="field-label">任务类型</label>
        <select v-model="filters.taskType" class="select">
          <option value="">全部</option>
          <option v-for="t in TASK_TYPES" :key="t" :value="t">{{ t }}</option>
        </select>
      </div>
      <div class="filter-item">
        <label class="field-label">任务状态</label>
        <select v-model="filters.status" class="select">
          <option value="">全部</option>
          <option v-for="s in STATUSES" :key="s" :value="s">{{ s }}</option>
        </select>
      </div>
      <div v-if="canViewAll" class="filter-item">
        <label class="field-label">归属人</label>
        <select v-model="filters.ownerId" class="select">
          <option value="">全部归属人</option>
          <option v-for="owner in owners" :key="owner.id" :value="owner.id">
            {{ owner.displayName }}
          </option>
        </select>
      </div>
      <div class="filter-actions">
        <button class="btn btn-primary" @click="search">查询</button>
        <button class="btn btn-secondary" @click="reset">重置</button>
      </div>
    </section>

    <!-- 表格 -->
    <section class="card-canvas table-card">
      <div class="table-wrap">
        <table class="task-table">
          <thead>
            <tr>
              <th class="col-date">日期</th>
              <th class="col-type">任务类型</th>
              <th class="col-desc">任务描述</th>
              <th class="col-status">任务状态</th>
              <th class="col-owner">归属人</th>
              <th class="col-summary">任务总结</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="7" class="table-empty">加载中…</td>
            </tr>
            <tr v-else-if="!list.length">
              <td colspan="7" class="table-empty">暂无任务，点击右上角「新建任务」开始登记</td>
            </tr>
            <template v-else>
              <tr v-for="row in list" :key="row.id">
              <td class="col-date body-sm">{{ row.taskDate }}</td>
              <td class="col-type"><TaskBadge :type="row.taskType" /></td>
              <td class="col-desc body-sm">{{ row.description || '—' }}</td>
              <td class="col-status"><TaskBadge :status="row.status" /></td>
              <td class="col-owner body-sm">{{ row.ownerName || '—' }}</td>
              <td class="col-summary body-sm summary-cell" :title="row.summary">{{ row.summary || '—' }}</td>
              <td class="col-actions">
                <button class="btn btn-sm btn-secondary" @click="openEdit(row)">编辑</button>
                <button class="btn btn-sm btn-danger-text" @click="remove(row)">删除</button>
              </td>
            </tr>
            </template>
          </tbody>
        </table>
      </div>

      <!-- 分页 -->
      <div class="pagination">
        <span class="caption">共 {{ total }} 条 · 第 {{ page }} / {{ totalPages }} 页</span>
        <div class="pager-btns">
          <button class="btn btn-sm btn-secondary" :disabled="page <= 1" @click="prevPage">上一页</button>
          <button class="btn btn-sm btn-secondary" :disabled="page >= totalPages" @click="nextPage">下一页</button>
        </div>
      </div>
    </section>

    <!-- 新增 / 编辑弹窗 -->
    <AppModal v-model:open="editorOpen" :open="editorOpen" :title="editingId ? '编辑任务' : '新建任务'" @close="editorOpen = false">
      <form class="task-form" @submit.prevent="save">
        <div class="form-row">
          <div class="form-field">
            <label class="field-label">任务日期 <span class="req">*</span></label>
            <input v-model="form.taskDate" type="date" class="input" required />
          </div>
          <div class="form-field">
            <label class="field-label">任务类型 <span class="req">*</span></label>
            <select v-model="form.taskType" class="select" required>
              <option value="" disabled>请选择</option>
              <option v-for="t in TASK_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
        </div>
        <div class="form-row">
          <div class="form-field">
            <label class="field-label">任务状态 <span class="req">*</span></label>
            <select v-model="form.status" class="select" required>
              <option v-for="s in STATUSES" :key="s" :value="s">{{ s }}</option>
            </select>
          </div>
          <div v-if="canManageAll" class="form-field">
            <label class="field-label">归属人 <span class="req">*</span></label>
            <select v-model="form.ownerId" class="select" required>
              <option value="" disabled>请选择</option>
              <option v-for="owner in owners" :key="owner.id" :value="owner.id">
                {{ owner.displayName }}
              </option>
            </select>
          </div>
        </div>
        <div class="form-field">
          <label class="field-label">任务描述</label>
          <textarea v-model="form.description" class="textarea" maxlength="1000" placeholder="输入任务描述（可选，最多 1000 字）" />
        </div>
        <div class="form-field">
          <label class="field-label">任务总结</label>
          <textarea v-model="form.summary" class="textarea" maxlength="2000" placeholder="输入任务总结（可选，最多 2000 字）" />
        </div>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <div class="form-actions">
          <button type="button" class="btn btn-secondary" @click="editorOpen = false">取消</button>
          <button type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </AppModal>

    <!-- 日报总结弹窗（AI 汇总） -->
    <AppModal :open="dailyOpen" title="日报总结" width="560px" @close="closeDaily">
      <div class="daily-modal">
        <div v-if="dailyLoading" class="daily-drafting" role="status" aria-live="polite">
          <div class="daily-draft-sheet" aria-hidden="true">
            <span class="daily-draft-line daily-draft-line-1" />
            <span class="daily-draft-line daily-draft-line-2" />
            <span class="daily-draft-line daily-draft-line-3" />
          </div>
          <div class="daily-wait-copy">
            <p class="daily-wait-title">{{ dailyWaitCopy }}</p>
            <p class="daily-wait-hint">{{ dailyWaitHint }}</p>
          </div>
          <div class="daily-wait-dots" aria-hidden="true">
            <span />
            <span />
            <span />
          </div>
        </div>

        <div v-else-if="dailyError" class="daily-error">
          <p class="daily-error-text">{{ dailyError }}</p>
          <button class="btn btn-secondary btn-sm" @click="openDaily">重试</button>
        </div>

        <template v-else-if="dailyData">
          <div class="daily-meta">
            <span class="badge" :class="dailyData.source === 'ai' ? 'badge-coral daily-badge-ai' : ''">
              {{
                dailyData.status === 'DEGRADED'
                  ? 'AI 降级 · 模板生成'
                  : dailyData.source === 'ai'
                    ? 'AI 生成'
                    : '模板生成'
              }}
            </span>
            <span class="caption">基于当前登录人 {{ dailyData.taskCount }} 项今日任务 · {{ dailyData.date }}</span>
          </div>
          <p class="daily-text">{{ dailyData.content }}</p>
          <p v-if="dailyData.status === 'DEGRADED'" class="daily-hint caption">
            AI 服务暂时繁忙，已切换为模板汇总；内容仍基于你今天的任务记录。
          </p>
          <p v-else-if="dailyData.source === 'template'" class="daily-hint caption">
            未配置 AI_API_KEY，当前为模板汇总；在 backend 的环境变量中配置后将由 AI 生成
          </p>
          <button class="btn btn-primary" @click="copyDaily">
            {{ copyState === 'ok' ? '已复制 ✓' : '一键复制' }}
          </button>
        </template>
      </div>
    </AppModal>
  </div>
</template>

<style scoped>
.tasks-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-lg);
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-lg);
  flex-wrap: wrap;
}
.page-head-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.page-head-actions {
  display: flex;
  gap: var(--sp-sm);
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-md);
  padding: var(--sp-md) var(--sp-lg);
  flex-wrap: wrap;
}
.filter-item {
  min-width: 160px;
}
.filter-actions {
  display: flex;
  gap: var(--sp-sm);
  margin-left: auto;
}

/* 表格 */
.table-card {
  padding: 0;
  overflow: hidden;
}
.table-wrap {
  overflow-x: auto;
}
.task-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 860px;
}
.task-table th {
  text-align: left;
  padding: 12px 16px;
  font-size: 13px;
  font-weight: 500;
  color: var(--muted);
  background: var(--surface-soft);
  border-bottom: 1px solid var(--hairline);
  white-space: nowrap;
}
.task-table td {
  padding: 12px 16px;
  border-bottom: 1px solid var(--hairline-soft);
  vertical-align: middle;
}
.task-table tbody tr:last-child td {
  border-bottom: none;
}
.task-table tbody tr:hover {
  background: var(--surface-soft);
}
.col-date {
  white-space: nowrap;
  width: 110px;
}
.col-type,
.col-status {
  width: 110px;
}
.col-desc {
  min-width: 220px;
}
.col-summary {
  min-width: 180px;
  max-width: 260px;
}
.summary-cell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted);
}
.col-actions {
  width: 130px;
  white-space: nowrap;
}
.table-empty {
  text-align: center;
  color: var(--muted);
  padding: var(--sp-xxl) 0 !important;
}

/* 分页 */
.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-md) var(--sp-lg);
  border-top: 1px solid var(--hairline-soft);
}
.pager-btns {
  display: flex;
  gap: var(--sp-sm);
}

/* 表单 */
.task-form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-md);
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-md);
}
.form-error {
  color: var(--error);
  font-size: 13px;
}
.req {
  color: var(--error);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-sm);
  margin-top: var(--sp-xs);
}

/* 日报弹窗 */
.daily-modal {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-md);
  padding: var(--sp-sm) 0;
  text-align: center;
}
.daily-drafting {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-lg);
  padding: var(--sp-md) 0 var(--sp-sm);
}
.daily-draft-sheet {
  width: min(320px, 100%);
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 22px 24px;
  text-align: left;
  background: var(--canvas);
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  box-shadow: 0 8px 22px rgba(20, 20, 19, 0.06);
}
.daily-draft-line {
  display: block;
  height: 5px;
  border-radius: var(--r-pill);
  background:
    linear-gradient(90deg, var(--surface-cream-strong) 0%, var(--primary) 50%, var(--surface-cream-strong) 100%);
  background-size: 220% 100%;
  opacity: 0.45;
  animation: daily-line-breathe 1.4s ease-in-out infinite;
}
.daily-draft-line-1 {
  width: 58%;
}
.daily-draft-line-2 {
  width: 92%;
  animation-delay: 0.2s;
}
.daily-draft-line-3 {
  width: 74%;
  animation-delay: 0.4s;
}
.daily-wait-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.daily-wait-title {
  color: var(--body-strong);
  font-family: var(--font-display);
  font-size: 20px;
  line-height: 1.35;
}
.daily-wait-hint {
  color: var(--muted);
  font-size: 13px;
}
.daily-wait-dots {
  display: flex;
  gap: 5px;
}
.daily-wait-dots span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--primary);
  opacity: 0.35;
  animation: daily-dot-breathe 1.2s ease-in-out infinite;
}
.daily-wait-dots span:nth-child(2) {
  animation-delay: 0.18s;
}
.daily-wait-dots span:nth-child(3) {
  animation-delay: 0.36s;
}
@keyframes daily-line-breathe {
  0%,
  100% {
    opacity: 0.45;
    background-position: 100% 0;
  }
  50% {
    opacity: 1;
    background-position: 0 0;
  }
}
@keyframes daily-dot-breathe {
  0%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  50% {
    opacity: 0.9;
    transform: translateY(-2px);
  }
}
.daily-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-md);
  padding: var(--sp-md) 0;
}
.daily-error-text {
  color: var(--error);
  font-size: 14px;
}
.daily-meta {
  display: flex;
  align-items: center;
  gap: var(--sp-sm);
}
.daily-badge-ai {
  font-size: 12px;
}
.daily-text {
  font-size: 15px;
  line-height: 1.7;
  color: var(--body-strong);
  text-align: left;
  white-space: pre-wrap;
  background: var(--surface-soft);
  border: 1px solid var(--hairline-soft);
  border-radius: var(--r-md);
  padding: var(--sp-md) var(--sp-lg);
  width: 100%;
}
.daily-hint {
  max-width: 100%;
}

@media (max-width: 700px) {
  .form-row {
    grid-template-columns: 1fr;
  }
  .filter-actions {
    margin-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .daily-draft-line,
  .daily-wait-dots span {
    animation: none;
  }
}
</style>

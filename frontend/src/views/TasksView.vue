<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { taskApi } from '../api'
import TaskBadge from '../components/TaskBadge.vue'
import AppModal from '../components/AppModal.vue'

const TASK_TYPES = ['审批', '硬件问题', '软件问题', '网络问题']
const STATUSES = ['完成', '未完成', '延期']

// ---- 列表与筛选 ----
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const filters = reactive({ taskDate: '', taskType: '', status: '' })

async function load() {
  loading.value = true
  try {
    const params = {
      page: page.value,
      size: size.value,
      taskDate: filters.taskDate || undefined,
      taskType: filters.taskType || undefined,
      status: filters.status || undefined
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
  summary: ''
})
const formError = ref('')

function openCreate() {
  editingId.value = null
  form.taskDate = new Date().toISOString().slice(0, 10)
  form.taskType = ''
  form.description = ''
  form.status = '未完成'
  form.summary = ''
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

// ---- 日报总结弹窗（占位，待后续开发） ----
const dailyOpen = ref(false)
const copyState = ref('') // '' | 'ok'
const DAILY_TEXT = '等待后续开发'

async function copyDaily() {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(DAILY_TEXT)
    } else {
      // 非安全上下文降级方案
      const ta = document.createElement('textarea')
      ta.value = DAILY_TEXT
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

onMounted(load)
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
        <button class="btn btn-secondary" @click="dailyOpen = true">日报总结</button>
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
              <th class="col-summary">任务总结</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="6" class="table-empty">加载中…</td>
            </tr>
            <tr v-else-if="!list.length">
              <td colspan="6" class="table-empty">暂无任务，点击右上角「新建任务」开始登记</td>
            </tr>
            <template v-else>
              <tr v-for="row in list" :key="row.id">
              <td class="col-date body-sm">{{ row.taskDate }}</td>
              <td class="col-type"><TaskBadge :type="row.taskType" /></td>
              <td class="col-desc body-sm">{{ row.description || '—' }}</td>
              <td class="col-status"><TaskBadge :status="row.status" /></td>
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

    <!-- 日报总结弹窗（占位） -->
    <AppModal :open="dailyOpen" title="日报总结" width="420px" @close="dailyOpen = false">
      <div class="daily-modal">
        <p class="daily-text">{{ DAILY_TEXT }}</p>
        <button class="btn btn-primary" @click="copyDaily">
          {{ copyState === 'ok' ? '已复制 ✓' : '一键复制' }}
        </button>
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
  gap: var(--sp-lg);
  padding: var(--sp-lg) 0;
}
.daily-text {
  font-family: var(--font-display);
  font-size: 24px;
  color: var(--ink);
}

@media (max-width: 700px) {
  .form-row {
    grid-template-columns: 1fr;
  }
  .filter-actions {
    margin-left: 0;
  }
}
</style>

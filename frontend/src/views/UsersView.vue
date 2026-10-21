<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { roleApi, userApi } from '../api'
import AppModal from '../components/AppModal.vue'

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const roles = ref([])
const filters = reactive({ username: '', displayName: '', enabled: '', roleId: '' })

const editorOpen = ref(false)
const editingId = ref(null)
const saving = ref(false)
const formError = ref('')
const form = reactive({ username: '', password: '', displayName: '', roleId: '', enabled: true })

const resetOpen = ref(false)
const resetTarget = ref(null)
const resetPassword = ref('')
const resetError = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

async function load() {
  loading.value = true
  try {
    const data = await userApi.page({
      page: page.value,
      size: size.value,
      username: filters.username || undefined,
      displayName: filters.displayName || undefined,
      enabled: filters.enabled === '' ? undefined : filters.enabled === 'true',
      roleId: filters.roleId || undefined
    })
    list.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    alert(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  try {
    roles.value = await roleApi.options()
  } catch (e) {
    alert(e.message || '角色加载失败')
  }
}

function search() {
  page.value = 1
  load()
}

function resetFilters() {
  filters.username = ''
  filters.displayName = ''
  filters.enabled = ''
  filters.roleId = ''
  search()
}

function openCreate() {
  editingId.value = null
  form.username = ''
  form.password = ''
  form.displayName = ''
  form.roleId = roles.value[0]?.id || ''
  form.enabled = true
  formError.value = ''
  editorOpen.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.username = row.username
  form.password = ''
  form.displayName = row.displayName
  form.roleId = row.roleId
  form.enabled = row.enabled
  formError.value = ''
  editorOpen.value = true
}

async function save() {
  if (!form.username || !form.displayName || !form.roleId) {
    formError.value = '请填写用户名、姓名并选择角色'
    return
  }
  if (!editingId.value && !form.password) {
    formError.value = '请填写初始密码'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    if (editingId.value) {
      await userApi.update(editingId.value, {
        displayName: form.displayName,
        roleId: Number(form.roleId),
        enabled: form.enabled
      })
    } else {
      await userApi.create({
        username: form.username,
        password: form.password,
        displayName: form.displayName,
        roleId: Number(form.roleId),
        enabled: form.enabled
      })
    }
    editorOpen.value = false
    load()
  } catch (e) {
    formError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  const action = row.enabled ? '禁用' : '启用'
  if (!window.confirm(`确认${action}账号「${row.displayName}」？`)) return
  try {
    await userApi.status(row.id, !row.enabled)
    load()
  } catch (e) {
    alert(e.message || `${action}失败`)
  }
}

function openReset(row) {
  resetTarget.value = row
  resetPassword.value = ''
  resetError.value = ''
  resetOpen.value = true
}

async function submitReset() {
  if (!resetPassword.value) {
    resetError.value = '请输入新密码'
    return
  }
  resetError.value = ''
  try {
    await userApi.resetPassword(resetTarget.value.id, resetPassword.value)
    resetOpen.value = false
  } catch (e) {
    resetError.value = e.message || '重置失败'
  }
}

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

function formatTime(value) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

onMounted(() => {
  loadRoles()
  load()
})
</script>

<template>
  <div class="users-page">
    <section class="page-head">
      <div>
        <h1 class="display-md">人员管理</h1>
        <p class="body-md head-sub">管理登录账号、角色和启用状态</p>
      </div>
      <button class="btn btn-primary" @click="openCreate">+ 新增账号</button>
    </section>

    <section class="card-canvas filter-bar">
      <div class="filter-item">
        <label class="field-label">用户名</label>
        <input v-model.trim="filters.username" class="input" placeholder="用户名" @keyup.enter="search" />
      </div>
      <div class="filter-item">
        <label class="field-label">姓名</label>
        <input v-model.trim="filters.displayName" class="input" placeholder="姓名" @keyup.enter="search" />
      </div>
      <div class="filter-item">
        <label class="field-label">状态</label>
        <select v-model="filters.enabled" class="select">
          <option value="">全部</option>
          <option value="true">启用</option>
          <option value="false">禁用</option>
        </select>
      </div>
      <div class="filter-item">
        <label class="field-label">角色</label>
        <select v-model="filters.roleId" class="select">
          <option value="">全部</option>
          <option v-for="role in roles" :key="role.id" :value="role.id">{{ role.name }}</option>
        </select>
      </div>
      <div class="filter-actions">
        <button class="btn btn-primary" @click="search">查询</button>
        <button class="btn btn-secondary" @click="resetFilters">重置</button>
      </div>
    </section>

    <section class="card-canvas table-card">
      <div class="table-wrap">
        <table class="user-table">
          <thead>
            <tr>
              <th>用户名</th>
              <th>姓名</th>
              <th>角色</th>
              <th>状态</th>
              <th>创建时间</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="6" class="table-empty">加载中…</td>
            </tr>
            <tr v-else-if="!list.length">
              <td colspan="6" class="table-empty">暂无账号</td>
            </tr>
            <template v-else>
              <tr v-for="row in list" :key="row.id">
                <td class="body-sm">{{ row.username }}</td>
                <td class="body-sm">{{ row.displayName }}</td>
                <td><span class="badge">{{ row.roleName || '—' }}</span></td>
                <td>
                  <span class="badge" :class="row.enabled ? 'badge-success' : 'badge-warning'">
                    {{ row.enabled ? '启用' : '禁用' }}
                  </span>
                </td>
                <td class="body-sm">{{ formatTime(row.createdAt) }}</td>
                <td class="col-actions">
                  <button class="btn btn-sm btn-secondary" @click="openEdit(row)">编辑</button>
                  <button class="btn btn-sm btn-danger-text" @click="toggleStatus(row)">
                    {{ row.enabled ? '禁用' : '启用' }}
                  </button>
                  <button class="btn btn-sm btn-secondary" @click="openReset(row)">重置密码</button>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <div class="pagination">
        <span class="caption">共 {{ total }} 条 · 第 {{ page }} / {{ totalPages }} 页</span>
        <div class="pager-btns">
          <button class="btn btn-sm btn-secondary" :disabled="page <= 1" @click="prevPage">上一页</button>
          <button class="btn btn-sm btn-secondary" :disabled="page >= totalPages" @click="nextPage">下一页</button>
        </div>
      </div>
    </section>

    <AppModal :open="editorOpen" :title="editingId ? '编辑账号' : '新增账号'" @close="editorOpen = false">
      <form class="user-form" @submit.prevent="save">
        <div class="form-field">
          <label class="field-label">用户名 <span class="req">*</span></label>
          <input v-model.trim="form.username" class="input" :disabled="Boolean(editingId)" required />
        </div>
        <div v-if="!editingId" class="form-field">
          <label class="field-label">初始密码 <span class="req">*</span></label>
          <input v-model="form.password" type="password" class="input" autocomplete="new-password" required />
        </div>
        <div class="form-field">
          <label class="field-label">姓名 <span class="req">*</span></label>
          <input v-model.trim="form.displayName" class="input" required />
        </div>
        <div class="form-field">
          <label class="field-label">角色 <span class="req">*</span></label>
          <select v-model="form.roleId" class="select" required>
            <option value="" disabled>请选择角色</option>
            <option v-for="role in roles" :key="role.id" :value="role.id">{{ role.name }}</option>
          </select>
        </div>
        <label class="check-line">
          <input v-model="form.enabled" type="checkbox" />
          <span>启用账号</span>
        </label>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <div class="form-actions">
          <button type="button" class="btn btn-secondary" @click="editorOpen = false">取消</button>
          <button type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </AppModal>

    <AppModal :open="resetOpen" title="重置密码" @close="resetOpen = false">
      <form class="user-form" @submit.prevent="submitReset">
        <p class="body-sm reset-tip">为「{{ resetTarget?.displayName }}」设置新密码。</p>
        <div class="form-field">
          <label class="field-label">新密码 <span class="req">*</span></label>
          <input v-model="resetPassword" type="password" class="input" autocomplete="new-password" required />
        </div>
        <p v-if="resetError" class="form-error">{{ resetError }}</p>
        <div class="form-actions">
          <button type="button" class="btn btn-secondary" @click="resetOpen = false">取消</button>
          <button type="submit" class="btn btn-primary">确认重置</button>
        </div>
      </form>
    </AppModal>
  </div>
</template>

<style scoped>
.users-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-lg);
}
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-lg);
}
.head-sub {
  margin-top: var(--sp-xs);
}
.filter-bar {
  display: grid;
  grid-template-columns: repeat(4, minmax(140px, 1fr)) auto;
  gap: var(--sp-md);
  align-items: end;
}
.filter-actions {
  display: flex;
  gap: var(--sp-xs);
}
.table-card {
  padding: 0;
  overflow: hidden;
}
.table-wrap {
  overflow-x: auto;
}
.user-table {
  width: 100%;
  border-collapse: collapse;
}
.user-table th {
  padding: 14px 16px;
  background: var(--surface-cream-strong);
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}
.user-table td {
  padding: 14px 16px;
  border-top: 1px solid var(--hairline-soft);
  vertical-align: middle;
}
.user-table tbody tr:hover {
  background: var(--surface-soft);
}
.col-actions {
  width: 250px;
  white-space: nowrap;
}
.table-empty {
  padding: 44px 16px;
  text-align: center;
  color: var(--muted);
}
.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-md) var(--sp-lg);
  border-top: 1px solid var(--hairline-soft);
}
.pager-btns {
  display: flex;
  gap: var(--sp-xs);
}
.user-form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-md);
}
.check-line {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--body);
  font-size: 14px;
}
.form-error {
  color: var(--error);
  font-size: 13px;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-xs);
  margin-top: var(--sp-xs);
}
.reset-tip {
  color: var(--muted);
}
@media (max-width: 980px) {
  .filter-bar {
    grid-template-columns: 1fr 1fr;
  }
  .filter-actions {
    align-self: end;
  }
}
@media (max-width: 640px) {
  .page-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .filter-bar {
    grid-template-columns: 1fr;
  }
}
</style>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { roleApi } from '../api'
import AppModal from '../components/AppModal.vue'

const list = ref([])
const permissions = ref([])
const loading = ref(false)
const editorOpen = ref(false)
const editing = ref(null)
const saving = ref(false)
const formError = ref('')
const form = reactive({ code: '', name: '', description: '', permissionIds: [] })

const isBuiltIn = computed(() => Boolean(editing.value?.builtIn))
const isSuperAdmin = computed(() => editing.value?.code === 'SUPER_ADMIN')

function permissionName(code) {
  return permissions.value.find(permission => permission.code === code)?.name || code
}

async function load() {
  loading.value = true
  try {
    const [roles, allPermissions] = await Promise.all([
      roleApi.list(),
      roleApi.permissions()
    ])
    list.value = roles
    permissions.value = allPermissions
  } catch (e) {
    alert(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.code = ''
  form.name = ''
  form.description = ''
  form.permissionIds = []
  formError.value = ''
  editorOpen.value = true
}

function openEdit(role) {
  editing.value = role
  form.code = role.code
  form.name = role.name
  form.description = role.description || ''
  form.permissionIds = [...role.permissionIds]
  formError.value = ''
  editorOpen.value = true
}

function togglePermission(id) {
  if (isSuperAdmin.value) return
  const ids = new Set(form.permissionIds)
  ids.has(id) ? ids.delete(id) : ids.add(id)
  form.permissionIds = [...ids]
}

async function save() {
  if (!form.code || !form.name) {
    formError.value = '请填写角色编码和名称'
    return
  }
  saving.value = true
  formError.value = ''
  const payload = {
    code: form.code,
    name: form.name,
    description: form.description,
    permissionIds: form.permissionIds
  }
  try {
    if (editing.value) {
      await roleApi.update(editing.value.id, payload)
    } else {
      await roleApi.create(payload)
    }
    editorOpen.value = false
    load()
  } catch (e) {
    formError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function remove(role) {
  if (!window.confirm(`确认删除角色「${role.name}」？`)) return
  try {
    await roleApi.remove(role.id)
    load()
  } catch (e) {
    alert(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="roles-page">
    <section class="page-head">
      <div>
        <h1 class="display-md">角色管理</h1>
        <p class="body-md head-sub">配置角色权限和账号可见范围</p>
      </div>
      <button class="btn btn-primary" @click="openCreate">+ 新增角色</button>
    </section>

    <section class="card-canvas table-card">
      <div class="table-wrap">
        <table class="role-table">
          <thead>
            <tr>
              <th>角色编码</th>
              <th>角色名称</th>
              <th>说明</th>
              <th>账号数</th>
              <th>权限</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="6" class="table-empty">加载中…</td>
            </tr>
            <tr v-else-if="!list.length">
              <td colspan="6" class="table-empty">暂无角色</td>
            </tr>
            <template v-else>
              <tr v-for="role in list" :key="role.id">
                <td>
                  <code class="role-code">{{ role.code }}</code>
                  <span v-if="role.builtIn" class="badge built-in-badge">内置</span>
                </td>
                <td class="body-sm">{{ role.name }}</td>
                <td class="body-sm description-cell">{{ role.description || '—' }}</td>
                <td class="body-sm">{{ role.userCount }}</td>
                <td>
                  <div class="permission-tags">
                    <span v-for="code in role.permissionCodes" :key="code" class="badge permission-tag">
                      {{ permissionName(code) }}
                    </span>
                    <span v-if="!role.permissionCodes?.length" class="caption">无权限</span>
                  </div>
                </td>
                <td class="col-actions">
                  <button class="btn btn-sm btn-secondary" @click="openEdit(role)">编辑</button>
                  <button
                    v-if="role.code !== 'SUPER_ADMIN'"
                    class="btn btn-sm btn-danger-text"
                    @click="remove(role)"
                  >
                    删除
                  </button>
                  <span v-else class="caption locked-text">已锁定</span>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </section>

    <AppModal :open="editorOpen" :title="editing ? '编辑角色' : '新增角色'" width="620px" @close="editorOpen = false">
      <form class="role-form" @submit.prevent="save">
        <div class="form-row">
          <div class="form-field">
            <label class="field-label">角色编码 <span class="req">*</span></label>
            <input
              v-model.trim="form.code"
              class="input"
              :disabled="isBuiltIn"
              placeholder="例如 IT_ADMIN"
              required
            />
          </div>
          <div class="form-field">
            <label class="field-label">角色名称 <span class="req">*</span></label>
            <input v-model.trim="form.name" class="input" required />
          </div>
        </div>
        <div class="form-field">
          <label class="field-label">说明</label>
          <textarea v-model="form.description" class="textarea" maxlength="255" />
        </div>
        <div class="form-field">
          <div class="permission-head">
            <label class="field-label">权限配置</label>
            <span v-if="isSuperAdmin" class="caption">超级管理员固定拥有全部权限</span>
          </div>
          <div class="permission-grid">
            <label
              v-for="permission in permissions"
              :key="permission.id"
              class="permission-option"
              :class="{ disabled: isSuperAdmin }"
            >
              <input
                type="checkbox"
                :checked="form.permissionIds.includes(permission.id)"
                :disabled="isSuperAdmin"
                @change="togglePermission(permission.id)"
              />
              <span>
                <strong>{{ permission.name }}</strong>
                <small>{{ permission.code }}</small>
              </span>
            </label>
          </div>
        </div>
        <p v-if="formError" class="form-error">{{ formError }}</p>
        <div class="form-actions">
          <button type="button" class="btn btn-secondary" @click="editorOpen = false">取消</button>
          <button type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </AppModal>
  </div>
</template>


<style scoped>
.roles-page {
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
.table-card {
  padding: 0;
  overflow: hidden;
}
.table-wrap {
  overflow-x: auto;
}
.role-table {
  width: 100%;
  border-collapse: collapse;
}
.role-table th {
  padding: 14px 16px;
  background: var(--surface-cream-strong);
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}
.role-table td {
  padding: 14px 16px;
  border-top: 1px solid var(--hairline-soft);
  vertical-align: top;
}
.role-table tbody tr:hover {
  background: var(--surface-soft);
}
.role-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.built-in-badge {
  margin-left: 6px;
  background: var(--surface-cream-strong);
  color: var(--muted);
  font-size: 11px;
}
.description-cell {
  max-width: 260px;
}
.permission-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  min-width: 260px;
}
.permission-tag {
  font-size: 11px;
  padding: 2px 8px;
}
.col-actions {
  width: 170px;
  white-space: nowrap;
}
.locked-text {
  margin-left: 8px;
}
.table-empty {
  padding: 44px 16px;
  text-align: center;
  color: var(--muted);
}
.role-form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-md);
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-md);
}
.permission-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-md);
}
.permission-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: var(--sp-xs);
}
.permission-option {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 12px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: var(--canvas);
  cursor: pointer;
}
.permission-option.disabled {
  cursor: not-allowed;
  opacity: 0.68;
}
.permission-option input {
  margin-top: 3px;
}
.permission-option span {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.permission-option strong {
  font-size: 13px;
  font-weight: 500;
}
.permission-option small {
  color: var(--muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
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
@media (max-width: 760px) {
  .page-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .form-row,
  .permission-grid {
    grid-template-columns: 1fr;
  }
}
</style>

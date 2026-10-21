import { computed, reactive } from 'vue'
import { authApi } from '../api'
import { clearSession, getSession, saveSession } from '../auth/session'

const initial = getSession()

const state = reactive({
  token: initial?.token || '',
  user: initial ? {
    id: initial.id,
    username: initial.username,
    displayName: initial.displayName,
    roleId: initial.roleId,
    roleCode: initial.roleCode,
    roleName: initial.roleName,
    permissions: initial.permissions || []
  } : null
})

function persist() {
  saveSession({
    token: state.token,
    ...(state.user || {})
  })
}

export const auth = {
  user: computed(() => state.user),
  isLoggedIn: computed(() => Boolean(state.token && state.user)),
  hasPermission(code) {
    return Boolean(state.user?.permissions?.includes(code))
  },
  setSession(response) {
    state.token = response.token || ''
    state.user = {
      id: response.id,
      username: response.username,
      displayName: response.displayName,
      roleId: response.roleId,
      roleCode: response.roleCode,
      roleName: response.roleName,
      permissions: response.permissions || []
    }
    persist()
  },
  async login(username, password) {
    const response = await authApi.login({ username, password })
    this.setSession(response)
    return response
  },
  async refresh() {
    const response = await authApi.me()
    this.setSession({ ...response, token: state.token })
    return response
  },
  logout() {
    this.clear()
    return authApi.logout().catch(() => null)
  },
  clear() {
    state.token = ''
    state.user = null
    clearSession()
  }
}

import axios from 'axios'
import { clearSession, getToken } from '../auth/session'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && body.code !== 0) {
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body ? body.data : null
  },
  (err) => {
    const status = err.response?.status
    const msg = err.response?.data?.msg || err.message || '网络异常'
    if (status === 401) {
      clearSession()
      if (window.location.pathname !== '/login') window.location.assign('/login')
    }
    return Promise.reject(new Error(msg))
  }
)

export const authApi = {
  login(data) { return http.post('/auth/login', data) },
  me() { return http.get('/auth/me') },
  logout() { return http.post('/auth/logout') }
}

export const taskApi = {
  page(params) { return http.get('/tasks', { params }) },
  getById(id) { return http.get(`/tasks/${id}`) },
  create(data) { return http.post('/tasks', data) },
  update(id, data) { return http.put(`/tasks/${id}`, data) },
  remove(id) { return http.delete(`/tasks/${id}`) }
}

export const statsApi = {
  today() { return http.get('/stats/today') }
}

export const newsApi = {
  hardware() { return http.get('/news/hardware') }
}

export const dailySummaryApi = {
  create(date) {
    return http.post('/daily-summary/jobs', null, { params: date ? { date } : {} })
  },
  status(jobId) {
    return http.get(`/daily-summary/jobs/${jobId}`)
  }
}

export const userApi = {
  page(params) { return http.get('/users', { params }) },
  options() { return http.get('/users/options') },
  create(data) { return http.post('/users', data) },
  update(id, data) { return http.put(`/users/${id}`, data) },
  status(id, enabled) { return http.patch(`/users/${id}/status`, { enabled }) },
  resetPassword(id, password) { return http.post(`/users/${id}/reset-password`, { password }) }
}

export const roleApi = {
  list() { return http.get('/roles') },
  options() { return http.get('/roles/options') },
  permissions() { return http.get('/permissions') },
  create(data) { return http.post('/roles', data) },
  update(id, data) { return http.put(`/roles/${id}`, data) },
  remove(id) { return http.delete(`/roles/${id}`) }
}

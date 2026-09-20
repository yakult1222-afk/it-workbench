import axios from 'axios'

// 默认走同源 /api（开发环境由 Vite 代理转发到后端）；
// 独立部署时可通过 VITE_API_BASE 指定后端地址，例如 VITE_API_BASE=http://192.168.1.10:8080/api
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000
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
    const msg = err.response?.data?.msg || err.message || '网络异常'
    return Promise.reject(new Error(msg))
  }
)

// ---- 任务 CRUD ----
export const taskApi = {
  page(params) {
    return http.get('/tasks', { params })
  },
  getById(id) {
    return http.get(`/tasks/${id}`)
  },
  create(data) {
    return http.post('/tasks', data)
  },
  update(id, data) {
    return http.put(`/tasks/${id}`, data)
  },
  remove(id) {
    return http.delete(`/tasks/${id}`)
  }
}

// ---- 首页统计 ----
export const statsApi = {
  today() {
    return http.get('/stats/today')
  }
}

// ---- PC 硬件新闻 ----
export const newsApi = {
  hardware() {
    return http.get('/news/hardware')
  }
}

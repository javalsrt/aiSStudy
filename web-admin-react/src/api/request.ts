import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 300000, // 5 分钟：AI 出题 / 文档识别等接口可能需要较长时间
})

// 请求拦截：附加 token；访客模式拦截所有写操作
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    const guestMode = localStorage.getItem('guest-mode') === '1'
    const method = (config.method || 'get').toLowerCase()
    if (guestMode && method !== 'get') {
      return Promise.reject({
        response: {
          status: 403,
          data: { message: '访客模式仅支持浏览，不能进行增删改操作' },
        },
      })
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截：后端直接返回业务数据，不需要取 .data.data
request.interceptors.response.use(
  (response) => {
    return response.data
  },
  (error: AxiosError) => {
    // 401 表示登录已过期，需要清除状态并跳转登录页；
    // 403 仅表示当前角色无权限访问该资源，不应清 token。
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      localStorage.removeItem('roles')
      localStorage.removeItem('permissions')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default request

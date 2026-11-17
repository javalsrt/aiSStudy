import { getToken, logout } from './store.js'

/**
 * 后端 API 地址配置
 *
 * - 开发环境（npm run dev）：使用本机局域网 IP，方便真机调试
 *   ⚠️ 真机调试前请先修改 DEV_BASE 为你电脑的局域网 IP（ipconfig 查询）
 * - 生产环境（npm run build:prod）：使用部署域名
 *   ⚠️ 发布前请修改 PROD_BASE 为线上后端地址
 */
const DEV_BASE = 'http://192.168.0.146:8080'
const PROD_BASE = 'https://zhzdai.xyz:8081'

// uni-app 构建：开发环境 NODE_ENV === 'development'，生产构建为 'production'
const BASE = process.env.NODE_ENV === 'development' ? DEV_BASE : PROD_BASE

/** 通用请求 */
export function request(url, options = {}) {
  const token = getToken()
  const header = { 'Content-Type': 'application/json', ...options.header }
  if (token) header['Authorization'] = 'Bearer ' + token

  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE + url,
      method: options.method || 'GET',
      data: options.data,
      header,
      success(res) {
        if (res.statusCode === 401) {
          logout()
          uni.reLaunch({ url: '/pages/login/index' })
          return
        }
        resolve({ ...res.data, _status: res.statusCode })
      },
      fail(err) { reject(err) }
    })
  })
}

/** 上传文件 */
export function uploadFile(courseName, filePath) {
  const token = getToken()
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: BASE + '/api/chat/upload-file',
      filePath,
      name: 'file',
      formData: { courseName },
      header: { 'Authorization': 'Bearer ' + token },
      success(res) {
        try { resolve(JSON.parse(res.data)) }
        catch { resolve(res.data) }
      },
      fail(err) { reject(err) }
    })
  })
}

// ===== API 方法 =====

export function login(username, password) {
  return request('/api/auth/login', { method: 'POST', data: { username, password } })
}

export function getStudentCourses() {
  return request('/api/schedule/student/courses')
}

export function getChatMessages(courseName) {
  return request('/api/chat/' + encodeURIComponent(courseName))
}

export function sendMessage(courseName, content, senderRole = 'student') {
  return request('/api/chat/send', {
    method: 'POST',
    data: { courseName, content, senderRole }
  })
}

export function askAI(courseName, content) {
  return request('/api/chat/rag', {
    method: 'POST',
    data: { courseName, content }
  })
}

export function getUnreadCount() {
  return request('/api/chat/unread')
}

export function markAsRead(courseName) {
  return request('/api/chat/read', {
    method: 'POST',
    data: { courseName }
  })
}

export function getSchedule(week, semester) {
  let url = '/api/schedule/student/my?week=' + week
  if (semester) url += '&semester=' + encodeURIComponent(semester)
  return request(url)
}

export function getStudentSemesters() {
  return request('/api/schedule/student/semesters')
}

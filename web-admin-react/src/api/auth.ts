import request from './request'
import type { LoginResponse } from '@/types'

// 后端登录返回 role 是字符串 "1"学生/"2"教师/"3"管理员
const roleMap: Record<string, 'student' | 'teacher' | 'admin'> = {
  '1': 'student',
  '2': 'teacher',
  '3': 'admin',
}

export const login = (data: { username: string; password: string }) => {
  return request.post<any, LoginResponse>('/auth/login', data)
}

export interface CaptchaData {
  captchaId: string
  /** PNG data URL，可直接放入 img src */
  image: string
}

/** 管理端图形验证码 */
export const getCaptcha = () => {
  return request.get<any, CaptchaData>('/auth/captcha')
}

/** 管理端登录：账号密码 + 验证码 */
export const adminLogin = (data: {
  username: string
  password: string
  captchaId: string
  captchaCode: string
}) => {
  return request.post<any, LoginResponse>('/auth/admin-login', data)
}

/** 访客只读登录：无需账号密码，仅用于浏览演示数据 */
export const guestLogin = () => {
  return request.post<any, LoginResponse>('/auth/guest')
}

// 把后端返回转换成前端 User
export const parseLoginResponse = (res: LoginResponse) => {
  // 优先使用 RBAC roles 数组中的第一个角色，再回退到旧 role 字段
  // 后端 role 字段是 Integer，JSON 解析后可能是 number，需要转为字符串再查映射
  const role = (res.roles && res.roles.length > 0
    ? res.roles[0]
    : roleMap[String(res.role)]) || 'student'
  return {
    token: res.token,
    user: {
      id: res.userId,
      username: res.username,
      realName: res.realName,
      role: role as 'admin' | 'teacher' | 'student',
      avatarUrl: res.avatarUrl,
      roles: res.roles || [],
      permissions: res.permissions || [],
    },
  }
}

export const logout = () => {
  // 后端没有 logout 接口，前端清除即可
  return Promise.resolve()
}

import { create } from 'zustand'
import type { User } from '@/types'

const LS_TOKEN = 'token'
const LS_USER = 'user'
const LS_ROLES = 'roles'
const LS_PERMISSIONS = 'permissions'

interface AuthState {
  token: string | null
  user: User | null
  /** 后端返回的 RBAC 角色编码列表 */
  roles: string[]
  /** 后端返回的 RBAC 权限编码列表 */
  permissions: string[]
  isAuthenticated: boolean
  setAuth: (token: string, user: User) => void
  logout: () => void
  init: () => void
  hasRole: (role: string) => boolean
  hasPermission: (permission: string) => boolean
}

export const useAuthStore = create<AuthState>((set, get) => ({
  token: null,
  user: null,
  roles: [],
  permissions: [],
  isAuthenticated: false,

  setAuth: (token, user) => {
    const roles = user.roles || []
    const permissions = user.permissions || []
    localStorage.setItem(LS_TOKEN, token)
    localStorage.setItem(LS_USER, JSON.stringify(user))
    localStorage.setItem(LS_ROLES, JSON.stringify(roles))
    localStorage.setItem(LS_PERMISSIONS, JSON.stringify(permissions))
    set({ token, user, roles, permissions, isAuthenticated: true })
  },

  logout: () => {
    localStorage.removeItem(LS_TOKEN)
    localStorage.removeItem(LS_USER)
    localStorage.removeItem(LS_ROLES)
    localStorage.removeItem(LS_PERMISSIONS)
    // 退出时同时解除访客只读标记
    localStorage.removeItem('guest-mode')
    set({ token: null, user: null, roles: [], permissions: [], isAuthenticated: false })
  },

  init: () => {
    const token = localStorage.getItem(LS_TOKEN)
    const userStr = localStorage.getItem(LS_USER)
    if (token && userStr) {
      try {
        const user: User = JSON.parse(userStr)
        const roles: string[] = JSON.parse(localStorage.getItem(LS_ROLES) || '[]')
        const permissions: string[] = JSON.parse(localStorage.getItem(LS_PERMISSIONS) || '[]')
        set({ token, user, roles, permissions, isAuthenticated: true })
      } catch (e) {
        localStorage.removeItem(LS_TOKEN)
        localStorage.removeItem(LS_USER)
        localStorage.removeItem(LS_ROLES)
        localStorage.removeItem(LS_PERMISSIONS)
      }
    }
  },

  hasRole: (role) => get().roles.includes(role),

  hasPermission: (permission) => get().permissions.includes(permission),
}))

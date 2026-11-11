import { create } from 'zustand'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'zhixue-theme'

const readTheme = (): Theme =>
  window.localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light'

const applyTheme = (theme: Theme) => {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  document.documentElement.style.colorScheme = theme
}

interface ThemeState {
  theme: Theme
  setTheme: (theme: Theme) => void
  toggle: () => void
}

/** 全局明暗主题：持久化到 localStorage，并同步 <html class="dark">。 */
export const useThemeStore = create<ThemeState>((set, get) => ({
  theme: readTheme(),
  setTheme: (theme) => {
    window.localStorage.setItem(STORAGE_KEY, theme)
    applyTheme(theme)
    set({ theme })
  },
  toggle: () => get().setTheme(get().theme === 'dark' ? 'light' : 'dark'),
}))

/** 首帧前调用，避免刷新时先亮后暗的闪烁。 */
export const initTheme = () => applyTheme(useThemeStore.getState().theme)

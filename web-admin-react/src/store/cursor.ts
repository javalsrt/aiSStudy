import { create } from 'zustand'

const STORAGE_KEY = 'zhixue-target-cursor'

const readEnabled = () => window.localStorage.getItem(STORAGE_KEY) === '1'

interface CursorState {
  enabled: boolean
  setEnabled: (enabled: boolean) => void
  toggle: () => void
}

/** 目标光标开关：持久化到 localStorage，默认关闭，保持系统鼠标。 */
export const useCursorStore = create<CursorState>((set, get) => ({
  enabled: readEnabled(),
  setEnabled: (enabled) => {
    window.localStorage.setItem(STORAGE_KEY, enabled ? '1' : '0')
    set({ enabled })
  },
  toggle: () => get().setEnabled(!get().enabled),
}))

import { create } from 'zustand'

/**
 * 登录 / 退出登录的像素过渡：
 * play() 先让全屏像素铺满遮住页面，铺满后执行跳转（切换账号态），
 * 再由同一组像素退场，露出新页面。
 */
interface AuthTransitionState {
  active: boolean
  /** 每次强制复位自增，用于重挂载过渡层，保证遮罩一定被清掉 */
  epoch: number
  action: (() => void) | null
  play: (action: () => void) => void
  complete: (covered: boolean) => void
  forceReset: () => void
}

export const useAuthTransitionStore = create<AuthTransitionState>((set, get) => ({
  active: false,
  epoch: 0,
  action: null,

  play: (action) => {
    // 过渡进行中再次触发时直接切换，避免动画叠加
    if (get().active) {
      action()
      return
    }
    set({ active: true, action })
  },

  complete: (covered) => {
    if (!covered) return
    const action = get().action
    set({ action: null })
    try {
      action?.()
    } finally {
      // 无论跳转是否抛错，都必须让像素退场
      window.setTimeout(() => set({ active: false }), 120)
    }
  },

  forceReset: () => {
    const action = get().action
    set((state) => ({ active: false, action: null, epoch: state.epoch + 1 }))
    action?.()
  },
}))

/** 在任意组件中触发登录态切换的像素过渡 */
export const playAuthTransition = (action: () => void) => {
  useAuthTransitionStore.getState().play(action)
}

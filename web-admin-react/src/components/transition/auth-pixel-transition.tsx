import { useEffect } from 'react'
import { cn } from '@/lib/utils'
import { useAuthTransitionStore } from '@/store/auth-transition'
import { PixelSwap } from './pixel-swap'

/** 兜底时长：超过这个时间过渡仍未结束，强制复位，避免遮罩卡住不消失 */
const WATCHDOG_MS = 2600

/**
 * 登录 / 退出登录的全屏像素过渡层。
 * 挂在路由外层，页面切换时不会被卸载，像素铺满后执行跳转再退场。
 */
export function AuthPixelTransition() {
  const active = useAuthTransitionStore((state) => state.active)
  const epoch = useAuthTransitionStore((state) => state.epoch)
  const complete = useAuthTransitionStore((state) => state.complete)
  const forceReset = useAuthTransitionStore((state) => state.forceReset)

  // 兜底：动画因掉帧、切后台等原因停在中间态时，强制清掉遮罩
  useEffect(() => {
    if (!active) return
    const timer = window.setTimeout(forceReset, WATCHDOG_MS)
    return () => window.clearTimeout(timer)
  }, [active, epoch, forceReset])

  return (
    <div
      aria-hidden="true"
      className={cn('fixed inset-0 z-[200]', active ? 'pointer-events-auto' : 'pointer-events-none')}
    >
      <PixelSwap
        key={epoch}
        active={active}
        pattern="center"
        pixelSize={64}
        duration={320}
        pixelDuration={280}
        randomness={0.25}
        bounce={0.3}
        onComplete={complete}
        secondContent={
          <div className="h-full w-full bg-gradient-to-br from-primary-550 via-primary-500 to-cyan-500" />
        }
      />
    </div>
  )
}

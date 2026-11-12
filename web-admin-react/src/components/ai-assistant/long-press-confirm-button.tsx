import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { Loader2, Lock, Trash2 } from 'lucide-react'
import { cn } from '@/lib/utils'

interface LongPressConfirmButtonProps {
  onConfirm: () => void
  confirming?: boolean
  disabled?: boolean
  duration?: number
}

/**
 * 删除确认按钮：
 * 1. 按住 3 秒，进度条走满后解锁；
 * 2. 解锁后再点击一次才真正执行删除。
 */
export function LongPressConfirmButton({
  onConfirm,
  confirming = false,
  disabled = false,
  duration = 3000,
}: LongPressConfirmButtonProps) {
  const [progress, setProgress] = useState(0)
  const [pressing, setPressing] = useState(false)
  const [unlocked, setUnlocked] = useState(false)
  const startRef = useRef(0)
  const rafRef = useRef<number | null>(null)

  const stopAnimation = () => {
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current)
      rafRef.current = null
    }
  }

  useEffect(() => {
    return () => stopAnimation()
  }, [])

  const start = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (disabled || confirming || unlocked) return
    if (event.pointerType === 'mouse' && event.button !== 0) return
    event.currentTarget.setPointerCapture(event.pointerId)
    startRef.current = Date.now()
    setPressing(true)
    setProgress(0)
    stopAnimation()

    const tick = () => {
      const elapsed = Date.now() - startRef.current
      const percent = Math.min(100, (elapsed / duration) * 100)
      setProgress(percent)
      if (percent >= 100) {
        setPressing(false)
        setUnlocked(true)
        rafRef.current = null
        return
      }
      rafRef.current = requestAnimationFrame(tick)
    }
    rafRef.current = requestAnimationFrame(tick)
  }

  const cancel = () => {
    if (unlocked) return
    stopAnimation()
    setPressing(false)
    setProgress(0)
  }

  const handleClick = () => {
    if (unlocked && !confirming && !disabled) {
      onConfirm()
    }
  }

  const remainingSeconds = Math.max(
    1,
    Math.ceil((duration - (progress / 100) * duration) / 1000)
  )

  return (
    <button
      type="button"
      disabled={disabled || confirming}
      aria-label={unlocked ? '点击确认删除' : '按住 3 秒解锁删除'}
      title={unlocked ? '点击确认删除' : '按住 3 秒解锁，再点击确认'}
      onPointerDown={start}
      onPointerUp={cancel}
      onPointerCancel={cancel}
      onPointerLeave={cancel}
      onContextMenu={(event) => event.preventDefault()}
      onClick={handleClick}
      className={cn(
        'relative flex h-8 touch-none select-none items-center justify-center overflow-hidden rounded-lg px-3 text-caption-1-medium text-white transition-colors',
        unlocked ? 'bg-danger hover:bg-danger/90' : 'bg-danger/80 hover:bg-danger',
        (disabled || confirming) && 'cursor-not-allowed opacity-60'
      )}
    >
      <span
        aria-hidden="true"
        className="absolute inset-y-0 left-0 bg-black/20"
        style={{ width: `${progress}%` }}
      />
      <span className="relative flex items-center gap-1.5 whitespace-nowrap">
        {confirming ? (
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
        ) : unlocked ? (
          <Trash2 className="h-3.5 w-3.5" />
        ) : (
          <Lock className="h-3.5 w-3.5" />
        )}
        {confirming
          ? '删除中…'
          : unlocked
            ? '点击确认删除'
            : pressing
              ? `继续按住 ${remainingSeconds} 秒`
              : '按住 3 秒解锁删除'}
      </span>
    </button>
  )
}

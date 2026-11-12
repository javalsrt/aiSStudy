import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { Sparkles } from 'lucide-react'
import { AssistantPanel } from './assistant-panel'
import { cn } from '@/lib/utils'

const EDGE_GAP = 8

interface PanelBox {
  x: number
  y: number
  width: number
  height: number
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

/**
 * 管理端右下角 AI 助手入口。
 * 桌面端可按住面板头部拖动窗口，位置限制在可视区域内。
 * 访客模式（只读）不展示，避免误导为可以执行写操作。
 */
export function FloatingAssistant() {
  const [open, setOpen] = useState(false)
  // 拖动后固定为绝对坐标；为空时使用默认的右下角贴边布局
  const [box, setBox] = useState<PanelBox | null>(null)
  const [dragging, setDragging] = useState(false)
  const panelRef = useRef<HTMLDivElement>(null)
  const dragRef = useRef<{
    offsetX: number
    offsetY: number
    width: number
    height: number
  } | null>(null)
  const isGuest = typeof window !== 'undefined' && localStorage.getItem('guest-mode') === '1'

  // 窗口尺寸变化时把面板收回可视区域
  useEffect(() => {
    if (!open) return
    const handleResize = () => {
      setBox((prev) => {
        if (!prev) return prev
        const width = Math.min(prev.width, window.innerWidth - EDGE_GAP * 2)
        const height = Math.min(prev.height, window.innerHeight - EDGE_GAP * 2)
        return {
          width,
          height,
          x: clamp(prev.x, EDGE_GAP, Math.max(EDGE_GAP, window.innerWidth - width - EDGE_GAP)),
          y: clamp(prev.y, EDGE_GAP, Math.max(EDGE_GAP, window.innerHeight - height - EDGE_GAP)),
        }
      })
    }
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [open])

  const startDrag = (event: ReactPointerEvent<HTMLElement>) => {
    // 移动端是全屏面板，不启用拖动
    if (window.innerWidth < 640) return
    // 点在按钮等交互元素上时不触发拖动
    if ((event.target as HTMLElement).closest('button, a, input, textarea, select')) return
    const el = panelRef.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    dragRef.current = {
      offsetX: event.clientX - rect.left,
      offsetY: event.clientY - rect.top,
      width: rect.width,
      height: rect.height,
    }
    event.currentTarget.setPointerCapture(event.pointerId)
    setDragging(true)
  }

  const moveDrag = (event: ReactPointerEvent<HTMLElement>) => {
    const drag = dragRef.current
    if (!drag) return
    setBox({
      width: drag.width,
      height: drag.height,
      x: clamp(
        event.clientX - drag.offsetX,
        EDGE_GAP,
        Math.max(EDGE_GAP, window.innerWidth - drag.width - EDGE_GAP)
      ),
      y: clamp(
        event.clientY - drag.offsetY,
        EDGE_GAP,
        Math.max(EDGE_GAP, window.innerHeight - drag.height - EDGE_GAP)
      ),
    })
  }

  const endDrag = (event: ReactPointerEvent<HTMLElement>) => {
    if (!dragRef.current) return
    dragRef.current = null
    setDragging(false)
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  if (isGuest) return null

  return (
    <>
      {!open && (
        <button
          type="button"
          aria-label="打开智学 AI 助手"
          title="智学 AI 助手"
          onClick={() => setOpen(true)}
          className="group fixed bottom-5 right-5 z-[60] flex h-14 w-14 items-center justify-center rounded-full bg-neutral-950 text-white shadow-[0_10px_30px_rgba(15,23,42,0.28)] transition-all duration-200 hover:-translate-y-0.5 hover:bg-neutral-800 active:scale-95"
        >
          <Sparkles className="h-5 w-5 transition-transform duration-300 group-hover:rotate-12" />
          <span className="absolute -right-0.5 -top-0.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-primary-500 px-1 text-[9px] font-semibold text-white ring-2 ring-white">
            AI
          </span>
        </button>
      )}

      {open && (
        <div
          ref={panelRef}
          className={cn(
            'fixed z-[70]',
            !box && 'inset-0 sm:inset-auto sm:bottom-4 sm:right-4 sm:top-4 sm:w-[440px]'
          )}
          style={
            box ? { left: box.x, top: box.y, width: box.width, height: box.height } : undefined
          }
        >
          <div
            className={cn(
              'h-full animate-slide-in-right overflow-hidden bg-surface-primary shadow-[0_24px_80px_rgba(15,23,42,0.18)] sm:rounded-3xl sm:border sm:border-line-separator',
              dragging && 'shadow-[0_30px_90px_rgba(15,23,42,0.28)]'
            )}
          >
            <AssistantPanel
              onClose={() => setOpen(false)}
              onNavigate={() => {
                // 手机端是全屏面板，跳转页面时先收起，避免挡住目标页面
                if (window.innerWidth < 640) setOpen(false)
              }}
              dragging={dragging}
              dragHandlers={{
                onPointerDown: startDrag,
                onPointerMove: moveDrag,
                onPointerUp: endDrag,
                onPointerCancel: endDrag,
              }}
            />
          </div>
        </div>
      )}
    </>
  )
}

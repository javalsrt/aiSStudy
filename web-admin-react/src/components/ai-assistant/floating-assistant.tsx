import { useState } from 'react'
import { Sparkles } from 'lucide-react'
import { AssistantPanel } from './assistant-panel'

/**
 * 管理端右下角 AI 助手入口。
 * 访客模式（只读）不展示，避免误导为可以执行写操作。
 */
export function FloatingAssistant() {
  const [open, setOpen] = useState(false)
  const isGuest = typeof window !== 'undefined' && localStorage.getItem('guest-mode') === '1'
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
        <div className="fixed inset-0 z-[70] sm:inset-auto sm:bottom-4 sm:right-4 sm:top-4 sm:w-[440px]">
          <div className="h-full animate-slide-in-right overflow-hidden bg-surface-primary shadow-[0_24px_80px_rgba(15,23,42,0.18)] sm:rounded-3xl sm:border sm:border-line-separator">
            <AssistantPanel onClose={() => setOpen(false)} />
          </div>
        </div>
      )}
    </>
  )
}

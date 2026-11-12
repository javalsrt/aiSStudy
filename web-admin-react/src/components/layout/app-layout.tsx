import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import { Eye, Menu } from 'lucide-react'
import { FloatingAssistant } from '@/components/ai-assistant/floating-assistant'
import { Sidebar } from './sidebar'

export function AppLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  // 访客模式：只读提示条，写操作由 request 拦截器与后端双重拦截
  const isGuest = typeof window !== 'undefined' && localStorage.getItem('guest-mode') === '1'
  return (
    <div className="relative isolate min-h-screen bg-surface-primary">
      {/* 页面背景舞台：外层留白 + 大圆角画布，与侧栏、卡片形成同心圆角 */}
      <div
        aria-hidden="true"
        className="app-canvas pointer-events-none fixed inset-2 -z-10 rounded-[32px]"
      />
      <Sidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      {/* 移除了顶部 Header：移动端只保留一个浮动菜单按钮 */}
      <button
        type="button"
        aria-label="打开菜单"
        onClick={() => setSidebarOpen(true)}
        className="fixed left-3 top-3 z-30 flex h-9 w-9 items-center justify-center rounded-xl border border-line-separator bg-surface-primary text-ink-secondary shadow-card transition-colors hover:text-ink-primary lg:hidden"
      >
        <Menu className="h-4 w-4" />
      </button>
      <div className="flex min-h-screen min-w-0 flex-col lg:ml-[272px]">
        <main className="flex-1 px-4 pb-5 pt-16 sm:px-5 lg:px-3 lg:pb-3 lg:pt-3">
          <div className="max-w-[1440px] mx-auto">
            {isGuest && (
              <div className="mb-3 flex items-center gap-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-caption-1-regular text-amber-700">
                <Eye className="h-4 w-4 shrink-0" />
                访客模式：仅可浏览演示数据，所有增删改操作已禁用
              </div>
            )}
            <Outlet />
          </div>
        </main>
      </div>
      <FloatingAssistant />
    </div>
  )
}

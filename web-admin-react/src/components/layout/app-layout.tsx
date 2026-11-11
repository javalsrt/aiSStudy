import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import { Menu } from 'lucide-react'
import { Sidebar } from './sidebar'

export function AppLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  return (
    <div className="min-h-screen bg-surface-canvas">
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
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}

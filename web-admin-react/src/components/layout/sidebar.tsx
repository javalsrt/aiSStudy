import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import {
  adminMenuGroups,
  teacherMenuGroups,
  adminSearchEntries,
  teacherSearchEntries,
  type MenuItem,
  type SearchEntry,
} from '@/config/menu'
import { ChevronDown, Search, X } from 'lucide-react'
import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react'
import { SidebarFooter } from './sidebar-footer'

/**
 * 侧边栏（移植 BoardUI Sidebar）
 * 浮动面板：260px 宽、24px 圆角、1px 细边、侧边栏高程阴影、12px 内边距。
 * 结构：品牌/团队 → 快速搜索（Ctrl K）→ 带蓝色选中态的导航 → 用户卡。
 */
export function Sidebar({ open = false, onClose }: { open?: boolean; onClose?: () => void }) {
  const { user } = useAuthStore()
  const location = useLocation()
  const navigate = useNavigate()
  const menuGroups = user?.role === 'admin' ? adminMenuGroups : teacherMenuGroups
  const searchEntries = user?.role === 'admin' ? adminSearchEntries : teacherSearchEntries
  const [expandedKeys, setExpandedKeys] = useState<string[]>(['/admin/staff'])
  const [collapsedGroups, setCollapsedGroups] = useState<string[]>([])
  const [query, setQuery] = useState('')
  const [highlight, setHighlight] = useState(0)
  const searchRef = useRef<HTMLInputElement>(null)
  const itemRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const indicatorGroup = useRef<string | null>(null)
  const fadeTimer = useRef<number | null>(null)
  const [indicator, setIndicator] = useState({
    top: 0,
    height: 36,
    visible: false,
    slide: false,
  })

  // 桌面端 Ctrl/⌘ + K 聚焦侧边栏搜索
  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        if (window.matchMedia('(min-width: 1024px)').matches) {
          searchRef.current?.focus()
        }
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  const toggleExpand = (path: string) => {
    setExpandedKeys((prev) =>
      prev.includes(path) ? prev.filter((k) => k !== path) : [...prev, path]
    )
  }

  const toggleGroup = (title: string) => {
    setCollapsedGroups((prev) =>
      prev.includes(title) ? prev.filter((item) => item !== title) : [...prev, title]
    )
  }

  const isChildActive = (item: MenuItem) => {
    if (!item.children) return false
    return item.children.some((child) => location.pathname === child.path)
  }

  // 选中指示条：同组内滑动，跨组先淡出再淡入
  useLayoutEffect(() => {
    if (fadeTimer.current) {
      window.clearTimeout(fadeTimer.current)
      fadeTimer.current = null
    }
    const entry = menuGroups
      .flatMap((group) =>
        group.items
          .filter((item) => !item.children)
          .map((item) => ({ item, group: group.title }))
      )
      .find(
        ({ item }) =>
          location.pathname === item.path || location.pathname.startsWith(`${item.path}/`)
      )
    const el = entry ? itemRefs.current[entry.item.path] : null
    if (!entry || !el) {
      indicatorGroup.current = null
      setIndicator((prev) => (prev.visible ? { ...prev, visible: false } : prev))
      return
    }
    const next = { top: el.offsetTop, height: el.offsetHeight }
    const groupChanged =
      indicatorGroup.current !== null && indicatorGroup.current !== entry.group
    indicatorGroup.current = entry.group
    if (groupChanged) {
      setIndicator((prev) => ({ ...prev, visible: false }))
      fadeTimer.current = window.setTimeout(() => {
        setIndicator({ ...next, visible: true, slide: false })
        fadeTimer.current = null
      }, 140)
    } else {
      setIndicator((prev) => ({ ...next, visible: true, slide: prev.visible }))
    }
  }, [location.pathname, collapsedGroups, expandedKeys, menuGroups, query])

  useEffect(() => {
    return () => {
      if (fadeTimer.current) window.clearTimeout(fadeTimer.current)
    }
  }, [])

  // 搜索覆盖系统全部可直达功能，而不是只过滤当前菜单
  const keyword = query.trim().toLowerCase()
  const results = keyword
    ? searchEntries.filter((entry) =>
        `${entry.title} ${entry.group} ${entry.keywords ?? ''}`.toLowerCase().includes(keyword)
      )
    : []

  useEffect(() => {
    setHighlight(0)
  }, [query])

  const handleSearchSelect = (entry: SearchEntry) => {
    navigate(entry.path)
    setQuery('')
    onClose?.()
  }

  const handleSearchKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (!keyword) return
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setHighlight((index) => Math.min(index + 1, results.length - 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setHighlight((index) => Math.max(index - 1, 0))
    } else if (event.key === 'Enter') {
      event.preventDefault()
      const target = results[highlight] ?? results[0]
      if (target) handleSearchSelect(target)
    } else if (event.key === 'Escape') {
      setQuery('')
    }
  }

  const handleNavigate = () => {
    setQuery('')
    onClose?.()
  }

  const itemBase =
    'relative z-10 flex h-9 items-center gap-2.5 rounded-xl px-2.5 text-body-2-medium transition-colors duration-150'

  return (
    <>
      {/* 移动端抽屉遮罩 */}
      {open && (
        <div className="fixed inset-0 z-40 bg-black/40 lg:hidden" onClick={onClose} />
      )}

      <aside
        className={cn(
          'fixed left-3 top-3 z-50 flex h-[calc(100vh-24px)] w-[260px] shrink-0 flex-col gap-3 overflow-hidden rounded-3xl border border-line-separator bg-surface-primary p-3 shadow-sidebar transition-transform duration-200 lg:z-40 lg:translate-x-0',
          open ? 'translate-x-0' : '-translate-x-[calc(100%+16px)]'
        )}
      >
        {/* 品牌 / 团队 */}
        <div className="flex items-center gap-2.5 px-1 py-1">
          <img src="/logo.svg" alt="智学职达" className="h-8 w-8 shrink-0" />
          <div className="min-w-0 flex-1">
            <div className="truncate text-body-2-medium text-ink-primary">智学职达</div>
            <div className="truncate text-caption-2-medium text-ink-tertiary">AI Learning</div>
          </div>
          <button
            type="button"
            aria-label="关闭菜单"
            onClick={onClose}
            className="flex h-7 w-7 items-center justify-center rounded-full text-ink-tertiary transition-colors hover:bg-neutral-100 hover:text-ink-primary lg:hidden"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* 快速搜索 */}
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-tertiary" />
          <input
            ref={searchRef}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={handleSearchKeyDown}
            placeholder="快速搜索"
            aria-label="快速搜索菜单"
            aria-expanded={Boolean(keyword)}
            autoComplete="off"
            className="h-9 w-full rounded-full bg-neutral-100 pl-9 pr-14 text-body-2-regular text-ink-primary outline-hidden transition-shadow placeholder:text-ink-tertiary focus:bg-surface-primary focus:shadow-[0_0_0_1px_#3b82f6,0_0_0_4px_rgba(59,130,246,0.14)]"
          />
          {query ? (
            <button
              type="button"
              aria-label="清空搜索"
              onClick={() => setQuery('')}
              className="absolute right-2 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-full text-ink-tertiary transition-colors hover:bg-neutral-200 hover:text-ink-primary"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          ) : (
            <kbd className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 rounded border border-line-separator bg-surface-primary px-1.5 py-0.5 text-[10px] font-medium leading-4 text-ink-tertiary">
              Ctrl K
            </kbd>
          )}
        </div>

        {/* 搜索 / 导航 */}
        <div className="scrollbar-none flex-1 overflow-y-auto">
          {keyword ? (
            <div className="space-y-0.5">
              <div className="px-2 py-1 text-caption-2-medium text-ink-tertiary">功能</div>
              {results.length > 0 ? (
                results.map((entry, index) => {
                  const Icon = entry.icon
                  const [pathname, search] = entry.path.split('?')
                  const isCurrent =
                    location.pathname === pathname &&
                    (!search || location.search === `?${search}`)
                  return (
                    <button
                      key={entry.path}
                      type="button"
                      onClick={() => handleSearchSelect(entry)}
                      onMouseEnter={() => setHighlight(index)}
                      className={cn(
                        'flex h-9 w-full items-center gap-2.5 rounded-xl px-2.5 text-left text-body-2-medium transition-colors duration-150',
                        isCurrent
                          ? 'bg-blue-50 text-blue-700'
                          : index === highlight
                            ? 'bg-neutral-100 text-ink-primary'
                            : 'text-ink-secondary hover:bg-neutral-100 hover:text-ink-primary'
                      )}
                    >
                      <Icon className="h-4 w-4 shrink-0" />
                      <span className="min-w-0 flex-1 truncate">{entry.title}</span>
                      <span className="shrink-0 text-caption-2-medium text-ink-tertiary">
                        {entry.group}
                      </span>
                    </button>
                  )
                })
              ) : (
                <div className="px-3 py-8 text-center text-caption-1-regular text-ink-tertiary">
                  未找到相关功能
                  <div className="mt-1 text-[11px]">试试搜索「课程」「学生」「统计」</div>
                </div>
              )}
            </div>
          ) : (
            <nav className="relative space-y-1">
              {/* 选中指示条：同组切换滑动，跨组淡出→跳位→淡入 */}
              <span
                aria-hidden="true"
                className="pointer-events-none absolute inset-x-0 top-0 z-0 rounded-xl bg-blue-500"
                style={{
                  height: indicator.height,
                  transform: `translateY(${indicator.top}px)`,
                  opacity: indicator.visible ? 1 : 0,
                  transition: indicator.slide
                    ? 'transform 240ms cubic-bezier(0.22,1,0.36,1), opacity 160ms ease'
                    : 'opacity 160ms ease',
                }}
              />
              {menuGroups.map((group) => {
                if (group.items.length === 0) return null
                const collapsed = collapsedGroups.includes(group.title)
                return (
                  <div key={group.title}>
                    {/* 分类标题：只做轻量归类，不加分隔线 */}
                    <button
                      type="button"
                      onClick={() => toggleGroup(group.title)}
                      className="flex h-7 w-full items-center justify-between rounded-md px-2.5 text-caption-1-medium text-ink-tertiary transition-colors hover:text-ink-secondary"
                    >
                      <span>{group.title}</span>
                      <ChevronDown
                        className={cn(
                          'h-3.5 w-3.5 transition-transform duration-200',
                          collapsed && '-rotate-90'
                        )}
                      />
                    </button>
                    {!collapsed && (
                      <div className="space-y-0.5 pb-1">
                        {group.items.map((item) => (
                          <div
                            key={item.path}
                            ref={(el) => {
                              itemRefs.current[item.path] = el
                            }}
                          >
                            {item.children ? (
                              <>
                                <button
                                  onClick={() => toggleExpand(item.path)}
                                  className={cn(
                                    itemBase,
                                    'w-full',
                                    isChildActive(item)
                                      ? 'bg-blue-50 text-blue-700'
                                      : 'text-ink-secondary hover:bg-neutral-100 hover:text-ink-primary'
                                  )}
                                >
                                  <item.icon className="h-[18px] w-[18px] shrink-0" />
                                  <span className="flex-1 truncate text-left">{item.title}</span>
                                  <ChevronDown
                                    className={cn(
                                      'h-4 w-4 shrink-0 transition-transform duration-200',
                                      expandedKeys.includes(item.path) && 'rotate-180'
                                    )}
                                  />
                                </button>
                                {expandedKeys.includes(item.path) && (
                                  <div className="ml-5 mt-0.5 space-y-0.5 pl-2.5">
                                    {item.children.map((child) => (
                                      <NavLink
                                        key={child.path}
                                        to={child.path}
                                        onClick={handleNavigate}
                                        className={({ isActive }) =>
                                          cn(
                                            'flex h-8 items-center gap-2 rounded-lg px-2.5 text-caption-1-medium transition-colors duration-150',
                                            isActive
                                              ? 'bg-blue-100 text-blue-700'
                                              : 'text-ink-secondary hover:bg-neutral-100 hover:text-ink-primary'
                                          )
                                        }
                                      >
                                        <child.icon className="h-4 w-4 shrink-0" />
                                        <span className="truncate">{child.title}</span>
                                      </NavLink>
                                    ))}
                                  </div>
                                )}
                              </>
                            ) : (
                              <NavLink
                                to={item.path}
                                onClick={handleNavigate}
                                className={({ isActive }) =>
                                  cn(
                                    itemBase,
                                    isActive
                                      ? indicator.visible
                                        ? 'text-white hover:text-white'
                                        : 'text-ink-primary'
                                      : 'text-ink-secondary hover:bg-neutral-100 hover:text-ink-primary'
                                  )
                                }
                              >
                                <item.icon className="h-[18px] w-[18px] shrink-0" />
                                <span className="truncate">{item.title}</span>
                              </NavLink>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )
              })}
            </nav>
          )}
        </div>

        {/* 用户与消息通知（原顶部 Header 迁移至此） */}
        <SidebarFooter />
      </aside>
    </>
  )
}

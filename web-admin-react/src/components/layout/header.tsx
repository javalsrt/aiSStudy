import { Bell, Search, LogOut, User, Settings, MessageCircle, Bot, X } from 'lucide-react'
import { useAuthStore } from '@/store/auth'
import { useEffect, useRef, useState } from 'react'
import * as DropdownMenuPrimitive from '@radix-ui/react-dropdown-menu'
import { useNavigate, useLocation } from 'react-router-dom'
import { getTeacherUnreadNotifications, markTeacherRead, TeacherChatNotification } from '@/api/chat'
import { cn } from '@/lib/utils'

export function Header() {
  const { user, logout } = useAuthStore()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchVisible, setSearchVisible] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [notiOpen, setNotiOpen] = useState(false)
  const [notifications, setNotifications] = useState<TeacherChatNotification[]>([])
  const [notiLoading, setNotiLoading] = useState(false)
  const notiRef = useRef<HTMLDivElement>(null)

  const isTeacher = user?.role === 'teacher'
  const notiCount = notifications.length

  // 教师当前正在查看的课程（在课程聊天页时），该课程消息不提示
  const currentChatCourse =
    location.pathname === '/teacher-chat'
      ? new URLSearchParams(location.search).get('course')
      : null

  useEffect(() => {
    if (!isTeacher) return
    const load = async () => {
      setNotiLoading(true)
      try {
        const data = await getTeacherUnreadNotifications()
        // 过滤掉教师自己发的消息（后端已排除）以及当前正在查看的课程
        const filtered = (data || []).filter(
          (n) => n.senderRole !== 'teacher' && n.courseName !== currentChatCourse
        )
        setNotifications(filtered)
      } catch (e) {
        console.error('加载教师通知失败', e)
      } finally {
        setNotiLoading(false)
      }
    }
    load()
    // 每 30 秒轮询一次
    const timer = setInterval(load, 30000)
    return () => clearInterval(timer)
  }, [isTeacher, currentChatCourse])

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (notiRef.current && !notiRef.current.contains(event.target as Node)) {
        setNotiOpen(false)
      }
    }
    if (notiOpen) {
      document.addEventListener('mousedown', handleClickOutside)
    }
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [notiOpen])

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const handleNotiClick = (courseName: string, courseId?: number) => {
    setNotiOpen(false)
    // 立即从本地通知列表移除该课程相关提示
    setNotifications((prev) => prev.filter((n) => n.courseName !== courseName))
    // 同步标记后端已读：优先用 courseId（同名课程隔离），无ID时退回课程名
    markTeacherRead(courseId ?? courseName).catch(() => {})
    navigate(`/teacher-chat?course=${encodeURIComponent(courseName)}`)
  }

  return (
    <header className="h-20 bg-white/80 backdrop-blur-md border-b border-neutral-200/60 sticky top-0 z-30 px-8 flex items-center justify-between">
      {/* Breadcrumb / Search */}
      <div className="flex items-center gap-4">
        {searchVisible ? (
          <div className="relative">
            <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
            <input
              type="text"
              placeholder="搜索..."
              className="w-80 h-11 pl-11 pr-4 rounded-full bg-neutral-100 border-0 text-sm text-neutral-900 placeholder:text-neutral-400 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:bg-white transition-all"
              autoFocus
            />
          </div>
        ) : (
          <button
            onClick={() => setSearchVisible(true)}
            className="w-10 h-10 rounded-full hover:bg-neutral-100 flex items-center justify-center text-neutral-500 hover:text-neutral-900 transition-colors"
          >
            <Search className="w-5 h-5" />
          </button>
        )}
      </div>

      {/* Right actions */}
      <div className="flex items-center gap-2">
        {/* Notifications（仅教师显示消息通知） */}
        {isTeacher && (
          <div className="relative" ref={notiRef}>
            <button
              onClick={() => setNotiOpen((v) => !v)}
              className="relative w-10 h-10 rounded-full hover:bg-neutral-100 flex items-center justify-center text-neutral-500 hover:text-neutral-900 transition-colors"
            >
              <Bell className="w-5 h-5" />
              {notiCount > 0 && (
                <span className="absolute top-2 right-2 min-w-[18px] h-[18px] px-1 flex items-center justify-center bg-danger text-white text-[10px] font-bold rounded-full border-2 border-white">
                  {notiCount > 99 ? '99+' : notiCount}
                </span>
              )}
            </button>

            {notiOpen && (
              <div className="absolute right-0 top-12 w-[360px] bg-white rounded-2xl shadow-xl border border-neutral-200 overflow-hidden z-50">
                <div className="flex items-center justify-between px-4 py-3 border-b border-neutral-100">
                  <span className="font-semibold text-sm text-neutral-800">消息通知</span>
                  <button
                    onClick={() => setNotiOpen(false)}
                    className="w-6 h-6 rounded-full hover:bg-neutral-100 flex items-center justify-center text-neutral-400"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>
                <div className="max-h-[420px] overflow-y-auto">
                  {notiLoading && notifications.length === 0 && (
                    <div className="py-8 text-center text-sm text-neutral-400">加载中...</div>
                  )}
                  {!notiLoading && notifications.length === 0 && (
                    <div className="py-10 text-center">
                      <Bell className="w-10 h-10 mx-auto text-neutral-200 mb-2" />
                      <div className="text-sm text-neutral-400">暂无新消息</div>
                    </div>
                  )}
                  {notifications.map((n) => (
                    <button
                      key={n.id}
                      onClick={() => handleNotiClick(n.courseName, n.courseId)}
                      className="w-full text-left px-4 py-3 hover:bg-neutral-50 transition-colors border-b border-neutral-50 last:border-0"
                    >
                      <div className="flex items-start gap-3">
                        <div
                          className={cn(
                            'w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0 mt-0.5',
                            n.senderRole === 'ai'
                              ? 'bg-purple-100 text-purple-600'
                              : 'bg-primary-100 text-primary-600'
                          )}
                        >
                          {n.senderRole === 'ai' ? (
                            <Bot className="w-4 h-4" />
                          ) : (
                            <MessageCircle className="w-4 h-4" />
                          )}
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-sm font-medium text-neutral-800 truncate">
                              {n.senderName || (n.senderRole === 'ai' ? 'AI 助教' : '学生')}
                            </span>
                            <span className="text-xs text-neutral-400 whitespace-nowrap">
                              {n.createdAt ? new Date(n.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : ''}
                            </span>
                          </div>
                          <div className="text-xs text-neutral-500 mt-0.5 line-clamp-2">
                            {n.preview || n.content}
                          </div>
                          <div className="text-xs text-primary-600 mt-1 truncate">来自：{n.courseName}</div>
                        </div>
                      </div>
                    </button>
                  ))}
                </div>
                {notifications.length > 0 && (
                  <div className="px-4 py-2 border-t border-neutral-100 bg-neutral-50 text-center">
                    <button
                      onClick={() => {
                        setNotiOpen(false)
                        navigate('/teacher-chat')
                      }}
                      className="text-xs text-primary-600 hover:text-primary-700 font-medium"
                    >
                      查看全部聊天
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {!isTeacher && (
          <button className="relative w-10 h-10 rounded-full hover:bg-neutral-100 flex items-center justify-center text-neutral-500 hover:text-neutral-900 transition-colors">
            <Bell className="w-5 h-5" />
          </button>
        )}

        {/* User menu */}
        <DropdownMenuPrimitive.Root open={menuOpen} onOpenChange={setMenuOpen}>
          <DropdownMenuPrimitive.Trigger asChild>
            <button
              type="button"
              aria-label="用户菜单"
              aria-haspopup="menu"
              data-testid="user-avatar-menu"
              onClick={(e) => {
                e.stopPropagation()
                setMenuOpen((prev) => !prev)
              }}
              className="flex items-center gap-3 pl-2 pr-3 py-1.5 rounded-full hover:bg-neutral-100 transition-colors outline-none"
            >
              <div className="w-8 h-8 rounded-full bg-gradient-to-br from-primary-400 to-primary-600 flex items-center justify-center text-white text-sm font-medium">
                {user?.realName?.charAt(0) || user?.username?.charAt(0) || 'U'}
              </div>
              <span className="text-sm font-medium text-neutral-700 hidden md:block">
                {user?.realName || user?.username}
              </span>
            </button>
          </DropdownMenuPrimitive.Trigger>
          <DropdownMenuPrimitive.Portal>
            <DropdownMenuPrimitive.Content
              align="end"
              sideOffset={8}
              className="z-50 min-w-[10rem] overflow-hidden rounded-xl border border-neutral-200 bg-white p-1 text-neutral-900 shadow-md"
            >
              <DropdownMenuPrimitive.Label className="px-3 py-2 text-sm font-semibold">
                <div className="flex flex-col">
                  <span className="font-medium">{user?.realName || user?.username}</span>
                  <span className="text-xs text-neutral-400">
                    {user?.role === 'admin' ? '管理员' : user?.role === 'teacher' ? '教师' : '学生'}
                  </span>
                </div>
              </DropdownMenuPrimitive.Label>
              <DropdownMenuPrimitive.Separator className="-mx-1 my-1 h-px bg-neutral-200" />
              <DropdownMenuPrimitive.Item className="relative flex cursor-default select-none items-center rounded-lg px-3 py-2 text-sm outline-none transition-colors focus:bg-neutral-100 focus:text-neutral-900">
                <User className="w-4 h-4 mr-2" />
                个人信息
              </DropdownMenuPrimitive.Item>
              <DropdownMenuPrimitive.Item className="relative flex cursor-default select-none items-center rounded-lg px-3 py-2 text-sm outline-none transition-colors focus:bg-neutral-100 focus:text-neutral-900">
                <Settings className="w-4 h-4 mr-2" />
                设置
              </DropdownMenuPrimitive.Item>
              <DropdownMenuPrimitive.Separator className="-mx-1 my-1 h-px bg-neutral-200" />
              <DropdownMenuPrimitive.Item
                onSelect={handleLogout}
                className="relative flex cursor-default select-none items-center rounded-lg px-3 py-2 text-sm outline-none transition-colors focus:bg-neutral-100 focus:text-neutral-900 text-danger"
              >
                <LogOut className="w-4 h-4 mr-2" />
                退出登录
              </DropdownMenuPrimitive.Item>
            </DropdownMenuPrimitive.Content>
          </DropdownMenuPrimitive.Portal>
        </DropdownMenuPrimitive.Root>
      </div>
    </header>
  )
}

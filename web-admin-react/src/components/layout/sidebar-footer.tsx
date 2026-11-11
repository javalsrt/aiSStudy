import { Bell, Bot, ChevronUp, LogOut, MessageCircle, Settings, User, X } from 'lucide-react'
import * as DropdownMenuPrimitive from '@radix-ui/react-dropdown-menu'
import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/store/auth'
import {
  getTeacherUnreadNotifications,
  markTeacherRead,
  TeacherChatNotification,
} from '@/api/chat'
import { cn } from '@/lib/utils'
import { ThemeToggle } from './theme-toggle'

/**
 * 侧边栏底部：用户菜单 + 消息通知。
 * 原顶部 Header 的头像菜单和铃铛整体迁移到这里，菜单向上弹出。
 */
export function SidebarFooter() {
  const { user, logout } = useAuthStore()
  const navigate = useNavigate()
  const location = useLocation()
  const [notiOpen, setNotiOpen] = useState(false)
  const [notifications, setNotifications] = useState<TeacherChatNotification[]>([])
  const [notiLoading, setNotiLoading] = useState(false)

  const isTeacher = user?.role === 'teacher'
  const notiCount = notifications.length
  const displayName = user?.realName || user?.username || '用户'
  const roleLabel = user?.role === 'admin' ? '管理员' : user?.role === 'teacher' ? '教师' : '学生'

  // 教师当前正在查看的课程（在课程聊天页时），该课程消息不提示
  const currentChatCourse =
    location.pathname === '/teacher-chat'
      ? new URLSearchParams(location.search).get('course')
      : null
  const currentChatCourseId =
    location.pathname === '/teacher-chat'
      ? new URLSearchParams(location.search).get('courseId')
      : null

  const isInActiveRoom = (n: TeacherChatNotification) => {
    const activeId =
      currentChatCourseId ||
      (window as unknown as { __activeChatCourseId?: number | null }).__activeChatCourseId
    if (activeId) return String(n.courseId ?? '') === String(activeId)
    return !!currentChatCourse && n.courseName === currentChatCourse
  }

  useEffect(() => {
    if (!isTeacher) {
      setNotifications([])
      return
    }
    const load = async () => {
      setNotiLoading(true)
      try {
        const data = await getTeacherUnreadNotifications()
        const filtered = (data || []).filter(
          (n) => n.senderRole !== 'teacher' && !isInActiveRoom(n)
        )
        setNotifications(filtered)
      } catch (error) {
        console.error('加载教师通知失败', error)
      } finally {
        setNotiLoading(false)
      }
    }
    load()
    const timer = setInterval(load, 30000)
    return () => clearInterval(timer)
  }, [isTeacher, currentChatCourse])

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const handleNotiClick = (courseName: string, courseId?: number) => {
    setNotiOpen(false)
    setNotifications((prev) =>
      prev.filter((n) => (courseId ? n.courseId !== courseId : n.courseName !== courseName))
    )
    const key = courseId ?? courseName
    markTeacherRead(key)
      .catch(() => {})
      .finally(() => {
        getTeacherUnreadNotifications()
          .then((data) => {
            setNotifications(
              (data || []).filter((n) => n.senderRole !== 'teacher' && !isInActiveRoom(n))
            )
          })
          .catch(() => {})
      })
    const qs = courseId
      ? `course=${encodeURIComponent(courseName)}&courseId=${courseId}`
      : `course=${encodeURIComponent(courseName)}`
    navigate(`/teacher-chat?${qs}`)
  }

  return (
    <div className="mt-auto space-y-2">
      {/* 明暗切换 */}
      <div className="flex items-center justify-between px-1">
        <span className="text-caption-2-medium text-ink-tertiary">外观</span>
        <ThemeToggle />
      </div>

      <div className="flex items-center gap-2">
      {/* 用户菜单 */}
      <DropdownMenuPrimitive.Root>
        <DropdownMenuPrimitive.Trigger asChild>
          <button
            type="button"
            aria-label="用户菜单"
            aria-haspopup="menu"
            data-testid="user-avatar-menu"
            className="flex h-11 min-w-0 flex-1 items-center gap-2.5 rounded-xl bg-neutral-100 px-2 text-left transition-colors hover:bg-neutral-200/70"
          >
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-neutral-500 to-neutral-700 text-caption-1-medium text-white">
              {displayName.charAt(0)}
            </div>
            <div className="min-w-0 flex-1">
              <div className="truncate text-body-2-medium text-ink-primary">{displayName}</div>
              <div className="truncate text-caption-2-medium text-ink-tertiary">{roleLabel}</div>
            </div>
            <ChevronUp className="h-3.5 w-3.5 shrink-0 text-ink-tertiary" />
          </button>
        </DropdownMenuPrimitive.Trigger>
        <DropdownMenuPrimitive.Portal>
          <DropdownMenuPrimitive.Content
            side="top"
            align="start"
            sideOffset={8}
            className="z-[60] min-w-[220px] overflow-hidden rounded-2xl border border-line-separator bg-surface-primary p-1 text-ink-primary shadow-dropdown"
          >
            <DropdownMenuPrimitive.Label className="px-3 py-2">
              <div className="flex flex-col">
                <span className="text-body-2-medium text-ink-primary">{displayName}</span>
                <span className="text-caption-2-medium text-ink-tertiary">{roleLabel}</span>
              </div>
            </DropdownMenuPrimitive.Label>
            <DropdownMenuPrimitive.Separator className="-mx-1 my-1 h-px bg-line-separator" />
            <DropdownMenuPrimitive.Item className="relative flex cursor-default select-none items-center rounded-xl px-3 py-2 text-body-2-regular outline-hidden transition-colors focus:bg-neutral-100 focus:text-ink-primary">
              <User className="mr-2 h-4 w-4" />
              个人信息
            </DropdownMenuPrimitive.Item>
            <DropdownMenuPrimitive.Item className="relative flex cursor-default select-none items-center rounded-xl px-3 py-2 text-body-2-regular outline-hidden transition-colors focus:bg-neutral-100 focus:text-ink-primary">
              <Settings className="mr-2 h-4 w-4" />
              设置
            </DropdownMenuPrimitive.Item>
            <DropdownMenuPrimitive.Separator className="-mx-1 my-1 h-px bg-line-separator" />
            <DropdownMenuPrimitive.Item
              onSelect={handleLogout}
              className="relative flex cursor-default select-none items-center rounded-xl px-3 py-2 text-body-2-regular text-danger outline-hidden transition-colors focus:bg-rose-50 focus:text-danger"
            >
              <LogOut className="mr-2 h-4 w-4" />
              退出登录
            </DropdownMenuPrimitive.Item>
          </DropdownMenuPrimitive.Content>
        </DropdownMenuPrimitive.Portal>
      </DropdownMenuPrimitive.Root>

      {/* 消息通知 */}
      <DropdownMenuPrimitive.Root open={notiOpen} onOpenChange={setNotiOpen}>
        <DropdownMenuPrimitive.Trigger asChild>
          <button
            type="button"
            aria-label="消息通知"
            className="relative flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-neutral-100 text-ink-secondary transition-colors hover:bg-neutral-200/70 hover:text-ink-primary"
          >
            <Bell className="h-[18px] w-[18px]" />
            {notiCount > 0 && (
              <span className="absolute right-1.5 top-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[9px] font-bold leading-none text-white">
                {notiCount > 99 ? '99+' : notiCount}
              </span>
            )}
          </button>
        </DropdownMenuPrimitive.Trigger>
        <DropdownMenuPrimitive.Portal>
          <DropdownMenuPrimitive.Content
            side="top"
            align="start"
            sideOffset={8}
            collisionPadding={12}
            className="z-[60] w-[340px] overflow-hidden rounded-2xl border border-line-separator bg-surface-primary p-0 shadow-dropdown"
          >
            <div className="flex items-center justify-between border-b border-line-separator px-4 py-3">
              <span className="text-body-2-medium text-ink-primary">消息通知</span>
              <button
                type="button"
                aria-label="关闭通知"
                onClick={() => setNotiOpen(false)}
                className="flex h-6 w-6 items-center justify-center rounded-full text-ink-tertiary transition-colors hover:bg-neutral-100 hover:text-ink-primary"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="max-h-[420px] overflow-y-auto">
              {notiLoading && notifications.length === 0 && (
                <div className="py-8 text-center text-body-2-regular text-ink-tertiary">
                  加载中...
                </div>
              )}
              {!notiLoading && notifications.length === 0 && (
                <div className="py-10 text-center">
                  <Bell className="mx-auto mb-2 h-9 w-9 text-neutral-200" />
                  <div className="text-body-2-regular text-ink-tertiary">暂无新消息</div>
                </div>
              )}
              {notifications.map((n) => (
                <DropdownMenuPrimitive.Item
                  key={n.id}
                  onSelect={() => handleNotiClick(n.courseName, n.courseId)}
                  className="w-full cursor-default border-b border-line-separator px-4 py-3 text-left outline-hidden transition-colors last:border-0 focus:bg-neutral-50"
                >
                  <div className="flex items-start gap-3">
                    <div
                      className={cn(
                        'mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full',
                        n.senderRole === 'ai'
                          ? 'bg-emerald-50 text-emerald-600'
                          : 'bg-neutral-100 text-ink-secondary'
                      )}
                    >
                      {n.senderRole === 'ai' ? (
                        <Bot className="h-4 w-4" />
                      ) : (
                        <MessageCircle className="h-4 w-4" />
                      )}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="truncate text-body-2-medium text-ink-primary">
                          {n.senderName || (n.senderRole === 'ai' ? 'AI 助教' : '学生')}
                        </span>
                        <span className="whitespace-nowrap text-caption-2-regular text-ink-tertiary">
                          {n.createdAt
                            ? new Date(n.createdAt).toLocaleTimeString([], {
                                hour: '2-digit',
                                minute: '2-digit',
                              })
                            : ''}
                        </span>
                      </div>
                      <div className="mt-0.5 line-clamp-2 text-caption-1-regular text-ink-secondary">
                        {n.preview || n.content}
                      </div>
                      <div className="mt-1 truncate text-caption-2-regular text-ink-tertiary">
                        来自：{n.courseName}
                      </div>
                    </div>
                  </div>
                </DropdownMenuPrimitive.Item>
              ))}
            </div>
            {notifications.length > 0 && (
              <div className="border-t border-line-separator px-4 py-2">
                <DropdownMenuPrimitive.Item
                  onSelect={() => {
                    setNotiOpen(false)
                    navigate('/teacher-chat')
                  }}
                  className="cursor-default rounded-lg px-2 py-1.5 text-center text-caption-1-medium text-blue-600 outline-hidden transition-colors focus:bg-neutral-50"
                >
                  查看全部聊天
                </DropdownMenuPrimitive.Item>
              </div>
            )}
          </DropdownMenuPrimitive.Content>
        </DropdownMenuPrimitive.Portal>
      </DropdownMenuPrimitive.Root>
      </div>
    </div>
  )
}

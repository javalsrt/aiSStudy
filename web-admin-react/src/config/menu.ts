import {
  LayoutDashboard,
  BookOpen,
  BarChart3,
  Users,
  GraduationCap,
  CalendarClock,
  Upload,
  ListTree,
  CalendarDays,
  ClipboardList,
  MessageSquare,
  Clock,
  BookMarked,
  ClipboardCheck,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface MenuItem {
  path: string
  title: string
  icon: LucideIcon
  roles?: string[]
  children?: MenuItem[]
}

export const teacherMenus: MenuItem[] = [
  { path: '/dashboard', title: '数据总览', icon: LayoutDashboard },
  { path: '/courses', title: '课程管理', icon: BookOpen },
  { path: '/course-chapters', title: '章节管理', icon: ListTree },
  { path: '/stats', title: '学习统计', icon: BarChart3 },
  { path: '/exam-homework', title: '考试作业', icon: ClipboardList },
  { path: '/teacher-chat', title: '课程聊天', icon: MessageSquare },
]

export const adminMenus: MenuItem[] = [
  { path: '/dashboard', title: '数据总览', icon: LayoutDashboard },
  { path: '/admin/staff', title: '人员管理', icon: Users },
  { path: '/admin/course-import', title: '课程导入', icon: Upload },
  { path: '/admin/semester', title: '学期管理', icon: CalendarClock },
  { path: '/admin/schedule', title: '排课管理', icon: CalendarDays },
  { path: '/admin/bell', title: '作息设置', icon: Clock },
  { path: '/admin/school-info', title: '校园信息', icon: BookMarked },
  { path: '/admin/leave', title: '请假审批', icon: ClipboardCheck },

  { path: '/courses', title: '课程管理', icon: BookOpen },
  { path: '/course-chapters', title: '章节管理', icon: ListTree },
  { path: '/stats', title: '学习统计', icon: BarChart3 },
  { path: '/exam-homework', title: '考试作业', icon: ClipboardList },
]

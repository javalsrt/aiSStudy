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
  UserCheck,
  UserCog,
  ShieldCheck,
  History,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

export interface MenuItem {
  path: string
  title: string
  icon: LucideIcon
  roles?: string[]
  children?: MenuItem[]
}

/** 侧边栏功能分组：只做简单分类，不添加分隔线 */
export interface MenuGroup {
  title: string
  items: MenuItem[]
}

/** 全局功能搜索条目：覆盖侧边栏之外、可通过路由直达的功能 */
export interface SearchEntry {
  path: string
  title: string
  group: string
  icon: LucideIcon
  keywords?: string
}

export const teacherMenus: MenuItem[] = [
  { path: '/dashboard', title: '数据总览', icon: LayoutDashboard },
  { path: '/teacher/class', title: '班级管理', icon: Users },
  { path: '/teacher/leave', title: '请假审批', icon: ClipboardCheck },
  { path: '/courses', title: '课程管理', icon: BookOpen },
  { path: '/course-chapters', title: '章节管理', icon: ListTree },
  { path: '/stats', title: '学习统计', icon: BarChart3 },
  { path: '/exam-homework', title: '考试作业', icon: ClipboardList },
  { path: '/teacher-chat', title: '课程聊天', icon: MessageSquare },
  { path: '/ai-records', title: 'AI 操作记录', icon: History },
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
  { path: '/ai-records', title: 'AI 操作记录', icon: History },
]

/** 侧边栏简单分组：只做功能归类，不添加分隔线 */
export const adminMenuGroups: MenuGroup[] = [
  { title: '总览', items: adminMenus.filter((item) => item.path === '/dashboard') },
  {
    title: '教学',
    items: adminMenus.filter((item) =>
      ['/courses', '/course-chapters', '/stats', '/exam-homework'].includes(item.path)
    ),
  },
  {
    title: '教务',
    items: adminMenus.filter((item) =>
      ['/admin/course-import', '/admin/semester', '/admin/schedule', '/admin/bell'].includes(item.path)
    ),
  },
  {
    title: '人员',
    items: adminMenus.filter((item) =>
      ['/admin/staff', '/admin/leave'].includes(item.path)
    ),
  },
  {
    title: '校园',
    items: adminMenus.filter((item) => item.path === '/admin/school-info'),
  },
  {
    title: 'AI',
    items: adminMenus.filter((item) => item.path === '/ai-records'),
  },
]

export const teacherMenuGroups: MenuGroup[] = [
  { title: '总览', items: teacherMenus.filter((item) => item.path === '/dashboard') },
  {
    title: '教学',
    items: teacherMenus.filter((item) =>
      ['/teacher/class', '/courses', '/course-chapters', '/stats', '/exam-homework'].includes(item.path)
    ),
  },
  {
    title: '沟通',
    items: teacherMenus.filter((item) => item.path === '/teacher-chat'),
  },
  { title: '事务', items: teacherMenus.filter((item) => item.path === '/teacher/leave') },
  { title: 'AI', items: teacherMenus.filter((item) => item.path === '/ai-records') },
]

export const adminSearchEntries: SearchEntry[] = [
  { path: '/dashboard', title: '数据总览', group: '总览', icon: LayoutDashboard, keywords: '首页 概览 看板 dashboard home' },
  { path: '/admin/staff', title: '人员管理', group: '人员', icon: Users, keywords: '用户 教师 学生 staff user' },
  { path: '/admin/staff?tab=teachers', title: '教师管理', group: '人员', icon: UserCheck, keywords: '老师 教师 teacher' },
  { path: '/admin/staff?tab=students', title: '学生管理', group: '人员', icon: GraduationCap, keywords: '学生 同学 student' },
  { path: '/admin/staff?tab=duty', title: '值班管理', group: '人员', icon: ShieldCheck, keywords: '值班 值日 duty' },
  { path: '/admin/staff?tab=cadre', title: '班干部管理', group: '人员', icon: UserCog, keywords: '班干部 班长 cadre' },
  { path: '/admin/course-import', title: '课程导入', group: '课程', icon: Upload, keywords: '导入 excel 批量 import' },
  { path: '/admin/semester', title: '学期管理', group: '教学', icon: CalendarClock, keywords: '学期 学年 semester' },
  { path: '/admin/schedule', title: '排课管理', group: '教学', icon: CalendarDays, keywords: '课表 排课 教室 schedule' },
  { path: '/admin/bell', title: '作息设置', group: '教学', icon: Clock, keywords: '铃声 上下课 作息 bell' },
  { path: '/admin/school-info', title: '校园信息', group: '校园', icon: BookMarked, keywords: '学校 公告 资讯 school' },
  { path: '/admin/leave', title: '请假审批', group: '审批', icon: ClipboardCheck, keywords: '请假 审批 leave' },
  { path: '/courses', title: '课程管理', group: '课程', icon: BookOpen, keywords: '课程 course' },
  { path: '/course-chapters', title: '章节管理', group: '课程', icon: ListTree, keywords: '章节 课时 chapter' },
  { path: '/stats', title: '学习统计', group: '数据', icon: BarChart3, keywords: '统计 分析 报表 stats' },
  { path: '/exam-homework', title: '考试作业', group: '教学', icon: ClipboardList, keywords: '考试 作业 测验 exam homework' },
  { path: '/ai-records', title: 'AI 操作记录', group: 'AI', icon: History, keywords: 'AI 操作记录 审计 恢复 删除日志 log record' },
]

export const teacherSearchEntries: SearchEntry[] = [
  { path: '/dashboard', title: '数据总览', group: '总览', icon: LayoutDashboard, keywords: '首页 概览 看板 dashboard home' },
  { path: '/teacher/class', title: '班级管理', group: '教学', icon: Users, keywords: '班级 学生 class' },
  { path: '/teacher/leave', title: '请假审批', group: '审批', icon: ClipboardCheck, keywords: '请假 审批 leave' },
  { path: '/courses', title: '课程管理', group: '课程', icon: BookOpen, keywords: '课程 course' },
  { path: '/course-chapters', title: '章节管理', group: '课程', icon: ListTree, keywords: '章节 课时 chapter' },
  { path: '/stats', title: '学习统计', group: '数据', icon: BarChart3, keywords: '统计 分析 报表 stats' },
  { path: '/exam-homework', title: '考试作业', group: '教学', icon: ClipboardList, keywords: '考试 作业 测验 exam homework' },
  { path: '/teacher-chat', title: '课程聊天', group: '沟通', icon: MessageSquare, keywords: '聊天 消息 沟通 chat' },
  { path: '/ai-records', title: 'AI 操作记录', group: 'AI', icon: History, keywords: 'AI 操作记录 审计 恢复 删除日志 log record' },
]

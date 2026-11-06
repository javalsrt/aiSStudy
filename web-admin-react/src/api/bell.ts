import request from './request'

export interface BellPeriod {
  node: number
  startTime: string
  endTime: string
}

export interface BellInfo {
  grade?: string | null
  weekNumber?: number | null
  parity?: number
  parityLabel?: string
  periods: BellPeriod[]
}

/**
 * 作息表（服务端自动适配）：
 * - 传 classId：按班级所在年级解析（管理端排课场景）
 * - 传 week：按该周单双周解析（周视图）
 * - 都不传：按登录用户年级 + 今天
 */
export const getBellTimes = (params: { classId?: number; week?: number } = {}) => {
  return request.get<any, BellInfo>('/bell/today', { params })
}

/* ===== 作息管理（管理员） ===== */

export interface BellPeriodRow {
  node: number
  startTime: string
  endTime: string
}

export interface AdminBellInfo {
  classId: number | null
  className?: string | null
  grade?: string | null
  semester: string
  /** 该范围自己的配置（默认范围=全局默认作息） */
  periods: BellPeriodRow[]
  /** 当前实际生效（班级 > 年级 > 默认） */
  effective: BellPeriodRow[]
  /** 标准作息建议 */
  preset: BellPeriodRow[]
}

/** 读取作息配置（classId 为空=默认作息） */
export const getAdminBell = (params: { classId?: number; semester?: string } = {}) => {
  return request.get<any, AdminBellInfo>('/admin/bell', { params })
}

/** 作息表图片识别：上传图片由 AI 视觉提取节次时间（不落库） */
export const recognizeBellImage = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return request.post<any, { periods?: BellPeriodRow[]; count?: number; error?: string }>(
    '/admin/bell/recognize',
    form,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  )
}

/* ===== 作息方案概览 & 变更记录 ===== */

export interface BellOverviewClass {
  id: number
  name: string
  grade?: string | null
  /** class=班级独立配置 grade=按年级 default=继承默认 */
  source: 'class' | 'grade' | 'default'
}

export interface BellOverviewGroup {
  signature: string
  label: string
  periods: BellPeriodRow[]
  classCount: number
  isDefault: boolean
  classes: BellOverviewClass[]
}

export interface BellOverview {
  totalClasses: number
  groups: BellOverviewGroup[]
}

export interface BellChangeLog {
  id: number
  operatorName?: string | null
  scopeType: 'CLASS' | 'DEFAULT'
  classNames?: string | null
  classCount: number
  nodeCount: number
  syncedSchedules: number
  semester?: string | null
  createdAt: string
}

/** 各班作息方案分组（哪些班级时间一致） */
export const getBellOverview = () => {
  return request.get<any, BellOverview>('/admin/bell/overview')
}

/** 最近作息变更记录 */
export const getBellLogs = (limit = 8) => {
  return request.get<any, BellChangeLog[]>('/admin/bell/logs', { params: { limit } })
}

/** 保存作息（classIds 批量 > classId 单个 > 默认作息），同时同步已排课时间 */
export const saveAdminBell = (data: {
  classId?: number | null
  classIds?: number[]
  semester?: string
  periods: BellPeriodRow[]
}) => {
  return request.post<
    any,
    { msg: string; saved: number; classes: number; syncedSchedules: number }
  >('/admin/bell', data)
}

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

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

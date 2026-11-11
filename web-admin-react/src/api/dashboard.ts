import request from './request'
import type { ActivityItem, TeacherStats, TrendItem } from '@/types'

// 教师数据总览（管理员也用这个）
export const getTeacherStats = () => {
  return request.get<any, TeacherStats>('/teacher/stats')
}

// 近7天学习时长趋势
export const getTeacherTrend = () => {
  return request.get<any, TrendItem[]>('/teacher/trend')
}

// 今年每日活跃学生（预览环境由 Vite 中间件查真实学习行为数据）
export const getTeacherActivity = () => {
  return request.get<any, ActivityItem[]>('/teacher/activity')
}

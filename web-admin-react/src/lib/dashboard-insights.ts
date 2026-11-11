import type { TrendItem } from '@/types'

/** 趋势区间：周（真实 7 天）/ 月 / 学期 */
export type TrendPeriod = '7d' | '30d' | '90d'

export interface ActivityPoint {
  /** 展示用短日期 M/D */
  date: string
  /** 0=周日 … 6=周六，用于热力图按星期对齐 */
  weekday: number
  /** 当日在线人数 */
  count: number
  /** 0-4 活跃等级，用于热力图着色 */
  level: number
}

/** 学习活跃热力图色阶：0 灰底，1-4 由浅到深（主题蓝） */
export const ACTIVITY_LEVEL_COLORS = ['#ebebeb', '#dbeafe', '#93c5fd', '#3b82f6', '#1d4ed8']

export const TREND_PERIODS: { value: TrendPeriod; label: string; title: string }[] = [
  { value: '7d', label: '周', title: '近 7 天' },
  { value: '30d', label: '月', title: '近 30 天' },
  { value: '90d', label: '学期', title: '本学期' },
]

/**
 * 演示用环比：后端 /teacher/stats 暂未返回历史对比字段。
 * 接入真实历史数据后，删除此常量，直接从接口读取 change 即可。
 */
export const DEMO_CHANGES = {
  students: { value: '+2.4%', direction: 'up' as const },
  online: { value: '+8.1%', direction: 'up' as const },
  focus: { value: '-3.2%', direction: 'down' as const },
  accuracy: { value: '+1.6%', direction: 'up' as const },
}

/** 确定性伪随机：同一序号永远得到同一数值，避免刷新时曲线跳动 */
const wave = (index: number) => {
  const x = Math.sin(index * 12.9898 + 78.233) * 43758.5453
  return x - Math.floor(x)
}

const formatMD = (date: Date) => `${date.getMonth() + 1}/${date.getDate()}`

/** 接口返回 YYYY-MM-DD，图表上统一短格式 M/D，避免 7 个刻度互相挤压 */
const shortDate = (value: string) => {
  const matched = value.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/)
  return matched ? `${Number(matched[2])}/${Number(matched[3])}` : value
}

/**
 * 构造趋势序列（演示数据）。
 * 说明：接口只提供近 7 天，月/学期视图以真实 7 日均值为基准向前补齐，
 * 曲线形态固定、可复现；待后端提供区间统计接口后整体替换此函数。
 */
export function buildTrendSeries(trend: TrendItem[], period: TrendPeriod): TrendItem[] {
  if (period === '7d') {
    return trend.map((item) => ({ ...item, date: shortDate(item.date) }))
  }

  const real = trend.slice(-7)
  const base =
    real.length > 0 ? real.reduce((sum, item) => sum + item.minutes, 0) / real.length : 180
  const days = period === '30d' ? 30 : 91
  const today = new Date()
  const daily: TrendItem[] = []

  for (let i = days - 1; i >= 0; i -= 1) {
    const date = new Date(today)
    date.setDate(today.getDate() - i)
    const index = days - 1 - i
    const isReal = i < real.length
    // 真实 7 天保持原值，历史部分按基准值 + 固定波动 + 轻微上升趋势补齐
    const minutes = isReal
      ? real[real.length - 1 - i].minutes
      : Math.round(base * (0.62 + wave(index) * 0.55) * (1 + (index / days) * 0.18))
    daily.push({ date: formatMD(date), minutes })
  }

  // 学期视图按周聚合成日均，避免 91 个点挤在一起
  if (period === '90d') {
    const weekly: TrendItem[] = []
    for (let i = 0; i < daily.length; i += 7) {
      const chunk = daily.slice(i, i + 7)
      const avg = chunk.reduce((sum, item) => sum + item.minutes, 0) / chunk.length
      weekly.push({ date: chunk[0].date, minutes: Math.round(avg) })
    }
    return weekly
  }

  return daily
}

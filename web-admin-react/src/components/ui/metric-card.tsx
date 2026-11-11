import type { ComponentType, ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * 指标卡片（移植 BoardUI 主页仪表盘 Stat Card）
 * 结构：左上图标 → 标签 → 数值 + 涨跌标签。
 * tone="panel" 用于 #f7f7f7 灰色面板（白色图标底），默认用于白色卡片。
 * 尺寸遵循模板：高 132px、圆角 16px、内边距 16px，数值 24/34/500，标签 14/20/500。
 */
interface MetricCardProps {
  label: string
  value: ReactNode
  icon?: ComponentType<{ className?: string }>
  /** 环比变化，如 { value: '+16%', direction: 'up' } */
  change?: { value: string; direction: 'up' | 'down' }
  footer?: ReactNode
  /** panel：灰色面板上的统计卡；card：白色底卡片 */
  tone?: 'card' | 'panel'
  className?: string
}

export function MetricCard({
  label,
  value,
  icon: Icon,
  change,
  footer,
  tone = 'card',
  className,
}: MetricCardProps) {
  const up = change?.direction === 'up'
  const isPanel = tone === 'panel'
  return (
    <div
      className={cn(
        'flex h-[132px] flex-col justify-between rounded-3xl p-4',
        isPanel
          ? 'bg-neutral-100'
          : 'border border-line-separator bg-surface-primary shadow-card transition-shadow duration-200 hover:shadow-raised',
        className
      )}
    >
      {Icon && (
        <span
          className={cn(
            'flex w-fit items-center rounded-md p-1.5',
            isPanel ? 'bg-surface-primary' : 'bg-neutral-100'
          )}
        >
          <Icon className="w-4 h-4 text-ink-secondary" />
        </span>
      )}
      <div className="min-w-0">
        <div className="truncate text-body-medium text-ink-secondary">{label}</div>
        <div className="mt-0.5 flex items-center gap-2">
          <span className="text-[24px] leading-[34px] font-medium text-ink-primary tnum">
            {value}
          </span>
          {change && (
            <span
              className={cn(
                'inline-flex shrink-0 items-center rounded-md px-1.5 py-0.5 text-sm font-medium leading-5 tnum',
                up ? 'bg-blue-100 text-blue-700' : 'bg-rose-200 text-rose-800'
              )}
            >
              {change.value}
            </span>
          )}
        </div>
        {footer && <div className="mt-0.5 text-caption-1-regular text-ink-tertiary">{footer}</div>}
      </div>
    </div>
  )
}

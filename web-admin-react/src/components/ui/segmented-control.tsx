import { useLayoutEffect, useRef, useState, type ComponentType } from 'react'
import { cn } from '@/lib/utils'

/**
 * 分段控件（移植 BoardUI SegmentedControl）
 * 灰色第三轨道 + 滑动白色拇指；同一时刻仅一段选中。
 * 用测量 offsetLeft 的方式实现滑动，兼容中文标签的不同宽度。
 */
export interface SegmentedOption<T extends string> {
  value: T
  /** 文字标签；仅图标时留空 */
  label?: string
  /** 仅图标模式的图标组件 */
  icon?: ComponentType<{ className?: string }>
  /** 图标模式的必需可访问名称 */
  ariaLabel?: string
}

interface SegmentedControlProps<T extends string> {
  value: T
  onChange: (value: T) => void
  options: SegmentedOption<T>[]
  size?: 'sm' | 'md'
  ariaLabel?: string
  className?: string
}

export function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  size = 'md',
  ariaLabel,
  className,
}: SegmentedControlProps<T>) {
  const trackRef = useRef<HTMLDivElement>(null)
  const [thumb, setThumb] = useState<{ left: number; width: number } | null>(null)

  useLayoutEffect(() => {
    const measure = () => {
      const track = trackRef.current
      if (!track) return
      const el = track.querySelector<HTMLElement>(`[data-segment="${value}"]`)
      if (!el) return
      setThumb({ left: el.offsetLeft, width: el.offsetWidth })
    }
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [value, options.length, size])

  return (
    <div
      ref={trackRef}
      role="tablist"
      aria-label={ariaLabel}
      className={cn(
        'relative inline-flex items-center rounded-full border border-line-separator/80 bg-neutral-100 p-1',
        className
      )}
    >
      {thumb && (
        <span
          aria-hidden="true"
          className="absolute top-1 bottom-1 left-0 rounded-full bg-surface-primary shadow-[0_1px_2px_rgba(16,24,40,0.06),0_4px_10px_-4px_rgba(16,24,40,0.14)] transition-[transform,width] duration-200 ease-[cubic-bezier(0.2,0,0,1)]"
          style={{ transform: `translateX(${thumb.left}px)`, width: thumb.width }}
        />
      )}
      {options.map((option) => {
        const active = option.value === value
        const Icon = option.icon
        return (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={active}
            aria-label={option.ariaLabel}
            data-segment={option.value}
            onClick={() => onChange(option.value)}
            className={cn(
              'relative z-10 inline-flex items-center justify-center gap-1.5 rounded-full transition-colors duration-150',
              'focus:outline-hidden focus-visible:ring-2 focus-visible:ring-primary/30',
              size === 'sm' ? 'h-6 px-2.5' : 'h-7 px-3',
              option.label ? 'text-caption-1-medium' : 'px-2',
              active
                ? 'text-ink-primary'
                : 'text-ink-secondary hover:text-ink-primary'
            )}
          >
            {Icon && <Icon className="w-[15px] h-[15px]" />}
            {option.label}
          </button>
        )
      })}
    </div>
  )
}

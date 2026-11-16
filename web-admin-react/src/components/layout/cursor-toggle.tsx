import { Crosshair, MousePointer2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useCursorStore } from '@/store/cursor'

/**
 * 侧边栏底部「光标」开关：与黑夜模式开关同款白色滑块分段控件。
 * 左段为默认鼠标，右段为目标光标；状态持久化，默认关闭。
 */
export function CursorToggle() {
  const { enabled, setEnabled } = useCursorStore()

  const options = [
    { value: false, label: '默认鼠标', Icon: MousePointer2 },
    { value: true, label: '目标光标', Icon: Crosshair },
  ]

  return (
    <div className="flex items-center gap-1.5">
      <span className="text-caption-2-medium text-ink-tertiary">光标</span>
      <div
        role="group"
        aria-label="光标效果"
        className="relative inline-flex w-fit items-center gap-1 rounded-full bg-neutral-200 p-1"
      >
        <span
          aria-hidden="true"
          className={cn(
            'pointer-events-none absolute top-1 start-1 size-8 rounded-full bg-white shadow-light transition-transform duration-200 ease-[cubic-bezier(0.22,1,0.36,1)]',
            enabled && 'translate-x-9'
          )}
        />
        {options.map(({ value, label, Icon }) => {
          const active = enabled === value
          return (
            <button
              key={label}
              type="button"
              aria-label={label}
              aria-pressed={active}
              title={label}
              onClick={() => setEnabled(value)}
              className={cn(
                'relative z-10 flex size-8 items-center justify-center rounded-full transition-colors duration-200',
                active
                  ? value
                    ? 'text-blue-500'
                    : 'text-ink-primary'
                  : 'text-ink-tertiary hover:text-ink-secondary'
              )}
            >
              <Icon className="size-4" />
            </button>
          )
        })}
      </div>
    </div>
  )
}

import { flushSync } from 'react-dom'
import { Moon, Sun } from 'lucide-react'
import type { MouseEvent as ReactMouseEvent } from 'react'
import { cn } from '@/lib/utils'
import { useThemeStore, type Theme } from '@/store/theme'

/** 最小化的 View Transition 类型，避免依赖 lib.dom 版本 */
type ViewTransitionDocument = Document & {
  startViewTransition?: (callback: () => void) => { ready: Promise<void> }
}

/**
 * 明暗切换（移植 BoardUI 的白色滑块分段开关）。
 * 轨道 76×40、内边距 4px；白色滑块 32px 圆，dark 时右移 36px。
 * 切换时用 View Transitions 从点击的图标中心做圆形扩散。
 */
export function ThemeToggle() {
  const { theme, setTheme } = useThemeStore()

  const changeTheme = (value: Theme, origin: { x: number; y: number }) => {
    const doc = document as ViewTransitionDocument
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (typeof doc.startViewTransition !== 'function' || reduceMotion) {
      setTheme(value)
      return
    }

    const transition = doc.startViewTransition(() => {
      flushSync(() => setTheme(value))
    })

    // 覆盖到视口最远角所需的半径
    const radius = Math.hypot(
      Math.max(origin.x, window.innerWidth - origin.x),
      Math.max(origin.y, window.innerHeight - origin.y)
    )

    transition.ready
      .then(() => {
        document.documentElement.animate(
          {
            clipPath: [
              `circle(0px at ${origin.x}px ${origin.y}px)`,
              `circle(${radius}px at ${origin.x}px ${origin.y}px)`,
            ],
          },
          {
            duration: 520,
            easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
            pseudoElement: '::view-transition-new(root)',
          }
        )
      })
      .catch(() => {
        // 过渡被浏览器跳过时静默降级
      })
  }

  const options: { value: Theme; label: string; Icon: typeof Sun }[] = [
    { value: 'light', label: '日间模式', Icon: Sun },
    { value: 'dark', label: '夜间模式', Icon: Moon },
  ]

  return (
    <div
      role="group"
      aria-label="主题切换"
      className="relative inline-flex w-fit items-center gap-1 rounded-full bg-neutral-200 p-1"
    >
      <span
        aria-hidden="true"
        className={cn(
          'pointer-events-none absolute top-1 start-1 size-8 rounded-full bg-white shadow-light transition-transform duration-200 ease-[cubic-bezier(0.22,1,0.36,1)]',
          theme === 'dark' && 'translate-x-9'
        )}
      />
      {options.map(({ value, label, Icon }) => {
        const active = theme === value
        return (
          <button
            key={value}
            type="button"
            aria-label={label}
            aria-pressed={active}
            title={label}
            onClick={(event: ReactMouseEvent<HTMLButtonElement>) => {
              if (active) return
              const rect = event.currentTarget.getBoundingClientRect()
              changeTheme(value, {
                x: rect.left + rect.width / 2,
                y: rect.top + rect.height / 2,
              })
            }}
            className={cn(
              'relative z-10 flex size-8 items-center justify-center rounded-full transition-colors duration-200',
              active
                ? value === 'dark'
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
  )
}

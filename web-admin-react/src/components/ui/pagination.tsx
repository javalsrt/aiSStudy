import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * 分页（移植 BoardUI Pagination）
 * 数字页 + 省略号 + 前后翻页；当前页为深色实心块。
 */
function getPageItems(current: number, total: number): Array<number | 'ellipsis'> {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const items: Array<number | 'ellipsis'> = [1]
  const start = Math.max(2, current - 1)
  const end = Math.min(total - 1, current + 1)
  if (start > 2) items.push('ellipsis')
  for (let p = start; p <= end; p += 1) items.push(p)
  if (end < total - 1) items.push('ellipsis')
  items.push(total)
  return items
}

interface PaginationProps {
  page: number
  totalPages: number
  onChange: (page: number) => void
  className?: string
}

export function Pagination({ page, totalPages, onChange, className }: PaginationProps) {
  const total = Math.max(1, totalPages)
  const items = getPageItems(page, total)
  const base =
    'inline-flex h-8 min-w-8 items-center justify-center rounded-lg px-2 text-body-2-medium tnum transition-colors duration-150 disabled:opacity-40 disabled:pointer-events-none'

  return (
    <nav aria-label="分页" className={cn('flex items-center gap-1', className)}>
      <button
        type="button"
        aria-label="上一页"
        className={cn(base, 'text-ink-secondary hover:bg-neutral-100 hover:text-ink-primary')}
        onClick={() => onChange(page - 1)}
        disabled={page <= 1}
      >
        <ChevronLeft className="w-4 h-4" />
      </button>
      {items.map((item, i) =>
        item === 'ellipsis' ? (
          <span key={`e-${i}`} className="px-1 text-ink-tertiary">
            …
          </span>
        ) : (
          <button
            key={item}
            type="button"
            aria-current={item === page ? 'page' : undefined}
            className={cn(
              base,
              item === page
                ? 'bg-neutral-900 text-white'
                : 'text-ink-secondary hover:bg-neutral-100 hover:text-ink-primary'
            )}
            onClick={() => onChange(item)}
          >
            {item}
          </button>
        )
      )}
      <button
        type="button"
        aria-label="下一页"
        className={cn(base, 'text-ink-secondary hover:bg-neutral-100 hover:text-ink-primary')}
        onClick={() => onChange(page + 1)}
        disabled={page >= total}
      >
        <ChevronRight className="w-4 h-4" />
      </button>
    </nav>
  )
}

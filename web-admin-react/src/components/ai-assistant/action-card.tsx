import { AlertTriangle, ArrowUpRight, CheckCircle2, Loader2, MinusCircle, ShieldAlert, XCircle } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { AssistantActionCard } from '@/types/assistant'

interface ActionCardProps {
  card: AssistantActionCard
  confirming?: boolean
  onConfirm?: (token: string, approved: boolean) => void
}

const STATUS_META = {
  success: {
    icon: CheckCircle2,
    label: '执行成功',
    tone: 'border-emerald-200 bg-emerald-50/60 text-emerald-700',
    iconTone: 'text-emerald-600',
  },
  failed: {
    icon: XCircle,
    label: '执行失败',
    tone: 'border-red-200 bg-red-50/60 text-red-600',
    iconTone: 'text-red-500',
  },
  pending_confirm: {
    icon: AlertTriangle,
    label: '待确认',
    tone: 'border-amber-200 bg-amber-50/60 text-amber-700',
    iconTone: 'text-amber-600',
  },
  canceled: {
    icon: MinusCircle,
    label: '已取消',
    tone: 'border-line-separator bg-neutral-100 text-ink-secondary',
    iconTone: 'text-ink-tertiary',
  },
} as const

export function ActionCard({ card, confirming = false, onConfirm }: ActionCardProps) {
  const navigate = useNavigate()
  const meta = STATUS_META[card.status]
  const Icon = meta.icon
  const pending = card.status === 'pending_confirm' && Boolean(card.confirmToken)

  return (
    <div className={cn('mt-2 overflow-hidden rounded-2xl border', meta.tone)}>
      <div className="flex items-center gap-2 px-3 py-2.5">
        <Icon className={cn('h-4 w-4 shrink-0', meta.iconTone)} />
        <span className="min-w-0 flex-1 truncate text-caption-1-medium">{card.label}</span>
        <span className="shrink-0 rounded-full bg-white/70 px-2 py-0.5 text-caption-2-medium">
          {meta.label}
        </span>
      </div>

      <div className="bg-surface-primary px-3 py-3">
        <p className="text-body-2-regular text-ink-primary">{card.summary}</p>

        {card.fields && card.fields.length > 0 && (
          <div className="mt-2.5 divide-y divide-line-separator overflow-hidden rounded-xl border border-line-separator bg-surface-primary">
            {card.fields.map((field) => (
              <div key={field.label} className="flex items-start justify-between gap-3 px-3 py-1.5">
                <span className="shrink-0 text-caption-1-regular text-ink-tertiary">{field.label}</span>
                <span className="min-w-0 break-all text-right text-caption-1-medium text-ink-primary">
                  {field.value}
                </span>
              </div>
            ))}
          </div>
        )}

        {card.items && card.items.length > 0 && (
          <div className="mt-2.5 space-y-1.5">
            {card.items.slice(0, 5).map((item, index) => {
              const entries = Object.entries(item)
                .filter(([key]) => key !== 'id')
                .slice(0, 7)
              return (
                <div
                  key={index}
                  className="rounded-xl border border-line-separator bg-surface-secondary/60 px-3 py-2"
                >
                  <div className="grid grid-cols-2 gap-x-3 gap-y-1">
                    {entries.map(([key, value]) => (
                      <div key={key} className="min-w-0">
                        <span className="text-[11px] text-ink-tertiary">{key}</span>
                        <div className="truncate text-caption-1-medium text-ink-primary">
                          {value === null || value === undefined || value === '' ? '-' : String(value)}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )
            })}
            {card.items.length > 5 && (
              <div className="px-1 text-[11px] text-ink-tertiary">
                仅展示前 5 条，完整结果请到对应页面查看
              </div>
            )}
          </div>
        )}

        {pending ? (
          <div className="mt-3 rounded-xl border border-amber-200 bg-amber-50/60 px-3 py-2.5">
            <div className="flex items-center gap-1.5 text-caption-2-medium text-amber-700">
              <ShieldAlert className="h-3.5 w-3.5" />
              此操作会修改系统数据，确认后才会真正执行
            </div>
            <div className="mt-2 flex justify-end gap-2">
              <Button
                type="button"
                variant="secondary"
                size="sm"
                disabled={confirming}
                onClick={() => card.confirmToken && onConfirm?.(card.confirmToken, false)}
              >
                取消
              </Button>
              <Button
                type="button"
                variant="destructive"
                size="sm"
                disabled={confirming}
                onClick={() => card.confirmToken && onConfirm?.(card.confirmToken, true)}
              >
                {confirming && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                确认执行
              </Button>
            </div>
          </div>
        ) : (
          card.link && (
            <div className="mt-3 flex justify-end">
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => navigate(card.link!)}
              >
                去查看
                <ArrowUpRight className="h-3.5 w-3.5" />
              </Button>
            </div>
          )
        )}
      </div>
    </div>
  )
}

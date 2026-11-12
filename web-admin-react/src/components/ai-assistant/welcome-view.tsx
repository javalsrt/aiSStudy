import {
  BarChart3,
  BookMarked,
  BookOpen,
  Bot,
  ClipboardCheck,
  ClipboardList,
  History,
  School,
  Sparkles,
  UserCheck,
  Users,
  type LucideIcon,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import type { AssistantCapability, AssistantMeta } from '@/types/assistant'

const ICONS: Record<string, LucideIcon> = {
  student: Users,
  teacher: UserCheck,
  class: School,
  course: BookOpen,
  exam: ClipboardList,
  leave: ClipboardCheck,
  school: BookMarked,
  stats: BarChart3,
  records: History,
}

interface WelcomeViewProps {
  meta: AssistantMeta
  onSuggestion: (text: string) => void
}

export function WelcomeView({ meta, onSuggestion }: WelcomeViewProps) {
  const suggestions = meta.suggestions

  return (
    <div className="px-4 pb-4 pt-6">
      <div className="flex items-start gap-3">
        <div className="relative flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-primary-500 to-blue-500 text-white shadow-[0_6px_18px_rgba(91,88,255,0.28)]">
          <Bot className="h-6 w-6" />
          <span className="absolute -right-0.5 -top-0.5 flex h-4 w-4 items-center justify-center rounded-full bg-white">
            <Sparkles className="h-2.5 w-2.5 text-amber-500" />
          </span>
        </div>
        <div className="min-w-0 pt-0.5">
          <h2 className="text-title-3-medium text-ink-primary">{meta.greeting}</h2>
          <p className="mt-1 text-caption-1-regular leading-5 text-ink-secondary">{meta.subtitle}</p>
        </div>
      </div>

      <div className="mt-6">
        <div className="text-caption-1-medium text-ink-tertiary">我可以帮你</div>
        <div className="mt-2 grid grid-cols-2 gap-2">
          {meta.capabilities.map((capability) => (
            <CapabilityCard key={capability.key} capability={capability} />
          ))}
        </div>
      </div>

      <div className="mt-5">
        <div className="text-caption-1-medium text-ink-tertiary">试着这样问我</div>
        <div className="mt-2 flex flex-wrap gap-1.5">
          {suggestions.map((suggestion) => (
            <button
              key={suggestion}
              type="button"
              onClick={() => onSuggestion(suggestion)}
              className="rounded-full border border-line-separator bg-surface-primary px-3 py-1.5 text-left text-caption-1-regular text-ink-secondary transition-colors hover:border-primary-200 hover:bg-primary-50/60 hover:text-primary-700"
            >
              {suggestion}
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}

function CapabilityCard({ capability }: { capability: AssistantCapability }) {
  const Icon = ICONS[capability.key] ?? Sparkles
  return (
    <div
      className={cn(
        'group flex items-start gap-2.5 rounded-2xl border border-line-separator bg-surface-primary p-2.5',
        'transition-all duration-150 hover:-translate-y-0.5 hover:border-primary-200 hover:shadow-card'
      )}
    >
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-primary-50 text-primary-600">
        <Icon className="h-4 w-4" />
      </div>
      <div className="min-w-0">
        <div className="text-caption-1-medium text-ink-primary">{capability.title}</div>
        <div className="mt-0.5 text-[11px] leading-4 text-ink-tertiary">{capability.desc}</div>
      </div>
    </div>
  )
}

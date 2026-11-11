import * as React from 'react'
import { cn } from '@/lib/utils'

export interface TextareaProps
  extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {}

const Textarea = React.forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, ...props }, ref) => {
    return (
      <textarea
        className={cn(
          'flex min-h-[90px] w-full rounded-lg border border-line-separator bg-surface-primary px-3 py-2 text-body-2-regular text-ink-primary outline-hidden transition-[box-shadow,border-color] duration-150 placeholder:text-ink-tertiary focus-visible:border-emerald-300 focus-visible:shadow-[0_0_0_1px_#86efac,0_0_0_4px_rgb(134_239_172/0.18)] disabled:cursor-not-allowed disabled:opacity-50 resize-y',
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Textarea.displayName = 'Textarea'

export { Textarea }

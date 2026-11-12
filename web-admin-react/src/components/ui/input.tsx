import * as React from 'react'
import { cn } from '@/lib/utils'

export interface InputProps
  extends React.InputHTMLAttributes<HTMLInputElement> {}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(
          'flex h-9 w-full rounded-full border border-line-separator bg-surface-primary px-4 py-1.5 text-body-2-regular text-ink-primary shadow-[inset_0_-1px_0_rgba(16,24,40,0.04),0_1px_2px_rgba(16,24,40,0.04)] outline-hidden transition-[box-shadow,border-color,background-color] duration-150 placeholder:text-ink-tertiary hover:border-line-strong focus-visible:border-emerald-300 focus-visible:shadow-[0_0_0_1px_#86efac,0_0_0_4px_rgb(134_239_172/0.18)] disabled:cursor-not-allowed disabled:opacity-50',
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Input.displayName = 'Input'

export { Input }

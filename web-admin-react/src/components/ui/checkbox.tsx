'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

interface CheckboxProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'checked'> {
  checked?: boolean | 'indeterminate'
  onCheckedChange?: (checked: boolean | 'indeterminate') => void
}

const Checkbox = React.forwardRef<HTMLInputElement, CheckboxProps>(
  ({ className, checked, onCheckedChange, disabled, id, ...props }, ref) => {
    const innerRef = React.useRef<HTMLInputElement | null>(null)
    const setRefs = React.useCallback(
      (node: HTMLInputElement | null) => {
        innerRef.current = node
        if (typeof ref === 'function') ref(node)
        else if (ref) (ref as React.MutableRefObject<HTMLInputElement | null>).current = node
      },
      [ref]
    )

    React.useEffect(() => {
      if (innerRef.current) {
        innerRef.current.indeterminate = checked === 'indeterminate'
      }
    }, [checked])

    const isChecked = checked === true

    return (
      <div className="relative inline-flex items-center justify-center">
        <input
          type="checkbox"
          ref={setRefs}
          id={id}
          disabled={disabled}
          checked={isChecked}
          onChange={(e) => {
            const next = e.target.checked
            onCheckedChange?.(next)
          }}
          className={cn(
            'peer h-5 w-5 cursor-pointer rounded-md border border-neutral-300 bg-surface-primary text-emerald-500 shadow-xs transition-colors hover:border-neutral-400 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-emerald-300 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 accent-emerald-500',
            className
          )}
          {...props}
        />
      </div>
    )
  }
)
Checkbox.displayName = 'Checkbox'

export { Checkbox }

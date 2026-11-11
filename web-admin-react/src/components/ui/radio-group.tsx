'use client'

import * as React from 'react'
import { cn } from '@/lib/utils'

interface RadioGroupContextValue {
  value: string
  onValueChange: (v: string) => void
  disabled?: boolean
  name: string
}

const RadioGroupContext = React.createContext<RadioGroupContextValue | null>(null)

interface RadioGroupProps extends Omit<React.HTMLAttributes<HTMLDivElement>, 'onChange'> {
  value?: string
  defaultValue?: string
  onValueChange?: (value: string) => void
  disabled?: boolean
  name?: string
}

const RadioGroup = React.forwardRef<HTMLDivElement, RadioGroupProps>(
  ({ className, value: controlledValue, defaultValue = '', onValueChange, disabled, name, ...props }, ref) => {
    const [internalValue, setInternalValue] = React.useState<string>(defaultValue)
    const isControlled = controlledValue !== undefined
    const value = isControlled ? (controlledValue ?? '') : internalValue
    const groupName = name || React.useId()

    const handleChange = React.useCallback(
      (v: string) => {
        if (!isControlled) setInternalValue(v)
        onValueChange?.(v)
      },
      [isControlled, onValueChange]
    )

    return (
      <RadioGroupContext.Provider value={{ value, onValueChange: handleChange, disabled, name: groupName }}>
        <div
          ref={ref}
          role="radiogroup"
          className={cn('flex flex-col gap-2', className)}
          {...props}
        />
      </RadioGroupContext.Provider>
    )
  }
)
RadioGroup.displayName = 'RadioGroup'

interface RadioGroupItemProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type' | 'onChange'> {
  value: string
}

const RadioGroupItem = React.forwardRef<HTMLInputElement, RadioGroupItemProps>(
  ({ className, value, disabled: itemDisabled, id, ...props }, ref) => {
    const ctx = React.useContext(RadioGroupContext)
    if (!ctx) return null
    const disabled = itemDisabled || ctx.disabled
    const checked = ctx.value === value

    return (
      <input
        ref={ref}
        id={id}
        type="radio"
        name={ctx.name}
        value={value}
        checked={checked}
        disabled={disabled}
        onChange={() => ctx.onValueChange(value)}
        className={cn(
          'peer h-5 w-5 cursor-pointer rounded-full border border-neutral-300 bg-surface-primary text-emerald-500 shadow-xs transition-colors hover:border-neutral-400 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-emerald-300 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 accent-emerald-500',
          className
        )}
        {...props}
      />
    )
  }
)
RadioGroupItem.displayName = 'RadioGroupItem'

export { RadioGroup, RadioGroupItem }

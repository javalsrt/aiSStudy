import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const badgeVariants = cva(
  'inline-flex items-center rounded-full px-3 py-1 text-xs font-medium transition-colors focus:outline-hidden focus:ring-2 focus:ring-emerald-300 focus:ring-offset-2',
  {
    variants: {
      variant: {
        default: 'bg-neutral-900 text-white hover:bg-neutral-800',
        primary: 'bg-primary-50 text-primary-600 hover:bg-primary-100',
        secondary: 'bg-neutral-100 text-neutral-700 hover:bg-neutral-200',
        outline: 'border border-neutral-200 text-neutral-700 hover:bg-neutral-100',
        success: 'bg-success/10 text-success hover:bg-success/15',
        warning: 'bg-warning/10 text-warning hover:bg-warning/15',
        danger: 'bg-danger/10 text-danger hover:bg-danger/15',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  }
)

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return (
    <div className={cn(badgeVariants({ variant }), className)} {...props} />
  )
}

export { Badge, badgeVariants }

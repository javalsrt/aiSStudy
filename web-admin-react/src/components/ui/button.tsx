import * as React from 'react'
import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap text-body-2-medium font-semibold transition-all duration-150 ease-out focus-visible:outline-hidden focus-visible:shadow-[0_0_0_1px_#86efac,0_0_0_4px_rgb(134_239_172/0.18)] disabled:pointer-events-none disabled:opacity-50 active:scale-[0.97] [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0 [&_svg]:translate-y-[0.08em]',
  {
    variants: {
      variant: {
        default:
          'bg-neutral-950 text-white shadow-[0_1px_2px_rgba(0,0,0,0.12),0_8px_18px_-12px_rgba(0,0,0,0.35)] hover:bg-neutral-800 hover:shadow-[0_2px_4px_rgba(0,0,0,0.12),0_12px_26px_-12px_rgba(0,0,0,0.35)]',
        primary:
          'bg-neutral-950 text-white shadow-[0_1px_2px_rgba(0,0,0,0.12),0_8px_18px_-12px_rgba(0,0,0,0.35)] hover:bg-neutral-800 hover:shadow-[0_2px_4px_rgba(0,0,0,0.12),0_12px_26px_-12px_rgba(0,0,0,0.35)]',
        secondary:
          'border border-line-separator bg-surface-primary text-ink-primary shadow-[0_1px_2px_rgba(16,24,40,0.04)] hover:border-line-strong hover:bg-neutral-100 hover:shadow-[0_2px_6px_rgba(16,24,40,0.06),0_10px_24px_-16px_rgba(16,24,40,0.18)]',
        outline:
          'border border-line-separator bg-surface-primary text-ink-primary shadow-[0_1px_2px_rgba(16,24,40,0.04)] hover:border-line-strong hover:bg-neutral-100 hover:shadow-[0_2px_6px_rgba(16,24,40,0.06),0_10px_24px_-16px_rgba(16,24,40,0.18)]',
        ghost: 'text-ink-primary hover:bg-neutral-100',
        link: 'text-primary-550 underline-offset-4 hover:underline',
        destructive:
          'bg-danger text-white shadow-[0_1px_2px_rgba(243,57,57,0.24),0_8px_18px_-12px_rgba(243,57,57,0.5)] hover:bg-danger/90',
        success:
          'bg-success text-white shadow-[0_1px_2px_rgba(13,167,64,0.24),0_8px_18px_-12px_rgba(13,167,64,0.5)] hover:bg-success/90',
      },
      size: {
        default: 'h-9 rounded-[var(--radius-control)] px-4',
        sm: 'h-8 rounded-[12px] px-3 text-caption-1-medium',
        xs: 'h-6 rounded-[10px] px-2 text-caption-2-medium',
        lg: 'h-10 rounded-[16px] px-5 text-body-medium',
        icon: 'h-9 w-9 rounded-[var(--radius-control)]',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  }
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button'
    return (
      <Comp
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    )
  }
)
Button.displayName = 'Button'

export { Button, buttonVariants }

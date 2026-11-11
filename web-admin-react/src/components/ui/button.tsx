import * as React from 'react'
import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap text-body-2-medium transition-all duration-150 ease-out focus-visible:outline-hidden focus-visible:shadow-[0_0_0_1px_#86efac,0_0_0_4px_rgb(134_239_172/0.18)] disabled:pointer-events-none disabled:opacity-50 active:scale-[0.97] [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0 [&_svg]:translate-y-[0.08em]',
  {
    variants: {
      variant: {
        default: 'bg-neutral-950 text-white hover:bg-neutral-800 shadow-[0_1px_2px_rgba(0,0,0,0.12)]',
        primary: 'bg-neutral-950 text-white hover:bg-neutral-800 shadow-[0_1px_2px_rgba(0,0,0,0.12)]',
        secondary: 'border border-line-separator bg-surface-primary text-ink-primary hover:bg-neutral-100',
        outline: 'border border-line-separator bg-surface-primary text-ink-primary hover:bg-neutral-100 hover:border-line-strong',
        ghost: 'text-ink-primary hover:bg-neutral-100',
        link: 'text-primary-550 underline-offset-4 hover:underline',
        destructive: 'bg-danger text-white hover:bg-danger/90 shadow-[0_1px_2px_rgba(243,57,57,0.24)]',
        success: 'bg-success text-white hover:bg-success/90',
      },
      size: {
        default: 'h-9 px-4 rounded-[10px]',
        sm: 'h-8 px-3 rounded-lg text-caption-1-medium',
        xs: 'h-6 px-2 rounded-md text-caption-2-medium',
        lg: 'h-10 px-5 rounded-xl text-body-medium',
        icon: 'h-9 w-9 rounded-[10px]',
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

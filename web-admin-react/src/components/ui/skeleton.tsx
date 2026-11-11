import * as React from 'react'
import { cn } from '@/lib/utils'

type SkeletonShape = 'rect' | 'circle' | 'text'

interface SkeletonProps extends React.HTMLAttributes<HTMLDivElement> {
  shape?: SkeletonShape
}

/**
 * 骨架屏（移植 BoardUI Skeleton）
 * rect：卡片/图片占位；circle：头像；text：文本行。
 * 动画为 2.2s 扫光，减弱动态效果时自动切换为脉动。
 */
export function Skeleton({ className, shape = 'rect', ...props }: SkeletonProps) {
  return (
    <div
      aria-hidden="true"
      className={cn(
        'skeleton',
        shape === 'circle' ? 'rounded-full' : shape === 'text' ? 'h-3 rounded-md' : 'rounded-lg',
        className
      )}
      {...props}
    />
  )
}

interface SkeletonTextProps {
  lines?: number
  className?: string
  lineClassName?: string
}

/** 多行文本骨架：末行略短，模拟自然段落 */
export function SkeletonText({ lines = 3, className, lineClassName }: SkeletonTextProps) {
  return (
    <div aria-hidden="true" className={cn('space-y-2', className)}>
      {Array.from({ length: lines }).map((_, index) => (
        <Skeleton
          key={index}
          shape="text"
          className={cn(index === lines - 1 ? 'w-2/3' : 'w-full', lineClassName)}
        />
      ))}
    </div>
  )
}

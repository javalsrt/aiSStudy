import { clsx, type ClassValue } from 'clsx'
import { extendTailwindMerge } from 'tailwind-merge'

/**
 * 自定义复合字体类注册进 tailwind-merge 的 font-size 分组。
 * 否则 cn() 合并时会把 text-title-2-semibold 当成颜色类丢弃
 * （BoardUI 的 cx() 做的是同一件事）。
 */
const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      'font-size': [
        {
          text: [
            'display-4-medium',
            'title-1-semibold',
            'title-2-semibold',
            'title-3-medium',
            'headline-medium',
            'body-medium',
            'body-2-regular',
            'body-2-medium',
            'caption-1-regular',
            'caption-1-medium',
            'caption-2-medium',
          ],
        },
      ],
    },
  },
})

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatDate(date: string | Date) {
  const d = new Date(date)
  return d.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

export function formatDateTime(date: string | Date) {
  const d = new Date(date)
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

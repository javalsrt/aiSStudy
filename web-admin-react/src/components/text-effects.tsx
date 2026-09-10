import { useEffect, useId, useState, type CSSProperties, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

/* ============================================================
   文字动效组件（参考 ReactBits Text Animations）
   - DecryptText：解密文字（乱码逐位还原）
   - ShinyText：闪光文字
   - CircularText：循环旋转文字
   ============================================================ */

const SCRAMBLE_CHARS =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*'

interface DecryptTextProps {
  text: string
  /** 每次迭代间隔（ms） */
  speed?: number
  /** 乱码阶段迭代次数 */
  cycles?: number
  className?: string
  style?: CSSProperties
}

/** 解密文字：挂载时逐位从乱码还原为目标文本 */
export function DecryptText({ text, speed = 42, cycles = 6, className, style }: DecryptTextProps) {
  const [display, setDisplay] = useState(text)

  useEffect(() => {
    let alive = true
    let frame = 0
    const totalFrames = cycles + text.length

    const timer = setInterval(() => {
      if (!alive) return
      frame += 1
      if (frame >= totalFrames) {
        setDisplay(text)
        clearInterval(timer)
        return
      }
      // 前 cycles 帧全乱码，之后从左到右逐位还原
      const revealed = Math.max(0, frame - cycles)
      const next = text
        .split('')
        .map((ch, i) => {
          if (ch === ' ' || i < revealed) return ch
          return SCRAMBLE_CHARS[Math.floor(Math.random() * SCRAMBLE_CHARS.length)]
        })
        .join('')
      setDisplay(next)
    }, speed)

    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [text, speed, cycles])

  return (
    <span className={cn('inline-block whitespace-nowrap', className)} style={style}>
      {display}
    </span>
  )
}

interface ShinyTextProps {
  text: string
  className?: string
  /** 基础文字颜色 */
  color?: string
  /** 高光颜色 */
  shineColor?: string
  /** 动画单程时长（s） */
  speed?: number
  /** 延迟（s） */
  delay?: number
  /** 是否往返 */
  yoyo?: boolean
  /** 悬停暂停 */
  pauseOnHover?: boolean
}

/** 闪光文字：一道高光不断扫过文字 */
export function ShinyText({
  text,
  className,
  color = '#b5b5b5',
  shineColor = '#ffffff',
  speed = 2.4,
  delay = 0,
  yoyo = false,
  pauseOnHover = false,
}: ShinyTextProps) {
  return (
    <span
      className={cn('trae-shiny-text inline-block', className)}
      data-yoyo={yoyo}
      data-pause-hover={pauseOnHover}
      style={
        {
          '--ts-speed': `${speed}s`,
          '--ts-delay': `${delay}s`,
          backgroundImage: `linear-gradient(90deg, ${color} 42%, ${shineColor} 50%, ${color} 58%)`,
        } as CSSProperties
      }
    >
      {text}
    </span>
  )
}

/** 渐变流动文字：横向渐变持续平移（ReactBits GradientText） */
interface GradientTextProps {
  children: ReactNode
  className?: string
  /** 渐变颜色数组，首尾建议同色以无缝循环 */
  colors?: string[]
  /** 动画单程时长（s） */
  speed?: number
}

export function GradientText({
  children,
  className,
  colors = ['#ffaa40', '#9c40ff', '#ffaa40'],
  speed = 8,
}: GradientTextProps) {
  return (
    <span
      className={cn('trae-gradient-text inline-block', className)}
      style={
        {
          '--ts-duration': `${speed}s`,
          backgroundImage: `linear-gradient(to right, ${colors.join(', ')})`,
        } as CSSProperties
      }
    >
      {children}
    </span>
  )
}

interface CircularTextProps {
  /** 环绕圆圈的文字 */
  text: string
  /** 旋转一圈时长（s） */
  spinDuration?: number
  /** 圆圈上的字号 */
  fontSize?: number
  /** 内圈中心内容（不随环旋转） */
  children?: ReactNode
  className?: string
  textClassName?: string
  /** 悬停暂停 */
  pauseOnHover?: boolean
}

/** 循环文字：文字沿大圆环绕并持续旋转，中心内容静止居中 */
export function CircularText({
  text,
  spinDuration = 40,
  fontSize = 26,
  children,
  className,
  textClassName,
  pauseOnHover = true,
}: CircularTextProps) {
  const rawId = useId()
  const pathId = `trae-circle-path-${rawId.replace(/[^a-zA-Z0-9_-]/g, '')}`
  // 使用 textLength 撑满整个圆周，文案长短都能均匀铺满
  const perimeter = 2 * Math.PI * 230

  return (
    <div className={cn('relative', className)}>
      <div
        className={cn('trae-spin w-full h-full', pauseOnHover && 'trae-spin-pause-hover')}
        style={{ '--ts-duration': `${spinDuration}s` } as CSSProperties}
      >
        <svg viewBox="0 0 600 600" className="block h-full w-full overflow-visible">
          <defs>
            <path
              id={pathId}
              d={`M 300,300 m -230,0 a 230,230 0 1,1 460,0 a 230,230 0 1,1 -460,0`}
              fill="none"
            />
          </defs>
          <text
            fill="rgba(255,255,255,0.85)"
            fontSize={fontSize}
            textLength={perimeter}
            lengthAdjust="spacingAndGlyphs"
            style={{ filter: 'drop-shadow(0 0 8px rgba(255,255,255,0.25))' }}
          >
            <textPath href={`#${pathId}`} className={textClassName}>
              {text}
            </textPath>
          </text>
        </svg>
      </div>
      {/* 中心内容静态层 */}
      <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
        {children}
      </div>
    </div>
  )
}
import { useEffect, useRef, type CSSProperties, type ReactNode } from 'react'
import './pixel-swap.css'

/**
 * Pixel Swap（移植 reactbits.dev Pixel Swap 的像素波动揭示动画）
 * 原理：把容器切成像素网格，每个像素按“弹簧”从 0 长到 1（带回弹），
 * 用所有像素矩形的并集路径作为上层内容的 clip-path，形成逐像素铺开的过渡。
 * 这里只保留受控模式（active），去掉指针悬停/点击触发，供页面切换使用。
 */

const MAX_PIXELS = 600
/** 物理步长：1/240 秒，保证不同刷新率下回弹一致 */
const STEP = 1 / 240

export type PixelPattern =
  | 'random'
  | 'center'
  | 'edges'
  | 'left-to-right'
  | 'right-to-left'
  | 'top-to-bottom'
  | 'bottom-to-top'
  | 'diagonal'
  | 'spiral'

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

const smoothstep = (edge0: number, edge1: number, value: number) => {
  const t = clamp((value - edge0) / (edge1 - edge0), 0, 1)
  return t * t * (3 - 2 * t)
}

/** 确定性哈希：随机模式与噪点都用它，避免每次渲染抖动 */
const hash = (index: number) => {
  let h = Math.imul(index + 1, 0x27d4eb2d)
  h = Math.imul(h ^ (h >>> 15), 0x85ebca6b)
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}

const round = (value: number) => Math.round(value * 10) / 10

interface PixelGrid {
  count: number
  size: number
  stride: number
  gap: number
  width: number
  height: number
  cx: Float32Array
  cy: Float32Array
  gx: Float32Array
  gy: Float32Array
}

const buildGrid = (width: number, height: number, pixelSize: number, gap: number): PixelGrid => {
  let size = pixelSize
  let columns = Math.max(1, Math.ceil((width + gap) / (size + gap)))
  let rows = Math.max(1, Math.ceil((height + gap) / (size + gap)))
  // 像素总量封顶，超大屏自动放大像素，避免 clip-path 过长
  if (columns * rows > MAX_PIXELS) {
    size = Math.ceil(size * Math.sqrt((columns * rows) / MAX_PIXELS))
    columns = Math.max(1, Math.ceil((width + gap) / (size + gap)))
    rows = Math.max(1, Math.ceil((height + gap) / (size + gap)))
  }
  const stride = size + gap
  const startX = (width - (columns * stride - gap)) / 2
  const startY = (height - (rows * stride - gap)) / 2
  const count = columns * rows
  const cx = new Float32Array(count)
  const cy = new Float32Array(count)
  const gx = new Float32Array(count)
  const gy = new Float32Array(count)
  for (let row = 0; row < rows; row += 1) {
    for (let column = 0; column < columns; column += 1) {
      const index = row * columns + column
      cx[index] = startX + column * stride + size / 2
      cy[index] = startY + row * stride + size / 2
      gx[index] = columns > 1 ? column / (columns - 1) : 0.5
      gy[index] = rows > 1 ? row / (rows - 1) : 0.5
    }
  }
  return { count, size, stride, gap, width, height, cx, cy, gx, gy }
}

/** 每个像素的入场顺序：0 先出现，1 最后出现 */
const orderOf = (grid: PixelGrid, index: number, pattern: PixelPattern) => {
  const x = grid.gx[index]
  const y = grid.gy[index]
  if (pattern === 'random') return hash(index + 11)
  if (pattern === 'center') return Math.hypot(x - 0.5, y - 0.5) / Math.SQRT1_2
  if (pattern === 'edges') return 1 - Math.min(x, 1 - x, y, 1 - y) * 2
  if (pattern === 'left-to-right') return x
  if (pattern === 'right-to-left') return 1 - x
  if (pattern === 'top-to-bottom') return y
  if (pattern === 'bottom-to-top') return 1 - y
  if (pattern === 'diagonal') return (x + y) / 2
  const angle = (Math.atan2(y - 0.5, x - 0.5) + Math.PI) / (Math.PI * 2)
  return (angle + Math.hypot(x - 0.5, y - 0.5) / Math.SQRT1_2) / 2
}

/** 生成单个像素的圆角矩形路径（支持旋转），拼进同一个 path 作为 clip-path */
const pixelPath = (x: number, y: number, half: number, radius: number, angle: number) => {
  if (!angle) {
    const x0 = round(x - half)
    const x1 = round(x + half)
    const y0 = round(y - half)
    const y1 = round(y + half)
    if (radius < 0.2) return `M${x0} ${y0}H${x1}V${y1}H${x0}Z`
    const r = round(radius)
    return `M${round(x0 + r)} ${y0}H${round(x1 - r)}A${r} ${r} 0 0 1 ${x1} ${round(
      y0 + r
    )}V${round(y1 - r)}A${r} ${r} 0 0 1 ${round(x1 - r)} ${y1}H${round(
      x0 + r
    )}A${r} ${r} 0 0 1 ${x0} ${round(y1 - r)}V${round(y0 + r)}A${r} ${r} 0 0 1 ${round(
      x0 + r
    )} ${y0}Z`
  }
  const cos = Math.cos(angle)
  const sin = Math.sin(angle)
  const at = (u: number, v: number) => `${round(x + u * cos - v * sin)} ${round(y + u * sin + v * cos)}`
  const h = half
  if (radius < 0.2) return `M${at(-h, -h)}L${at(h, -h)}L${at(h, h)}L${at(-h, h)}Z`
  const r = round(radius)
  const k = h - radius
  return `M${at(-k, -h)}L${at(k, -h)}A${r} ${r} 0 0 1 ${at(h, -k)}L${at(h, k)}A${r} ${r} 0 0 1 ${at(
    k,
    h
  )}L${at(-k, h)}A${r} ${r} 0 0 1 ${at(-h, k)}L${at(-h, -k)}A${r} ${r} 0 0 1 ${at(-k, -h)}Z`
}

interface Engine {
  grid: PixelGrid
  /** 每个像素当前的展开进度 0-1 */
  value: Float32Array
  velocity: Float32Array
  /** 每个像素开始翻转的时间戳 */
  flipAt: Float64Array
  target: Uint8Array
  desired: number
  last: number
  running: boolean
  raf: number
}

export interface PixelSwapProps {
  firstContent?: ReactNode
  secondContent?: ReactNode
  /** 受控：true 表示展示 secondContent */
  active: boolean
  pattern?: PixelPattern
  pixelSize?: number
  gap?: number
  pixelRadius?: number
  pixelScale?: number
  pixelSpin?: number
  /** 波动扫过整个容器的时长（ms） */
  duration?: number
  /** 单个像素长满的时长（ms） */
  pixelDuration?: number
  randomness?: number
  bounce?: number
  onComplete?: (active: boolean) => void
  className?: string
  style?: CSSProperties
}

export function PixelSwap({
  firstContent,
  secondContent,
  active,
  pattern = 'center',
  pixelSize = 48,
  gap = 0,
  pixelRadius = 0,
  pixelScale = 0,
  pixelSpin = 0,
  duration = 400,
  pixelDuration = 350,
  randomness = 0.2,
  bounce = 0.3,
  onComplete,
  className = '',
  style,
}: PixelSwapProps) {
  const rootRef = useRef<HTMLDivElement>(null)
  const layerRefs = useRef<(HTMLDivElement | null)[]>([])
  const shownRef = useRef(false)
  const engineRef = useRef<Engine | null>(null)
  const settingsRef = useRef({
    pixelSize: Math.max(8, Math.round(pixelSize)),
    gap: Math.max(0, gap),
    pixelRadius: clamp(pixelRadius, 0, 50),
    pixelScale: clamp(pixelScale, 0, 0.9),
    pixelSpin: (clamp(pixelSpin, -360, 360) * Math.PI) / 180,
    duration: Math.max(0, duration),
    pixelDuration: Math.max(60, pixelDuration),
    randomness: clamp(randomness, 0, 1),
    bounce: clamp(bounce, 0, 1),
    onComplete,
  })
  settingsRef.current = {
    pixelSize: Math.max(8, Math.round(pixelSize)),
    gap: Math.max(0, gap),
    pixelRadius: clamp(pixelRadius, 0, 50),
    pixelScale: clamp(pixelScale, 0, 0.9),
    pixelSpin: (clamp(pixelSpin, -360, 360) * Math.PI) / 180,
    duration: Math.max(0, duration),
    pixelDuration: Math.max(60, pixelDuration),
    randomness: clamp(randomness, 0, 1),
    bounce: clamp(bounce, 0, 1),
    onComplete,
  }

  const setLayer = (index: number, visible: boolean, clip: string) => {
    const layer = layerRefs.current[index]
    if (!layer) return
    layer.setAttribute('data-visible', String(visible))
    layer.style.clipPath = clip
  }

  const finish = () => {
    const engine = engineRef.current
    if (!engine) return
    engine.running = false
    rootRef.current?.removeAttribute('data-swapping')
    const target = engine.desired === 1
    setLayer(1, target, '')
    setLayer(0, !target, '')
    shownRef.current = target
    settingsRef.current.onComplete?.(target)
  }

  const frame = (now: number) => {
    const engine = engineRef.current
    if (!engine) return
    const s = settingsRef.current
    const grid = engine.grid
    const elapsed = clamp((now - engine.last) / 1000, 0, 1 / 20)
    engine.last = now
    const omega = (Math.PI * 2) / (s.pixelDuration / 1000)
    const zeta = clamp(1 - s.bounce, 0.15, 1)
    const steps = Math.max(1, Math.ceil(elapsed / STEP))
    const dt = elapsed / steps
    const pop = s.pixelScale
    const half = grid.size / 2
    const cover = grid.gap / 2 + 0.5
    let moving = false
    let path = ''

    for (let i = 0; i < grid.count; i += 1) {
      if (now >= engine.flipAt[i] && engine.target[i] !== engine.desired) {
        engine.target[i] = engine.desired
        if (engine.target[i] === 1 && engine.value[i] < pop) engine.value[i] = pop
      }
      let x = engine.value[i]
      let v = engine.velocity[i]
      const goal = engine.target[i]
      // 弹簧积分：向目标值收敛，bounce 控制过冲
      for (let n = 0; n < steps; n += 1) {
        v += (-omega * omega * (x - goal) - 2 * zeta * omega * v) * dt
        x += v * dt
      }
      if (goal === 0 && x <= pop) {
        x = 0
        v = 0
      }
      if (Math.abs(x - goal) > 0.001 || Math.abs(v) > 0.01 || now < engine.flipAt[i]) moving = true
      else {
        x = goal
        v = 0
      }
      engine.value[i] = x
      engine.velocity[i] = v
      if (x <= 0.002) continue
      const size = Math.min(x * 1.08, 1.25) * (half + cover * smoothstep(0.6, 0.93, x))
      const radius = Math.min(size, (s.pixelRadius / 50) * size * (1 - smoothstep(0.6, 0.93, x)))
      path += pixelPath(grid.cx[i], grid.cy[i], size, radius, s.pixelSpin * (1 - x))
    }

    setLayer(1, path !== '', path ? `path('${path}')` : '')
    if (moving) engine.raf = requestAnimationFrame(frame)
    else finish()
  }

  const launch = (to: boolean) => {
    const root = rootRef.current
    const s = settingsRef.current
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const width = root?.clientWidth ?? 0
    const height = root?.clientHeight ?? 0
    if (!root || reduce || !width || !height) return false
    const now = performance.now()
    let engine = engineRef.current
    if (!engine?.running) {
      const grid = buildGrid(width, height, s.pixelSize, s.gap)
      const start = shownRef.current ? 1 : 0
      engine = {
        grid,
        value: new Float32Array(grid.count).fill(start),
        velocity: new Float32Array(grid.count),
        flipAt: new Float64Array(grid.count),
        target: new Uint8Array(grid.count).fill(start),
        desired: start,
        last: now,
        running: true,
        raf: engineRef.current?.raf ?? 0,
      }
      engineRef.current = engine
    }
    engine.desired = to ? 1 : 0
    const grid = engine.grid
    const orders = new Float32Array(grid.count)
    let first = Infinity
    for (let i = 0; i < grid.count; i += 1) {
      orders[i] = orderOf(grid, i, pattern) * (1 - s.randomness) + hash(i) * s.randomness
      const settled = engine.target[i] === engine.desired && Math.abs(engine.value[i] - engine.desired) < 0.001
      if (!settled) first = Math.min(first, orders[i])
    }
    if (first === Infinity) first = 0
    for (let i = 0; i < grid.count; i += 1) {
      engine.flipAt[i] = now + Math.max(0, orders[i] - first) * s.duration
    }
    root.setAttribute('data-swapping', '')
    setLayer(0, true, '')
    cancelAnimationFrame(engine.raf)
    engine.raf = requestAnimationFrame(frame)
    return true
  }

  const request = (to: boolean) => {
    const engine = engineRef.current
    if (!engine?.running && shownRef.current === to) return
    if (launch(to)) return
    // 无法播放（如用户开启了减少动态效果）时直接切换
    setLayer(1, to, '')
    setLayer(0, !to, '')
    shownRef.current = to
    settingsRef.current.onComplete?.(to)
  }

  useEffect(() => {
    request(active)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  useEffect(
    () => () => {
      const engine = engineRef.current
      if (!engine) return
      cancelAnimationFrame(engine.raf)
      engine.running = false
    },
    []
  )

  return (
    <div ref={rootRef} className={['pixel-swap', className].filter(Boolean).join(' ')} style={style}>
      {[firstContent, secondContent].map((content, index) => (
        <div
          key={index}
          ref={(element) => {
            layerRefs.current[index] = element
          }}
          className="pixel-swap__layer"
          style={{ zIndex: index }}
          data-visible={String(index === 0)}
          aria-hidden={index !== 0}
        >
          {content}
        </div>
      ))}
    </div>
  )
}

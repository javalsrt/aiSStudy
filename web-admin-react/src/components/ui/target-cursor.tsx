import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type RefObject,
} from 'react'
import { createPortal } from 'react-dom'
import './target-cursor.css'

/* ============================================================
   目标光标（移植 React Bits Target Cursor）
   - 四角括线围绕指针旋转，命中可交互元素时吸附并匹配其圆角
   - 指针圆点 + 混合模式反色，浅色 / 深色界面都能看清
   - 仅精细指针设备启用，触屏与减少动效设置自动降级
   ============================================================ */

const CORNER_ANGLES = [0, 90, 180, 270]
const QUARTER = Math.PI / 2

interface SpringState {
  x: number
  v: number
}

const spring = (state: SpringState, goal: number, stiffness: number, damping: number, dt: number) => {
  const steps = 2
  const h = dt / steps
  for (let i = 0; i < steps; i += 1) {
    state.v += (stiffness * (goal - state.x) - damping * state.v) * h
    state.x += state.v * h
  }
}

const resolveElement = (
  value: RefObject<HTMLElement> | HTMLElement | null | undefined,
): HTMLElement | null => (value && 'current' in value ? value.current : value) || null

export interface TargetCursorProps {
  /** 需要吸附的可交互元素选择器 */
  targetSelector?: string
  cursorColor?: string
  /** 吸附到目标后的颜色（用于 color-mix 过渡） */
  cursorColorOnTarget?: string
  /** 空闲时括线旋转一圈的秒数，0 为不旋转 */
  spinDuration?: number
  /** 吸附 / 释放的过渡时长（秒） */
  hoverDuration?: number
  /** 吸附时指针轻微跟随目标中心的视差 */
  parallaxOn?: boolean
  /** 隐藏系统默认指针 */
  hideDefaultCursor?: boolean
  /** 空闲时括线方框的边长 */
  size?: number
  /** 单个角括线的长度 */
  cornerSize?: number
  /** 括线粗细 */
  thickness?: number
  /** 吸附时在目标外侧留出的间距 */
  padding?: number
  /** 吸附时匹配目标的圆角 */
  matchRadius?: boolean
  dotSize?: number
  blendMode?: CSSProperties['mixBlendMode']
  /** 读取 data-cursor-label 作为悬浮说明 */
  showLabel?: boolean
  clickEffect?: boolean
  /** 限制作用范围 */
  container?: RefObject<HTMLElement> | HTMLElement | null
}

export function TargetCursor({
  targetSelector = '.cursor-target',
  cursorColor = '#ffffff',
  cursorColorOnTarget,
  spinDuration = 2,
  hoverDuration = 0.2,
  parallaxOn = true,
  hideDefaultCursor = true,
  size = 36,
  cornerSize = 12,
  thickness = 3,
  padding = 6,
  matchRadius = true,
  dotSize = 4,
  blendMode = 'difference',
  showLabel = true,
  clickEffect = true,
  container,
}: TargetCursorProps) {
  const [host, setHost] = useState<HTMLElement | null>(null)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const cornerRefs = useRef<Array<HTMLSpanElement | null>>([])
  const dotRef = useRef<HTMLSpanElement | null>(null)
  const labelRef = useRef<HTMLSpanElement | null>(null)
  const wakeRef = useRef<(() => void) | null>(null)

  const settingsRef = useRef({
    targetSelector,
    cursorColor,
    cursorColorOnTarget,
    spinDuration,
    hoverDuration: Math.max(0.05, hoverDuration),
    parallaxOn,
    hideDefaultCursor,
    size: Math.max(4, size),
    cornerSize: Math.max(2, cornerSize),
    thickness: Math.max(0.5, thickness),
    padding,
    matchRadius,
    dotSize: Math.max(0, dotSize),
    showLabel,
    clickEffect,
    container,
  })
  settingsRef.current = {
    targetSelector,
    cursorColor,
    cursorColorOnTarget,
    spinDuration,
    hoverDuration: Math.max(0.05, hoverDuration),
    parallaxOn,
    hideDefaultCursor,
    size: Math.max(4, size),
    cornerSize: Math.max(2, cornerSize),
    thickness: Math.max(0.5, thickness),
    padding,
    matchRadius,
    dotSize: Math.max(0, dotSize),
    showLabel,
    clickEffect,
    container,
  }

  /* 只在支持悬停的精细指针设备挂载 */
  useEffect(() => {
    const finePointer = window.matchMedia('(hover: hover) and (pointer: fine)')
    const update = () => setHost(finePointer.matches ? document.body : null)
    update()
    finePointer.addEventListener('change', update)
    return () => finePointer.removeEventListener('change', update)
  }, [])

  useEffect(() => {
    const root = rootRef.current
    if (!host || !root) return undefined
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const style = document.createElement('style')
    document.head.appendChild(style)

    const pointer = { x: 0, y: 0, inside: false, seen: false, pressed: false }
    const centerX: SpringState = { x: 0, v: 0 }
    const centerY: SpringState = { x: 0, v: 0 }
    const halfW: SpringState = { x: 0, v: 0 }
    const halfH: SpringState = { x: 0, v: 0 }
    const radius: SpringState = { x: 0, v: 0 }
    const turn: SpringState = { x: 0, v: 0 }
    const lock: SpringState = { x: 0, v: 0 }
    const press: SpringState = { x: 0, v: 0 }
    const presence: SpringState = { x: 0, v: 0 }

    let target: Element | null = null
    let targetVisual: Element | null = null
    let targetRadius = 0
    let quarter = 0
    let spinSpeed = 0
    let raf = 0
    let last = 0
    let lastLabel = ''
    let lastColor = ''
    let lastScope: HTMLElement | null = null

    const scopeOf = () => resolveElement(settingsRef.current.container)

    const hideCursor = () => {
      const settings = settingsRef.current
      const scope = scopeOf()
      if (scope !== lastScope) {
        lastScope?.removeAttribute('data-target-cursor-scope')
        scope?.setAttribute('data-target-cursor-scope', '')
        lastScope = scope
      }
      const selector = scope ? '[data-target-cursor-scope]' : 'html'
      const rule =
        settings.hideDefaultCursor && pointer.inside
          ? `${selector}, ${selector} * { cursor: none !important; }`
          : ''
      if (style.textContent !== rule) style.textContent = rule
    }

    const findTarget = (x: number, y: number, element?: Element | null): Element | null => {
      const settings = settingsRef.current
      const node = element || document.elementFromPoint(x, y)
      const found = node instanceof Element ? node.closest(settings.targetSelector) : null
      const scope = scopeOf()
      if (found && scope && !scope.contains(found)) return null
      return found
    }

    /**
     * 命中区可能是 0×0 的按钮（如图谱节点），取第一个有实际面积的子元素作为视觉目标，
     * 让括线贴合真实圆盘 / 胶囊，而不是缩在中心一点。
     */
    const visualOf = (element: Element): Element => {
      const rect = element.getBoundingClientRect()
      if (rect.width > 2 && rect.height > 2) return element
      for (let index = 0; index < element.children.length; index += 1) {
        const child = element.children[index]
        const childRect = child.getBoundingClientRect()
        if (childRect.width > 2 && childRect.height > 2) return child
      }
      return element
    }

    const lockOn = (next: Element | null) => {
      if (next === target) return
      target = next
      targetVisual = target ? visualOf(target) : null
      if (target && targetVisual) {
        const angle = turn.x
        quarter = Math.round(angle / QUARTER)
        const corner = parseFloat(getComputedStyle(targetVisual).borderTopLeftRadius) || 0
        targetRadius = corner
        spinSpeed = 0
      }
    }

    const frame = (now: number) => {
      raf = 0
      const settings = settingsRef.current
      const dt = last ? Math.min(0.05, (now - last) / 1000) : 1 / 60
      last = now
      if (target && !target.isConnected) lockOn(null)
      if (target && (!targetVisual || !targetVisual.isConnected)) {
        targetVisual = visualOf(target)
      }
      const rect = targetVisual ? targetVisual.getBoundingClientRect() : null
      const locked = Boolean(rect) && pointer.inside
      const snapW = 4.6 / settings.hoverDuration
      const snapK = snapW * snapW
      const snapC = 2 * 0.62 * snapW

      spring(presence, pointer.inside ? 1 : 0, 260, 32, dt)
      spring(lock, locked ? 1 : 0, snapK, 2 * snapW, dt)
      spring(press, pointer.pressed && settings.clickEffect ? 1 : 0, 900, 40, dt)

      let goalX = pointer.x
      let goalY = pointer.y
      let goalW = settings.size / 2
      let goalH = settings.size / 2
      let goalR = Math.min(2, settings.cornerSize / 4)

      if (locked && rect) {
        const cx = rect.left + rect.width / 2
        const cy = rect.top + rect.height / 2
        const lean = settings.parallaxOn ? 0.08 : 0
        goalX = cx + (pointer.x - cx) * lean
        goalY = cy + (pointer.y - cy) * lean
        const odd = Math.abs(quarter) % 2 === 1
        const w = rect.width / 2 + settings.padding
        const h = rect.height / 2 + settings.padding
        goalW = odd ? h : w
        goalH = odd ? w : h
        goalR = settings.matchRadius
          ? Math.min(targetRadius + Math.max(0, settings.padding), settings.cornerSize)
          : 0
      }

      if (!pointer.inside && presence.x < 0.01) pointer.seen = false
      if (!pointer.seen) {
        centerX.x = goalX
        centerY.x = goalY
        halfW.x = goalW
        halfH.x = goalH
        pointer.seen = pointer.inside
      }

      const follow = locked ? snapK : 2200
      const followC = locked ? snapC : 2 * Math.sqrt(2200)
      spring(centerX, goalX, follow, followC, dt)
      spring(centerY, goalY, follow, followC, dt)
      spring(halfW, goalW, snapK, snapC, dt)
      spring(halfH, goalH, snapK, snapC, dt)
      spring(radius, goalR, snapK, 2 * snapW, dt)

      if (locked) {
        spring(turn, quarter * QUARTER, snapK, snapC, dt)
      } else {
        const speed =
          settings.spinDuration > 0 && !reduce ? (Math.PI * 2) / settings.spinDuration : 0
        spinSpeed += (speed - spinSpeed) * (1 - Math.exp(-dt * 3))
        turn.v = spinSpeed
        turn.x += spinSpeed * dt
      }

      const squeeze = 1 - press.x * 0.14
      const cos = Math.cos(turn.x)
      const sin = Math.sin(turn.x)
      const w = Math.max(0, halfW.x) * squeeze
      const h = Math.max(0, halfH.x) * squeeze
      const local = [
        [-w, -h],
        [w, -h],
        [w, h],
        [-w, h],
      ]
      const r = Math.max(0, Math.min(radius.x, settings.cornerSize))
      const degrees = (turn.x * 180) / Math.PI
      let left = Infinity
      let top = Infinity

      cornerRefs.current.forEach((corner, i) => {
        if (!corner) return
        const [lx, ly] = local[i]
        const x = centerX.x + lx * cos - ly * sin
        const y = centerY.x + lx * sin + ly * cos
        left = Math.min(left, x)
        top = Math.min(top, y)
        corner.style.transform = `translate3d(${x.toFixed(2)}px, ${y.toFixed(2)}px, 0) rotate(${(
          CORNER_ANGLES[i] + degrees
        ).toFixed(2)}deg)`
        corner.style.width = `${settings.cornerSize}px`
        corner.style.height = `${settings.cornerSize}px`
        corner.style.borderWidth = `${settings.thickness}px 0 0 ${settings.thickness}px`
        corner.style.borderTopLeftRadius = `${r.toFixed(2)}px`
      })

      const dot = dotRef.current
      if (dot) {
        const dotScale = 1 - press.x * 0.35
        dot.style.transform = `translate3d(${pointer.x}px, ${pointer.y}px, 0) translate(-50%, -50%) scale(${dotScale.toFixed(
          3,
        )})`
        dot.style.width = `${settings.dotSize}px`
        dot.style.height = `${settings.dotSize}px`
      }

      const label = labelRef.current
      if (label) {
        const text =
          settings.showLabel && target ? target.getAttribute('data-cursor-label') || '' : ''
        if (text && text !== lastLabel) label.textContent = text
        if (text) lastLabel = text
        const shown = text ? Math.max(0, Math.min(1, lock.x)) : 0
        label.style.transform = `translate3d(${left.toFixed(2)}px, ${(top - 8).toFixed(
          2,
        )}px, 0) translateY(-100%) translateY(${((1 - shown) * 4).toFixed(2)}px)`
        label.style.opacity = shown.toFixed(3)
      }

      const mix = settings.cursorColorOnTarget
        ? Math.round(Math.max(0, Math.min(1, lock.x)) * 100)
        : 0
      const color = mix
        ? `color-mix(in oklab, ${settings.cursorColorOnTarget} ${mix}%, ${settings.cursorColor})`
        : settings.cursorColor
      if (color !== lastColor) {
        root.style.setProperty('--target-cursor-color', color)
        lastColor = color
      }
      root.style.opacity = Math.max(0, Math.min(1, presence.x)).toFixed(3)

      const resting =
        !pointer.inside &&
        presence.x < 0.002 &&
        Math.abs(presence.v) < 0.002 &&
        Math.abs(lock.x) < 0.002 &&
        Math.abs(press.x) < 0.002
      const spinning = pointer.inside && !locked && spinSpeed > 0.0001
      const moving =
        Math.abs(centerX.v) +
          Math.abs(centerY.v) +
          Math.abs(halfW.v) +
          Math.abs(halfH.v) +
          Math.abs(turn.v) >
          0.01 ||
        Math.abs(goalX - centerX.x) + Math.abs(goalY - centerY.x) > 0.05 ||
        Math.abs(lock.v) + Math.abs(press.v) + Math.abs(presence.v) > 0.001
      if (!resting && (spinning || moving || locked)) raf = requestAnimationFrame(frame)
      else last = 0
    }

    const wake = () => {
      if (!raf) raf = requestAnimationFrame(frame)
    }
    wakeRef.current = () => {
      hideCursor()
      wake()
    }

    const insideScope = (x: number, y: number, element: Element | null) => {
      const scope = scopeOf()
      if (!scope) return true
      if (element && scope.contains(element)) return true
      const box = scope.getBoundingClientRect()
      return x >= box.left && x <= box.right && y >= box.top && y <= box.bottom
    }

    const onPointerMove = (event: PointerEvent) => {
      if (event.pointerType && event.pointerType !== 'mouse') return
      pointer.x = event.clientX
      pointer.y = event.clientY
      const inside = insideScope(pointer.x, pointer.y, event.target as Element | null)
      if (inside !== pointer.inside) {
        pointer.inside = inside
        hideCursor()
      }
      lockOn(inside ? findTarget(pointer.x, pointer.y, event.target as Element | null) : null)
      wake()
    }
    const onPointerDown = (event: PointerEvent) => {
      if (event.pointerType && event.pointerType !== 'mouse') return
      pointer.pressed = true
      wake()
    }
    const onPointerUp = () => {
      pointer.pressed = false
      wake()
    }
    const onLeave = () => {
      pointer.inside = false
      pointer.pressed = false
      lockOn(null)
      hideCursor()
      wake()
    }
    const onScroll = () => {
      if (!pointer.inside) return
      lockOn(findTarget(pointer.x, pointer.y))
      wake()
    }

    window.addEventListener('pointermove', onPointerMove, { passive: true })
    window.addEventListener('pointerdown', onPointerDown, { passive: true })
    window.addEventListener('pointerup', onPointerUp, { passive: true })
    window.addEventListener('scroll', onScroll, { passive: true, capture: true })
    document.documentElement.addEventListener('pointerleave', onLeave)
    window.addEventListener('blur', onLeave)

    return () => {
      cancelAnimationFrame(raf)
      wakeRef.current = null
      window.removeEventListener('pointermove', onPointerMove)
      window.removeEventListener('pointerdown', onPointerDown)
      window.removeEventListener('pointerup', onPointerUp)
      window.removeEventListener('scroll', onScroll, { capture: true })
      document.documentElement.removeEventListener('pointerleave', onLeave)
      window.removeEventListener('blur', onLeave)
      lastScope?.removeAttribute('data-target-cursor-scope')
      style.remove()
    }
  }, [host])

  useEffect(() => {
    wakeRef.current?.()
  })

  if (!host) return null

  return createPortal(
    <div
      ref={rootRef}
      className="target-cursor"
      style={{ mixBlendMode: blendMode, opacity: 0 }}
      aria-hidden="true"
    >
      {CORNER_ANGLES.map((angle, i) => (
        <span
          key={angle}
          ref={(element) => {
            cornerRefs.current[i] = element
          }}
          className="target-cursor__corner"
        />
      ))}
      <span ref={dotRef} className="target-cursor__dot" />
      <span ref={labelRef} className="target-cursor__label" />
    </div>,
    host,
  )
}

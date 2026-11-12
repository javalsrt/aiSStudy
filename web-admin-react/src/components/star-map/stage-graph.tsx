import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from 'react'
import {
  bezierAt,
  motionSkipped,
  paintDial,
  paintDot,
  paintNetwork,
  paintOrb,
  paintOrbit,
  paintPulse,
  paintRipple,
  paintThread,
  readStagePalette,
  spring,
  springOf,
  type NetworkData,
  type StagePalette,
  type SpringState,
} from './engine'
import { cn } from '@/lib/utils'
import './star-map.css'

/* ============================================================
   图谱舞台（React 版）：节点与连线由页面算好目标坐标，本组件负责「活起来」——
   - 节点以临界阻尼弹簧追随目标，层级切换即布局变形；新节点从 from 节点处长出
   - 连线是三次贝塞尔，带单向 / 往返脉冲
   - 悬停节点：一层邻居保持，其余降到 25%
   - 珍珠球（core）、玻璃刻度表盘（hub）、圆点（dot）与背景网络画在一张 Canvas 上，
     DOM 只保留可点的标签与命中区，右上角带「重播 / 暂停」
   ============================================================ */

export interface GraphNode {
  id: string
  x: number
  y: number
  label: string
  sub?: string
  /** core 中心珍珠球 / hub 玻璃刻度表盘 / item 胶囊 / dot 圆点 */
  kind?: 'core' | 'hub' | 'item' | 'dot'
  cat?: number
  tone?: 'danger' | 'off' | 'accent' | 'lime'
  icon?: ReactNode
  count?: number | string
  /** 锚点在节点的哪侧：left = 节点在锚点右边展开 */
  align?: 'center' | 'left' | 'right'
  from?: string
  delay?: number
  interactive?: boolean
  title?: string
  /** 常驻轻漂（能力岛游动），只对 core / hub 生效 */
  float?: boolean
  /** 珍珠球 / 刻度表盘的直径（px） */
  size?: number
  /** 表盘刻度的着色比例（0–1） */
  ratio?: number
  /** 密集布局：名称默认隐藏，悬停 / 聚焦时浮出 */
  compact?: boolean
}

export interface GraphLink {
  a: string
  b: string
  flow?: 'none' | 'forward' | 'both'
  cat?: number
  tone?: 'danger' | 'accent' | 'lime'
  shape?: 'arc' | 'h' | 'v'
  dashed?: boolean
}

interface NodeState {
  x: SpringState
  y: SpringState
  o: SpringState
  s: SpringState
  born: number
}

type GhostNode = GraphNode & { ghost: true }

interface StageGraphProps {
  nodes: GraphNode[]
  links: GraphLink[]
  activeId?: string | null
  highlight?: string[] | null
  network?: NetworkData | null
  networkFocus?: number | null
  networkAlpha?: number
  orbits?: Array<{ rx: number; ry: number }>
  burstKey?: number
  label?: string
  className?: string
  topLeft?: ReactNode
  topRight?: ReactNode
  hint?: ReactNode
  onSelect?: (node: GraphNode) => void
  onHover?: (id: string | null) => void
  onBack?: () => void
  onSize?: (size: { w: number; h: number }) => void
}

export function StageGraph({
  nodes,
  links,
  activeId = null,
  highlight = null,
  network = null,
  networkFocus = null,
  networkAlpha = 1,
  orbits = [],
  burstKey = 0,
  label = '关系星系',
  className,
  topLeft,
  topRight,
  hint,
  onSelect,
  onHover,
  onBack,
  onSize,
}: StageGraphProps) {
  const rootRef = useRef<HTMLDivElement | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const nodeEls = useRef(new Map<string, HTMLElement>())
  const states = useRef(new Map<string, NodeState>())
  const lastNodes = useRef(new Map<string, GraphNode>())
  const ghostsRef = useRef<GhostNode[]>([])
  const [ghosts, setGhosts] = useState<GhostNode[]>([])
  const [hoverId, setHoverId] = useState<string | null>(null)
  const hoverRef = useRef<string | null>(null)
  const [paused, setPaused] = useState(false)
  const pausedRef = useRef(false)
  const sizeRef = useRef({ w: 0, h: 0 })
  const palette = useRef<StagePalette | null>(null)
  const stageStart = useRef(performance.now())
  const burstAt = useRef(-1)
  const nodesRef = useRef(nodes)
  const linksRef = useRef(links)
  const networkRef = useRef(network)
  const networkFocusRef = useRef(networkFocus)
  const networkAlphaRef = useRef(networkAlpha)
  const orbitsRef = useRef(orbits)
  const activeRef = useRef(activeId)
  const highlightRef = useRef(highlight)
  const onHoverRef = useRef(onHover)
  const onBackRef = useRef(onBack)

  nodesRef.current = nodes
  linksRef.current = links
  networkRef.current = network
  networkFocusRef.current = networkFocus
  networkAlphaRef.current = networkAlpha
  orbitsRef.current = orbits
  activeRef.current = activeId
  highlightRef.current = highlight
  pausedRef.current = paused
  onHoverRef.current = onHover
  onBackRef.current = onBack

  const updateHover = (id: string | null) => {
    hoverRef.current = id
    setHoverId(id)
    onHoverRef.current?.(id)
  }

  const ensureState = useCallback((node: GraphNode, now: number) => {
    const existing = states.current.get(node.id)
    if (existing) return existing
    const origin = node.from ? states.current.get(node.from) : null
    const snap = motionSkipped()
    const sx = snap ? node.x : origin ? origin.x.x : node.x
    const sy = snap ? node.y : origin ? origin.y.x : node.y
    const state: NodeState = {
      x: springOf(sx),
      y: springOf(sy),
      o: springOf(snap ? 1 : 0),
      s: springOf(snap ? 1 : origin ? 0.4 : 0.82),
      born: now,
    }
    states.current.set(node.id, state)
    return state
  }, [])

  /* ── 节点变化：新增弹簧状态，移除的节点作为幽灵退回 from 节点后淡出 ── */
  useEffect(() => {
    const now = performance.now()
    const ids = new Set(nodes.map((n) => n.id))
    const leaving: GhostNode[] = []
    lastNodes.current.forEach((node, id) => {
      if (ids.has(id) || !states.current.has(id) || motionSkipped()) {
        if (!ids.has(id)) states.current.delete(id)
        return
      }
      const back = node.from && ids.has(node.from) ? nodes.find((n) => n.id === node.from) : null
      leaving.push({
        ...node,
        x: back?.x ?? node.x,
        y: back?.y ?? node.y,
        interactive: false,
        ghost: true,
      })
    })
    ghostsRef.current = [...ghostsRef.current.filter((g) => !ids.has(g.id)), ...leaving]
    setGhosts(ghostsRef.current)
    nodes.forEach((n) => ensureState(n, now))
    lastNodes.current = new Map(nodes.map((n) => [n.id, n]))
  }, [nodes, ensureState])

  /* ── 帧循环：弹簧、DOM 定位、Canvas 绘制 ── */
  useEffect(() => {
    const root = rootRef.current
    if (!root) return
    const nodeMap = nodeEls.current
    const stateMap = states.current
    let raf = 0
    let last = 0
    let time = 0
    let frames = 0
    let offscreen = false
    let warned = false

    const geometry = (l: GraphLink, t: number, live: boolean) => {
      const A = stateMap.get(l.a)
      const B = stateMap.get(l.b)
      if (!A || !B) return null
      const x1 = A.x.x
      const y1 = A.y.x
      const x2 = B.x.x
      const y2 = B.y.x
      const dx = x2 - x1
      const dy = y2 - y1
      if (l.shape === 'h') {
        const sway = live ? Math.sin(t * 1.1 + x2 * 0.013 + y2 * 0.017) * 6 : 0
        return { x1, y1, c1x: x1 + dx * 0.5, c1y: y1 + sway, c2x: x2 - dx * 0.45, c2y: y2 - sway * 0.5, x2, y2 }
      }
      if (l.shape === 'v') {
        return { x1, y1, c1x: x1, c1y: y1 + dy * 0.5, c2x: x2, c2y: y2 - dy * 0.5, x2, y2 }
      }
      const d = Math.hypot(dx, dy) || 1
      const bend = (live ? 14 * Math.sin(t * 0.5 + (x1 + y2) * 0.01) : 0) + d * 0.08
      const ux = dx / d
      const uy = dy / d
      return {
        x1,
        y1,
        c1x: x1 + dx * 0.35 - uy * bend,
        c1y: y1 + dy * 0.35 + ux * bend,
        c2x: x1 + dx * 0.7 - uy * bend,
        c2y: y1 + dy * 0.7 + ux * bend,
        x2,
        y2,
      }
    }

    const drawStage = (t: number, live: boolean, lit: Set<string> | null) => {
      const canvas = canvasRef.current
      const { w, h } = sizeRef.current
      if (!canvas || !w || !h) return
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
        canvas.width = Math.round(w * dpr)
        canvas.height = Math.round(h * dpr)
      }
      const ctx = canvas.getContext('2d')
      if (!ctx) return
      if (!palette.current && rootRef.current) palette.current = readStagePalette(rootRef.current)
      const p = palette.current
      if (!p) return
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
      ctx.clearRect(0, 0, w, h)
      const now = performance.now()
      const all = [...nodesRef.current, ...ghostsRef.current]
      const isDim = (id: string) => Boolean(lit && !lit.has(id))

      if (orbitsRef.current.length) {
        const core = nodesRef.current.find((n) => n.kind === 'core')
        const st = core ? stateMap.get(core.id) : null
        if (st) {
          orbitsRef.current.forEach((o) =>
            paintOrbit(ctx, st.x.x, st.y.x, o.rx, o.ry, t, p, Math.min(1, st.o.x)),
          )
        }
      }
      if (networkRef.current) {
        paintNetwork(ctx, networkRef.current, {
          t,
          since: (now - stageStart.current) / 1000,
          live,
          palette: p,
          focusCat: networkFocusRef.current,
          alpha: networkAlphaRef.current,
          labels: true,
        })
      }
      const colorOf = (cat?: number, tone?: string) =>
        tone === 'danger' ? p.danger : tone === 'lime' ? p.lime : cat ? p.cat[cat] : p.accent

      // 连线与脉冲
      linksRef.current.forEach((l, i) => {
        const g = geometry(l, t, live)
        const A = stateMap.get(l.a)
        const B = stateMap.get(l.b)
        if (!g || !A || !B) return
        const vis = Math.max(0, Math.min(A.o.x, B.o.x, 1))
        if (vis < 0.01) return
        const on = !lit || (lit.has(l.a) && lit.has(l.b))
        const active = Boolean(activeRef.current && (l.a === activeRef.current || l.b === activeRef.current))
        const c = colorOf(l.cat, l.tone)
        const a = vis * (on ? (lit || active ? 0.85 : 0.42) : 0.1)
        paintThread(ctx, g, c, a, on && (lit || active) ? 1.6 : 1.1, l.dashed)
        if (!live || vis < 0.5 || !l.flow || l.flow === 'none') return
        const lanes = l.flow === 'both' ? 2 : 1
        for (let k = 0; k < lanes; k += 1) {
          let u = (t * (k ? 0.24 : 0.3) + i * 0.137 + k * 0.5) % 1
          if (k === 1) u = 1 - u
          paintPulse(
            ctx,
            bezierAt(g.x1, g.c1x, g.c2x, g.x2, u),
            bezierAt(g.y1, g.c1y, g.c2y, g.y2, u),
            k ? p.lime : c,
            Math.sin(u * Math.PI) * vis * (on ? 1 : 0.2),
            3,
            p.paper,
          )
        }
      })

      // 珍珠球 / 刻度表盘 / 圆点
      all.forEach((node) => {
        const st = stateMap.get(node.id)
        if (!st) return
        const o = Math.max(0, Math.min(1, st.o.x))
        if (o < 0.01) return
        const dim = isDim(node.id)
        const hot = node.id === hoverRef.current || node.id === activeRef.current
        const x = st.x.x
        const y = st.y.x
        if (node.kind === 'core') {
          paintOrb(ctx, x, y, ((node.size ?? 96) / 2) * st.s.x, t, p, {
            alpha: o * (dim ? 0.45 : 1),
            live,
          })
        } else if (node.kind === 'hub') {
          paintDial(ctx, x, y, (node.size ?? 104) * st.s.x, t, colorOf(node.cat, node.tone), p, {
            alpha: o * (dim ? 0.35 : node.tone === 'off' ? 0.55 : 1),
            ratio: node.ratio,
            live,
            active: hot,
          })
        } else if (node.kind === 'dot') {
          paintDot(ctx, x, y, hot ? 4.6 : 3.6, colorOf(node.cat, node.tone), o * (dim ? 0.25 : 1), p, hot)
        }
      })

      // 开场涟漪
      if (burstAt.current >= 0) {
        const k = (now - burstAt.current) / 1800
        if (k >= 1 || !live) {
          burstAt.current = -1
        } else {
          const core = nodesRef.current.find((n) => n.kind === 'core')
          const st = core ? stateMap.get(core.id) : null
          paintRipple(ctx, st?.x.x ?? w / 2, st?.y.x ?? h / 2, k, Math.min(w, h) * 0.42, p)
        }
      }
    }

    const step = (dt: number, t: number) => {
      const now = performance.now()
      const snap = motionSkipped()
      const live = !snap && !pausedRef.current
      const all = [...nodesRef.current, ...ghostsRef.current]
      // 高亮集合：悬停优先，其次页面传入的链路
      let lit: Set<string> | null = null
      if (hoverRef.current) {
        const set = new Set<string>([hoverRef.current])
        linksRef.current.forEach((l) => {
          if (l.a === hoverRef.current) set.add(l.b)
          if (l.b === hoverRef.current) set.add(l.a)
        })
        lit = set
      } else if (highlightRef.current?.length) {
        lit = new Set(highlightRef.current)
      }

      all.forEach((node, i) => {
        const st = stateMap.get(node.id)
        if (!st) return
        const ghost = 'ghost' in node
        const ready = snap || now - st.born >= (node.delay ?? 0) * 1000
        const drifts = node.float && live && (node.kind === 'hub' || node.kind === 'core')
        const drift = drifts
          ? { x: Math.sin(t * 0.37 + i * 1.7) * 7, y: Math.sin(t * 0.9 + i * 2.1) * 6 }
          : { x: 0, y: 0 }
        const tx = ready || ghost ? node.x + drift.x : st.x.x
        const ty = ready || ghost ? node.y + drift.y : st.y.x
        if (snap) {
          st.x.x = tx
          st.y.x = ty
          st.o.x = ghost ? 0 : 1
          st.s.x = 1
        } else {
          spring(st.x, tx, dt, 70)
          spring(st.y, ty, dt, 70)
          spring(st.o, ghost ? 0 : ready ? 1 : 0, dt, ghost ? 120 : 50)
          spring(st.s, ghost ? 0.5 : ready ? 1 : st.s.x, dt, 60)
        }
        const el = nodeMap.get(node.id)
        if (el) {
          const o = Math.max(0, Math.min(1, st.o.x))
          el.style.transform = `translate3d(${st.x.x}px, ${st.y.x}px, 0) scale(${st.s.x})`
          el.style.opacity = String(o)
          el.style.visibility = o < 0.01 ? 'hidden' : ''
        }
      })

      // 幽灵退场完毕即移除
      if (ghostsRef.current.length && ghostsRef.current.every((g) => (stateMap.get(g.id)?.o.x ?? 0) < 0.02)) {
        ghostsRef.current.forEach((g) => stateMap.delete(g.id))
        ghostsRef.current = []
        setGhosts([])
      }

      drawStage(t, live, lit)
    }

    const tick = (now: number) => {
      raf = requestAnimationFrame(tick)
      if (document.hidden) {
        last = 0
        return
      }
      if (frames++ % 20 === 0) {
        const r = root.getBoundingClientRect()
        offscreen = r.width === 0 || r.bottom < 0 || r.top > window.innerHeight
      }
      if (offscreen) {
        last = 0
        return
      }
      const dt = last ? Math.max(0, Math.min(1 / 30, (now - last) / 1000)) : 1 / 60
      last = now
      time += dt
      try {
        step(dt, time)
      } catch (error) {
        if (!warned) {
          warned = true
          console.error(error)
        }
      }
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [])

  /* ── 尺寸回传 ── */
  useEffect(() => {
    const root = rootRef.current
    if (!root) return
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect
      if (Math.abs(width - sizeRef.current.w) > 0.5 || Math.abs(height - sizeRef.current.h) > 0.5) {
        sizeRef.current = { w: width, h: height }
        onSize?.({ w: width, h: height })
      }
    })
    observer.observe(root)
    return () => observer.disconnect()
  }, [onSize])

  /* ── 主题切换后重新取绘制色 ── */
  useEffect(() => {
    const observer = new MutationObserver(() => {
      palette.current = null
    })
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme'] })
    return () => observer.disconnect()
  }, [])

  /* ── 开场 / 重播 ── */
  useEffect(() => {
    stageStart.current = performance.now()
    burstAt.current = performance.now()
  }, [])

  useEffect(() => {
    burstAt.current = performance.now()
  }, [burstKey])

  const replay = () => {
    states.current.clear()
    ghostsRef.current = []
    setGhosts([])
    const now = performance.now()
    nodesRef.current.forEach((n) => ensureState(n, now))
    stageStart.current = now
    burstAt.current = now
    setPaused(false)
  }

  const nodeById = new Map([...nodes, ...ghosts].map((n) => [n.id, n]))

  const isDim = (id: string) => {
    if (hoverId) {
      if (id === hoverId) return false
      return !links.some(
        (l) => (l.a === hoverId && l.b === id) || (l.b === hoverId && l.a === id),
      )
    }
    return Boolean(highlight?.length && !highlight.includes(id))
  }

  const onKey = (event: ReactKeyboardEvent, node: GraphNode) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      onBackRef.current?.()
      return
    }
    const dir = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[
      event.key
    ]
    if (!dir) return
    event.preventDefault()
    let best: GraphNode | null = null
    let bestScore = Infinity
    nodesRef.current.forEach((other) => {
      if (other.id === node.id || other.interactive === false) return
      const dx = other.x - node.x
      const dy = other.y - node.y
      const along = dx * dir[0] + dy * dir[1]
      if (along <= 4) return
      const across = Math.abs(dx * dir[1]) + Math.abs(dy * dir[0])
      const score = along + across * 2.2
      if (score < bestScore) {
        bestScore = score
        best = other
      }
    })
    if (best) nodeEls.current.get((best as GraphNode).id)?.focus()
  }

  const nodeClass = (n: GraphNode) =>
    cn(
      'sm-node',
      `is-${n.kind ?? 'item'}`,
      `align-${n.align ?? 'center'}`,
      n.cat ? `cat-${n.cat}` : '',
      n.tone ? `tone-${n.tone}` : '',
      {
        'is-active': n.id === activeId,
        'is-dim': isDim(n.id),
        'is-static': n.interactive === false,
        'is-ghost': 'ghost' in n,
        'is-compact': n.compact,
      },
    )

  return (
    <div
      ref={rootRef}
      className={cn('sm-stage', className)}
      role="group"
      aria-label={label}
      tabIndex={-1}
    >
      <canvas ref={canvasRef} className="sm-canvas" aria-hidden="true" />
      <div className="sm-layer">
        {[...nodes, ...ghosts].map((n) => (
          <button
            key={n.id}
            ref={(el) => {
              if (el) nodeEls.current.set(n.id, el)
              else nodeEls.current.delete(n.id)
            }}
            type="button"
            className={nodeClass(n)}
            style={n.size ? ({ '--size': `${n.size}px` } as CSSProperties) : undefined}
            aria-pressed={activeId === n.id}
            aria-label={[n.label, n.sub, n.count !== undefined ? String(n.count) : '']
              .filter(Boolean)
              .join('，')}
            title={n.title ?? (n.kind === 'dot' ? n.label : undefined)}
            tabIndex={n.interactive === false || 'ghost' in n ? -1 : 0}
            onClick={() => {
              if (n.interactive === false || 'ghost' in n) return
              onSelect?.(nodeById.get(n.id) as GraphNode)
            }}
            onPointerEnter={() => {
              if (n.interactive === false || 'ghost' in n) return
              updateHover(n.id)
            }}
            onPointerLeave={() => updateHover(null)}
            onFocus={() => updateHover(n.id)}
            onBlur={() => updateHover(null)}
            onKeyDown={(event) => onKey(event, n)}
          >
            <span className="sm-body">
              {n.kind !== 'core' && <span className="sm-dot">{n.icon}</span>}
              {n.kind === 'hub' && n.count !== undefined && <span className="sm-count">{n.count}</span>}
              <span className="sm-text">
                <span className="sm-label">{n.label}</span>
                {n.sub && <span className="sm-sub">{n.sub}</span>}
              </span>
              {n.kind !== 'hub' && n.kind !== 'core' && n.count !== undefined && (
                <span className="sm-badge">{n.count}</span>
              )}
            </span>
          </button>
        ))}
      </div>

      {topLeft && <div className="sm-corner is-tl">{topLeft}</div>}
      <div className="sm-corner is-tr">
        {topRight}
        <div className="sm-tools" role="toolbar" aria-label="动效控制">
          <button type="button" title="重播开场" onClick={replay}>
            重播
          </button>
          <button
            type="button"
            aria-pressed={paused}
            title={paused ? '继续动效' : '暂停动效'}
            onClick={() => setPaused((value) => !value)}
          >
            {paused ? '继续' : '暂停'}
          </button>
        </div>
      </div>
      {hint && <p className="sm-hint">{hint}</p>}
    </div>
  )
}

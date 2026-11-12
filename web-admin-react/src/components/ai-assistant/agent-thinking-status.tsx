import { useEffect, useState } from 'react'

const STAR_PATH =
  'M12 0C13 7 17 11 24 12C17 13 13 17 12 24C11 17 7 13 0 12C7 11 11 7 12 0Z'

/** 五颗星的位置与相对大小，与 Android 端 AgentStarsView 一致 */
const STARS = [
  { x: 50, y: 46, scale: 1 },
  { x: 18, y: 22, scale: 0.55 },
  { x: 82, y: 26, scale: 0.45 },
  { x: 78, y: 76, scale: 0.55 },
  { x: 22, y: 78, scale: 0.4 },
]

const BASE_SIZE = 14

function phaseOf(elapsed: number) {
  if (elapsed < 1100) {
    return { title: '正在理解问题', detail: '分析问题意图，判断需要哪些校园数据', percent: 15 }
  }
  if (elapsed < 3000) {
    return { title: '正在检索校园资料', detail: '查询校内规定、课程资料与个人成绩', percent: 45 }
  }
  return { title: '正在生成回答', detail: '组织答案结构，准备输出', percent: 76 }
}

/**
 * AI 助手思考状态：对齐 Android 端的星星动画 + 左下角灰色小字。
 * 只作为等待提示，不写入消息列表。
 */
export function AgentThinkingStatus() {
  const [elapsed, setElapsed] = useState(0)

  useEffect(() => {
    const startAt = Date.now()
    const timer = window.setInterval(() => setElapsed(Date.now() - startAt), 160)
    return () => window.clearInterval(timer)
  }, [])

  const phase = phaseOf(elapsed)
  const seconds = (elapsed / 1000).toFixed(1)

  return (
    <div className="flex items-center gap-2 px-4 pb-1 pt-0.5" role="status" aria-live="polite">
      <div className="relative h-5 w-5 shrink-0" aria-hidden="true">
        {STARS.map((star, index) => {
          const size = BASE_SIZE * star.scale
          return (
            <svg
              key={index}
              viewBox="0 0 24 24"
              fill="currentColor"
              className="agent-star absolute text-[#9FA9BC]"
              style={{
                width: size,
                height: size,
                left: `${star.x}%`,
                top: `${star.y}%`,
                marginLeft: -size / 2,
                marginTop: -size / 2,
                animationDelay: `${index * 196}ms`,
              }}
            >
              <path d={STAR_PATH} />
            </svg>
          )
        })}
      </div>
      <div className="min-w-0">
        <div className="text-[11px] leading-4 text-[#B7BCC6]">{phase.title}</div>
        <div className="mt-0.5 truncate text-[11px] leading-4 text-[#98A0AE]">
          · {phase.detail}… {phase.percent}% · {seconds}s
        </div>
      </div>
    </div>
  )
}

import { useEffect, useRef } from 'react'
import katex from 'katex'
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore katex auto-render 无官方类型声明
import renderMathInElement from 'katex/contrib/auto-render'
import 'katex/dist/katex.min.css'

/** LaTeX 数学定界符：$$...$$ 独立公式，$...$ 行内公式 */
const MATH_SPLIT = /(\$\$[\s\S]+?\$\$|\$[^$\n]+?\$)/g

/**
 * 纯文本渲染：支持 LaTeX 数学公式（$...$ 行内 / $$...$$ 独立块）。
 * 用于 AI 回复、题目内容等纯文本场景。
 */
export function MathText({ text, className }: { text: string; className?: string }) {
  if (!text || !text.includes('$')) {
    return <span className={className}>{text}</span>
  }
  const parts = text.split(MATH_SPLIT).filter((p) => p !== '')
  return (
    <span className={className}>
      {parts.map((part, i) => {
        if (part.startsWith('$$') && part.endsWith('$$') && part.length > 4) {
          const html = katex.renderToString(part.slice(2, -2), {
            displayMode: true,
            throwOnError: false,
          })
          return <span key={i} className="block my-2 overflow-x-auto" dangerouslySetInnerHTML={{ __html: html }} />
        }
        if (part.startsWith('$') && part.endsWith('$') && part.length > 2) {
          const html = katex.renderToString(part.slice(1, -1), {
            displayMode: false,
            throwOnError: false,
          })
          return <span key={i} dangerouslySetInnerHTML={{ __html: html }} />
        }
        return <span key={i}>{part}</span>
      })}
    </span>
  )
}

/**
 * HTML 渲染 + LaTeX 公式二次渲染。
 * 用于后端返回 HTML 格式题干（答题报告、学情统计等），HTML 渲染后对其中的 $...$/$$...$$ 做 KaTeX 排版。
 */
export function HtmlMath({ html, className }: { html: string; className?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (ref.current && html && html.includes('$')) {
      renderMathInElement(ref.current, {
        delimiters: [
          { left: '$$', right: '$$', display: true },
          { left: '$', right: '$', display: false },
        ],
        throwOnError: false,
      })
    }
  }, [html])
  return <div ref={ref} className={className} dangerouslySetInnerHTML={{ __html: html || '' }} />
}

import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'

/** 动态加载 mermaid.js（CDN），渲染流程图/结构图为 SVG */
function MermaidView({ code }: { code: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    const render = () => {
      const m = (window as unknown as { mermaid?: { initialize: Function; render: Function } }).mermaid
      if (!m) return
      m.initialize({ startOnLoad: false, theme: 'neutral' })
      const id = 'mmd-' + Math.random().toString(36).slice(2)
      m.render(id, code)
        .then(({ svg }: { svg: string }) => {
          if (!cancelled && ref.current) ref.current.innerHTML = svg
        })
        .catch((e: unknown) => {
          if (!cancelled) setError(e instanceof Error ? e.message : String(e))
        })
    }
    const w = window as unknown as { mermaid?: object }
    if (!w.mermaid) {
      const s = document.createElement('script')
      s.src = 'https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js'
      s.onload = render
      s.onerror = () => !cancelled && setError('Mermaid 脚本加载失败')
      document.head.appendChild(s)
    } else {
      render()
    }
    return () => {
      cancelled = true
    }
  }, [code])

  if (error) {
    return (
      <div className="my-3 rounded-lg border border-red-100 bg-red-50 p-3 text-xs text-red-500 whitespace-pre-wrap">
        {code}
      </div>
    )
  }
  return <div ref={ref} className="my-3 overflow-x-auto rounded-lg border border-neutral-100 bg-white p-3" />
}

/**
 * 管理端富内容渲染：Markdown（标题/列表/加粗）+ GFM 表格 + LaTeX 公式 + Mermaid 图 + 代码高亮底色。
 * 用于课时内容查看、章节内容预览等场景。
 */
export function MarkdownView({ content, className }: { content: string; className?: string }) {
  return (
    <div className={className}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeKatex]}
        components={{
          pre: ({ children }) => <>{children}</>,
          code({ className: cls, children, ...props }) {
            const match = /language-(\w+)/.exec(cls || '')
            if (match && match[1] === 'mermaid') {
              return <MermaidView code={String(children).replace(/\n$/, '')} />
            }
            return (
              <code
                className={cls || ''}
                style={
                  match
                    ? { display: 'block', background: '#1E293B', color: '#E2E8F0', padding: '12px',
                        borderRadius: 8, overflowX: 'auto', fontSize: 13, fontFamily: 'monospace' }
                    : { background: '#E5F1FF', color: '#0A84FF', padding: '1px 5px',
                        borderRadius: 4, fontSize: '0.9em' }
                }
                {...props}
              >
                {children}
              </code>
            )
          },
          table({ children }) {
            return (
              <div className="my-3 overflow-x-auto">
                <table className="w-full border-collapse text-sm">{children}</table>
              </div>
            )
          },
          th({ children }) {
            return <th className="border border-neutral-200 bg-neutral-100 px-3 py-2 text-left font-medium">{children}</th>
          },
          td({ children }) {
            return <td className="border border-neutral-200 px-3 py-2 align-top">{children}</td>
          },
          blockquote({ children }) {
            return (
              <blockquote className="my-3 border-l-4 border-primary-500 bg-primary-50/50 px-4 py-2 rounded-r-lg text-sm">
                {children}
              </blockquote>
            )
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}

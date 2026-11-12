import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { Bot, Loader2, RotateCcw, Send, Sparkles, X } from 'lucide-react'
import { chatWithAssistant, confirmAssistantAction, getAssistantMeta } from '@/api/assistant'
import { MarkdownView } from '@/components/markdown-view'
import { SegmentedControl } from '@/components/ui/segmented-control'
import { useAuthStore } from '@/store/auth'
import { cn } from '@/lib/utils'
import type {
  AssistantActionCard,
  AssistantMessage,
  AssistantMeta,
  AssistantMode,
  AssistantReply,
} from '@/types/assistant'
import { ActionCard } from './action-card'
import { WelcomeView } from './welcome-view'

const MODE_KEY = 'ai-assistant-mode'

function uid() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function createSessionId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID().replace(/-/g, '')
  }
  return `${Date.now()}${Math.random().toString(36).slice(2, 10)}`
}

function loadMessages(key: string): AssistantMessage[] {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function fallbackMeta(role: 'admin' | 'teacher'): AssistantMeta {
  const admin = role === 'admin'
  return {
    greeting: '你好，我是智学 AI 助手',
    subtitle: admin
      ? '可以直接告诉我：查学生、改信息、看课表、审批请假'
      : '可以帮你查任教班级、管理学生、看课表、审批请假',
    role,
    capabilities: [
      { key: 'student', title: '学生管理', desc: '查询、新增、修改、删除' },
      { key: 'class', title: '班级管理', desc: admin ? '班级查询与维护' : '查看任教班级' },
      { key: 'course', title: '课程与课表', desc: admin ? '课程维护、课表查询' : '课程与班级课表' },
      { key: 'exam', title: '考试作业', desc: '查询、上下架、删除' },
      { key: 'leave', title: '请假审批', desc: '查询待办、通过或驳回' },
      { key: 'stats', title: '数据统计', desc: '学生、班级、课程概览' },
    ],
    agentSuggestions: admin
      ? ['现在有多少学生和教师？', '查一下24级计算机应用1班的学生', '列出待审批的请假']
      : ['我教哪些班级？', '查一下我班级的学生', '列出我班级待审批的请假'],
    chatSuggestions: ['怎么批量导入学生？', '怎么发布考试或作业？', '怎么审批学生请假？'],
  }
}

function errorMessage(error: unknown) {
  const e = error as {
    response?: { data?: { error?: string; message?: string } }
    message?: string
  }
  return e?.response?.data?.error || e?.response?.data?.message || e?.message || '请求失败，请稍后再试'
}

function toAssistantMessage(reply: AssistantReply, mode: AssistantMode): AssistantMessage {
  return {
    id: uid(),
    role: 'assistant',
    content: reply.content || '',
    action: reply.action,
    suggestions: reply.suggestions,
    mode,
  }
}

interface AssistantPanelProps {
  onClose: () => void
}

export function AssistantPanel({ onClose }: AssistantPanelProps) {
  const user = useAuthStore((state) => state.user)
  const role: 'admin' | 'teacher' = user?.role === 'admin' ? 'admin' : 'teacher'
  const storageKey = `ai-assistant-messages-${user?.id ?? 'anon'}`
  const sessionKey = `ai-assistant-session-${user?.id ?? 'anon'}`

  const [messages, setMessages] = useState<AssistantMessage[]>(() => loadMessages(storageKey))
  const [sessionId, setSessionId] = useState(() => {
    const existing = sessionStorage.getItem(sessionKey)
    if (existing) return existing
    const id = createSessionId()
    sessionStorage.setItem(sessionKey, id)
    return id
  })
  const [mode, setMode] = useState<AssistantMode>(() =>
    localStorage.getItem(MODE_KEY) === 'chat' ? 'chat' : 'agent'
  )
  const [meta, setMeta] = useState<AssistantMeta>(() => fallbackMeta(role))
  const [metaLoading, setMetaLoading] = useState(true)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [confirmingToken, setConfirmingToken] = useState<string | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    let cancelled = false
    setMetaLoading(true)
    getAssistantMeta()
      .then((data) => {
        if (!cancelled) setMeta(data)
      })
      .catch(() => {
        if (!cancelled) setMeta(fallbackMeta(role))
      })
      .finally(() => {
        if (!cancelled) setMetaLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [role])

  useEffect(() => {
    localStorage.setItem(storageKey, JSON.stringify(messages.slice(-30)))
  }, [messages, storageKey])

  useEffect(() => {
    localStorage.setItem(MODE_KEY, mode)
  }, [mode])

  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages, sending])

  useEffect(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`
  }, [input])

  const pendingAction = useMemo<AssistantActionCard | undefined>(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const action = messages[i].action
      if (action?.status === 'pending_confirm' && action.confirmToken) return action
    }
    return undefined
  }, [messages])

  const sendMessage = async (text?: string) => {
    const content = (text ?? input).trim()
    if (!content || sending || pendingAction) return
    const userMessage: AssistantMessage = {
      id: uid(),
      role: 'user',
      content,
      mode,
    }
    setMessages((prev) => [...prev, userMessage])
    setInput('')
    setSending(true)
    try {
      const reply = await chatWithAssistant({ sessionId, mode, message: content })
      setMessages((prev) => [...prev, toAssistantMessage(reply, mode)])
    } catch (error) {
      setMessages((prev) => [
        ...prev,
        {
          id: uid(),
          role: 'assistant',
          content: errorMessage(error),
          mode,
          error: true,
        },
      ])
    } finally {
      setSending(false)
      textareaRef.current?.focus()
    }
  }

  const handleConfirm = async (token: string, approved: boolean) => {
    if (confirmingToken) return
    setConfirmingToken(token)
    try {
      const reply = await confirmAssistantAction({ sessionId, token, approved })
      const nextStatus: AssistantActionCard['status'] = approved ? 'success' : 'canceled'
      setMessages((prev) => {
        const updated = prev.map((message) => {
          if (message.action?.confirmToken !== token) return message
          return {
            ...message,
            action: {
              ...message.action,
              status: nextStatus,
              confirmToken: undefined,
              summary: approved ? message.action.summary : '已取消，本次没有修改任何数据。',
            },
          }
        })
        return [...updated, toAssistantMessage(reply, 'agent')]
      })
    } catch (error) {
      setMessages((prev) => [
        ...prev,
        {
          id: uid(),
          role: 'assistant',
          content: errorMessage(error),
          mode: 'agent',
          error: true,
        },
      ])
    } finally {
      setConfirmingToken(null)
    }
  }

  const handleSuggestion = (text: string) => {
    if (pendingAction && text.includes('确认')) {
      handleConfirm(pendingAction.confirmToken!, true)
      return
    }
    if (pendingAction && text.includes('取消')) {
      handleConfirm(pendingAction.confirmToken!, false)
      return
    }
    sendMessage(text)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      sendMessage()
    }
  }

  const newChat = () => {
    const id = createSessionId()
    sessionStorage.setItem(sessionKey, id)
    setSessionId(id)
    setMessages([])
    setInput('')
    localStorage.removeItem(storageKey)
    textareaRef.current?.focus()
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* 头部 */}
      <header className="flex h-14 shrink-0 items-center gap-2 border-b border-line-separator px-3">
        <div className="relative flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary-500 to-blue-500 text-white">
          <Bot className="h-4 w-4" />
          <span className="absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full bg-emerald-400 ring-2 ring-white" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="text-body-2-medium text-ink-primary">智学 AI 助手</div>
          <div className="truncate text-[11px] text-ink-tertiary">
            {role === 'admin' ? '管理员模式' : '教师模式'} ·{' '}
            {mode === 'agent' ? '可查询和修改数据' : '仅解答使用问题'}
          </div>
        </div>
        <button
          type="button"
          aria-label="新建对话"
          title="新建对话"
          onClick={newChat}
          className="flex h-7 w-7 items-center justify-center rounded-lg text-ink-tertiary transition-colors hover:bg-neutral-100 hover:text-ink-primary"
        >
          <RotateCcw className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          aria-label="关闭助手"
          title="关闭"
          onClick={onClose}
          className="flex h-7 w-7 items-center justify-center rounded-lg text-ink-tertiary transition-colors hover:bg-neutral-100 hover:text-ink-primary"
        >
          <X className="h-4 w-4" />
        </button>
      </header>

      {/* 消息区 */}
      <div ref={scrollRef} className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">
        {messages.length === 0 ? (
          metaLoading ? (
            <div className="flex h-full items-center justify-center text-caption-1-regular text-ink-tertiary">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              正在加载助手能力…
            </div>
          ) : (
            <WelcomeView meta={meta} mode={mode} onSuggestion={handleSuggestion} />
          )
        ) : (
          <div className="space-y-4 px-3 py-4">
            {messages.map((message) => (
              <MessageItem
                key={message.id}
                message={message}
                confirming={confirmingToken === message.action?.confirmToken}
                onConfirm={handleConfirm}
                onSuggestion={handleSuggestion}
                pending={Boolean(pendingAction)}
              />
            ))}
            {sending && <TypingIndicator mode={mode} />}
          </div>
        )}
      </div>

      {/* 输入区 */}
      <footer className="shrink-0 border-t border-line-separator p-3">
        {pendingAction && (
          <div className="mb-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-1.5 text-[11px] text-amber-700">
            有一个操作等待确认，请先确认或取消后再继续对话
          </div>
        )}
        <div className="rounded-2xl border border-line-separator bg-surface-primary transition-shadow focus-within:shadow-[0_0_0_1px_#3b82f6,0_0_0_4px_rgba(59,130,246,0.12)]">
          <div className="flex items-center justify-between px-2.5 pt-2">
            <SegmentedControl<AssistantMode>
              value={mode}
              onChange={(value) => {
                if (sending) return
                setMode(value)
              }}
              options={[
                { value: 'agent', label: 'Agent' },
                { value: 'chat', label: 'Chat' },
              ]}
              size="sm"
              ariaLabel="助手模式"
            />
            <span className="text-[10px] text-ink-tertiary">
              {mode === 'agent' ? '自然语言操作数据' : '使用问题解答'}
            </span>
          </div>
          <div className="flex items-end gap-2 p-2">
            <textarea
              ref={textareaRef}
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={handleKeyDown}
              rows={1}
              disabled={sending || Boolean(pendingAction)}
              placeholder={
                pendingAction
                  ? '请先确认或取消待执行操作'
                  : mode === 'agent'
                    ? '例如：查一下24级计算机应用1班的学生'
                    : '例如：怎么批量导入学生？'
              }
              className="max-h-[120px] min-h-[36px] flex-1 resize-none bg-transparent px-1.5 py-1.5 text-body-2-regular text-ink-primary outline-none placeholder:text-ink-tertiary disabled:cursor-not-allowed disabled:opacity-60"
            />
            <button
              type="button"
              aria-label="发送"
              disabled={!input.trim() || sending || Boolean(pendingAction)}
              onClick={() => sendMessage()}
              className={cn(
                'flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-neutral-950 text-white transition-all',
                'hover:bg-neutral-800 active:scale-95 disabled:pointer-events-none disabled:opacity-35'
              )}
            >
              {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
            </button>
          </div>
        </div>
        <div className="mt-2 text-center text-[10px] leading-4 text-ink-tertiary">
          内容由 AI 生成，仅供参考；执行结果以系统数据为准
        </div>
      </footer>
    </div>
  )
}

function MessageItem({
  message,
  confirming,
  onConfirm,
  onSuggestion,
  pending,
}: {
  message: AssistantMessage
  confirming: boolean
  onConfirm: (token: string, approved: boolean) => void
  onSuggestion: (text: string) => void
  pending: boolean
}) {
  if (message.role === 'user') {
    return (
      <div className="flex justify-end">
        <div className="max-w-[86%] rounded-2xl bg-neutral-950 px-3.5 py-2.5 text-body-2-regular leading-6 text-white">
          <span className="whitespace-pre-wrap break-words">{message.content}</span>
        </div>
      </div>
    )
  }

  return (
    <div className="flex items-start gap-2">
      <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-xl bg-primary-50 text-primary-600">
        <Sparkles className="h-3.5 w-3.5" />
      </div>
      <div className="min-w-0 flex-1">
        <div
          className={cn(
            'rounded-2xl border px-3.5 py-2.5 text-body-2-regular leading-6',
            message.error
              ? 'border-red-200 bg-red-50/60 text-red-600'
              : 'border-line-separator bg-surface-secondary/50 text-ink-primary'
          )}
        >
          {message.error ? (
            <span className="whitespace-pre-wrap break-words">{message.content}</span>
          ) : (
            <MarkdownView
              content={message.content || '（无内容）'}
              className="space-y-2 break-words [&_a]:text-primary-600 [&_a]:underline [&_h1]:text-base [&_h1]:font-semibold [&_h2]:text-sm [&_h2]:font-semibold [&_h3]:text-sm [&_h3]:font-medium [&_li]:ml-4 [&_li]:list-disc [&_ol_li]:list-decimal [&_p]:leading-6 [&_strong]:font-semibold"
            />
          )}
        </div>

        {message.action && (
          <ActionCard card={message.action} confirming={confirming} onConfirm={onConfirm} />
        )}

        {message.suggestions && message.suggestions.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {message.suggestions
              .filter((suggestion) => !(pending && (suggestion.includes('确认') || suggestion.includes('取消'))))
              .map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => onSuggestion(suggestion)}
                  className="rounded-full border border-line-separator bg-surface-primary px-2.5 py-1 text-[11px] text-ink-secondary transition-colors hover:border-primary-200 hover:bg-primary-50/60 hover:text-primary-700"
                >
                  {suggestion}
                </button>
              ))}
          </div>
        )}
      </div>
    </div>
  )
}

function TypingIndicator({ mode }: { mode: AssistantMode }) {
  return (
    <div className="flex items-start gap-2">
      <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-xl bg-primary-50 text-primary-600">
        <Sparkles className="h-3.5 w-3.5" />
      </div>
      <div className="flex items-center gap-2 rounded-2xl border border-line-separator bg-surface-secondary/50 px-3.5 py-2.5">
        <span className="flex items-center gap-1">
          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-ink-tertiary [animation-delay:-0.2s]" />
          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-ink-tertiary [animation-delay:-0.1s]" />
          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-ink-tertiary" />
        </span>
        <span className="text-[11px] text-ink-tertiary">
          {mode === 'agent' ? '正在理解你的指令…' : '正在整理回答…'}
        </span>
      </div>
    </div>
  )
}

import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Card } from '@/components/ui/card'
import { MathText } from '@/components/rich-text'
import { Button } from '@/components/ui/button'
import {
  MessageSquare,
  Send,
  Image as ImageIcon,
  Paperclip,
  Loader2,
  AtSign,
  Sparkles,
  FileText,
  Hash,
} from 'lucide-react'
import { getTeacherCoursesForSelect } from '@/api/exam-homework'
import {
  getPublicMessages,
  sendChatMessage,
  uploadChatFile,
  getCourseStudents,
  markTeacherRead,
  type ChatMessage,
  type CourseStudent,
} from '@/api/chat'
import type { CourseItem } from '@/types'
import { cn } from '@/lib/utils'

/** 解析消息内容前缀：[image]/[file]/[exam]，返回展示类型 */
function parseContent(raw: string | undefined) {
  const content = raw || ''
  if (content.startsWith('[image]')) {
    return { type: 'image', url: content.substring(7), text: '[图片]' }
  }
  if (content.startsWith('[file]')) {
    const rest = content.substring(6)
    const sep = rest.indexOf('|')
    const fileName = sep > 0 ? rest.substring(0, sep) : rest
    const url = sep > 0 ? rest.substring(sep + 1) : '#'
    return { type: 'file', fileName, url, text: '📎 ' + fileName }
  }
  if (content.startsWith('[exam]')) {
    const parts = content.substring(6).split('|')
    const title = parts[0] || '考试/作业'
    return { type: 'exam', title, text: '📋 ' + title }
  }
  return { type: 'text', text: content }
}

/** 把裸链接变成可点击 */
function renderTextWithLinks(text: string) {
  const parts = text.split(/(https?:\/\/[^\s]+)/g)
  return parts.map((part, i) =>
    /^https?:\/\//.test(part) ? (
      <a
        key={i}
        href={part}
        target="_blank"
        rel="noreferrer"
        className="text-blue-600 underline break-all"
      >
        {part}
      </a>
    ) : (
      <span key={i}>{part}</span>
    )
  )
}

const ROLE_LABEL: Record<string, string> = {
  teacher: '教师',
  student: '学生',
  ai: 'AI',
  system: '系统',
}

export function TeacherChatPage() {
  const [searchParams] = useSearchParams()
  const initialCourse = searchParams.get('course')

  const [courses, setCourses] = useState<CourseItem[]>([])
  const [activeCourse, setActiveCourse] = useState<CourseItem | null>(null)
  const [msgs, setMsgs] = useState<ChatMessage[]>([])
  const [students, setStudents] = useState<CourseStudent[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(true)
  const [sending, setSending] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState('')
  const [showMention, setShowMention] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const lastMsgCountRef = useRef(0)

  // 加载教师课程，优先选中 URL 参数指定的课程
  useEffect(() => {
    getTeacherCoursesForSelect()
      .then((list) => {
        setCourses(list || [])
        if (list && list.length > 0) {
          const target = initialCourse
            ? list.find((c) => c.courseName === initialCourse)
            : null
          setActiveCourse(target || list[0])
        }
      })
      .catch(() => setError('课程加载失败'))
      .finally(() => setLoading(false))
  }, [])

  // 切换课程时加载聊天记录与学生列表，并标记该课程消息已读
  useEffect(() => {
    if (!activeCourse) return
    const courseName = activeCourse.courseName
    setMsgs([])
    setError('')
    getPublicMessages(courseName)
      .then((list) => {
        setMsgs(list || [])
        lastMsgCountRef.current = (list || []).length
        // 切换课程后立即滚动到底部，不使用平滑动画
        requestAnimationFrame(() => scrollToBottom(true))
      })
      .catch(() => setError('聊天记录加载失败'))
    getCourseStudents(courseName)
      .then(setStudents)
      .catch(() => setStudents([]))
    markTeacherRead(courseName).catch(() => {})
  }, [activeCourse])

  // 轮询刷新聊天记录（3 秒）：AI 异步回复、学生消息无需刷新页面即可显示
  useEffect(() => {
    if (!activeCourse) return
    const courseName = activeCourse.courseName
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return
      getPublicMessages(courseName)
        .then((list) => {
          setMsgs((prev) => {
            const cur = prev || []
            const next = list || []
            // 长度和首尾 id 都相同则视为无新消息，跳过更新
            if (
              cur.length === next.length &&
              (next.length === 0 ||
                (cur[0].id === next[0].id && cur[cur.length - 1].id === next[next.length - 1].id))
            ) {
              return prev
            }
            return next
          })
        })
        .catch(() => {})
    }, 3000)
    return () => window.clearInterval(timer)
  }, [activeCourse])

  // 判断用户是否在消息区底部附近
  const isNearBottom = () => {
    const el = scrollRef.current
    if (!el) return true
    return el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }

  // 滚动到底部
  const scrollToBottom = (immediate = false) => {
    const el = scrollRef.current
    if (!el) return
    if (immediate) {
      el.scrollTop = el.scrollHeight
    } else {
      // 简单平滑滚动，避免 scrollIntoView 从头滑到底的视觉抖动
      const start = el.scrollTop
      const end = el.scrollHeight - el.clientHeight
      const distance = end - start
      if (distance <= 0) return
      const duration = 200
      const startTime = performance.now()
      const easeOutQuad = (t: number) => t * (2 - t)
      const step = (now: number) => {
        const elapsed = now - startTime
        const progress = Math.min(elapsed / duration, 1)
        el.scrollTop = start + distance * easeOutQuad(progress)
        if (progress < 1) requestAnimationFrame(step)
      }
      requestAnimationFrame(step)
    }
  }

  // 自动滚动到底部：只在消息数量增加且用户在底部附近时滚动
  useEffect(() => {
    const currentCount = msgs.length
    const prevCount = lastMsgCountRef.current
    lastMsgCountRef.current = currentCount
    if (currentCount > prevCount && isNearBottom()) {
      scrollToBottom(false)
    }
  }, [msgs])

  const doSend = async (content: string) => {
    if (!activeCourse || !content.trim()) return
    setSending(true)
    try {
      const msg = await sendChatMessage(activeCourse.courseName, content.trim(), 'teacher')
      setMsgs((prev) => [...prev, msg])
      setInput('')
    } catch (e: any) {
      setError(e.response?.data?.error || '消息发送失败')
    } finally {
      setSending(false)
    }
  }

  const sendText = () => {
    if (!input.trim()) return
    doSend(input)
  }

  const handleFile = async (file: File) => {
    if (!activeCourse) return
    setUploading(true)
    try {
      const { url, fileName } = await uploadChatFile(activeCourse.courseName, file)
      const isImage = file.type.startsWith('image/')
      const content = isImage ? `[image]${url}` : `[file]${fileName}|${url}`
      await doSendRaw(content)
    } catch (e: any) {
      setError(e.response?.data?.error || '文件上传失败')
    } finally {
      setUploading(false)
    }
  }

  const doSendRaw = async (content: string) => {
    if (!activeCourse) return
    setSending(true)
    try {
      const msg = await sendChatMessage(activeCourse.courseName, content, 'teacher')
      setMsgs((prev) => [...prev, msg])
    } catch (e: any) {
      setError(e.response?.data?.error || '消息发送失败')
    } finally {
      setSending(false)
    }
  }

  const mentionStudent = (name: string) => {
    setInput((prev) => (prev.endsWith('@') || prev === '' ? prev + '@' + name + ' ' : prev + '@' + name + ' '))
    setShowMention(false)
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-neutral-900">课程聊天</h1>
          <p className="text-sm text-neutral-500 mt-1">选择课程进入群聊，可 @AI 或 @学生，也可发送图片、文件与链接</p>
        </div>
      </div>

      <div className="grid grid-cols-[280px_1fr] gap-6 h-[calc(100vh-220px)] min-h-[480px]">
        {/* 左侧课程列表 */}
        <Card className="overflow-hidden flex flex-col">
          <div className="px-4 py-3 border-b border-neutral-100 text-sm font-semibold text-neutral-700">
            我的课程
          </div>
          <div className="flex-1 overflow-y-auto p-2 space-y-1">
            {loading && (
              <div className="flex items-center justify-center py-10 text-neutral-400">
                <Loader2 className="w-5 h-5 animate-spin mr-2" /> 加载中
              </div>
            )}
            {!loading && courses.length === 0 && (
              <div className="text-center text-neutral-400 text-sm py-10">暂无授课课程</div>
            )}
            {courses.map((c) => (
              <button
                key={c.courseName + (c.classId || '')}
                onClick={() => setActiveCourse(c)}
                className={cn(
                  'w-full text-left px-3 py-3 rounded-xl transition-colors',
                  activeCourse?.courseName === c.courseName
                    ? 'bg-primary-50 text-primary-700'
                    : 'hover:bg-neutral-50 text-neutral-700'
                )}
              >
                <div className="flex items-center gap-2">
                  <MessageSquare className="w-4 h-4 flex-shrink-0" />
                  <span className="font-medium text-sm truncate">{c.courseName}</span>
                </div>
                {c.className && (
                  <div className="mt-1 pl-6 text-xs text-neutral-400">
                    {c.className}
                    {c.semester ? ` · ${c.semester}` : ''}
                  </div>
                )}
              </button>
            ))}
          </div>
        </Card>

        {/* 右侧聊天室 */}
        <Card className="flex flex-col overflow-hidden">
          {!activeCourse ? (
            <div className="flex-1 flex items-center justify-center text-neutral-400">
              请先选择课程
            </div>
          ) : (
            <>
              {/* 聊天头部 */}
              <div className="px-5 py-3 border-b border-neutral-100 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <MessageSquare className="w-5 h-5 text-primary-600" />
                  <span className="font-semibold text-neutral-900">{activeCourse.courseName}</span>
                  {activeCourse.className && (
                    <span className="text-xs text-neutral-400">· {activeCourse.className}</span>
                  )}
                </div>
              </div>

              {/* 消息区 */}
              <div
                ref={scrollRef}
                className="flex-1 overflow-y-scroll px-5 py-4 space-y-3 bg-neutral-50/50"
                style={{ scrollbarGutter: 'stable both-edges' }}
              >
                {error && (
                  <div className="text-center text-red-500 text-sm py-2">{error}</div>
                )}
                {msgs.length === 0 && (
                  <div className="text-center text-neutral-400 text-sm py-16">
                    暂无消息，发送第一条消息开启课程群聊
                  </div>
                )}
                {msgs.map((m) => {
                  const parsed = parseContent(m.content)
                  const isTeacher = m.senderRole === 'teacher'
                  const isAi = m.senderRole === 'ai'
                  const isImage = parsed.type === 'image'
                  const isFile = parsed.type === 'file'
                  const isExam = parsed.type === 'exam'
                  return (
                    <div key={m.id} className={cn('flex', isTeacher ? 'justify-end' : 'justify-start')}>
                      <div
                        className={cn(
                          'max-w-[75%] min-w-[80px] px-4 py-2.5 rounded-2xl text-sm leading-relaxed break-words',
                          isTeacher
                            ? 'bg-primary-600 text-white rounded-br-md'
                            : isAi
                            ? 'bg-purple-50 text-neutral-800 border border-purple-100 rounded-bl-md'
                            : 'bg-white text-neutral-800 border border-neutral-100 rounded-bl-md'
                        )}
                      >
                        <div
                          className={cn(
                            'text-xs font-medium mb-1',
                            isTeacher ? 'text-white/80' : isAi ? 'text-purple-600' : 'text-neutral-400'
                          )}
                        >
                          {isTeacher
                            ? '教师'
                            : isAi
                            ? m.senderName || 'AI'
                            : (m.senderName || '学生') + ` · ${ROLE_LABEL[m.senderRole] || ''}`}
                        </div>
                        {isImage ? (
                          <img
                            src={parsed.url}
                            alt="图片"
                            className="max-w-[260px] min-h-[80px] rounded-lg mt-1 cursor-pointer bg-neutral-100 object-cover"
                            onClick={() => window.open(parsed.url, '_blank')}
                            onError={(e) => {
                              // 若直接加载失败，尝试静态资源路径兜底
                              const src = e.currentTarget.src
                              if (!src.includes('/api/chat/download-file') && parsed.url) {
                                e.currentTarget.src = parsed.url
                              }
                            }}
                          />
                        ) : isFile ? (
                          <a
                            href={parsed.url}
                            target="_blank"
                            rel="noreferrer"
                            className={cn(
                              'flex items-center gap-3 rounded-xl p-3 min-w-[200px] max-w-[320px] transition-colors',
                              isTeacher
                                ? 'bg-white/15 hover:bg-white/20'
                                : 'bg-neutral-50 hover:bg-neutral-100 border border-neutral-100'
                            )}
                          >
                            <div
                              className={cn(
                                'w-11 h-11 rounded-xl flex items-center justify-center flex-shrink-0',
                                isTeacher ? 'bg-white/25' : 'bg-primary-100'
                              )}
                            >
                              <FileText className={cn('w-5 h-5', isTeacher ? 'text-white' : 'text-primary-600')} />
                            </div>
                            <div className="flex-1 min-w-0 text-left">
                              <div
                                className={cn(
                                  'text-sm font-medium truncate',
                                  isTeacher ? 'text-white' : 'text-neutral-800'
                                )}
                              >
                                {parsed.fileName}
                              </div>
                              <div className={cn('text-xs mt-0.5', isTeacher ? 'text-white/70' : 'text-neutral-500')}>
                                点击下载/查看
                              </div>
                            </div>
                          </a>
                        ) : isExam ? (
                          <div className="flex items-center gap-2">
                            <Hash className="w-4 h-4 text-primary-500" />
                            {parsed.text}
                          </div>
                        ) : (
                          <div className="whitespace-pre-wrap">
                            {parsed.text.includes('$') ? (
                              <MathText text={parsed.text} />
                            ) : (
                              renderTextWithLinks(parsed.text)
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>

              {/* 输入区 */}
              <div className="px-4 py-3 border-t border-neutral-100 bg-white">
                <div className="flex items-center gap-2">
                  <input
                    ref={fileRef}
                    type="file"
                    className="hidden"
                    onChange={(e) => {
                      const f = e.target.files?.[0]
                      if (f) handleFile(f)
                      e.target.value = ''
                    }}
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={uploading}
                    onClick={() => fileRef.current?.click()}
                    title="发送图片"
                  >
                    {uploading ? <Loader2 className="w-5 h-5 animate-spin" /> : <ImageIcon className="w-5 h-5" />}
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={uploading}
                    onClick={() => fileRef.current?.click()}
                    title="发送文件"
                  >
                    <Paperclip className="w-5 h-5" />
                  </Button>
                  <div className="relative flex-1">
                    <input
                      value={input}
                      onChange={(e) => setInput(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && !e.shiftKey) {
                          e.preventDefault()
                          sendText()
                        }
                      }}
                      onFocus={() => setShowMention(true)}
                      onBlur={() => setTimeout(() => setShowMention(false), 200)}
                      placeholder="输入消息，@AI 提问，@学生名 私发，Enter 发送"
                      className="w-full h-10 px-3 pr-9 text-sm border border-neutral-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-200"
                    />
                    <button
                      type="button"
                      onClick={() => setShowMention((v) => !v)}
                      className="absolute right-2 top-1/2 -translate-y-1/2 text-neutral-400 hover:text-primary-600"
                      title="@ 点名"
                    >
                      <AtSign className="w-4 h-4" />
                    </button>
                    {showMention && students.length > 0 && (
                      <div className="absolute bottom-11 left-0 right-0 bg-white border border-neutral-200 rounded-xl shadow-lg overflow-hidden z-10 max-h-56 overflow-y-auto">
                        <div className="px-3 py-2 text-xs text-neutral-400 border-b border-neutral-100 flex items-center gap-1">
                          <Sparkles className="w-3 h-3" /> 选择学生或 AI
                        </div>
                        <button
                          className="w-full text-left px-3 py-2 text-sm hover:bg-primary-50 flex items-center gap-2"
                          onMouseDown={() => {
                            setInput((p) => p + '@AI ')
                            setShowMention(false)
                          }}
                        >
                          <Sparkles className="w-4 h-4 text-purple-500" /> AI 智能助教
                        </button>
                        {students.map((s) => (
                          <button
                            key={s.id}
                            className="w-full text-left px-3 py-2 text-sm hover:bg-primary-50"
                            onMouseDown={() => mentionStudent(s.realName)}
                          >
                            {s.realName}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                  <Button onClick={sendText} disabled={sending || !input.trim()}>
                    {sending ? <Loader2 className="w-4 h-4 animate-spin mr-1" /> : <Send className="w-4 h-4 mr-1" />}
                    发送
                  </Button>
                </div>
              </div>
            </>
          )}
        </Card>
      </div>
    </div>
  )
}
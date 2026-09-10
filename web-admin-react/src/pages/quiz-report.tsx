import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import {
  ArrowLeft,
  Brain,
  CheckCircle2,
  XCircle,
  HelpCircle,
  MinusCircle,
  ThumbsUp,
  AlertTriangle,
  Sparkles,
  ListOrdered,
  ClipboardList,
} from 'lucide-react'
import { getQuizSessionReport } from '@/api/stats'
import type { QuizReportDetail, QuizReportAnswer } from '@/types'

function formatDateTime(value?: string | null) {
  if (!value) return '-'
  return value.replace('T', ' ').substring(0, 19)
}

function formatDuration(seconds?: number | null) {
  if (seconds == null) return '-'
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  if (m > 0) return `${m}分${s > 0 ? `${s}秒` : ''}`
  return `${s}秒`
}

function difficultyLabel(d?: number) {
  switch (d) {
    case 1:
      return '基础'
    case 2:
      return '中等'
    case 3:
      return '进阶'
    default:
      return '-'
  }
}

function getCorrectnessLabel(isCorrect: number) {
  switch (isCorrect) {
    case 1:
      return { text: '正确', icon: CheckCircle2, className: 'bg-green-50 text-green-600 border-green-200' }
    case 0:
      return { text: '错误', icon: XCircle, className: 'bg-red-50 text-red-600 border-red-200' }
    case -1:
      return { text: '不会', icon: HelpCircle, className: 'bg-orange-50 text-orange-600 border-orange-200' }
    case -2:
      return { text: '跳过', icon: MinusCircle, className: 'bg-neutral-50 text-neutral-500 border-neutral-200' }
    default:
      return { text: '未知', icon: HelpCircle, className: 'bg-neutral-50 text-neutral-500 border-neutral-200' }
  }
}

function questionTypeLabel(type?: string) {
  const map: Record<string, string> = {
    single_choice: '单选题',
    multiple_choice: '多选题',
    true_false: '判断题',
    short_answer: '简答题',
    fill_blank: '填空题',
    单选: '单选题',
    多选: '多选题',
    判断: '判断题',
    解析: '解析题',
    填空: '填空题',
  }
  return map[type || ''] || type || '未知题型'
}

function parseOptions(options?: string | null): string[] {
  if (!options) return []
  try {
    const parsed = JSON.parse(options)
    if (Array.isArray(parsed)) return parsed.map(String)
    if (typeof parsed === 'object' && parsed !== null) {
      return Object.entries(parsed).map(([k, v]) => `${k}. ${v}`)
    }
  } catch {
    // fall through
  }
  return options ? [options] : []
}

function AnswerCard({ answer }: { answer: QuizReportAnswer }) {
  const status = getCorrectnessLabel(answer.isCorrect)
  const StatusIcon = status.icon
  const options = parseOptions(answer.options)

  return (
    <div className="p-4 rounded-xl border border-neutral-100 bg-white">
      <div className="flex items-start justify-between gap-3">
        <div className="text-sm font-medium text-neutral-900 flex items-center gap-2">
          <span className="text-neutral-500">第{answer.questionIndex}题</span>
          <span className="text-xs text-neutral-500 border border-neutral-200 rounded px-1.5 py-0.5 font-normal">
            {questionTypeLabel(answer.questionType)}
          </span>
        </div>
        <div
          className={`shrink-0 inline-flex items-center gap-1 px-2 py-1 rounded text-xs border ${status.className}`}
        >
          <StatusIcon className="w-3.5 h-3.5" />
          {status.text}
        </div>
      </div>

      <div
        className="text-sm text-neutral-700 mt-3 leading-relaxed break-all"
        dangerouslySetInnerHTML={{ __html: answer.question || '' }}
      />

      {options.length > 0 && (
        <div className="mt-2 space-y-1">
          {options.map((opt, i) => (
            <div key={i} className="text-xs text-neutral-600 pl-2 break-all">
              {opt}
            </div>
          ))}
        </div>
      )}

      <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
        <div className="p-2 rounded-lg bg-neutral-50">
          <span className="text-neutral-500">学生答案：</span>
          <span className="text-neutral-900 break-all">
            {answer.userAnswer || '未作答'}
          </span>
        </div>
        <div className="p-2 rounded-lg bg-neutral-50">
          <span className="text-neutral-500">正确答案：</span>
          <span className="text-neutral-900 break-all">
            {answer.correctAnswer || '-'}
          </span>
        </div>
      </div>

      <div className="mt-2 text-xs text-neutral-500">
        用时 {formatDuration(answer.durationSec)}
        {answer.modifiedCount > 0 && (
          <span className="ml-3">修改 {answer.modifiedCount} 次</span>
        )}
      </div>
    </div>
  )
}

export function QuizReportPage() {
  const { sessionId } = useParams()
  const navigate = useNavigate()
  const [report, setReport] = useState<QuizReportDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    const fetchReport = async () => {
      if (!sessionId) return
      setLoading(true)
      setError('')
      try {
        const data = await getQuizSessionReport(Number(sessionId))
        setReport(data)
      } catch (err: any) {
        setError(err.response?.data?.error || err.response?.data?.message || '报告加载失败')
      } finally {
        setLoading(false)
      }
    }
    fetchReport()
  }, [sessionId])

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[400px]">
        <div className="text-neutral-400">测评报告加载中...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="space-y-4">
        <Button variant="outline" size="sm" onClick={() => navigate('/stats')}>
          <ArrowLeft className="w-4 h-4 mr-1" />
          返回统计
        </Button>
        <div className="flex items-center justify-center min-h-[300px]">
          <div className="text-danger">{error}</div>
        </div>
      </div>
    )
  }

  const session = report?.session
  const student = report?.student || {}
  const answers = report?.answers || []
  const total = session?.totalQuestions ?? 0
  const correct = session?.correctCount ?? 0
  const accuracy = total > 0 ? Math.round((correct * 100) / total) : 0

  return (
    <div className="space-y-6">
      {/* 头部：返回 + 学生信息 + 测评概况 */}
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <Button variant="outline" size="sm" onClick={() => navigate('/stats')}>
            <ArrowLeft className="w-4 h-4 mr-1" />
            返回统计
          </Button>
          <h1 className="text-2xl font-bold text-neutral-900 mt-3 flex items-center gap-2">
            <Brain className="w-6 h-6 text-primary-550" />
            测评报告
          </h1>
          <p className="text-neutral-500 mt-1 text-sm">
            {student.realName || '学生'}
            {student.studentNo ? ` · ${student.studentNo}` : ''}
            {student.className ? ` · ${student.className}` : ''}
            {' · '}
            {session?.subject || '综合刷题'} · 第{session?.sessionNo ?? '-'}次 ·{' '}
            {difficultyLabel(session?.difficulty)}难度
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="secondary">{formatDateTime(session?.createdAt)}</Badge>
          {session?.status === 'evaluated' ? (
            <Badge variant="success">已完成测评</Badge>
          ) : (
            <Badge variant="secondary">已完成</Badge>
          )}
        </div>
      </div>

      {/* 总体得分卡片：正确率 + 四格统计 */}
      <Card>
        <CardContent className="p-6">
          <div className="flex flex-col sm:flex-row items-center gap-8">
            <div className="text-center shrink-0">
              <p className="text-sm text-neutral-500 mb-1">正确率</p>
              <p className="text-5xl font-bold text-primary-600">{accuracy}%</p>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-6 flex-1 w-full">
              <div className="text-center">
                <div className="text-2xl font-bold text-neutral-900">
                  {correct}/{total}
                </div>
                <div className="text-xs text-neutral-500 mt-1">答对题数</div>
              </div>
              <div className="text-center">
                <div className="text-2xl font-bold text-neutral-900">
                  {session?.answeredCount ?? 0}
                </div>
                <div className="text-xs text-neutral-500 mt-1">已答题</div>
              </div>
              <div className="text-center">
                <div className="text-2xl font-bold text-neutral-900">
                  {session?.skipCount ?? 0}
                </div>
                <div className="text-xs text-neutral-500 mt-1">未答/不会</div>
              </div>
              <div className="text-center">
                <div className="text-2xl font-bold text-neutral-900">
                  {formatDuration(session?.totalDurationSec)}
                </div>
                <div className="text-xs text-neutral-500 mt-1">总用时</div>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* 能力维度分析 */}
        <Card>
          <CardContent className="p-6">
            <h3 className="text-base font-semibold text-neutral-900 mb-4">能力维度分析</h3>
            {session?.scores && session.scores.length > 0 ? (
              <div className="space-y-4">
                {session.scores.map((s) => (
                  <div key={s.name}>
                    <div className="flex items-center justify-between text-sm mb-1.5">
                      <span className="text-neutral-700">{s.name}</span>
                      <span className="text-neutral-500 text-xs">{s.value}/10</span>
                    </div>
                    <Progress value={s.value * 10} />
                  </div>
                ))}
              </div>
            ) : (
              <div className="h-[120px] flex items-center justify-center text-neutral-400 text-sm">
                暂无能力评分数据
              </div>
            )}
          </CardContent>
        </Card>

        {/* 优势与薄弱 */}
        <Card>
          <CardContent className="p-6">
            <h3 className="text-base font-semibold text-neutral-900 mb-4">优势与薄弱</h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div className="rounded-xl bg-green-50 border border-green-100 p-4">
                <div className="flex items-center gap-1.5 text-sm font-semibold text-green-700 mb-3">
                  <ThumbsUp className="w-4 h-4" />
                  优势
                </div>
                {session?.strengths && session.strengths.length > 0 ? (
                  <ul className="space-y-2">
                    {session.strengths.map((s, i) => (
                      <li key={i} className="text-xs text-green-800 leading-relaxed">
                        · {s}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-xs text-green-700/60">暂无突出优势</p>
                )}
              </div>
              <div className="rounded-xl bg-red-50 border border-red-100 p-4">
                <div className="flex items-center gap-1.5 text-sm font-semibold text-red-700 mb-3">
                  <AlertTriangle className="w-4 h-4" />
                  薄弱
                </div>
                {session?.weaknesses && session.weaknesses.length > 0 ? (
                  <ul className="space-y-2">
                    {session.weaknesses.map((w, i) => (
                      <li key={i} className="text-xs text-red-800 leading-relaxed">
                        · {w}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-xs text-red-700/60">暂无薄弱环节</p>
                )}
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* AI 综合建议 */}
      <Card>
        <CardContent className="p-6">
          <h3 className="text-base font-semibold text-neutral-900 mb-3 flex items-center gap-2">
            <Sparkles className="w-4 h-4 text-primary-550" />
            AI 综合建议
          </h3>
          <p className="text-sm text-neutral-700 leading-relaxed">
            {session?.suggestion || '暂无综合建议'}
          </p>
        </CardContent>
      </Card>

      {/* AI 学习计划 */}
      {session?.studyPlan && session.studyPlan.length > 0 && (
        <Card>
          <CardContent className="p-6">
            <h3 className="text-base font-semibold text-neutral-900 mb-4 flex items-center gap-2">
              <ListOrdered className="w-4 h-4 text-primary-550" />
              AI 学习计划
            </h3>
            <ol className="space-y-3">
              {session.studyPlan.map((p, i) => (
                <li key={i} className="flex items-start gap-3">
                  <span className="shrink-0 w-6 h-6 rounded-full bg-primary-50 text-primary-600 text-xs font-semibold flex items-center justify-center">
                    {i + 1}
                  </span>
                  <span className="text-sm text-neutral-700 leading-relaxed">{p}</span>
                </li>
              ))}
            </ol>
          </CardContent>
        </Card>
      )}

      {/* 每题作答明细 */}
      <Card>
        <CardContent className="p-6">
          <h3 className="text-base font-semibold text-neutral-900 mb-4 flex items-center gap-2">
            <ClipboardList className="w-4 h-4 text-primary-550" />
            每题作答明细
            <span className="text-xs font-normal text-neutral-400">共 {answers.length} 题</span>
          </h3>
          {answers.length > 0 ? (
            <div className="space-y-3">
              {answers.map((a) => (
                <AnswerCard key={a.questionIndex} answer={a} />
              ))}
            </div>
          ) : (
            <div className="h-[120px] flex items-center justify-center text-neutral-400 text-sm border border-dashed border-neutral-200 rounded-lg">
              暂无作答明细
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

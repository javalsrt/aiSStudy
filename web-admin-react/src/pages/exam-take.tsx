import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  getStudentExam,
  submitStudentExam,
  type ExamQuestion,
} from '@/api/student-exam'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { useDialog } from '@/hooks/use-dialog'
import {
  BookOpen,
  ChevronLeft,
  ClipboardCheck,
  Loader2,
  Send,
} from 'lucide-react'

const TYPE_LABEL: Record<string, string> = {
  single_choice: '单选题',
  multiple_choice: '多选题',
  true_false: '判断题',
  fill_blank: '填空题',
  short_answer: '简答题',
}

const extractOptionLabel = (raw: string): { label: string; text: string } => {
  const m = raw.match(/^\s*([A-Z])[\.、\s:：\-\*]+(.*)$/)
  if (m) return { label: m[1], text: m[2].trim() }
  return { label: raw.trim().slice(0, 1), text: raw.trim() }
}

export default function ExamTakePage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { alert, confirm, DialogComponent } = useDialog()

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [title, setTitle] = useState('')
  const [examDesc, setExamDesc] = useState('')
  const [questions, setQuestions] = useState<ExamQuestion[]>([])
  const [answers, setAnswers] = useState<Record<string, any>>({})
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<{
    submissionId?: number
    totalScore?: number
  } | null>(null)

  const fetchDetail = async () => {
    if (!id) return
    setLoading(true)
    setError('')
    try {
      const data = await getStudentExam(id)
      setTitle(data.exam.title)
      setExamDesc(data.exam.description || '')
      setQuestions(data.questions || [])
      if (data.existingSubmission && data.existingSubmission.status === 'completed') {
        setResult({
          submissionId: data.existingSubmission.submissionId,
          totalScore: data.existingSubmission.totalScore,
        })
      }
    } catch (e: any) {
      setError(e?.response?.data?.message || e?.response?.data?.error || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchDetail()
  }, [id])

  const setAnswer = (qid: number, value: any) => {
    setAnswers((prev) => ({ ...prev, [String(qid)]: value }))
  }

  const toggleMulti = (qid: number, label: string) => {
    const prev: string[] = Array.isArray(answers[String(qid)]) ? [...answers[String(qid)]] : []
    const idx = prev.indexOf(label)
    if (idx >= 0) prev.splice(idx, 1)
    else prev.push(label)
    setAnswer(qid, prev)
  }

  const answeredCount = useMemo(() => {
    let n = 0
    for (const q of questions) {
      const v = answers[String(q.id)]
      if (Array.isArray(v) ? v.length > 0 : v != null && String(v).trim() !== '') n++
    }
    return n
  }, [answers, questions])

  const onSubmit = async () => {
    if (result) return
    const ok = await confirm({
      title: '确认提交？',
      description: `已作答 ${answeredCount}/${questions.length} 题，提交后无法修改，确认提交吗？`,
    })
    if (!ok) return
    setSubmitting(true)
    try {
      const res = await submitStudentExam(id!, { answers })
      setResult({ submissionId: res.submissionId, totalScore: res.totalScore })
      // 提交成功后刷新，加载新分数数据
      await fetchDetail()
      await alert({
        title: '提交成功',
        description: `总得分：${res.totalScore ?? '-'} 分`,
      })
    } catch (e: any) {
      await alert({
        title: '提交失败',
        description: e?.response?.data?.message || e?.response?.data?.error || '请重试',
      })
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <div className="min-h-[400px] flex items-center justify-center">
        <Loader2 className="w-6 h-6 animate-spin text-primary mr-3" /> 加载试卷中...
      </div>
    )
  }
  if (error) {
    return (
      <div className="space-y-4 p-6">
        <Button variant="outline" onClick={() => navigate(-1)}>
          <ChevronLeft className="w-4 h-4 mr-1" /> 返回
        </Button>
        <Card>
          <CardContent className="p-10 text-center text-danger">{error}</CardContent>
        </Card>
        {DialogComponent}
      </div>
    )
  }

  return (
    <div className="space-y-5 p-4 md:p-6 max-w-5xl mx-auto">
      {DialogComponent}
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-3">
          <Button variant="outline" size="sm" onClick={() => navigate(-1)}>
            <ChevronLeft className="w-4 h-4 mr-1" /> 返回课程
          </Button>
          <div>
            <h1 className="text-xl md:text-2xl font-bold text-neutral-900 flex items-center gap-2">
              <BookOpen className="w-6 h-6 text-primary" />
              {title}
            </h1>
            {examDesc && <p className="text-sm text-neutral-500 mt-1">{examDesc}</p>}
          </div>
        </div>
        <div className="flex items-center gap-3">
          <Badge variant="secondary">
            已作答 {answeredCount} / {questions.length}
          </Badge>
          {!result && (
            <Button onClick={onSubmit} disabled={submitting} className="gap-2">
              {submitting ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                <Send className="w-4 h-4" />
              )}
              {submitting ? '提交中...' : '提交试卷'}
            </Button>
          )}
          {result && (
            <Badge variant="success" className="text-sm px-3 py-1.5 gap-2">
              <ClipboardCheck className="w-4 h-4" />
              已完成 · 得分 {result.totalScore ?? '-'} 分
            </Badge>
          )}
        </div>
      </div>

      {questions.map((q, idx) => {
        const keyId = String(q.id)
        const answered =
          Array.isArray(answers[keyId])
            ? answers[keyId].length > 0
            : answers[keyId] != null && String(answers[keyId]).trim() !== ''
        return (
          <Card key={keyId} className={answered ? 'border-emerald-300/60' : ''}>
            <CardHeader className="py-3 px-5">
              <CardTitle className="flex items-start gap-3 text-base">
                <Badge variant="outline" className="shrink-0 mt-0.5">
                  {idx + 1} · {TYPE_LABEL[q.type] || q.type} · {q.score}分
                </Badge>
                <span className="font-medium text-neutral-900 whitespace-pre-wrap">
                  {q.content}
                </span>
              </CardTitle>
            </CardHeader>
            <CardContent className="pt-0 space-y-3">
              {result && (
                <div className="text-sm text-neutral-500">
                  （您已提交试卷，此处仅展示题目内容）
                </div>
              )}

              {q.type === 'single_choice' && (
                <RadioGroup
                  disabled={!!result}
                  value={answers[keyId] || ''}
                  onValueChange={(v) => setAnswer(q.id, v)}
                  className="space-y-2"
                >
                  {(q.options || []).map((o) => {
                    const { label, text } = extractOptionLabel(o)
                    return (
                      <div
                        key={label}
                        className="flex items-start gap-3 p-3 rounded-lg border border-neutral-100 hover:bg-neutral-50"
                      >
                        <RadioGroupItem value={label} id={`${keyId}-${label}`} />
                        <Label htmlFor={`${keyId}-${label}`} className="flex-1 cursor-pointer">
                          <span className="font-semibold mr-2">{label}.</span>
                          {text}
                        </Label>
                      </div>
                    )
                  })}
                </RadioGroup>
              )}

              {q.type === 'multiple_choice' && (
                <div className="space-y-2">
                  {(q.options || []).map((o) => {
                    const { label, text } = extractOptionLabel(o)
                    const checked = Array.isArray(answers[keyId])
                      ? (answers[keyId] as string[]).includes(label)
                      : false
                    return (
                      <div
                        key={label}
                        className="flex items-start gap-3 p-3 rounded-lg border border-neutral-100 hover:bg-neutral-50"
                      >
                        <Checkbox
                          disabled={!!result}
                          checked={checked}
                          onCheckedChange={() => toggleMulti(q.id, label)}
                          id={`${keyId}-m-${label}`}
                        />
                        <Label htmlFor={`${keyId}-m-${label}`} className="flex-1 cursor-pointer">
                          <span className="font-semibold mr-2">{label}.</span>
                          {text}
                        </Label>
                      </div>
                    )
                  })}
                </div>
              )}

              {q.type === 'true_false' && (
                <RadioGroup
                  disabled={!!result}
                  value={answers[keyId] || ''}
                  onValueChange={(v) => setAnswer(q.id, v)}
                  className="flex gap-4"
                >
                  {[
                    { v: '正确', k: 'T' },
                    { v: '错误', k: 'F' },
                  ].map((o) => (
                    <div
                      key={o.k}
                      className="flex items-center gap-2 p-3 rounded-lg border border-neutral-100 hover:bg-neutral-50 flex-1"
                    >
                      <RadioGroupItem value={o.v} id={`${keyId}-tf-${o.k}`} />
                      <Label htmlFor={`${keyId}-tf-${o.k}`} className="cursor-pointer flex-1">
                        {o.v}
                      </Label>
                    </div>
                  ))}
                </RadioGroup>
              )}

              {q.type === 'fill_blank' && (
                <Input
                  disabled={!!result}
                  placeholder="请输入答案..."
                  value={answers[keyId] || ''}
                  onChange={(e) => setAnswer(q.id, e.target.value)}
                />
              )}

              {q.type === 'short_answer' && (
                <Textarea
                  disabled={!!result}
                  rows={4}
                  placeholder="请输入简答题答案..."
                  value={answers[keyId] || ''}
                  onChange={(e) => setAnswer(q.id, e.target.value)}
                />
              )}
            </CardContent>
          </Card>
        )
      })}

      <div className="flex justify-end">
        {!result && (
          <Button onClick={onSubmit} disabled={submitting} size="lg" className="gap-2">
            {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
            {submitting ? '提交中，AI 正在自动评分简答题...' : '提交并交卷'}
          </Button>
        )}
      </div>
    </div>
  )
}

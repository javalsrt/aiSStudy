import { useEffect, useMemo, useRef, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { MathText } from '@/components/rich-text'
import { SpotlightCard } from '@/components/SpotlightCard'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Progress } from '@/components/ui/progress'
import {
  Plus,
  Search,
  FileText,
  Clock,
  Users,
  Calendar,
  Trash2,
  Eye,
  Edit3,
  ChevronRight,
  ChevronLeft,
  CheckCircle2,
  Upload,
  Loader2,
  AlertCircle,
} from 'lucide-react'
import {
  getExamHomeworkList,
  getTeacherClasses,
  getTeacherCoursesForSelect,
  publishExamHomework,
  generateQuestionsByRange,
  generateQuestionsByDocument,
  deleteExamHomework,
  toggleExamHomeworkStatus,
} from '@/api/exam-homework'
import {
  getExamSubmissions,
  adjustSubmissionAnswerScore,
} from '@/api/student-exam'
import type {
  ClassInfo,
  CourseItem,
  ExamHomeworkItem,
  QuestionPreview,
} from '@/types'

const questionTypeOptions = [
  { value: 'single_choice', label: '单选题' },
  { value: 'multiple_choice', label: '多选题' },
  { value: 'true_false', label: '判断题' },
  { value: 'fill_blank', label: '填空题' },
  { value: 'short_answer', label: '简答题' },
]

const difficultyOptions = [
  { value: 'easy', label: '简单' },
  { value: 'medium', label: '中等' },
  { value: 'hard', label: '困难' },
  { value: 'mixed', label: '混合' },
]

const steps = ['基础信息', '出题方式', '题目预览', '发布确认']

export function ExamHomeworkPage() {
  // 列表数据
  const [list, setList] = useState<ExamHomeworkItem[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [classes, setClasses] = useState<ClassInfo[]>([])
  const [courses, setCourses] = useState<CourseItem[]>([])

  // 筛选
  const [filterClassId, setFilterClassId] = useState<number | ''>('')
  const [filterType, setFilterType] = useState<'all' | 'exam' | 'homework'>('all')
  const [filterStatus, setFilterStatus] = useState<'all' | 'published' | 'draft' | 'ended'>('all')
  const [searchKeyword, setSearchKeyword] = useState('')

  // 发布弹窗
  const [publishOpen, setPublishOpen] = useState(false)
  const [currentStep, setCurrentStep] = useState(0)
  const [submitting, setSubmitting] = useState(false)

  // 表单数据
  const [form, setForm] = useState<{
    type: 'exam' | 'homework'
    classId: number | ''
    title: string
    description: string
    startTime: string
    endTime: string
    timeLimit: number
    totalScore: number
    passScore: number
    publishMode: 'immediate' | 'scheduled'
    scheduledTime: string

    // 出题方式
    questionMode: 'ai-range' | 'ai-document'

    // AI 范围出题
    courseId: number | ''
    chapterScope: 'all' | 'custom'
    selectedChapters: number[]
    questionTypes: string[]
    difficulty: string
    questionCount: number

    // AI 文档出题
    documentFile: File | null
    documentName: string
  }>({
    type: 'exam',
    classId: '',
    title: '',
    description: '',
    startTime: '',
    endTime: '',
    timeLimit: 60,
    totalScore: 100,
    passScore: 60,
    publishMode: 'immediate',
    scheduledTime: '',

    questionMode: 'ai-range',

    courseId: '',
    chapterScope: 'all',
    selectedChapters: [],
    questionTypes: ['single_choice', 'multiple_choice', 'true_false'],
    difficulty: 'medium',
    questionCount: 20,

    documentFile: null,
    documentName: '',
  })

  // 题目预览
  const [previewQuestions, setPreviewQuestions] = useState<QuestionPreview[]>([])
  const [generating, setGenerating] = useState(false)
  const [generationError, setGenerationError] = useState('')

  // 上传进度
  const [uploadProgress, setUploadProgress] = useState(0)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // 提交列表弹窗
  const [submissionsOpen, setSubmissionsOpen] = useState(false)
  const [submissionsExam, setSubmissionsExam] = useState<ExamHomeworkItem | null>(null)
  const [submissions, setSubmissions] = useState<any[]>([])
  const [submissionsLoading, setSubmissionsLoading] = useState(false)
  const [expandedSubmissionId, setExpandedSubmissionId] = useState<number | null>(null)
  const [adjustingAnswerId, setAdjustingAnswerId] = useState<number | null>(null)
  const [adjustForm, setAdjustForm] = useState<{ score: number; comment: string }>({ score: 0, comment: '' })

  // 加载班级和课程：只在组件挂载时加载一次，避免筛选切换时重复请求
  useEffect(() => {
    loadClasses()
    loadCourses()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 加载列表：筛选条件变化时重新加载
  useEffect(() => {
    loadList()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterClassId, filterType, filterStatus, searchKeyword])

  const loadList = async () => {
    setLoading(true)
    setError('')
    try {
      const res = await getExamHomeworkList({
        classId: filterClassId || undefined,
        type: filterType === 'all' ? undefined : filterType,
        status: filterStatus === 'all' ? undefined : filterStatus,
        keyword: searchKeyword || undefined,
      })
      setList(res.list || [])
    } catch (err: any) {
      setError(err.response?.data?.message || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  const loadClasses = async () => {
    try {
      const data = await getTeacherClasses()
      setClasses(data || [])
    } catch {
      // 静默失败，班级选择框留空
    }
  }

  const loadCourses = async () => {
    try {
      const data = await getTeacherCoursesForSelect()
      setCourses(data || [])
    } catch {
      setCourses([])
    }
  }

  const filteredList = useMemo(() => {
    return list.filter((item) => {
      if (searchKeyword) {
        const kw = searchKeyword.toLowerCase()
        return (
          item.title.toLowerCase().includes(kw) ||
          item.className.toLowerCase().includes(kw)
        )
      }
      return true
    })
  }, [list, searchKeyword])

  // 表单字段更新
  const updateForm = <K extends keyof typeof form>(key: K, value: (typeof form)[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  // 打开发布弹窗
  const openPublish = () => {
    try {
      setCurrentStep(0)
      setPreviewQuestions([])
      setGenerationError('')
      setUploadProgress(0)
      setGenerating(false)
      setSubmitting(false)
      setForm({
        type: 'exam',
        classId: '',
        title: '',
        description: '',
        startTime: '',
        endTime: '',
        timeLimit: 60,
        totalScore: 100,
        passScore: 60,
        publishMode: 'immediate',
        scheduledTime: '',
        questionMode: 'ai-range',
        courseId: '',
        chapterScope: 'all',
        selectedChapters: [],
        questionTypes: ['single_choice', 'multiple_choice', 'true_false'],
        difficulty: 'medium',
        questionCount: 20,
        documentFile: null,
        documentName: '',
      })
      setPublishOpen(true)
    } catch (e: any) {
      console.error('[openPublish] error:', e)
      alert('打开发布窗口失败：' + (e?.message || String(e)))
    }
  }

  // 验证当前步骤
  const validateStep = (step: number): string | null => {
    if (step === 0) {
      if (!form.classId) return '请选择班级'
      if (!form.title.trim()) return '请输入标题'
      if (!form.startTime) return '请选择开始时间'
      if (!form.endTime) return '请选择截止时间'
      if (new Date(form.endTime) <= new Date(form.startTime)) return '截止时间必须晚于开始时间'
      if (form.publishMode === 'scheduled' && !form.scheduledTime) return '请选择定时发布时间'
      if (form.publishMode === 'scheduled' && new Date(form.scheduledTime) > new Date(form.startTime)) {
        return '定时发布时间不能晚于开始时间'
      }
      if (form.timeLimit <= 0) return '限时必须大于 0'
      if (form.totalScore <= 0) return '总分必须大于 0'
      if (form.passScore < 0 || form.passScore > form.totalScore) return '及格分必须在 0 到总分之间'
    }
    if (step === 1) {
      if (form.questionMode === 'ai-range') {
        if (!form.courseId) return '请选择课程'
        if (form.questionTypes.length === 0) return '请至少选择一种题型'
        if (form.questionCount <= 0) return '题目数量必须大于 0'
      } else {
        if (!form.documentFile) return '请上传文档'
      }
    }
    if (step === 2) {
      if (previewQuestions.length === 0) return '请先生成题目预览'
    }
    return null
  }

  // 生成题目
  const handleGeneratePreview = async () => {
    if (generating) return
    const err = validateStep(1)
    if (err) {
      setGenerationError(err)
      return
    }
    setGenerating(true)
    setGenerationError('')
    try {
      let result: { questions: QuestionPreview[]; error?: string; failedTypes?: string[] }
      if (form.questionMode === 'ai-range') {
        result = await generateQuestionsByRange({
          courseId: form.courseId || undefined,
          chapterIds: form.chapterScope === 'custom' ? form.selectedChapters : undefined,
          questionTypes: form.questionTypes,
          difficulty: form.difficulty,
          count: form.questionCount,
        })
      } else {
        if (!form.documentFile) return
        const fd = new FormData()
        fd.append('file', form.documentFile)
        fd.append('questionTypes', form.questionTypes.join(','))
        fd.append('difficulty', form.difficulty)
        fd.append('count', String(form.questionCount))
        // 模拟上传进度
        setUploadProgress(30)
        result = await generateQuestionsByDocument(fd)
        setUploadProgress(100)
      }
      const questions = result.questions || []
      if (!questions || questions.length === 0) {
        setGenerationError(result.error || 'AI 未返回有效题目，请减少题目数量或重新生成')
        return
      }
      setPreviewQuestions(questions)
      if (result.error && result.failedTypes && result.failedTypes.length > 0) {
        setGenerationError(`${result.error}（已保留生成成功的题目，可继续发布或重新生成）`)
      }
      setCurrentStep(2)
    } catch (err: any) {
      setGenerationError(err.response?.data?.message || '生成题目失败，请重试')
    } finally {
      setGenerating(false)
    }
  }

  // 下一步
  const handleNext = async () => {
    const err = validateStep(currentStep)
    if (err) {
      setGenerationError(err)
      return
    }
    setGenerationError('')
    if (currentStep === 1) {
      await handleGeneratePreview()
    } else if (currentStep === 2) {
      setCurrentStep(3)
    } else {
      setCurrentStep((s) => Math.min(s + 1, steps.length - 1))
    }
  }

  // 上一步
  const handlePrev = () => {
    setGenerationError('')
    setCurrentStep((s) => Math.max(s - 1, 0))
  }

  // 提交发布
  const handlePublish = async () => {
    const err = validateStep(0) || validateStep(1) || validateStep(2)
    if (err) {
      setGenerationError(err)
      return
    }
    setSubmitting(true)
    try {
      await publishExamHomework({
        type: form.type,
        classId: Number(form.classId),
        title: form.title,
        description: form.description,
        startTime: form.startTime,
        endTime: form.endTime,
        timeLimit: form.timeLimit,
        totalScore: form.totalScore,
        passScore: form.passScore,
        publishMode: form.publishMode,
        scheduledTime: form.publishMode === 'scheduled' ? form.scheduledTime : undefined,
        questionMode: form.questionMode,
        courseId: form.questionMode === 'ai-range' ? Number(form.courseId) : undefined,
        chapterIds: form.questionMode === 'ai-range' && form.chapterScope === 'custom'
          ? form.selectedChapters
          : undefined,
        questionTypes: form.questionTypes,
        difficulty: form.difficulty,
        questionCount: form.questionCount,
        questions: previewQuestions,
      })
      setPublishOpen(false)
      loadList()
    } catch (err: any) {
      setGenerationError(err.response?.data?.message || '发布失败')
    } finally {
      setSubmitting(false)
    }
  }

  // 删除
  const handleDelete = async (id: number) => {
    if (!confirm('确定删除该考试/作业吗？')) return
    try {
      await deleteExamHomework(id)
      loadList()
    } catch (err: any) {
      alert(err.response?.data?.message || '删除失败')
    }
  }

  // 状态切换
  const handleToggleStatus = async (item: ExamHomeworkItem) => {
    const newStatus = item.status === 1 ? 0 : 1
    try {
      await toggleExamHomeworkStatus(item.id, newStatus)
      loadList()
    } catch (err: any) {
      alert(err.response?.data?.message || '操作失败')
    }
  }

  // 打开提交列表
  const openSubmissions = async (item: ExamHomeworkItem) => {
    setSubmissionsExam(item)
    setSubmissionsOpen(true)
    setSubmissions([])
    setExpandedSubmissionId(null)
    setSubmissionsLoading(true)
    try {
      const data = await getExamSubmissions(item.id)
      setSubmissions(data || [])
    } catch (err: any) {
      alert(err?.response?.data?.message || '加载提交列表失败')
    } finally {
      setSubmissionsLoading(false)
    }
  }

  // 调整简答题分数
  const openAdjustScore = (answer: any) => {
    setAdjustingAnswerId(answer.id)
    setAdjustForm({
      score: Math.min(answer.maxScore ?? 0, Math.max(0, Number(answer.finalScore ?? answer.autoScore ?? 0))),
      comment: answer.teacherComment || answer.aiComment || '',
    })
  }

  const confirmAdjustScore = async (submission: any) => {
    if (adjustingAnswerId == null) return
    try {
      const res = await adjustSubmissionAnswerScore(adjustingAnswerId, {
        finalScore: Number(adjustForm.score),
        teacherComment: adjustForm.comment,
      })
      alert(`调整成功！新分数：${res.newScore}分，剩余可调整次数：${res.remainingAdjust}`)
      // 刷新该提交
      setAdjustingAnswerId(null)
      const data = await getExamSubmissions(submissionsExam!.id)
      setSubmissions(data || [])
    } catch (err: any) {
      alert(err?.response?.data?.message || '调整失败')
    }
  }

  // 文件选择
  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file) {
      updateForm('documentFile', file)
      updateForm('documentName', file.name)
    }
  }

  const getStatusBadge = (status: number) => {
    switch (status) {
      case 1:
        return <Badge variant="success">进行中</Badge>
      case 0:
        return <Badge variant="secondary">未发布</Badge>
      case 2:
        return <Badge variant="outline">已结束</Badge>
      default:
        return <Badge variant="secondary">未知</Badge>
    }
  }

  const getTypeBadge = (type: 'exam' | 'homework') => {
    return type === 'exam'
      ? <Badge className="bg-orange-100 text-orange-700 hover:bg-orange-100">考试</Badge>
      : <Badge className="bg-blue-100 text-blue-700 hover:bg-blue-100">作业</Badge>
  }

  return (
    <div className="space-y-6">
      {/* 页面标题 */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-neutral-900">考试作业</h1>
          <p className="text-neutral-500 mt-1 text-sm">发布和管理考试、作业，支持 AI 出题与文档识别</p>
        </div>
        <Button
          className="gap-1"
          onClick={(e) => {
            e.stopPropagation()
            e.preventDefault()
            console.log('[ExamHomework] 点击发布考试/作业按钮')
            try {
              openPublish()
              console.log('[ExamHomework] openPublish 执行完成')
            } catch (err: any) {
              console.error('[ExamHomework] openPublish 异常:', err)
              alert('打开发布窗口失败：' + (err?.message || String(err)))
            }
          }}
        >
          <Plus className="w-4 h-4" />
          发布考试/作业
        </Button>
      </div>

      {/* 筛选区 */}
      <Card>
        <CardContent className="p-4">
          <div className="flex flex-wrap items-center gap-4">
            <div className="flex items-center gap-2">
              <span className="text-sm text-neutral-500">班级</span>
              <select
                className="h-9 rounded-md border border-neutral-200 bg-white px-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary-200"
                value={filterClassId}
                onChange={(e) => setFilterClassId(e.target.value ? Number(e.target.value) : '')}
              >
                <option value="">全部班级</option>
                {classes.map((c) => (
                  <option key={c.id} value={c.id}>{c.className}</option>
                ))}
              </select>
            </div>

            <div className="flex items-center gap-2">
              <span className="text-sm text-neutral-500">类型</span>
              <select
                className="h-9 rounded-md border border-neutral-200 bg-white px-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary-200"
                value={filterType}
                onChange={(e) => setFilterType(e.target.value as any)}
              >
                <option value="all">全部</option>
                <option value="exam">考试</option>
                <option value="homework">作业</option>
              </select>
            </div>

            <div className="flex items-center gap-2">
              <span className="text-sm text-neutral-500">状态</span>
              <select
                className="h-9 rounded-md border border-neutral-200 bg-white px-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary-200"
                value={filterStatus}
                onChange={(e) => setFilterStatus(e.target.value as any)}
              >
                <option value="all">全部</option>
                <option value="published">进行中</option>
                <option value="draft">未发布</option>
                <option value="ended">已结束</option>
              </select>
            </div>

            <div className="flex-1 min-w-[200px]">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
                <Input
                  placeholder="搜索标题或班级..."
                  className="pl-9"
                  value={searchKeyword}
                  onChange={(e) => setSearchKeyword(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && loadList()}
                />
              </div>
            </div>

            <Button variant="outline" onClick={loadList} disabled={loading}>
              {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : '刷新'}
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* 列表区 */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">考试作业列表</CardTitle>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="h-[300px] flex items-center justify-center text-neutral-400">
              <Loader2 className="w-5 h-5 animate-spin mr-2" />
              加载中...
            </div>
          ) : error ? (
            <div className="h-[300px] flex items-center justify-center text-danger">{error}</div>
          ) : filteredList.length === 0 ? (
            <div className="h-[300px] flex flex-col items-center justify-center text-neutral-400">
              <FileText className="w-12 h-12 mb-3 opacity-30" />
              <p>暂无考试/作业</p>
              <Button
                variant="outline"
                className="mt-4"
                onClick={(e) => {
                  e.stopPropagation()
                  e.preventDefault()
                  openPublish()
                }}
              >
                立即发布
              </Button>
            </div>
          ) : (
            <div className="space-y-3">
              {filteredList.map((item) => (
                <SpotlightCard
                  key={item.id}
                  className="flex items-center justify-between p-4"
                  border
                >
                  <div className="flex items-start gap-4">
                    <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${
                      item.type === 'exam' ? 'bg-orange-100 text-orange-600' : 'bg-blue-100 text-blue-600'
                    }`}>
                      <FileText className="w-5 h-5" />
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="font-semibold text-neutral-900">{item.title}</h3>
                        {getTypeBadge(item.type)}
                        {getStatusBadge(item.status)}
                      </div>
                      <p className="text-sm text-neutral-500 mt-1 line-clamp-1">{item.description || '暂无描述'}</p>
                      <div className="flex items-center gap-4 mt-2 text-xs text-neutral-500">
                        <span className="flex items-center gap-1">
                          <Users className="w-3.5 h-3.5" />
                          {item.className}
                        </span>
                        <span className="flex items-center gap-1">
                          <Calendar className="w-3.5 h-3.5" />
                          {item.startTime} ~ {item.endTime}
                        </span>
                        <span className="flex items-center gap-1">
                          <Clock className="w-3.5 h-3.5" />
                          限时 {item.timeLimit} 分钟
                        </span>
                        <span>总分 {item.totalScore} 分</span>
                        <span>题目 {item.questionCount} 道</span>
                      </div>
                    </div>
                  </div>
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" size="sm" onClick={() => openSubmissions(item)} title="查看提交">
                      <Users className="w-4 h-4 mr-1" />
                      提交
                    </Button>
                    <Button variant="ghost" size="icon" className="w-8 h-8" title="查看">
                      <Eye className="w-4 h-4" />
                    </Button>
                    <Button variant="ghost" size="icon" className="w-8 h-8" title="编辑">
                      <Edit3 className="w-4 h-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleToggleStatus(item)}
                    >
                      {item.status === 1 ? '下架' : '发布'}
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="w-8 h-8 text-danger hover:text-danger"
                      onClick={() => handleDelete(item.id)}
                    >
                      <Trash2 className="w-4 h-4" />
                    </Button>
                  </div>
                </SpotlightCard>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* 发布弹窗 */}
      <Dialog open={publishOpen} onOpenChange={setPublishOpen}>
        <DialogContent
          className="max-w-3xl max-h-[90vh] overflow-y-auto bg-white"
          style={{ pointerEvents: 'auto', zIndex: 9999 }}
        >
          <DialogHeader>
            <DialogTitle>发布{form.type === 'exam' ? '考试' : '作业'}</DialogTitle>
          </DialogHeader>

          {/* 步骤条 */}
          <div className="flex items-center justify-between mb-6 mt-2">
            {steps.map((step, idx) => (
              <div key={step} className="flex items-center gap-2">
                <div
                  className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium ${
                    idx <= currentStep
                      ? 'bg-primary-550 text-white'
                      : 'bg-neutral-100 text-neutral-500'
                  }`}
                >
                  {idx < currentStep ? <CheckCircle2 className="w-4 h-4" /> : idx + 1}
                </div>
                <span className={`text-sm ${idx <= currentStep ? 'text-neutral-900' : 'text-neutral-400'}`}>
                  {step}
                </span>
                {idx < steps.length - 1 && (
                  <ChevronRight className="w-4 h-4 text-neutral-300 ml-2" />
                )}
              </div>
            ))}
          </div>

          {generationError && (
            <div className="mb-4 p-3 rounded-lg bg-red-50 text-red-600 text-sm flex items-start gap-2">
              <AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />
              {generationError}
            </div>
          )}

          {/* 步骤1：基础信息 */}
          {currentStep === 0 && (
            <div className="space-y-4">
              <div>
                <Label>发布类型</Label>
                <div className="flex gap-3 mt-2">
                  {[
                    { value: 'exam', label: '考试', desc: '限时完成，计入成绩' },
                    { value: 'homework', label: '作业', desc: '课后练习，巩固知识' },
                  ].map((t) => (
                    <div
                      key={t.value}
                      onClick={() => updateForm('type', t.value as any)}
                      className={`flex-1 p-4 rounded-xl border cursor-pointer transition-all ${
                        form.type === t.value
                          ? 'border-primary-550 bg-primary-50'
                          : 'border-neutral-200 hover:border-primary-200'
                      }`}
                    >
                      <div className="font-semibold">{t.label}</div>
                      <div className="text-xs text-neutral-500 mt-1">{t.desc}</div>
                    </div>
                  ))}
                </div>
              </div>

              <div>
                <Label htmlFor="classId">选择班级</Label>
                <select
                  id="classId"
                  className="mt-1.5 w-full h-10 rounded-md border border-neutral-200 bg-white px-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary-200"
                  value={form.classId}
                  onChange={(e) => updateForm('classId', e.target.value ? Number(e.target.value) : '')}
                >
                  <option value="">请选择班级</option>
                  {classes.map((c) => (
                    <option key={c.id} value={c.id}>{c.className}</option>
                  ))}
                </select>
              </div>

              <div>
                <Label htmlFor="title">标题</Label>
                <Input
                  id="title"
                  className="mt-1.5"
                  placeholder="例如：Python 基础期中考试"
                  value={form.title}
                  onChange={(e) => updateForm('title', e.target.value)}
                />
              </div>

              <div>
                <Label htmlFor="description">描述</Label>
                <textarea
                  id="description"
                  rows={3}
                  className="mt-1.5 w-full rounded-md border border-neutral-200 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-200 resize-none"
                  placeholder="请输入考试/作业说明"
                  value={form.description}
                  onChange={(e) => updateForm('description', e.target.value)}
                />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <Label htmlFor="startTime">开始时间</Label>
                  <Input
                    id="startTime"
                    type="datetime-local"
                    className="mt-1.5"
                    value={form.startTime}
                    onChange={(e) => updateForm('startTime', e.target.value)}
                  />
                </div>
                <div>
                  <Label htmlFor="endTime">截止时间</Label>
                  <Input
                    id="endTime"
                    type="datetime-local"
                    className="mt-1.5"
                    value={form.endTime}
                    onChange={(e) => updateForm('endTime', e.target.value)}
                  />
                </div>
              </div>

              <div className="grid grid-cols-3 gap-4">
                <div>
                  <Label htmlFor="timeLimit">限时（分钟）</Label>
                  <Input
                    id="timeLimit"
                    type="number"
                    min={1}
                    className="mt-1.5"
                    value={form.timeLimit}
                    onChange={(e) => updateForm('timeLimit', Number(e.target.value))}
                  />
                </div>
                <div>
                  <Label htmlFor="totalScore">总分</Label>
                  <Input
                    id="totalScore"
                    type="number"
                    min={1}
                    className="mt-1.5"
                    value={form.totalScore}
                    onChange={(e) => updateForm('totalScore', Number(e.target.value))}
                  />
                </div>
                <div>
                  <Label htmlFor="passScore">及格分</Label>
                  <Input
                    id="passScore"
                    type="number"
                    min={0}
                    className="mt-1.5"
                    value={form.passScore}
                    onChange={(e) => updateForm('passScore', Number(e.target.value))}
                  />
                </div>
              </div>

              <div>
                <Label>发布方式</Label>
                <div className="flex items-center gap-4 mt-2">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="radio"
                      name="publishMode"
                      checked={form.publishMode === 'immediate'}
                      onChange={() => updateForm('publishMode', 'immediate')}
                    />
                    <span className="text-sm">立即发布</span>
                  </label>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input
                      type="radio"
                      name="publishMode"
                      checked={form.publishMode === 'scheduled'}
                      onChange={() => updateForm('publishMode', 'scheduled')}
                    />
                    <span className="text-sm">定时发布</span>
                  </label>
                </div>
                {form.publishMode === 'scheduled' && (
                  <Input
                    type="datetime-local"
                    className="mt-3"
                    value={form.scheduledTime}
                    onChange={(e) => updateForm('scheduledTime', e.target.value)}
                  />
                )}
              </div>
            </div>
          )}

          {/* 步骤2：出题方式 */}
          {currentStep === 1 && (
            <div className="space-y-5">
              <div>
                <Label>出题方式</Label>
                <div className="grid grid-cols-2 gap-4 mt-2">
                  {[
                    { value: 'ai-range', label: 'AI 按范围出题', desc: '选择课程章节，AI 自动生成题目' },
                    { value: 'ai-document', label: 'AI 识别文档出题', desc: '上传文档，AI 识别知识点出题' },
                  ].map((m) => (
                    <div
                      key={m.value}
                      onClick={() => updateForm('questionMode', m.value as any)}
                      className={`p-4 rounded-xl border cursor-pointer transition-all ${
                        form.questionMode === m.value
                          ? 'border-primary-550 bg-primary-50'
                          : 'border-neutral-200 hover:border-primary-200'
                      }`}
                    >
                      <div className="font-semibold">{m.label}</div>
                      <div className="text-xs text-neutral-500 mt-1">{m.desc}</div>
                    </div>
                  ))}
                </div>
              </div>

              {form.questionMode === 'ai-range' ? (
                <>
                  <div>
                    <Label htmlFor="courseId">选择课程</Label>
                    <select
                      id="courseId"
                      className="mt-1.5 w-full h-10 rounded-md border border-neutral-200 bg-white px-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary-200"
                      value={form.courseId}
                      onChange={(e) => updateForm('courseId', e.target.value ? Number(e.target.value) : '')}
                    >
                      <option value="">请选择课程</option>
                      {courses.map((c) => (
                        <option key={c.courseId} value={c.courseId}>{c.courseName}</option>
                      ))}
                    </select>
                  </div>

                  <div>
                    <Label>章节范围</Label>
                    <div className="flex items-center gap-4 mt-2">
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input
                          type="radio"
                          name="chapterScope"
                          checked={form.chapterScope === 'all'}
                          onChange={() => updateForm('chapterScope', 'all')}
                        />
                        <span className="text-sm">全部章节</span>
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input
                          type="radio"
                          name="chapterScope"
                          checked={form.chapterScope === 'custom'}
                          onChange={() => updateForm('chapterScope', 'custom')}
                        />
                        <span className="text-sm">指定章节</span>
                      </label>
                    </div>
                    {form.chapterScope === 'custom' && (
                      <div className="mt-3 p-3 rounded-lg bg-neutral-50 text-sm text-neutral-500">
                        暂无可选章节（需要后端提供章节列表接口）
                      </div>
                    )}
                  </div>
                </>
              ) : (
                <div>
                  <Label>上传文档</Label>
                  <div
                    onClick={() => fileInputRef.current?.click()}
                    className="mt-1.5 border-2 border-dashed border-neutral-200 rounded-xl p-8 text-center cursor-pointer hover:border-primary-300 hover:bg-primary-50/30 transition-colors"
                  >
                    <Upload className="w-8 h-8 mx-auto text-neutral-400 mb-2" />
                    <p className="text-sm text-neutral-600">
                      {form.documentName || '点击上传 Word / PDF / TXT 文档'}
                    </p>
                    <p className="text-xs text-neutral-400 mt-1">支持 .docx、.pdf、.txt</p>
                  </div>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept=".docx,.pdf,.txt,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain"
                    className="hidden"
                    onChange={handleFileChange}
                  />
                </div>
              )}

              <div>
                <Label>题型设置</Label>
                <div className="flex flex-wrap gap-2 mt-2">
                  {questionTypeOptions.map((qt) => (
                    <label
                      key={qt.value}
                      className={`px-3 py-1.5 rounded-full text-sm border cursor-pointer transition-colors ${
                        form.questionTypes.includes(qt.value)
                          ? 'bg-primary-50 border-primary-300 text-primary-700'
                          : 'bg-white border-neutral-200 text-neutral-600 hover:border-primary-200'
                      }`}
                    >
                      <input
                        type="checkbox"
                        className="hidden"
                        checked={form.questionTypes.includes(qt.value)}
                        onChange={(e) => {
                          if (e.target.checked) {
                            updateForm('questionTypes', [...form.questionTypes, qt.value])
                          } else {
                            updateForm(
                              'questionTypes',
                              form.questionTypes.filter((v) => v !== qt.value)
                            )
                          }
                        }}
                      />
                      {qt.label}
                    </label>
                  ))}
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <Label htmlFor="difficulty">难度</Label>
                  <select
                    id="difficulty"
                    className="mt-1.5 w-full h-10 rounded-md border border-neutral-200 bg-white px-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary-200"
                    value={form.difficulty}
                    onChange={(e) => updateForm('difficulty', e.target.value)}
                  >
                    {difficultyOptions.map((d) => (
                      <option key={d.value} value={d.value}>{d.label}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <Label htmlFor="questionCount">题目数量</Label>
                  <Input
                    id="questionCount"
                    type="number"
                    min={1}
                    max={100}
                    className="mt-1.5"
                    value={form.questionCount}
                    onChange={(e) => updateForm('questionCount', Number(e.target.value))}
                  />
                </div>
              </div>
            </div>
          )}

          {/* 步骤3：题目预览 */}
          {currentStep === 2 && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="font-semibold">题目预览（共 {previewQuestions.length} 道）</h3>
                <Button variant="outline" size="sm" onClick={() => setCurrentStep(1)}>
                  重新生成
                </Button>
              </div>

              {previewQuestions.length === 0 ? (
                <div className="h-[200px] flex flex-col items-center justify-center text-neutral-400 border border-dashed rounded-xl">
                  <AlertCircle className="w-8 h-8 mb-2 opacity-50" />
                  暂无题目，请返回上一步生成
                </div>
              ) : (
                <div className="space-y-3 max-h-[400px] overflow-y-auto pr-1">
                  {previewQuestions.map((q, idx) => (
                    <div key={idx} className="p-4 rounded-xl border border-neutral-100 bg-neutral-50/50">
                      <div className="flex items-start gap-3">
                        <span className="text-sm font-medium text-primary-600 mt-0.5">{idx + 1}.</span>
                        <div className="flex-1">
                          <p className="text-sm font-medium text-neutral-900">
                            <MathText text={q.content} />
                          </p>
                          {q.options && q.options.length > 0 && (
                            <div className="mt-2 space-y-1">
                              {q.options.map((opt, i) => (
                                <div key={i} className="text-sm text-neutral-600 pl-2">
                                  {String.fromCharCode(65 + i)}. <MathText text={opt} />
                                </div>
                              ))}
                            </div>
                          )}
                          <div className="mt-2 text-xs text-neutral-400">
                            题型：{questionTypeOptions.find((o) => o.value === q.type)?.label || q.type} · 分值：{q.score}分
                          </div>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* 步骤4：发布确认 */}
          {currentStep === 3 && (
            <div className="space-y-4">
              <h3 className="font-semibold">发布信息确认</h3>
              <div className="rounded-xl border border-neutral-100 divide-y">
                <div className="flex justify-between p-3 text-sm">
                  <span className="text-neutral-500">发布类型</span>
                  <span className="font-medium">{form.type === 'exam' ? '考试' : '作业'}</span>
                </div>
                <div className="flex justify-between p-3 text-sm">
                  <span className="text-neutral-500">标题</span>
                  <span className="font-medium">{form.title}</span>
                </div>
                <div className="flex justify-between p-3 text-sm">
                  <span className="text-neutral-500">班级</span>
                  <span className="font-medium">
                    {classes.find((c) => c.id === Number(form.classId))?.className || '-'}
                  </span>
                </div>
                <div className="flex justify-between p-3 text-sm">
                  <span className="text-neutral-500">时间</span>
                  <span className="font-medium">{form.startTime} ~ {form.endTime}</span>
                </div>
                <div className="flex justify-between p-3 text-sm">
                  <span className="text-neutral-500">限时/总分/及格分</span>
                  <span className="font-medium">{form.timeLimit}分钟 / {form.totalScore}分 / {form.passScore}分</span>
                </div>
                <div className="flex justify-between p-3 text-sm">
                  <span className="text-neutral-500">出题方式</span>
                  <span className="font-medium">
                    {form.questionMode === 'ai-range' ? 'AI 按范围出题' : 'AI 识别文档出题'}
                  </span>
                </div>
                <div className="flex justify-between p-3 text-sm">
                  <span className="text-neutral-500">题目数量</span>
                  <span className="font-medium">{previewQuestions.length} 道</span>
                </div>
                <div className="flex justify-between p-3 text-sm">
                  <span className="text-neutral-500">发布方式</span>
                  <span className="font-medium">
                    {form.publishMode === 'immediate' ? '立即发布' : `定时发布（${form.scheduledTime}）`}
                  </span>
                </div>
              </div>
            </div>
          )}

          {/* 底部按钮 */}
          <div className="flex justify-between mt-6 pt-4 border-t">
            <Button variant="outline" onClick={() => setPublishOpen(false)} disabled={submitting}>
              取消
            </Button>
            <div className="flex gap-2">
              {currentStep > 0 && (
                <Button variant="outline" onClick={handlePrev} disabled={submitting || generating}>
                  <ChevronLeft className="w-4 h-4 mr-1" />
                  上一步
                </Button>
              )}
              {currentStep < steps.length - 1 ? (
                <Button onClick={handleNext} disabled={submitting || generating}>
                  {generating && <Loader2 className="w-4 h-4 animate-spin mr-1" />}
                  {currentStep === 1 ? '生成题目' : '下一步'}
                  <ChevronRight className="w-4 h-4 ml-1" />
                </Button>
              ) : (
                <Button onClick={handlePublish} disabled={submitting}>
                  {submitting && <Loader2 className="w-4 h-4 animate-spin mr-1" />}
                  确认发布
                </Button>
              )}
            </div>
          </div>

          {/* 文档上传进度 */}
          {form.questionMode === 'ai-document' && uploadProgress > 0 && uploadProgress < 100 && (
            <div className="mt-4">
              <div className="text-xs text-neutral-500 mb-1">文档识别中...</div>
              <Progress value={uploadProgress} className="h-2" />
            </div>
          )}

          {/* 生成题目全屏遮罩，阻止误点击导致弹窗关闭或步骤跳转 */}
          {generating && (
            <div className="absolute inset-0 z-50 flex flex-col items-center justify-center bg-white/80 backdrop-blur-sm rounded-lg">
              <Loader2 className="w-10 h-10 animate-spin text-primary-550 mb-4" />
              <p className="text-sm font-medium text-neutral-700">AI 正在分批生成题目</p>
              <p className="text-xs text-neutral-500 mt-1">请勿关闭窗口或点击其他区域</p>
            </div>
          )}
        </DialogContent>
      </Dialog>

      {/* 提交列表弹窗 */}
      <Dialog open={submissionsOpen} onOpenChange={(o) => !o && setSubmissionsOpen(false)}>
        <DialogContent className="max-w-5xl max-h-[90vh] overflow-hidden flex flex-col bg-white">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Users className="w-5 h-5 text-primary-550" />
              学生提交列表
              {submissionsExam && (
                <span className="text-sm font-normal text-neutral-500 ml-2">
                  （{submissionsExam.title} · {submissions.length} 份提交）
                </span>
              )}
            </DialogTitle>
          </DialogHeader>

          <div className="flex-1 overflow-y-auto -mr-6 pr-6">
            {submissionsLoading ? (
              <div className="py-16 text-center text-neutral-400 flex items-center justify-center">
                <Loader2 className="w-5 h-5 animate-spin mr-2" /> 加载提交中...
              </div>
            ) : submissions.length === 0 ? (
              <div className="py-16 text-center text-neutral-400">
                <FileText className="w-12 h-12 mx-auto mb-3 opacity-30" />
                <p>暂无学生提交</p>
              </div>
            ) : (
              <div className="space-y-3">
                {submissions.map((s) => {
                  const expanded = expandedSubmissionId === s.id
                  const totalFinal = (s.answers || []).reduce(
                    (sum: number, a: any) => sum + Number(a.finalScore ?? a.autoScore ?? 0),
                    0
                  )
                  return (
                    <Card key={s.id} className="overflow-hidden">
                      <div
                        role="button"
                        tabIndex={0}
                        className="flex items-center justify-between p-4 cursor-pointer hover:bg-neutral-50 focus:outline-none focus:ring-2 focus:ring-primary-300"
                        onClick={() => setExpandedSubmissionId(expanded ? null : s.id)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            setExpandedSubmissionId(expanded ? null : s.id)
                          }
                        }}
                      >
                        <div className="flex items-center gap-3">
                          <div className="w-10 h-10 rounded-full bg-primary-100 text-primary-600 flex items-center justify-center font-semibold">
                            {(s.studentName || '?').slice(0, 1)}
                          </div>
                          <div>
                            <div className="font-medium text-neutral-900">
                              {s.studentName || '未知学生'}
                              <span className="text-xs text-neutral-400 ml-2">{s.studentNo || ''}</span>
                            </div>
                            <div className="text-xs text-neutral-500 mt-0.5">
                              提交时间：{s.submitTime || '-'} · 状态：
                              <Badge variant={s.status === 'completed' ? 'success' : 'secondary'} className="ml-1 text-xs">
                                {s.status === 'completed' ? '已完成' : s.status || '进行中'}
                              </Badge>
                            </div>
                          </div>
                        </div>
                        <div className="flex items-center gap-4">
                          <div className="text-right">
                            <div className="text-xs text-neutral-400">AI 评分 / 最终分</div>
                            <div className="font-semibold">
                              <span className="text-neutral-500">{s.autoScore ?? '-'}</span>
                              <span className="mx-2 text-neutral-300">/</span>
                              <span className="text-primary-600">{totalFinal ?? s.totalScore ?? '-'}</span>
                            </div>
                          </div>
                          <ChevronRight
                            className={`w-5 h-5 text-neutral-400 transition-transform ${expanded ? 'rotate-90' : ''}`}
                          />
                        </div>
                      </div>
                      {expanded && (
                        <div className="border-t border-neutral-100 bg-neutral-50/50 p-4 space-y-3">
                          {(s.answers || []).map((a: any) => {
                            const isShort = a.questionType === 'short_answer'
                            const adjusting = adjustingAnswerId === a.id
                            return (
                              <div key={a.id} className="p-4 rounded-xl border border-neutral-100 bg-white">
                                <div className="flex items-start gap-3 mb-2">
                                  <Badge variant="outline" className="flex-shrink-0 mt-0.5">
                                    {TYPE_LABEL[a.questionType] || a.questionType}
                                  </Badge>
                                  <div className="flex-1">
                                    <div className="text-sm font-medium text-neutral-900 whitespace-pre-wrap">
                                      {a.questionContent}
                                    </div>
                                    {a.questionType?.includes('choice') && a.referenceAnswer && (
                                      <div className="mt-1 text-xs text-success font-medium">
                                        参考答案：{a.referenceAnswer}
                                      </div>
                                    )}
                                  </div>
                                  <div className="text-right text-sm min-w-[100px]">
                                    <div className="text-neutral-500 text-xs">满分 {a.maxScore}</div>
                                    <div className="font-semibold text-neutral-800 mt-0.5">
                                      {a.finalScore ?? a.autoScore ?? 0} 分
                                      {isShort && a.adjustCount != null && a.adjustCount > 0 && (
                                        <span className="text-xs text-orange-500 ml-1">（已调整{a.adjustCount}次）</span>
                                      )}
                                    </div>
                                    {a.autoScore != null && a.finalScore != null && a.finalScore !== a.autoScore && (
                                      <div className="text-xs text-neutral-400 mt-0.5">
                                        AI 初始：{a.autoScore}
                                      </div>
                                    )}
                                  </div>
                                </div>
                                <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm border-t border-neutral-50 pt-3">
                                  <div>
                                    <div className="text-xs text-neutral-400 mb-1">学生答案</div>
                                    <div className="p-2 bg-neutral-50 rounded whitespace-pre-wrap text-neutral-700">
                                      {a.userAnswer || <span className="text-neutral-400">（未作答）</span>}
                                    </div>
                                  </div>
                                  <div>
                                    <div className="text-xs text-neutral-400 mb-1 flex items-center justify-between">
                                      <span>AI 评分{isShort && ' / 教师评语'}</span>
                                      {isShort && (
                                        !adjusting ? (
                                          <Button
                                            size="sm"
                                            variant="outline"
                                            disabled={a.adjustCount != null && a.adjustCount >= 2}
                                            onClick={(e) => {
                                              e.stopPropagation()
                                              openAdjustScore(a)
                                            }}
                                          >
                                            调整分数
                                          </Button>
                                        ) : (
                                          <div className="flex gap-1">
                                            <Button
                                              size="sm"
                                              onClick={(e) => {
                                                e.stopPropagation()
                                                confirmAdjustScore(s)
                                              }}
                                            >
                                              保存
                                            </Button>
                                            <Button
                                              size="sm"
                                              variant="outline"
                                              onClick={(e) => {
                                                e.stopPropagation()
                                                setAdjustingAnswerId(null)
                                              }}
                                            >
                                              取消
                                            </Button>
                                          </div>
                                        )
                                      )}
                                    </div>
                                    {adjusting && isShort ? (
                                      <div className="space-y-2 p-2 border border-primary-200 rounded bg-primary-50/50">
                                        <div className="flex items-center gap-2">
                                          <Label className="text-xs whitespace-nowrap">分数 (0-{a.maxScore})</Label>
                                          <Input
                                            type="number"
                                            min={0}
                                            max={a.maxScore}
                                            value={adjustForm.score}
                                            onChange={(e) =>
                                              setAdjustForm({
                                                ...adjustForm,
                                                score: Math.min(a.maxScore, Math.max(0, Number(e.target.value) || 0)),
                                              })
                                            }
                                            className="h-8 w-24"
                                          />
                                        </div>
                                        <div>
                                          <Label className="text-xs">教师评语</Label>
                                          <Input
                                            value={adjustForm.comment}
                                            onChange={(e) =>
                                              setAdjustForm({ ...adjustForm, comment: e.target.value })
                                            }
                                            placeholder="可输入评语"
                                            className="h-8 mt-1"
                                          />
                                        </div>
                                        <div className="text-xs text-orange-600">
                                          提示：每题最多可调整 2 次（已调整{a.adjustCount ?? 0}次）
                                        </div>
                                      </div>
                                    ) : (
                                      <div className="p-2 bg-neutral-50 rounded text-neutral-700 space-y-1">
                                        {a.aiComment && (
                                          <div className="text-xs">
                                            <span className="text-neutral-400">AI 评语：</span>
                                            {a.aiComment}
                                          </div>
                                        )}
                                        {a.teacherComment && (
                                          <div className="text-xs">
                                            <span className="text-neutral-400">教师评语：</span>
                                            <span className="text-primary-700 font-medium">{a.teacherComment}</span>
                                          </div>
                                        )}
                                        {!a.aiComment && !a.teacherComment && (
                                          <span className="text-neutral-400">-</span>
                                        )}
                                      </div>
                                    )}
                                  </div>
                                </div>
                              </div>
                            )
                          })}
                        </div>
                      )}
                    </Card>
                  )
                })}
              </div>
            )}
          </div>

          <div className="flex justify-end pt-4 mt-4 border-t">
            <Button variant="outline" onClick={() => setSubmissionsOpen(false)}>
              关闭
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}

const TYPE_LABEL: Record<string, string> = {
  single_choice: '单选题',
  multiple_choice: '多选题',
  true_false: '判断题',
  fill_blank: '填空题',
  short_answer: '简答题',
}

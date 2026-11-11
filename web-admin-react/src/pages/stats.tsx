import { useEffect, useMemo, useState, type CSSProperties } from 'react'
import { Select } from '@/components/ui/select'
import { useNavigate } from 'react-router-dom'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { MetricCard } from '@/components/ui/metric-card'
import { HtmlMath, MathText } from '@/components/rich-text'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog'
import {
  AreaChart,
  Area,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  RadarChart,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  Radar,
  Cell,
  LineChart,
  Line,
} from 'recharts'
import {
  Users,
  Activity,
  Clock,
  Award,
  FileText,
  ListChecks,
  CheckCircle2,
  XCircle,
  HelpCircle,
  MinusCircle,
  Eye,
  ArrowLeft,
  Search,
  ChevronDown,
  ChevronRight,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { useRole } from '@/hooks/use-role'
import {
  getTeacherStats,
  getTeacherTrend,
  getClassSummary,
  getStudentStats,
  getStudentFocusQuiz,
  getStudentChapterProgress,
  getStudentExamRecords,
  getStudentExamDetails,
} from '@/api/stats'
import type {
  TeacherStats,
  TrendItem,
  ClassSummaryItem,
  StudentStats,
  StudentFocusQuiz,
  StudentCourseChapterProgress,
  StudentExamRecord,
  StudentExamDetail,
  StudentExamAnswerDetail,
} from '@/types'

/**
 * BoardUI 图表基线：网格 / 坐标轴 / 提示框全页复用。
 * 曲线统一品牌色，条形按排名递减透明度，不再使用多色分类。
 */
const GRID_COLOR = '#ebebeb'
const AXIS_COLOR = '#a3a3a3'
const TICK = { fontSize: 12, fill: AXIS_COLOR }
const TOOLTIP_STYLE: CSSProperties = {
  borderRadius: '10px',
  border: '1px solid #ebebeb',
  background: '#ffffff',
  boxShadow: '0 1px 1px rgba(0,0,0,0.04), 0 4px 4px rgba(0,0,0,0.02)',
  fontSize: '12px',
}

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

function getCorrectnessLabel(isCorrect: number) {
  switch (isCorrect) {
    case 1:
      return { text: '正确', icon: CheckCircle2, className: 'bg-emerald-50 text-emerald-700 border-emerald-200' }
    case 0:
      return { text: '错误', icon: XCircle, className: 'bg-rose-50 text-rose-600 border-rose-200' }
    case -1:
      return { text: '不会', icon: HelpCircle, className: 'bg-amber-50 text-amber-700 border-amber-200' }
    case -2:
      return { text: '跳过', icon: MinusCircle, className: 'bg-neutral-50 text-ink-secondary border-line-separator' }
    default:
      return { text: '未知', icon: HelpCircle, className: 'bg-neutral-50 text-ink-secondary border-line-separator' }
  }
}

function questionTypeLabel(type?: string) {
  const map: Record<string, string> = {
    single_choice: '单选题',
    multiple_choice: '多选题',
    true_false: '判断题',
    short_answer: '简答题',
    fill_blank: '填空题',
  }
  return map[type || ''] || type || '未知题型'
}

function difficultyLabelOf(d?: number) {
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

export function StatsPage() {
  const { isAdmin } = useRole()
  const navigate = useNavigate()
  const [stats, setStats] = useState<TeacherStats | null>(null)
  const [trend, setTrend] = useState<TrendItem[]>([])
  const [classSummary, setClassSummary] = useState<ClassSummaryItem[]>([])
  const [majorFilter, setMajorFilter] = useState('all')
  const [classFilter, setClassFilter] = useState('all')
  const [studentQuery, setStudentQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const classMajors = useMemo(() => {
    const set = new Set<string>()
    classSummary.forEach((item) => {
      if (item.major) set.add(item.major)
    })
    return Array.from(set).sort((a, b) => a.localeCompare(b, 'zh-CN'))
  }, [classSummary])

  const classesForFilter = useMemo(
    () =>
      majorFilter === 'all'
        ? classSummary
        : classSummary.filter((item) => item.major === majorFilter),
    [classSummary, majorFilter]
  )

  const visibleClassSummary = useMemo(
    () =>
      majorFilter === 'all'
        ? classSummary
        : classSummary.filter((item) => item.major === majorFilter),
    [classSummary, majorFilter]
  )

  const selectedClass = useMemo(
    () =>
      classFilter === 'all'
        ? null
        : classSummary.find((item) => String(item.classId) === classFilter) ?? null,
    [classFilter, classSummary]
  )

  const classStudents = useMemo(() => {
    if (!selectedClass) return []
    return (stats?.students ?? [])
      .filter((student) => student.className === selectedClass.name)
      .sort(
        (a, b) => Number(b.online) - Number(a.online) || b.totalSeconds - a.totalSeconds
      )
  }, [stats, selectedClass])

  const [selectedStudentId, setSelectedStudentId] = useState<number | null>(null)
  const [studentStats, setStudentStats] = useState<StudentStats | null>(null)
  const [studentLoading, setStudentLoading] = useState(false)
  const [studentError, setStudentError] = useState('')

  const [focusQuiz, setFocusQuiz] = useState<StudentFocusQuiz | null>(null)
  const [chapterProgress, setChapterProgress] = useState<StudentCourseChapterProgress[]>([])
  const [examRecords, setExamRecords] = useState<StudentExamRecord[]>([])

  // 专注刷题tab：专注记录折叠状态、刷题记录筛选条件
  const [focusExpanded, setFocusExpanded] = useState(false)
  const [quizSubjectFilter, setQuizSubjectFilter] = useState('all')
  const [quizTimeFilter, setQuizTimeFilter] = useState('all')

  const [profileTab, setProfileTab] = useState('profile')
  const [detailOpen, setDetailOpen] = useState(false)
  const [selectedExam, setSelectedExam] = useState<StudentExamRecord | null>(null)
  const [examDetail, setExamDetail] = useState<StudentExamDetail | null>(null)
  const [examDetailLoading, setExamDetailLoading] = useState(false)
  const [examDetailError, setExamDetailError] = useState('')

  // 加载总览统计和趋势
  useEffect(() => {
    const fetchData = async () => {
      setLoading(true)
      setError('')
      try {
        const [statsData, trendData] = await Promise.all([
          getTeacherStats(),
          getTeacherTrend(),
        ])
        setStats(statsData)
        setTrend(trendData || [])
      } catch (err: any) {
        setError(err.response?.data?.message || err.response?.data?.error || '数据加载失败')
      } finally {
        setLoading(false)
      }
    }
    fetchData()
  }, [])

  // 加载班级学习概览（未选中学生时的右侧面板）
  useEffect(() => {
    const fetchClassSummary = async () => {
      try {
        const data = await getClassSummary()
        setClassSummary(data || [])
      } catch {
        setClassSummary([])
      }
    }
    fetchClassSummary()
  }, [])

  // 选中学生时加载画像、专注刷题、章节进度、考试作业（一次加载）
  useEffect(() => {
    if (selectedStudentId == null) {
      setStudentStats(null)
      setFocusQuiz(null)
      setChapterProgress([])
      setExamRecords([])
      setStudentError('')
      setProfileTab('profile')
      return
    }
    const fetchStudent = async () => {
      setStudentLoading(true)
      setStudentError('')
      setFocusExpanded(false)
      setQuizSubjectFilter('all')
      setQuizTimeFilter('all')
      try {
        const [profile, fq, cp, exams] = await Promise.all([
          getStudentStats(selectedStudentId),
          getStudentFocusQuiz(selectedStudentId),
          getStudentChapterProgress(selectedStudentId),
          getStudentExamRecords(selectedStudentId),
        ])
        setStudentStats(profile)
        setFocusQuiz(fq)
        setChapterProgress(cp || [])
        setExamRecords(exams || [])
      } catch (err: any) {
        setStudentError(err.response?.data?.message || err.response?.data?.error || '学生数据加载失败')
      } finally {
        setStudentLoading(false)
      }
    }
    fetchStudent()
  }, [selectedStudentId])

  const openExamDetail = async (record: StudentExamRecord) => {
    if (!selectedStudentId || !record.submissionId) return
    setSelectedExam(record)
    setDetailOpen(true)
    setExamDetailLoading(true)
    setExamDetailError('')
    try {
      const data = await getStudentExamDetails(selectedStudentId, record.examId)
      setExamDetail(data)
    } catch (err: any) {
      setExamDetailError(err.response?.data?.message || err.response?.data?.error || '作答明细加载失败')
    } finally {
      setExamDetailLoading(false)
    }
  }

  if (loading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-10 w-40" />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, index) => (
            <Skeleton key={index} className="h-[132px] rounded-3xl" />
          ))}
        </div>
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Skeleton className="h-[520px] rounded-3xl" />
          <Skeleton className="h-[520px] rounded-3xl lg:col-span-2" />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center min-h-[400px]">
        <div className="text-danger">{error}</div>
      </div>
    )
  }

  const cards = [
    {
      label: isAdmin ? '学生总数' : '授课学生',
      value: stats?.totalStudents ?? 0,
      icon: Users,
    },
    {
      label: '今日在线',
      value: stats?.onlineToday ?? 0,
      icon: Activity,
    },
    {
      label: '平均专注时长',
      value: `${stats?.avgFocusMinutes ?? 0} 分钟`,
      icon: Clock,
    },
    {
      label: '答题正确率',
      value: `${stats?.quizAccuracy ?? 0}%`,
      icon: Award,
    },
  ]

  const classRankingData = stats?.classFocusRanking || []
  const students = stats?.students || []
  const studentKeyword = studentQuery.trim().toLowerCase()
  const filteredStudents = studentKeyword
    ? students.filter(
        (student) =>
          student.realName.toLowerCase().includes(studentKeyword) ||
          student.studentNo.toLowerCase().includes(studentKeyword)
      )
    : students
  // 图表日期统一短格式 M/D，避免 7 个 ISO 日期刻度互相挤压、末端被裁切
  const trendSeries = trend.map((item) => {
    const matched = item.date.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/)
    return {
      ...item,
      date: matched ? `${Number(matched[2])}/${Number(matched[3])}` : item.date,
    }
  })

  // 刷题记录筛选与趋势数据
  const quizSessions = focusQuiz?.quizSessions || []
  const quizSubjects = Array.from(new Set(quizSessions.map((q) => q.subject || '综合刷题')))
  const filteredQuizSessions = quizSessions.filter((qs) => {
    if (quizSubjectFilter !== 'all' && (qs.subject || '综合刷题') !== quizSubjectFilter) return false
    if (quizTimeFilter !== 'all') {
      const days = Number(quizTimeFilter)
      const created = new Date(qs.createdAt).getTime()
      if (Number.isNaN(created) || Date.now() - created > days * 86400000) return false
    }
    return true
  })
  // 趋势按时间正序，x轴取月-日
  const accuracyTrend = [...filteredQuizSessions]
    .reverse()
    .map((qs, i) => ({
      date: (formatDateTime(qs.createdAt).split(' ')[0] || '').slice(5) || `第${i + 1}次`,
      accuracy: parseInt(qs.accuracy || '0', 10),
    }))
  const totalFocusMinutes = (focusQuiz?.focusSessions || []).reduce(
    (s, f) => s + (f.durationMinutes || 0),
    0
  )

  return (
    <div className="space-y-6">
      <div>
        {/* 副标题（不展示）：管理员=查看班级学习数据和趋势分析；教师=查看我的教学数据和学生情况 */}
        <h1 className="text-display-4-medium text-ink-primary">
          {isAdmin ? '学习统计' : '教学统计'}
        </h1>
      </div>

      {/* 统计卡片：与数据总览共用 BoardUI MetricCard，图标弱化为中性色 */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 lg:gap-5">
        {cards.map((card) => (
          <MetricCard
            key={card.label}
            label={card.label}
            value={card.value}
            icon={card.icon}
          />
        ))}
      </div>

      <Tabs defaultValue="students">
        <TabsList>
          <TabsTrigger value="students">学生列表</TabsTrigger>
          <TabsTrigger value="classRanking">班级排行</TabsTrigger>
          <TabsTrigger value="trend">近7天趋势</TabsTrigger>
        </TabsList>

        {/* 学生列表 + 学习画像 */}
        <TabsContent value="students" className="mt-6">
          <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
            {/* 学生列表：窄栏，选中态用中性底色而非品牌色 */}
            <Card className="lg:col-span-1 self-start">
              <CardHeader className="flex-row items-center justify-between space-y-0">
                <CardTitle>学生列表</CardTitle>
                <span className="text-caption-1-regular text-ink-tertiary tnum">
                  {studentKeyword
                    ? `${filteredStudents.length}/${students.length}`
                    : students.length}
                </span>
              </CardHeader>
              <CardContent>
                <div className="relative mb-3">
                  <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-tertiary" />
                  <Input
                    value={studentQuery}
                    onChange={(event) => setStudentQuery(event.target.value)}
                    placeholder="搜索姓名或学号"
                    aria-label="搜索学生姓名或学号"
                    className="h-9 pl-9"
                  />
                </div>
                {filteredStudents.length > 0 ? (
                  <div className="scrollbar-thin max-h-[calc(100vh-520px)] min-h-[240px] space-y-1 overflow-y-auto pr-1">
                    {filteredStudents.map((s) => (
                      <button
                        key={s.id}
                        type="button"
                        data-testid="student-list-item"
                        onClick={() => setSelectedStudentId(s.id)}
                        className={cn(
                          'w-full rounded-lg border px-3 py-2.5 text-left transition-colors',
                          selectedStudentId === s.id
                            ? 'border-line-separator bg-neutral-100'
                            : 'border-transparent hover:bg-neutral-50'
                        )}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate text-body-2-medium text-ink-primary">
                            {s.realName}
                          </span>
                          <span className="inline-flex shrink-0 items-center gap-1.5 text-caption-1-regular text-ink-tertiary">
                            <span
                              className={cn(
                                'h-1.5 w-1.5 rounded-full',
                                s.online ? 'bg-emerald-500' : 'bg-neutral-300'
                              )}
                            />
                            {s.online ? '在线' : '离线'}
                          </span>
                        </div>
                        <div className="mt-0.5 truncate text-caption-1-regular text-ink-tertiary">
                          {s.studentNo} · {s.className}
                        </div>
                        <div className="mt-1.5 flex items-center gap-3 text-caption-1-regular text-ink-secondary tnum">
                          <span>今日 {Math.floor(s.todaySeconds / 60)} 分钟</span>
                          <span>累计 {Math.floor(s.totalSeconds / 3600)} 小时</span>
                        </div>
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className="h-[200px] flex items-center justify-center text-ink-tertiary">
                    {students.length === 0 ? '暂无学生数据' : '未找到匹配的学生'}
                  </div>
                )}
              </CardContent>
            </Card>

            {/* 学生学习画像 / 专注刷题 / 章节进度 / 考试作业 */}
            <Card className="lg:col-span-3">
              <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3 space-y-0">
                <CardTitle>
                  {selectedStudentId == null ? '班级学习概览' : '学生学习画像'}
                </CardTitle>
                {selectedStudentId == null ? (
                  classSummary.length > 0 && (
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="w-[140px] shrink-0">
                        <Select
                          value={majorFilter}
                          onChange={(event) => {
                            setMajorFilter(event.target.value)
                            setClassFilter('all')
                          }}
                          aria-label="选择专业"
                          className="w-full"
                        >
                          <option value="all">全部专业</option>
                          {classMajors.map((major) => (
                            <option key={major} value={major}>
                              {major}
                            </option>
                          ))}
                        </Select>
                      </div>
                      <div className="w-[160px] shrink-0">
                        <Select
                          value={classFilter}
                          onChange={(event) => setClassFilter(event.target.value)}
                          aria-label="选择班级"
                          className="w-full"
                        >
                          <option value="all">全部班级</option>
                          {classesForFilter.map((item) => (
                            <option key={item.classId} value={String(item.classId)}>
                              {item.name}
                            </option>
                          ))}
                        </Select>
                      </div>
                    </div>
                  )
                ) : (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setSelectedStudentId(null)}
                  >
                    <ArrowLeft className="h-4 w-4" />
                    返回班级概览
                  </Button>
                )}
              </CardHeader>
              <CardContent>
                {selectedStudentId == null ? (
                  selectedClass ? (
                    classStudents.length > 0 ? (
                      <div>
                        <div className="mb-2 text-caption-1-regular text-ink-tertiary">
                          共 {classStudents.length} 名学生
                        </div>
                        <div className="max-h-[520px] overflow-auto">
                          <table className="w-full min-w-[520px]">
                            <thead className="sticky top-0 z-10 bg-surface-primary">
                              <tr className="border-b border-line-separator">
                                <th className="pb-2 pr-4 text-left text-caption-2-medium font-medium text-ink-tertiary">
                                  学生
                                </th>
                                <th className="w-28 px-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                                  今日时长
                                </th>
                                <th className="w-28 px-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                                  累计时长
                                </th>
                                <th className="w-20 pl-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                                  状态
                                </th>
                              </tr>
                            </thead>
                            <tbody>
                              {classStudents.map((student) => (
                                <tr
                                  key={student.id}
                                  onClick={() => setSelectedStudentId(student.id)}
                                  title={`查看 ${student.realName} 的学习画像`}
                                  className="group cursor-pointer border-b border-line-separator transition-colors last:border-b-0 hover:bg-neutral-50"
                                >
                                  <td className="py-3 pr-4">
                                    <div className="truncate text-body-2-medium text-ink-primary group-hover:underline">
                                      {student.realName}
                                    </div>
                                    <div className="truncate text-caption-1-regular text-ink-tertiary">
                                      {student.studentNo}
                                    </div>
                                  </td>
                                  <td className="px-2 py-3 text-right text-body-2-regular text-ink-primary tnum">
                                    {Math.floor(student.todaySeconds / 60)}
                                    <span className="ml-0.5 text-caption-1-regular text-ink-tertiary">
                                      分钟
                                    </span>
                                  </td>
                                  <td className="px-2 py-3 text-right text-body-2-regular text-ink-primary tnum">
                                    {Math.floor(student.totalSeconds / 3600)}
                                    <span className="ml-0.5 text-caption-1-regular text-ink-tertiary">
                                      小时
                                    </span>
                                  </td>
                                  <td className="pl-2 py-3 text-right text-caption-1-regular text-ink-tertiary">
                                    <span className="inline-flex items-center gap-1.5">
                                      <span
                                        className={cn(
                                          'h-1.5 w-1.5 rounded-full',
                                          student.online ? 'bg-emerald-500' : 'bg-neutral-300'
                                        )}
                                      />
                                      {student.online ? '在线' : '离线'}
                                    </span>
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    ) : (
                      <div className="h-[300px] flex flex-col items-center justify-center gap-1.5 text-body-2-regular text-ink-tertiary border border-dashed border-line-separator rounded-lg">
                        <span>该班级暂无学生数据</span>
                        <span className="text-caption-1-regular">{selectedClass.name}</span>
                      </div>
                    )
                  ) : visibleClassSummary.length > 0 ? (
                    /* 班级概览改为表格：数值统一中性色，横向对齐便于逐班比较 */
                    <div className="max-h-[560px] overflow-auto">
                      <table className="w-full min-w-[720px]">
                        <thead className="sticky top-0 z-10 bg-surface-primary">
                          <tr className="border-b border-line-separator">
                            <th className="pb-2 pr-4 text-left text-caption-2-medium font-medium text-ink-tertiary">
                              班级
                            </th>
                            <th className="w-16 px-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                              人数
                            </th>
                            <th className="w-16 px-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                              课程
                            </th>
                            <th className="w-16 px-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                              在线
                            </th>
                            <th className="w-24 px-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                              人均时长
                            </th>
                            <th className="w-20 px-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                              正确率
                            </th>
                            <th className="w-20 px-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                              完成率
                            </th>
                            <th className="w-20 pl-2 pb-2 text-right text-caption-2-medium font-medium text-ink-tertiary">
                              考试均分
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {visibleClassSummary.map((c) => (
                            <tr
                              key={c.classId}
                              onClick={() => setClassFilter(String(c.classId))}
                              title={`查看 ${c.name} 的学生`}
                              className="group cursor-pointer border-b border-line-separator transition-colors last:border-b-0 hover:bg-neutral-50"
                            >
                              <td className="py-3 pr-4">
                                <div className="min-w-0">
                                  <div className="truncate text-body-2-medium text-ink-primary group-hover:underline">
                                    {c.name}
                                  </div>
                                  <div className="truncate text-caption-1-regular text-ink-tertiary">
                                    {[c.grade, c.major].filter(Boolean).join(' · ') ||
                                      '专业年级未设置'}
                                  </div>
                                </div>
                              </td>
                              <td className="px-2 py-3 text-right text-body-2-regular text-ink-primary tnum">
                                {c.studentCount}
                              </td>
                              <td className="px-2 py-3 text-right text-body-2-regular text-ink-secondary tnum">
                                {c.courseCount}
                              </td>
                              <td className="px-2 py-3 text-right text-body-2-regular text-ink-primary tnum">
                                {c.onlineCount > 0 ? (
                                  <span className="inline-flex items-center gap-1.5">
                                    <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
                                    {c.onlineCount}
                                  </span>
                                ) : (
                                  <span className="text-ink-tertiary">0</span>
                                )}
                              </td>
                              <td className="px-2 py-3 text-right text-body-2-regular text-ink-primary tnum">
                                {c.avgMinutes}
                                <span className="ml-0.5 text-caption-1-regular text-ink-tertiary">
                                  分钟
                                </span>
                              </td>
                              <td className="px-2 py-3 text-right text-body-2-regular text-ink-primary tnum">
                                {c.accuracy}%
                              </td>
                              <td className="px-2 py-3 text-right text-body-2-regular text-ink-primary tnum">
                                {c.completionRate}%
                              </td>
                              <td className="pl-2 py-3 text-right text-body-2-regular text-ink-primary tnum">
                                {c.examAvgScore}
                                <span className="ml-0.5 text-caption-1-regular text-ink-tertiary">
                                  分
                                </span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <div className="h-[300px] flex items-center justify-center text-ink-tertiary text-body-2-regular border border-dashed border-line-separator rounded-lg">
                      {classSummary.length === 0 ? '暂无班级数据' : '没有匹配的班级'}
                    </div>
                  )
                ) : studentLoading ? (
                  <div className="h-[500px] flex items-center justify-center text-ink-tertiary">
                    加载中...
                  </div>
                ) : studentError ? (
                  <div className="h-[500px] flex items-center justify-center text-danger">
                    {studentError}
                  </div>
                ) : (
                  <Tabs value={profileTab} onValueChange={setProfileTab} className="w-full">
                    <TabsList className="mb-4">
                      <TabsTrigger value="profile">学习画像</TabsTrigger>
                      <TabsTrigger value="focusQuiz">专注刷题</TabsTrigger>
                      <TabsTrigger value="chapters">章节进度</TabsTrigger>
                      <TabsTrigger value="exams">考试作业</TabsTrigger>
                    </TabsList>

                    {/* 学习画像 */}
                    <TabsContent value="profile" className="mt-0">
                      <div className="space-y-6">
                        {/* 学生基础信息 */}
                        <div className="flex items-center justify-between p-4 bg-neutral-50 rounded-lg flex-wrap gap-4">
                          <div>
                            <div className="text-body-2-medium text-ink-primary">
                              {studentStats?.info.realName}
                            </div>
                            <div className="text-caption-1-regular text-ink-tertiary mt-0.5">
                              {studentStats?.info.studentNo} · {studentStats?.info.className}
                            </div>
                          </div>
                          <div className="flex gap-8 text-sm">
                            <div>
                              <div className="text-title-2-semibold text-ink-primary tnum">
                                {studentStats?.totalFocusMinutes}
                              </div>
                              <div className="text-caption-1-regular text-ink-tertiary">
                                累计专注(分钟)
                              </div>
                            </div>
                            <div>
                              <div className="text-title-2-semibold text-ink-primary tnum">
                                {studentStats?.loginDays}
                              </div>
                              <div className="text-caption-1-regular text-ink-tertiary">登录天数</div>
                            </div>
                            <div>
                              <div className="text-title-2-semibold text-ink-primary tnum">
                                {studentStats?.classAvgMinutes}
                              </div>
                              <div className="text-caption-1-regular text-ink-tertiary">
                                班级平均(分钟)
                              </div>
                            </div>
                          </div>
                        </div>

                        {/* 六维能力雷达图 */}
                        <div>
                          <h4 className="text-body-medium text-ink-primary mb-3">六维能力雷达图</h4>
                          <div className="h-72">
                            {studentStats?.scores && studentStats.scores.length > 0 ? (
                              <ResponsiveContainer width="100%" height="100%">
                                <RadarChart data={studentStats.scores}>
                                  <PolarGrid stroke={GRID_COLOR} />
                                  <PolarAngleAxis dataKey="name" tick={{ fontSize: 12, fill: AXIS_COLOR }} />
                                  <PolarRadiusAxis
                                    angle={30}
                                    domain={[0, 10]}
                                    tick={{ fontSize: 10, fill: AXIS_COLOR }}
                                    axisLine={false}
                                  />
                                  <Radar
                                    name="能力值"
                                    dataKey="value"
                                    stroke="#3b82f6"
                                    fill="#3b82f6"
                                    fillOpacity={0.12}
                                    strokeWidth={2}
                                  />
                                  <Tooltip contentStyle={TOOLTIP_STYLE} />
                                </RadarChart>
                              </ResponsiveContainer>
                            ) : (
                              <div className="h-full flex items-center justify-center text-ink-tertiary">
                                暂无能力数据
                              </div>
                            )}
                          </div>
                        </div>

                        {/* 专注时长趋势 */}
                        <div>
                          <h4 className="text-body-medium text-ink-primary mb-3">专注时长趋势</h4>
                          <div className="h-64">
                            {studentStats?.focusTrend && studentStats.focusTrend.length > 0 ? (
                              <ResponsiveContainer width="100%" height="100%">
                                <AreaChart data={studentStats.focusTrend} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                                  <defs>
                                    <linearGradient id="colorFocus" x1="0" y1="0" x2="0" y2="1">
                                      <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.16} />
                                      <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                                    </linearGradient>
                                  </defs>
                                  <CartesianGrid strokeDasharray="4 8" stroke={GRID_COLOR} vertical={false} />
                                  <XAxis
                                    dataKey="date"
                                    tick={TICK}
                                    axisLine={false}
                                    tickLine={false}
                                    minTickGap={16}
                                  />
                                  <YAxis tick={TICK} axisLine={false} tickLine={false} width={32} />
                                  <Tooltip contentStyle={TOOLTIP_STYLE} />
                                  <Area
                                    type="monotone"
                                    dataKey="minutes"
                                    stroke="#3b82f6"
                                    strokeWidth={2}
                                    fill="url(#colorFocus)"
                                    name="专注时长(分钟)"
                                  />
                                </AreaChart>
                              </ResponsiveContainer>
                            ) : (
                              <div className="h-full flex items-center justify-center text-ink-tertiary">
                                暂无趋势数据
                              </div>
                            )}
                          </div>
                        </div>

                        {/* 课程提问统计 */}
                        {studentStats?.questions && studentStats.questions.length > 0 && (
                          <div>
                            <h4 className="text-body-medium text-ink-primary mb-3">课程提问统计</h4>
                            <div className="h-56">
                              <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={studentStats.questions} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                                  <CartesianGrid strokeDasharray="4 8" stroke={GRID_COLOR} vertical={false} />
                                  <XAxis
                                    dataKey="name"
                                    tick={TICK}
                                    axisLine={false}
                                    tickLine={false}
                                    minTickGap={8}
                                  />
                                  <YAxis tick={TICK} axisLine={false} tickLine={false} width={32} />
                                  <Tooltip contentStyle={TOOLTIP_STYLE} cursor={{ fill: '#f7f7f7' }} />
                                  <Bar dataKey="count" name="提问次数" radius={[6, 6, 0, 0]} maxBarSize={40}>
                                    {studentStats.questions.map((_, i) => (
                                      <Cell
                                        key={i}
                                        fill="#3b82f6"
                                        fillOpacity={
                                          0.4 +
                                          (0.6 * (studentStats.questions.length - i)) /
                                            studentStats.questions.length
                                        }
                                      />
                                    ))}
                                  </Bar>
                                </BarChart>
                              </ResponsiveContainer>
                            </div>
                          </div>
                        )}
                      </div>
                    </TabsContent>

                    {/* 专注刷题 */}
                    <TabsContent value="focusQuiz" className="mt-0">
                      <div className="space-y-6 max-h-[560px] overflow-y-auto pr-1">
                        {/* 专注记录：折叠为一行汇总，不常看 */}
                        <div>
                          <h4 className="text-body-medium text-ink-primary mb-3">专注记录</h4>
                          {focusQuiz?.focusSessions && focusQuiz.focusSessions.length > 0 ? (
                            <div
                              data-testid="focus-summary"
                              className="rounded-xl border border-line-separator bg-surface-primary cursor-pointer select-none"
                              onClick={() => setFocusExpanded((v) => !v)}
                            >
                              <div className="p-3 flex items-center justify-between">
                                <div className="text-body-2-regular text-ink-secondary">
                                  累计专注{' '}
                                  <span className="font-medium text-ink-primary tnum">
                                    {totalFocusMinutes}
                                  </span>{' '}
                                  分钟 · 共{' '}
                                  <span className="font-medium text-ink-primary tnum">
                                    {focusQuiz.focusSessions.length}
                                  </span>{' '}
                                  次专注学习
                                </div>
                                {focusExpanded ? (
                                  <ChevronDown className="w-4 h-4 text-ink-tertiary shrink-0" />
                                ) : (
                                  <ChevronRight className="w-4 h-4 text-ink-tertiary shrink-0" />
                                )}
                              </div>
                              {focusExpanded && (
                                <div className="space-y-2 px-3 pb-3 border-t border-line-separator pt-3">
                                  {focusQuiz.focusSessions.map((fs) => (
                                    <div
                                      key={fs.id}
                                      className="p-3 rounded-lg bg-neutral-50 flex items-center justify-between"
                                    >
                                      <div>
                                        <div className="text-body-2-medium text-ink-primary tnum">
                                          {fs.durationMinutes} 分钟
                                        </div>
                                        <div className="text-caption-1-regular text-ink-tertiary mt-0.5 tnum">
                                          {formatDateTime(fs.startedAt)} ~{' '}
                                          {formatDateTime(fs.finishedAt)}
                                        </div>
                                      </div>
                                      <Badge variant="secondary">专注</Badge>
                                    </div>
                                  ))}
                                </div>
                              )}
                            </div>
                          ) : (
                            <div className="h-[80px] flex items-center justify-center text-ink-tertiary text-body-2-regular border border-dashed border-line-separator rounded-lg">
                              暂无专注记录
                            </div>
                          )}
                        </div>

                        {/* 刷题记录：重点展示 */}
                        <div>
                          <div className="mb-3 flex items-center gap-2">
                            <h4 className="text-body-medium text-ink-primary">刷题记录</h4>
                            {quizSessions.length > 0 && (
                              <span className="text-caption-1-regular text-ink-tertiary tnum">
                                共 {quizSessions.length} 次测评
                              </span>
                            )}
                          </div>
                          {quizSessions.length > 0 ? (
                            <>
                              {/* 正确率趋势折线图 */}
                              <div className="rounded-xl border border-line-separator bg-surface-primary p-4 mb-3">
                                <div className="text-caption-1-regular text-ink-tertiary mb-2">
                                  正确率趋势
                                </div>
                                <div className="h-40">
                                  {accuracyTrend.length >= 2 ? (
                                    <ResponsiveContainer width="100%" height="100%">
                                      <LineChart data={accuracyTrend} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                                        <CartesianGrid
                                          strokeDasharray="4 8"
                                          stroke={GRID_COLOR}
                                          vertical={false}
                                        />
                                        <XAxis
                                          dataKey="date"
                                          tick={{ fontSize: 11, fill: AXIS_COLOR }}
                                          axisLine={false}
                                          tickLine={false}
                                          minTickGap={16}
                                        />
                                        <YAxis
                                          domain={[0, 100]}
                                          unit="%"
                                          tick={{ fontSize: 11, fill: AXIS_COLOR }}
                                          axisLine={false}
                                          tickLine={false}
                                          width={40}
                                        />
                                        <Tooltip
                                          contentStyle={TOOLTIP_STYLE}
                                          formatter={(value: number) => [`${value}%`, '正确率']}
                                        />
                                        <Line
                                          type="monotone"
                                          dataKey="accuracy"
                                          stroke="#3b82f6"
                                          strokeWidth={2}
                                          dot={{ r: 3, fill: '#3b82f6' }}
                                          activeDot={{ r: 5 }}
                                        />
                                      </LineChart>
                                    </ResponsiveContainer>
                                  ) : (
                                    <div className="h-full flex items-center justify-center text-caption-1-regular text-ink-tertiary">
                                      至少 2 次测评后展示趋势
                                    </div>
                                  )}
                                </div>
                              </div>

                              {/* 筛选器：学科 + 时间范围 */}
                              <div className="flex items-center gap-2 mb-3">
                                <Select
                                  value={quizSubjectFilter}
                                  onChange={(e) => setQuizSubjectFilter(e.target.value)}
                                  className="h-8 rounded-lg border border-line-separator bg-surface-primary px-2 text-caption-1-regular text-ink-primary focus:outline-hidden focus:shadow-[0_0_0_1px_#86efac,0_0_0_4px_rgb(134_239_172/0.18)]"
                                >
                                  <option value="all">全部学科</option>
                                  {quizSubjects.map((s) => (
                                    <option key={s} value={s}>
                                      {s}
                                    </option>
                                  ))}
                                </Select>
                                <Select
                                  value={quizTimeFilter}
                                  onChange={(e) => setQuizTimeFilter(e.target.value)}
                                  className="h-8 rounded-lg border border-line-separator bg-surface-primary px-2 text-caption-1-regular text-ink-primary focus:outline-hidden focus:shadow-[0_0_0_1px_#86efac,0_0_0_4px_rgb(134_239_172/0.18)]"
                                >
                                  <option value="all">全部时间</option>
                                  <option value="7">近 7 天</option>
                                  <option value="30">近 30 天</option>
                                </Select>
                                <span className="text-caption-1-regular text-ink-tertiary tnum">
                                  {filteredQuizSessions.length} 条记录
                                </span>
                              </div>

                              {/* 记录列表 */}
                              <div className="space-y-2">
                                {filteredQuizSessions.length > 0 ? (
                                  filteredQuizSessions.map((qs) => (
                                    <div
                                      key={qs.id}
                                      className="p-3 rounded-xl border border-line-separator bg-surface-primary transition-colors hover:bg-neutral-50"
                                    >
                                      <div className="flex items-center justify-between gap-3">
                                        <div className="truncate text-body-2-medium text-ink-primary">
                                          {qs.subject || '综合刷题'}
                                        </div>
                                        <div className="flex shrink-0 items-center gap-2">
                                          <Badge
                                            variant={
                                              parseInt(qs.accuracy || '0', 10) >= 60
                                                ? 'success'
                                                : 'secondary'
                                            }
                                          >
                                            正确率 {qs.accuracy}
                                          </Badge>
                                          <Button
                                            size="sm"
                                            variant="outline"
                                            onClick={() => navigate(`/quiz-report/${qs.id}`)}
                                          >
                                            <Eye className="w-3.5 h-3.5 mr-1" />
                                            查看报告
                                          </Button>
                                        </div>
                                      </div>
                                      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-caption-1-regular text-ink-secondary tnum">
                                        <span>第 {qs.sessionNo ?? '-'} 次</span>
                                        <span>难度 {difficultyLabelOf(qs.difficulty)}</span>
                                        <span>总题 {qs.totalQuestions}</span>
                                        <span>答对 {qs.correctCount}</span>
                                        <span>跳过 {qs.skipCount}</span>
                                        <span>用时 {formatDuration(qs.totalDurationSec)}</span>
                                      </div>
                                      <div className="mt-1 text-caption-1-regular text-ink-tertiary tnum">
                                        {formatDateTime(qs.createdAt)}
                                      </div>
                                    </div>
                                  ))
                                ) : (
                                  <div className="h-[100px] flex items-center justify-center text-ink-tertiary text-body-2-regular border border-dashed border-line-separator rounded-lg">
                                    暂无符合条件的刷题记录
                                  </div>
                                )}
                              </div>
                            </>
                          ) : (
                            <div className="h-[120px] flex items-center justify-center text-ink-tertiary text-body-2-regular border border-dashed border-line-separator rounded-lg">
                              暂无刷题记录
                            </div>
                          )}
                        </div>
                      </div>
                    </TabsContent>

                    {/* 章节进度 */}
                    <TabsContent value="chapters" className="mt-0">
                      <div className="space-y-4 max-h-[520px] overflow-y-auto pr-1">
                        {chapterProgress && chapterProgress.length > 0 ? (
                          chapterProgress.map((course) => (
                            <div
                              key={course.courseId}
                              className="p-4 rounded-xl border border-line-separator bg-surface-primary"
                            >
                              <div className="flex items-center justify-between mb-3">
                                <span className="truncate text-body-2-medium text-ink-primary">
                                  {course.courseName}
                                </span>
                                <Badge variant={course.completionRate === 100 ? 'success' : 'secondary'}>
                                  {course.completedChapters}/{course.totalChapters}
                                </Badge>
                              </div>
                              <div className="mb-3">
                                <div className="flex items-center justify-between text-caption-1-regular text-ink-tertiary mb-1.5 tnum">
                                  <span>完成度</span>
                                  <span>{course.completionRate}%</span>
                                </div>
                                <Progress value={course.completionRate} />
                              </div>
                              <div className="space-y-1">
                                {course.chapters.map((ch) => (
                                  <div
                                    key={ch.chapterId}
                                    className="flex items-center justify-between py-2 px-3 rounded-lg bg-neutral-50"
                                  >
                                    <div className="flex items-center gap-2 min-w-0">
                                      <ListChecks
                                        className={cn(
                                          'w-4 h-4 shrink-0',
                                          ch.completed ? 'text-emerald-500' : 'text-neutral-300'
                                        )}
                                      />
                                      <span className="text-caption-1-regular text-ink-primary truncate">
                                        第{ch.chapterNo}章 {ch.chapterName}
                                      </span>
                                    </div>
                                    {ch.completed ? (
                                      <span className="text-caption-1-regular text-emerald-600 shrink-0 tnum">
                                        {formatDateTime(ch.completedAt)?.split(' ')[0] || '已完成'}
                                      </span>
                                    ) : (
                                      <span className="text-caption-1-regular text-ink-tertiary shrink-0">
                                        未学习
                                      </span>
                                    )}
                                  </div>
                                ))}
                              </div>
                            </div>
                          ))
                        ) : (
                          <div className="h-[300px] flex items-center justify-center text-ink-tertiary text-body-2-regular border border-dashed border-line-separator rounded-lg">
                            暂无章节进度数据
                          </div>
                        )}
                      </div>
                    </TabsContent>

                    {/* 考试作业 */}
                    <TabsContent value="exams" className="mt-0">
                      <div className="space-y-3 max-h-[520px] overflow-y-auto pr-1">
                        {examRecords && examRecords.length > 0 ? (
                          examRecords.map((record) => (
                            <div
                              key={record.examId}
                              className="p-4 rounded-xl border border-line-separator bg-surface-primary"
                            >
                              <div className="flex items-start justify-between gap-4">
                                <div className="min-w-0">
                                  <div className="flex items-center gap-2">
                                    <FileText className="w-4 h-4 text-ink-tertiary shrink-0" />
                                    <span className="text-body-2-medium text-ink-primary truncate">
                                      {record.title}
                                    </span>
                                  </div>
                                  <div className="text-caption-1-regular text-ink-tertiary mt-1">
                                    {record.courseName} · {record.className}
                                  </div>
                                </div>
                                <div className="shrink-0 text-right">
                                  {record.hasSubmitted ? (
                                    <Badge variant={record.isPass ? 'success' : 'danger'}>
                                      {record.score}分
                                    </Badge>
                                  ) : (
                                    <Badge variant="secondary">未提交</Badge>
                                  )}
                                </div>
                              </div>
                              <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-caption-1-regular text-ink-secondary tnum">
                                <span>类型 {record.type === 'exam' ? '考试' : '作业'}</span>
                                <span>总分 {record.totalScore}</span>
                                <span>及格 {record.passScore}</span>
                                {record.hasSubmitted && (
                                  <>
                                    <span>用时 {formatDuration(record.durationSec)}</span>
                                    <span>提交 {formatDateTime(record.submittedAt)}</span>
                                  </>
                                )}
                              </div>
                              <div className="mt-3 flex justify-end">
                                <Button
                                  size="sm"
                                  variant="outline"
                                  disabled={!record.hasSubmitted}
                                  onClick={() => openExamDetail(record)}
                                >
                                  <Eye className="w-3.5 h-3.5 mr-1" />
                                  查看每题明细
                                </Button>
                              </div>
                            </div>
                          ))
                        ) : (
                          <div className="h-[300px] flex items-center justify-center text-ink-tertiary text-body-2-regular border border-dashed border-line-separator rounded-lg">
                            暂无考试/作业记录
                          </div>
                        )}
                      </div>
                    </TabsContent>
                  </Tabs>
                )}
              </CardContent>
            </Card>
          </div>
        </TabsContent>

        {/* 班级学习时长排行 */}
        <TabsContent value="classRanking" className="mt-6">
          <Card>
            <CardHeader>
              <CardTitle>各班级平均学习时长排行</CardTitle>
            </CardHeader>
            <CardContent>
              {classRankingData.length > 0 ? (
                <div className="h-96">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart
                      data={classRankingData}
                      layout="vertical"
                      margin={{ top: 8, right: 16, left: 0, bottom: 0 }}
                    >
                      <CartesianGrid strokeDasharray="4 8" stroke={GRID_COLOR} horizontal={false} />
                      <XAxis
                        type="number"
                        tick={TICK}
                        unit="分"
                        axisLine={false}
                        tickLine={false}
                      />
                      <YAxis
                        type="category"
                        dataKey="name"
                        tick={TICK}
                        width={140}
                        axisLine={false}
                        tickLine={false}
                      />
                      <Tooltip
                        formatter={(value: number, _name: string, props: any) => [
                          `${value} 分钟（${props.payload.studentCount}人）`,
                          '人均时长',
                        ]}
                        contentStyle={TOOLTIP_STYLE}
                        cursor={{ fill: '#f7f7f7' }}
                      />
                      <Bar dataKey="avgMinutes" name="人均学习时长（分钟）" radius={[0, 6, 6, 0]} maxBarSize={28} fill="#3b82f6">
                        {classRankingData.map((_item: any, i: number) => (
                          <Cell
                            key={i}
                            fillOpacity={
                              0.45 +
                              (0.55 * (classRankingData.length - i)) / classRankingData.length
                            }
                          />
                        ))}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <div className="h-[300px] flex items-center justify-center text-ink-tertiary text-body-2-regular">
                  暂无班级数据
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* 近7天趋势 */}
        <TabsContent value="trend" className="mt-6">
          <Card>
            <CardHeader>
              <CardTitle>近 7 天学习时长趋势</CardTitle>
            </CardHeader>
            <CardContent>
              {trend.length > 0 ? (
                <div className="h-96">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={trendSeries} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                      <defs>
                        <linearGradient id="colorTrend" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.16} />
                          <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="4 8" stroke={GRID_COLOR} vertical={false} />
                      <XAxis
                        dataKey="date"
                        tick={TICK}
                        axisLine={false}
                        tickLine={false}
                        minTickGap={16}
                      />
                      <YAxis tick={TICK} axisLine={false} tickLine={false} width={40} />
                      <Tooltip contentStyle={TOOLTIP_STYLE} />
                      <Area
                        type="monotone"
                        dataKey="minutes"
                        stroke="#3b82f6"
                        strokeWidth={2}
                        fill="url(#colorTrend)"
                        name="学习时长(分钟)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <div className="h-[300px] flex items-center justify-center text-ink-tertiary text-body-2-regular">
                  暂无趋势数据
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      {/* 考试/作业每题作答明细弹窗 */}
      <Dialog open={detailOpen} onOpenChange={setDetailOpen}>
        <DialogContent className="max-w-3xl max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{selectedExam?.title || '作答明细'}</DialogTitle>
            <DialogDescription>
              {selectedExam?.courseName} · {selectedExam?.type === 'exam' ? '考试' : '作业'}
              {examDetail && (
                <span className="ml-2">
                  得分 {examDetail.submission.totalScore}/{examDetail.examInfo.totalScore} · 用时{' '}
                  {formatDuration(examDetail.submission.durationSec)}
                </span>
              )}
            </DialogDescription>
          </DialogHeader>

          {examDetailLoading ? (
            <div className="py-12 text-center text-ink-tertiary">加载作答明细...</div>
          ) : examDetailError ? (
            <div className="py-12 text-center text-danger">{examDetailError}</div>
          ) : examDetail ? (
            <div className="space-y-4 mt-2">
              {examDetail.answers.map((answer: StudentExamAnswerDetail, idx: number) => {
                const status = getCorrectnessLabel(answer.isCorrect)
                const StatusIcon = status.icon
                const options = parseOptions(answer.options)
                return (
                  <div
                    key={idx}
                    className="p-4 rounded-xl border border-line-separator bg-surface-primary"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="text-body-2-medium text-ink-primary">
                        <span className="text-ink-tertiary mr-2">第{answer.questionIndex}题</span>
                        <span className="text-caption-1-regular text-ink-secondary border border-line-separator rounded px-1.5 py-0.5">
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

                    <HtmlMath
                      className="text-body-2-regular text-ink-primary mt-3 leading-relaxed"
                      html={answer.question || ''}
                    />

                    {options.length > 0 && (
                      <div className="mt-2 space-y-1">
                        {options.map((opt, i) => (
                          <div key={i} className="text-caption-1-regular text-ink-secondary pl-2">
                            {opt}
                          </div>
                        ))}
                      </div>
                    )}

                    <div className="mt-3 grid grid-cols-1 sm:grid-cols-2 gap-3 text-caption-1-regular">
                      <div className="p-2 rounded-lg bg-neutral-50">
                        <span className="text-ink-tertiary">学生答案：</span>
                        <MathText
                          className="text-ink-primary break-all"
                          text={answer.userAnswer || '未作答'}
                        />
                      </div>
                      <div className="p-2 rounded-lg bg-neutral-50">
                        <span className="text-ink-tertiary">正确答案：</span>
                        <MathText
                          className="text-ink-primary break-all"
                          text={answer.correctAnswer || '-'}
                        />
                      </div>
                    </div>

                    <div className="mt-2 text-caption-1-regular text-ink-secondary tnum">
                      得分 {answer.userScore ?? '-'}/{answer.maxScore ?? '-'}
                      {answer.aiScore != null && (
                        <span className="ml-3">AI评分 {answer.aiScore}</span>
                      )}
                    </div>

                    {(answer.aiComment || answer.teacherComment) && (
                      <div className="mt-2 text-caption-1-regular text-ink-secondary bg-neutral-50 p-2 rounded-lg">
                        {answer.aiComment && <div>AI评语：{answer.aiComment}</div>}
                        {answer.teacherComment && <div>教师评语：{answer.teacherComment}</div>}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  )
}

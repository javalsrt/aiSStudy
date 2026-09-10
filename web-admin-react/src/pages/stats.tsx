import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
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
  Legend,
  Cell,
  LineChart,
  Line,
} from 'recharts'
import {
  Users,
  Activity,
  Clock,
  Award,
  TrendingUp,
  Brain,
  BookOpen,
  FileText,
  ListChecks,
  CheckCircle2,
  XCircle,
  HelpCircle,
  MinusCircle,
  Eye,
  School,
  ChevronDown,
  ChevronRight,
} from 'lucide-react'
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

const chartColors = ['#5b58ff', '#06b6d4', '#10b981', '#f59e0b', '#ec4899', '#6b7280']

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
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

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
      <div className="flex items-center justify-center min-h-[400px]">
        <div className="text-neutral-400">数据加载中...</div>
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
      color: 'bg-primary-50 text-primary-550',
    },
    {
      label: '今日在线',
      value: stats?.onlineToday ?? 0,
      icon: Activity,
      color: 'bg-green-50 text-green-600',
    },
    {
      label: '平均专注时长',
      value: `${stats?.avgFocusMinutes ?? 0} 分钟`,
      icon: Clock,
      color: 'bg-orange-50 text-orange-600',
    },
    {
      label: '答题正确率',
      value: `${stats?.quizAccuracy ?? 0}%`,
      icon: Award,
      color: 'bg-cyan-50 text-cyan-600',
    },
  ]

  const classRankingData = stats?.classFocusRanking || []
  const students = stats?.students || []

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
        <h1 className="text-2xl font-bold text-neutral-900">
          {isAdmin ? '学习统计' : '教学统计'}
        </h1>
        <p className="text-neutral-500 mt-1 text-sm">
          {isAdmin ? '查看班级学习数据和趋势分析' : '查看我的教学数据和学生情况'}
        </p>
      </div>

      {/* 统计卡片 */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
        {cards.map((card, i) => {
          const Icon = card.icon
          return (
            <Card key={i}>
              <CardContent className="p-6">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="text-sm text-neutral-500 mb-1">{card.label}</p>
                    <p className="text-3xl font-bold text-neutral-900">{card.value}</p>
                  </div>
                  <div className={`w-12 h-12 rounded-xl flex items-center justify-center ${card.color}`}>
                    <Icon className="w-6 h-6" />
                  </div>
                </div>
              </CardContent>
            </Card>
          )
        })}
      </div>

      <Tabs defaultValue="students">
        <TabsList>
          <TabsTrigger value="students">学生列表</TabsTrigger>
          <TabsTrigger value="classRanking">班级排行</TabsTrigger>
          <TabsTrigger value="trend">近7天趋势</TabsTrigger>
        </TabsList>

        {/* 学生列表 + 学习画像 */}
        <TabsContent value="students" className="mt-6">
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            {/* 学生列表 */}
            <Card className="lg:col-span-1">
              <CardHeader>
                <CardTitle className="text-base flex items-center gap-2">
                  <Users className="w-4 h-4 text-primary-550" />
                  学生列表
                </CardTitle>
              </CardHeader>
              <CardContent>
                {students.length > 0 ? (
                  <div className="space-y-2 max-h-[560px] overflow-y-auto pr-1">
                    {students.map((s) => (
                      <div
                        key={s.id}
                        data-testid="student-list-item"
                        onClick={() => setSelectedStudentId(s.id)}
                        className={`p-3 rounded-lg cursor-pointer transition-colors border ${
                          selectedStudentId === s.id
                            ? 'bg-primary-50 border-primary-200'
                            : 'bg-white border-neutral-100 hover:bg-neutral-50'
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <div>
                            <div className="font-medium text-neutral-900 text-sm">
                              {s.realName}
                            </div>
                            <div className="text-xs text-neutral-500 mt-1">
                              {s.studentNo} · {s.className}
                            </div>
                          </div>
                          {s.online ? (
                            <Badge variant="success">在线</Badge>
                          ) : (
                            <Badge variant="secondary">离线</Badge>
                          )}
                        </div>
                        <div className="flex items-center gap-3 mt-2 text-xs text-neutral-500">
                          <span>今日 {Math.floor(s.todaySeconds / 60)} 分钟</span>
                          <span>累计 {Math.floor(s.totalSeconds / 3600)} 小时</span>
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="h-[200px] flex items-center justify-center text-neutral-400">
                    暂无学生数据
                  </div>
                )}
              </CardContent>
            </Card>

            {/* 学生学习画像 / 专注刷题 / 章节进度 / 考试作业 */}
            <Card className="lg:col-span-2">
              <CardHeader>
                <CardTitle className="text-base flex items-center gap-2">
                  <Award className="w-4 h-4 text-primary-550" />
                  {selectedStudentId == null ? '班级学习概览' : '学生学习画像'}
                </CardTitle>
              </CardHeader>
              <CardContent>
                {selectedStudentId == null ? (
                  classSummary.length > 0 ? (
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 max-h-[560px] overflow-y-auto pr-1">
                      {classSummary.map((c) => (
                        <div
                          key={c.classId}
                          className="rounded-xl border border-neutral-100 bg-white p-5 transition-shadow hover:shadow-sm"
                        >
                          {/* 头部：图标 + 班级名 + 专业年级 */}
                          <div className="flex items-start gap-3">
                            <div className="w-11 h-11 rounded-xl bg-primary-50 text-primary-550 flex items-center justify-center shrink-0">
                              <School className="w-6 h-6" />
                            </div>
                            <div className="min-w-0 flex-1">
                              <div className="font-semibold text-neutral-900 truncate">{c.name}</div>
                              <div className="text-xs text-neutral-500 mt-0.5 truncate">
                                {[c.grade, c.major].filter(Boolean).join(' · ') || '专业年级未设置'}
                              </div>
                            </div>
                            <Badge variant="secondary" className="shrink-0">
                              {c.studentCount} 人
                            </Badge>
                          </div>

                          {/* 标签 pill */}
                          <div className="mt-3 flex flex-wrap gap-1.5">
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-sky-50 text-sky-600 text-xs font-medium">
                              <BookOpen className="w-3.5 h-3.5" />
                              {c.courseCount} 门课程
                            </span>
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-emerald-50 text-emerald-600 text-xs font-medium">
                              <Activity className="w-3.5 h-3.5" />
                              {c.onlineCount} 人在线
                            </span>
                          </div>

                          {/* 指标区 */}
                          <div className="mt-4 pt-4 border-t border-neutral-100 grid grid-cols-2 gap-x-4 gap-y-3">
                            <div>
                              <div className="text-2xl font-bold text-primary-600">
                                {c.avgMinutes}
                                <span className="text-xs font-normal text-neutral-400 ml-1">分钟</span>
                              </div>
                              <div className="text-xs text-neutral-500 mt-0.5">人均学习时长</div>
                            </div>
                            <div>
                              <div className="text-2xl font-bold text-green-600">
                                {c.accuracy}
                                <span className="text-xs font-normal text-neutral-400 ml-1">%</span>
                              </div>
                              <div className="text-xs text-neutral-500 mt-0.5">刷题正确率</div>
                            </div>
                            <div>
                              <div className="text-2xl font-bold text-cyan-600">
                                {c.completionRate}
                                <span className="text-xs font-normal text-neutral-400 ml-1">%</span>
                              </div>
                              <div className="text-xs text-neutral-500 mt-0.5">章节完成率</div>
                            </div>
                            <div>
                              <div className="text-2xl font-bold text-orange-600">
                                {c.examAvgScore}
                                <span className="text-xs font-normal text-neutral-400 ml-1">分</span>
                              </div>
                              <div className="text-xs text-neutral-500 mt-0.5">考试平均分</div>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="h-[300px] flex items-center justify-center text-neutral-400 text-sm border border-dashed border-neutral-200 rounded-lg">
                      暂无班级数据
                    </div>
                  )
                ) : studentLoading ? (
                  <div className="h-[500px] flex items-center justify-center text-neutral-400">
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
                            <div className="font-semibold text-neutral-900">
                              {studentStats?.info.realName}
                            </div>
                            <div className="text-xs text-neutral-500 mt-1">
                              {studentStats?.info.studentNo} · {studentStats?.info.className}
                            </div>
                          </div>
                          <div className="flex gap-6 text-sm">
                            <div className="text-center">
                              <div className="text-xl font-bold text-primary-600">
                                {studentStats?.totalFocusMinutes}
                              </div>
                              <div className="text-xs text-neutral-500">累计专注(分钟)</div>
                            </div>
                            <div className="text-center">
                              <div className="text-xl font-bold text-green-600">
                                {studentStats?.loginDays}
                              </div>
                              <div className="text-xs text-neutral-500">登录天数</div>
                            </div>
                            <div className="text-center">
                              <div className="text-xl font-bold text-orange-600">
                                {studentStats?.classAvgMinutes}
                              </div>
                              <div className="text-xs text-neutral-500">班级平均(分钟)</div>
                            </div>
                          </div>
                        </div>

                        {/* 六维能力雷达图 */}
                        <div>
                          <h4 className="text-sm font-semibold text-neutral-900 mb-3">
                            六维能力雷达图
                          </h4>
                          <div className="h-72">
                            {studentStats?.scores && studentStats.scores.length > 0 ? (
                              <ResponsiveContainer width="100%" height="100%">
                                <RadarChart data={studentStats.scores}>
                                  <PolarGrid stroke="#e6e9ef" />
                                  <PolarAngleAxis dataKey="name" stroke="#7f8798" fontSize={12} />
                                  <PolarRadiusAxis angle={30} domain={[0, 10]} stroke="#d1d7e2" fontSize={10} />
                                  <Radar
                                    name="能力值"
                                    dataKey="value"
                                    stroke="#5b58ff"
                                    fill="#5b58ff"
                                    fillOpacity={0.3}
                                    strokeWidth={2}
                                  />
                                  <Legend />
                                  <Tooltip
                                    contentStyle={{
                                      backgroundColor: '#fff',
                                      border: '1px solid #e6e9ef',
                                      borderRadius: '12px',
                                      boxShadow: '0 4px 16px rgba(0,0,0,0.06)',
                                    }}
                                  />
                                </RadarChart>
                              </ResponsiveContainer>
                            ) : (
                              <div className="h-full flex items-center justify-center text-neutral-400">
                                暂无能力数据
                              </div>
                            )}
                          </div>
                        </div>

                        {/* 专注时长趋势 */}
                        <div>
                          <h4 className="text-sm font-semibold text-neutral-900 mb-3">
                            专注时长趋势
                          </h4>
                          <div className="h-64">
                            {studentStats?.focusTrend && studentStats.focusTrend.length > 0 ? (
                              <ResponsiveContainer width="100%" height="100%">
                                <AreaChart data={studentStats.focusTrend}>
                                  <defs>
                                    <linearGradient id="colorFocus" x1="0" y1="0" x2="0" y2="1">
                                      <stop offset="5%" stopColor="#5b58ff" stopOpacity={0.3} />
                                      <stop offset="95%" stopColor="#5b58ff" stopOpacity={0} />
                                    </linearGradient>
                                  </defs>
                                  <CartesianGrid strokeDasharray="3 3" stroke="#e6e9ef" vertical={false} />
                                  <XAxis
                                    dataKey="date"
                                    stroke="#7f8798"
                                    fontSize={12}
                                    axisLine={false}
                                    tickLine={false}
                                  />
                                  <YAxis
                                    stroke="#7f8798"
                                    fontSize={12}
                                    axisLine={false}
                                    tickLine={false}
                                  />
                                  <Tooltip
                                    contentStyle={{
                                      backgroundColor: '#fff',
                                      border: '1px solid #e6e9ef',
                                      borderRadius: '12px',
                                      boxShadow: '0 4px 16px rgba(0,0,0,0.06)',
                                    }}
                                  />
                                  <Area
                                    type="monotone"
                                    dataKey="minutes"
                                    stroke="#5b58ff"
                                    strokeWidth={2}
                                    fill="url(#colorFocus)"
                                    name="专注时长(分钟)"
                                  />
                                </AreaChart>
                              </ResponsiveContainer>
                            ) : (
                              <div className="h-full flex items-center justify-center text-neutral-400">
                                暂无趋势数据
                              </div>
                            )}
                          </div>
                        </div>

                        {/* 课程提问统计 */}
                        {studentStats?.questions && studentStats.questions.length > 0 && (
                          <div>
                            <h4 className="text-sm font-semibold text-neutral-900 mb-3">
                              课程提问统计
                            </h4>
                            <div className="h-56">
                              <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={studentStats.questions}>
                                  <CartesianGrid strokeDasharray="3 3" stroke="#e6e9ef" vertical={false} />
                                  <XAxis
                                    dataKey="name"
                                    stroke="#7f8798"
                                    fontSize={12}
                                    axisLine={false}
                                    tickLine={false}
                                  />
                                  <YAxis
                                    stroke="#7f8798"
                                    fontSize={12}
                                    axisLine={false}
                                    tickLine={false}
                                  />
                                  <Tooltip
                                    contentStyle={{
                                      backgroundColor: '#fff',
                                      border: '1px solid #e6e9ef',
                                      borderRadius: '12px',
                                      boxShadow: '0 4px 16px rgba(0,0,0,0.06)',
                                    }}
                                  />
                                  <Bar dataKey="count" name="提问次数" radius={[6, 6, 0, 0]} maxBarSize={60}>
                                    {studentStats.questions.map((_, i) => (
                                      <Cell key={i} fill={chartColors[i % chartColors.length]} />
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
                          <h4 className="text-sm font-semibold text-neutral-900 mb-3 flex items-center gap-2">
                            <Clock className="w-4 h-4 text-primary-550" />
                            专注记录
                          </h4>
                          {focusQuiz?.focusSessions && focusQuiz.focusSessions.length > 0 ? (
                            <div
                              data-testid="focus-summary"
                              className="rounded-lg border border-neutral-100 bg-white cursor-pointer select-none"
                              onClick={() => setFocusExpanded((v) => !v)}
                            >
                              <div className="p-3 flex items-center justify-between">
                                <div className="text-sm text-neutral-600">
                                  累计专注{' '}
                                  <span className="font-semibold text-neutral-900">
                                    {totalFocusMinutes}
                                  </span>{' '}
                                  分钟 · 共{' '}
                                  <span className="font-semibold text-neutral-900">
                                    {focusQuiz.focusSessions.length}
                                  </span>{' '}
                                  次专注学习
                                </div>
                                {focusExpanded ? (
                                  <ChevronDown className="w-4 h-4 text-neutral-400 shrink-0" />
                                ) : (
                                  <ChevronRight className="w-4 h-4 text-neutral-400 shrink-0" />
                                )}
                              </div>
                              {focusExpanded && (
                                <div className="space-y-2 px-3 pb-3 border-t border-neutral-100 pt-3">
                                  {focusQuiz.focusSessions.map((fs) => (
                                    <div
                                      key={fs.id}
                                      className="p-3 rounded-lg bg-neutral-50 flex items-center justify-between"
                                    >
                                      <div>
                                        <div className="text-sm font-medium text-neutral-900">
                                          {fs.durationMinutes} 分钟
                                        </div>
                                        <div className="text-xs text-neutral-500 mt-1">
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
                            <div className="h-[80px] flex items-center justify-center text-neutral-400 text-sm border border-dashed border-neutral-200 rounded-lg">
                              暂无专注记录
                            </div>
                          )}
                        </div>

                        {/* 刷题记录：重点展示 */}
                        <div>
                          <h4 className="text-sm font-semibold text-neutral-900 mb-3 flex items-center gap-2">
                            <Brain className="w-4 h-4 text-primary-550" />
                            刷题记录
                            {quizSessions.length > 0 && (
                              <span className="text-xs font-normal text-neutral-400">
                                共 {quizSessions.length} 次测评
                              </span>
                            )}
                          </h4>
                          {quizSessions.length > 0 ? (
                            <>
                              {/* 正确率趋势折线图 */}
                              <div className="rounded-xl border border-neutral-100 bg-white p-4 mb-3">
                                <div className="text-xs text-neutral-500 mb-2">正确率趋势</div>
                                <div className="h-40">
                                  {accuracyTrend.length >= 2 ? (
                                    <ResponsiveContainer width="100%" height="100%">
                                      <LineChart data={accuracyTrend}>
                                        <CartesianGrid
                                          strokeDasharray="3 3"
                                          stroke="#e6e9ef"
                                          vertical={false}
                                        />
                                        <XAxis
                                          dataKey="date"
                                          stroke="#7f8798"
                                          fontSize={11}
                                          axisLine={false}
                                          tickLine={false}
                                        />
                                        <YAxis
                                          domain={[0, 100]}
                                          unit="%"
                                          stroke="#7f8798"
                                          fontSize={11}
                                          axisLine={false}
                                          tickLine={false}
                                          width={40}
                                        />
                                        <Tooltip
                                          contentStyle={{
                                            backgroundColor: '#fff',
                                            border: '1px solid #e6e9ef',
                                            borderRadius: '12px',
                                            boxShadow: '0 4px 16px rgba(0,0,0,0.06)',
                                          }}
                                          formatter={(value: number) => [`${value}%`, '正确率']}
                                        />
                                        <Line
                                          type="monotone"
                                          dataKey="accuracy"
                                          stroke="#5b58ff"
                                          strokeWidth={2}
                                          dot={{ r: 3, fill: '#5b58ff' }}
                                          activeDot={{ r: 5 }}
                                        />
                                      </LineChart>
                                    </ResponsiveContainer>
                                  ) : (
                                    <div className="h-full flex items-center justify-center text-xs text-neutral-400">
                                      至少 2 次测评后展示趋势
                                    </div>
                                  )}
                                </div>
                              </div>

                              {/* 筛选器：学科 + 时间范围 */}
                              <div className="flex items-center gap-2 mb-3">
                                <select
                                  value={quizSubjectFilter}
                                  onChange={(e) => setQuizSubjectFilter(e.target.value)}
                                  className="h-8 rounded-md border border-neutral-200 bg-white px-2 text-xs text-neutral-700 focus:outline-none focus:ring-1 focus:ring-primary-500"
                                >
                                  <option value="all">全部学科</option>
                                  {quizSubjects.map((s) => (
                                    <option key={s} value={s}>
                                      {s}
                                    </option>
                                  ))}
                                </select>
                                <select
                                  value={quizTimeFilter}
                                  onChange={(e) => setQuizTimeFilter(e.target.value)}
                                  className="h-8 rounded-md border border-neutral-200 bg-white px-2 text-xs text-neutral-700 focus:outline-none focus:ring-1 focus:ring-primary-500"
                                >
                                  <option value="all">全部时间</option>
                                  <option value="7">近 7 天</option>
                                  <option value="30">近 30 天</option>
                                </select>
                                <span className="text-xs text-neutral-400">
                                  {filteredQuizSessions.length} 条记录
                                </span>
                              </div>

                              {/* 记录列表 */}
                              <div className="space-y-2">
                                {filteredQuizSessions.length > 0 ? (
                                  filteredQuizSessions.map((qs) => (
                                    <div
                                      key={qs.id}
                                      className="p-3 rounded-lg border border-neutral-100 bg-white"
                                    >
                                      <div className="flex items-center justify-between">
                                        <div className="text-sm font-medium text-neutral-900">
                                          {qs.subject || '综合刷题'}
                                        </div>
                                        <div className="flex items-center gap-2">
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
                                      <div className="text-xs text-neutral-500 mt-2 flex flex-wrap gap-x-4 gap-y-1">
                                        <span>第 {qs.sessionNo ?? '-'} 次</span>
                                        <span>难度 {difficultyLabelOf(qs.difficulty)}</span>
                                        <span>总题 {qs.totalQuestions}</span>
                                        <span>答对 {qs.correctCount}</span>
                                        <span>跳过 {qs.skipCount}</span>
                                        <span>用时 {formatDuration(qs.totalDurationSec)}</span>
                                      </div>
                                      <div className="text-xs text-neutral-400 mt-1">
                                        {formatDateTime(qs.createdAt)}
                                      </div>
                                    </div>
                                  ))
                                ) : (
                                  <div className="h-[100px] flex items-center justify-center text-neutral-400 text-sm border border-dashed border-neutral-200 rounded-lg">
                                    暂无符合条件的刷题记录
                                  </div>
                                )}
                              </div>
                            </>
                          ) : (
                            <div className="h-[120px] flex items-center justify-center text-neutral-400 text-sm border border-dashed border-neutral-200 rounded-lg">
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
                              className="p-4 rounded-xl border border-neutral-100 bg-white"
                            >
                              <div className="flex items-center justify-between mb-3">
                                <div className="flex items-center gap-2">
                                  <BookOpen className="w-4 h-4 text-primary-550" />
                                  <span className="text-sm font-semibold text-neutral-900">
                                    {course.courseName}
                                  </span>
                                </div>
                                <Badge variant={course.completionRate === 100 ? 'success' : 'secondary'}>
                                  {course.completedChapters}/{course.totalChapters}
                                </Badge>
                              </div>
                              <div className="mb-3">
                                <div className="flex items-center justify-between text-xs text-neutral-500 mb-1">
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
                                        className={`w-4 h-4 shrink-0 ${
                                          ch.completed ? 'text-green-500' : 'text-neutral-300'
                                        }`}
                                      />
                                      <span className="text-xs text-neutral-700 truncate">
                                        第{ch.chapterNo}章 {ch.chapterName}
                                      </span>
                                    </div>
                                    {ch.completed ? (
                                      <span className="text-xs text-green-600 shrink-0">
                                        {formatDateTime(ch.completedAt)?.split(' ')[0] || '已完成'}
                                      </span>
                                    ) : (
                                      <span className="text-xs text-neutral-400 shrink-0">未学习</span>
                                    )}
                                  </div>
                                ))}
                              </div>
                            </div>
                          ))
                        ) : (
                          <div className="h-[300px] flex items-center justify-center text-neutral-400 text-sm border border-dashed border-neutral-200 rounded-lg">
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
                              className="p-4 rounded-xl border border-neutral-100 bg-white"
                            >
                              <div className="flex items-start justify-between gap-4">
                                <div className="min-w-0">
                                  <div className="flex items-center gap-2">
                                    <FileText className="w-4 h-4 text-primary-550 shrink-0" />
                                    <span className="text-sm font-semibold text-neutral-900 truncate">
                                      {record.title}
                                    </span>
                                  </div>
                                  <div className="text-xs text-neutral-500 mt-1">
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
                              <div className="text-xs text-neutral-500 mt-3 flex flex-wrap gap-x-4 gap-y-1">
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
                          <div className="h-[300px] flex items-center justify-center text-neutral-400 text-sm border border-dashed border-neutral-200 rounded-lg">
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
              <CardTitle className="text-base flex items-center gap-2">
                <Award className="w-4 h-4 text-primary-550" />
                各班级平均学习时长排行
              </CardTitle>
            </CardHeader>
            <CardContent>
              {classRankingData.length > 0 ? (
                <div className="h-96">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={classRankingData} layout="vertical">
                      <CartesianGrid strokeDasharray="3 3" stroke="#e6e9ef" />
                      <XAxis
                        type="number"
                        tick={{ fontSize: 12, fill: '#7f8798' }}
                        unit="分"
                        axisLine={false}
                        tickLine={false}
                      />
                      <YAxis
                        type="category"
                        dataKey="name"
                        tick={{ fontSize: 12, fill: '#7f8798' }}
                        width={140}
                        axisLine={false}
                        tickLine={false}
                      />
                      <Tooltip
                        formatter={(value: number, _name: string, props: any) => [
                          `${value} 分钟（${props.payload.studentCount}人）`,
                          '人均时长',
                        ]}
                        contentStyle={{
                          backgroundColor: '#fff',
                          border: '1px solid #e6e9ef',
                          borderRadius: '12px',
                          boxShadow: '0 4px 16px rgba(0,0,0,0.06)',
                        }}
                      />
                      <Legend />
                      <Bar dataKey="avgMinutes" name="人均学习时长（分钟）" radius={[0, 6, 6, 0]} maxBarSize={80}>
                        {classRankingData.map((_, i) => (
                          <Cell key={i} fill={chartColors[i % chartColors.length]} />
                        ))}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <div className="h-[300px] flex items-center justify-center text-neutral-400">
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
              <CardTitle className="text-base flex items-center gap-2">
                <TrendingUp className="w-4 h-4 text-primary-550" />
                近 7 天学习时长趋势
              </CardTitle>
            </CardHeader>
            <CardContent>
              {trend.length > 0 ? (
                <div className="h-96">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={trend}>
                      <defs>
                        <linearGradient id="colorTrend" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#5b58ff" stopOpacity={0.3} />
                          <stop offset="95%" stopColor="#5b58ff" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" stroke="#e6e9ef" vertical={false} />
                      <XAxis
                        dataKey="date"
                        stroke="#7f8798"
                        fontSize={12}
                        axisLine={false}
                        tickLine={false}
                      />
                      <YAxis
                        stroke="#7f8798"
                        fontSize={12}
                        axisLine={false}
                        tickLine={false}
                      />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: '#fff',
                          border: '1px solid #e6e9ef',
                          borderRadius: '12px',
                          boxShadow: '0 4px 16px rgba(0,0,0,0.06)',
                        }}
                      />
                      <Area
                        type="monotone"
                        dataKey="minutes"
                        stroke="#5b58ff"
                        strokeWidth={2}
                        fill="url(#colorTrend)"
                        name="学习时长(分钟)"
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <div className="h-[300px] flex items-center justify-center text-neutral-400">
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
            <div className="py-12 text-center text-neutral-400">加载作答明细...</div>
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
                    className="p-4 rounded-xl border border-neutral-100 bg-white"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="text-sm font-medium text-neutral-900">
                        <span className="text-neutral-500 mr-2">第{answer.questionIndex}题</span>
                        <span className="text-xs text-neutral-500 border border-neutral-200 rounded px-1.5 py-0.5">
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
                      className="text-sm text-neutral-700 mt-3 leading-relaxed"
                      dangerouslySetInnerHTML={{ __html: answer.question || '' }}
                    />

                    {options.length > 0 && (
                      <div className="mt-2 space-y-1">
                        {options.map((opt, i) => (
                          <div key={i} className="text-xs text-neutral-600 pl-2">
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
                      得分 {answer.userScore ?? '-'}/{answer.maxScore ?? '-'}
                      {answer.aiScore != null && (
                        <span className="ml-3">AI评分 {answer.aiScore}</span>
                      )}
                    </div>

                    {(answer.aiComment || answer.teacherComment) && (
                      <div className="mt-2 text-xs text-neutral-500 bg-neutral-50 p-2 rounded-lg">
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

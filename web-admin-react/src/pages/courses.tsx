import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Search,
  Plus,
  ChevronLeft,
  ChevronRight,
  LayoutList,
  Grid3X3,
  RefreshCw,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useRole } from '@/hooks/use-role'
import { useDialog } from '@/hooks/use-dialog'
import {
  getTeacherCourses,
  hideCourse,
  unhideCourse,
  removeCourse,
} from '@/api/courses'
import { SchedulePlanner } from '@/components/schedule-planner'
import type { CourseItem } from '@/types'

// 班级信息（来自后端 classes 数组）
interface ClassInfo {
  classId: number
  className: string
  scheduled: boolean
  scheduleId?: number
}

const COVER_GRADIENTS = [
  'from-blue-400 to-blue-600',
  'from-emerald-400 to-emerald-600',
  'from-purple-400 to-purple-600',
  'from-orange-400 to-orange-600',
  'from-pink-400 to-pink-600',
  'from-cyan-400 to-cyan-600',
  'from-indigo-400 to-indigo-600',
  'from-amber-400 to-amber-600',
]

const getCoverGradient = (name: string) => {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) >>> 0
  }
  return COVER_GRADIENTS[hash % COVER_GRADIENTS.length]
}

const WEEK_DAYS: Record<number, string> = {
  1: '星期一',
  2: '星期二',
  3: '星期三',
  4: '星期四',
  5: '星期五',
  6: '星期六',
  7: '星期日',
}

const formatTime = (course: CourseItem) => {
  if (course.scheduleInfo) {
    return course.scheduleInfo
  }
  const parts: string[] = []
  if (course.dayOfWeek != null) {
    parts.push(WEEK_DAYS[course.dayOfWeek] || `星期${course.dayOfWeek}`)
  }
  if (course.startTime && course.endTime) {
    parts.push(`${course.startTime}-${course.endTime}`)
  }
  return parts.join(' ')
}

/** 元信息：小号标签 + 值，超长省略，绝不换行堆叠 */
function MetaItem({
  label,
  value,
  chip,
}: {
  label: string
  value?: string | null
  chip?: boolean
}) {
  const text = value && String(value).trim() ? String(value) : '-'
  return (
    <div className="flex min-w-0 items-baseline gap-1.5">
      <span className="shrink-0 text-[11px] text-neutral-400">{label}</span>
      <span
        className={
          chip
            ? 'truncate rounded-md bg-neutral-100 px-1.5 py-0.5 text-xs text-neutral-600 tabular-nums'
            : 'max-w-[180px] truncate text-[13px] text-neutral-700'
        }
        title={text}
      >
        {text}
      </span>
    </div>
  )
}

/** 时段字符串 → 紧凑标签：去掉秒与「等 N 个时段」后缀，最多展示 2 个时段，其余折叠 */
function ScheduleCell({ text }: { text?: string }) {
  if (!text || text === '-') {
    return <span className="text-neutral-400">-</span>
  }
  // 后端 scheduleInfo 可能带「等 N 个时段」/「等N个时段」后缀，先剥离，避免污染标签
  const cleaned = text.replace(/[，,]?\s*等\s*\d+\s*个?时段\s*$/, '').trim()
  const items = cleaned
    .split(/[；;、]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => s.replace(/(\d{2}:\d{2}):\d{2}/g, '$1'))
  if (items.length === 0) return <span className="text-neutral-400">-</span>
  const shown = items.slice(0, 2)
  return (
    <div className="flex flex-wrap items-center gap-1.5" title={items.join('\n')}>
      {shown.map((it, i) => (
        <span
          key={i}
          className="inline-flex items-center rounded-md bg-neutral-100 px-2 py-0.5 text-xs text-neutral-600 whitespace-nowrap tabular-nums"
        >
          {it}
        </span>
      ))}
      {items.length > shown.length && (
        <span className="text-xs text-neutral-400 whitespace-nowrap">
          +{items.length - shown.length} 个时段
        </span>
      )}
    </div>
  )
}

const normalizeCourse = (raw: any): CourseItem & { classes: ClassInfo[] } => {
  const classes: any[] = Array.isArray(raw?.classes) ? raw.classes : []
  const className =
    raw.className ||
    (classes.length > 0
      ? classes.map((c) => c?.className).filter(Boolean).join('、')
      : undefined)

  let status: number | undefined = raw.status
  if (status == null && typeof raw.active === 'boolean') {
    status = raw.active ? 1 : 0
  }
  if (status == null) status = 1

  const firstClass = classes.length > 0 ? classes[0] : null

  return {
    courseName: raw.courseName,
    teacherName: raw.teacherName,
    className,
    semester: raw.semester,
    dayOfWeek: raw.dayOfWeek,
    startTime: raw.startTime,
    endTime: raw.endTime,
    startNode: raw.startNode,
    step: raw.step,
    classroom: raw.classroom,
    weeks: raw.weeks,
    status,
    credit: raw.credit,
    hours: raw.hours != null ? raw.hours : raw.credit,
    scheduleId: firstClass?.scheduleId,
    classId: firstClass?.classId,
    scheduleInfo: raw.scheduleInfo,
    classes: classes.map((c) => ({
      classId: c.classId,
      className: c.className,
      scheduled: c.scheduled,
      scheduleId: c.scheduleId,
    })),
  }
}

export function CoursesPage() {
  const { isAdmin, user } = useRole()
  const navigate = useNavigate()
  const { alert, confirm, select, DialogComponent } = useDialog()
  const [viewMode, setViewMode] = useState<'list' | 'grid'>('list')
  const [searchText, setSearchText] = useState('')
  const [page, setPage] = useState(1)
  const [activeTab, setActiveTab] = useState<'all' | 'active' | 'inactive'>('all')
  const pageSize = 10

  const [courses, setCourses] = useState<(CourseItem & { classes: ClassInfo[] })[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [actionLoading, setActionLoading] = useState(false)

  // 排课弹窗状态
  const [plannerOpen, setPlannerOpen] = useState(false)
  const [plannerData, setPlannerData] = useState<{
    courseName: string
    classId: number | null
    className: string
    courseId: number | null
    scheduled: boolean
    totalCredit: number
    semester?: string
  }>({
    courseName: '',
    classId: null,
    className: '',
    courseId: null,
    scheduled: false,
    totalCredit: 0,
    semester: '',
  })

  const fetchCourses = async () => {
    setLoading(true)
    setError('')
    try {
      const data = await getTeacherCourses()
      setCourses((data || []).map(normalizeCourse))
    } catch (err: any) {
      setError(
        err?.response?.data?.message ||
          err?.response?.data?.error ||
          '课程数据加载失败'
      )
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchCourses()
  }, [])

  const pickClassId = async (course: CourseItem & { classes: ClassInfo[] }): Promise<number | null> => {
    if (!course.classes || course.classes.length === 0) {
      await alert({ description: '该课程暂无班级，无法操作' })
      return null
    }
    if (course.classes.length === 1) {
      return course.classes[0].classId
    }
    const choice = await select({
      title: '请选择班级',
      description: '请点击下方班级进行操作',
      options: course.classes.map((c) => ({ value: String(c.classId), label: c.className })),
      cancelText: '取消',
    })
    if (!choice) return null
    return Number(choice)
  }

  const handleToggleStatus = async (course: CourseItem & { classes: ClassInfo[] }, checked: boolean) => {
    const classId = await pickClassId(course)
    if (classId == null) return
    const targetClassName = course.classes.find((c) => c.classId === classId)?.className || '指定班级'
    const actionText = checked ? '上架' : '下架'
    const confirmed = await confirm({
      title: `${actionText}课程`,
      description: `确定要${actionText}课程「${course.courseName}」在 ${targetClassName} 吗？`,
      confirmText: '确定',
      cancelText: '取消',
    })
    if (!confirmed) return
    setActionLoading(true)
    try {
      if (checked) {
        await unhideCourse({ courseName: course.courseName, classId })
      } else {
        await hideCourse(course.courseName, classId)
      }
      await fetchCourses()
    } catch (err: any) {
      await alert({
        description: err?.response?.data?.message || err?.response?.data?.error || `${actionText}失败`,
      })
    } finally {
      setActionLoading(false)
    }
  }

  const handleRemove = async (course: CourseItem & { classes: ClassInfo[] }) => {
    const classId = await pickClassId(course)
    if (classId == null) return
    const targetClassName = course.classes.find((c) => c.classId === classId)?.className || '指定班级'
    const confirmed = await confirm({
      title: '删除课程',
      description: `确定要彻底删除课程「${course.courseName}」在 ${targetClassName} 的记录吗？此操作不可恢复！`,
      confirmText: '删除',
      cancelText: '取消',
      variant: 'danger',
    })
    if (!confirmed) return
    setActionLoading(true)
    try {
      await removeCourse(course.courseName, classId)
      await fetchCourses()
    } catch (err: any) {
      await alert({
        description: err?.response?.data?.message || err?.response?.data?.error || '删除失败',
      })
    } finally {
      setActionLoading(false)
    }
  }

  // 选择班级（多班级课程需要）
  const pickClass = async (course: CourseItem & { classes: ClassInfo[] }, action: string) => {
    if (!course.classes || course.classes.length === 0) {
      await alert({ description: '该课程暂无班级，无法操作' })
      return null
    }
    if (course.classes.length === 1) {
      return course.classes[0]
    }
    const choice = await select({
      title: `请选择要${action}的班级`,
      description: '请点击下方班级进行操作',
      options: course.classes.map((c) => ({ value: String(c.classId), label: c.className })),
      cancelText: '取消',
    })
    if (!choice) return null
    return course.classes.find((c) => String(c.classId) === choice) || null
  }

  // 打开排课弹窗（仅未排课课程使用）
  const openPlanner = async (course: CourseItem & { classes: ClassInfo[] }) => {
    const cls = await pickClass(course, '排课')
    if (!cls) return
    setPlannerData({
      courseName: course.courseName,
      classId: cls.classId,
      className: cls.className,
      courseId: null,
      scheduled: cls.scheduled,
      totalCredit: course.credit || course.hours || 0,
      semester: course.semester,
    })
    setPlannerOpen(true)
  }

  const searchedCourses = courses.filter((c) => {
    if (!searchText) return true
    const keyword = searchText.toLowerCase()
    return (
      (c.courseName || '').toLowerCase().includes(keyword) ||
      (c.teacherName || '').toLowerCase().includes(keyword) ||
      (c.className || '').toLowerCase().includes(keyword)
    )
  })

  const activeCount = searchedCourses.filter((c) => c.status === 1).length
  const inactiveCount = searchedCourses.filter((c) => c.status === 0).length

  const filteredCourses = searchedCourses.filter((c) => {
    if (activeTab === 'active') return c.status === 1
    if (activeTab === 'inactive') return c.status === 0
    return true
  })

  const totalPages = Math.max(1, Math.ceil(filteredCourses.length / pageSize))
  const currentPage = Math.min(page, totalPages)
  const pagedCourses = filteredCourses.slice(
    (currentPage - 1) * pageSize,
    currentPage * pageSize
  )

  useEffect(() => {
    setPage(1)
  }, [activeTab, searchText])

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[400px]">
        <div className="text-neutral-400">课程加载中...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[400px] gap-4">
        <div className="text-danger">{error}</div>
        <Button variant="outline" onClick={fetchCourses}>
          重新加载
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {DialogComponent}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-neutral-900">
            {isAdmin ? '课程管理' : '我的课程'}
          </h1>
          <p className="text-neutral-500 mt-1 text-sm">
            {isAdmin
              ? '管理所有课程，支持下架、删除等操作'
              : `你好，${user?.realName || '老师'}，查看你教授的课程`}
          </p>
        </div>
        {isAdmin && (
          <Button
            variant="default"
            className="gap-2"
            onClick={() => navigate('/admin/course-import')}
          >
            <Plus className="w-4 h-4" />
            导入课程
          </Button>
        )}
      </div>

      <Card>
        <CardContent className="p-5">
          <div className="flex flex-col md:flex-row gap-4 items-start md:items-center justify-between">
            <div className="flex items-center gap-3 w-full md:w-auto">
              <div className="relative flex-1 md:w-80">
                <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
                <Input
                  placeholder={
                    isAdmin ? '搜索课程名称、教师、班级...' : '搜索课程名称...'
                  }
                  value={searchText}
                  onChange={(e) => setSearchText(e.target.value)}
                  className="pl-11 h-10"
                />
              </div>
            </div>

            <div className="flex items-center gap-2 w-full md:w-auto">
              <div className="flex items-center bg-neutral-100 rounded-full p-0.5">
                <button
                  aria-label="列表视图"
                  data-testid="view-list"
                  onClick={() => setViewMode('list')}
                  className={`p-2 rounded-full transition-all ${
                    viewMode === 'list'
                      ? 'bg-white shadow-sm text-neutral-900'
                      : 'text-neutral-500 hover:text-neutral-700'
                  }`}
                >
                  <LayoutList className="w-4 h-4" />
                </button>
                <button
                  aria-label="卡片视图"
                  data-testid="view-grid"
                  onClick={() => setViewMode('grid')}
                  className={`p-2 rounded-full transition-all ${
                    viewMode === 'grid'
                      ? 'bg-white shadow-sm text-neutral-900'
                      : 'text-neutral-500 hover:text-neutral-700'
                  }`}
                >
                  <Grid3X3 className="w-4 h-4" />
                </button>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      <Tabs
        value={activeTab}
        onValueChange={(v) => setActiveTab(v as 'all' | 'active' | 'inactive')}
      >
        <TabsList>
          <TabsTrigger value="all">
            全部课程 ({searchedCourses.length})
          </TabsTrigger>
          <TabsTrigger value="active">已上架 ({activeCount})</TabsTrigger>
          <TabsTrigger value="inactive">已下架 ({inactiveCount})</TabsTrigger>
        </TabsList>

        <TabsContent value={activeTab} className="mt-6">
          {viewMode === 'list' ? (
            <Card>
              <div className="overflow-hidden">
                {/* 固定列宽 + table-fixed：避免表头/内容被挤压成竖排文字 */}
                {/* 卡片行布局：不依赖表格列宽机制，任何屏幕宽度下都不会竖排/挤压 */}
                <div className="divide-y divide-neutral-100">
                  {pagedCourses.map((course, idx) => {
                    const active = course.status === 1
                    return (
                      <div
                        key={`${course.courseName}-${idx}`}
                        className="flex flex-col gap-3 px-5 py-4 transition-colors hover:bg-neutral-50/70 lg:flex-row lg:items-center"
                      >
                        {/* 课程 */}
                        <div className="flex min-w-0 items-center gap-3 lg:w-[230px] lg:shrink-0">
                          <div
                            className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br ${getCoverGradient(
                              course.courseName || ''
                            )} text-sm font-bold text-white`}
                          >
                            {course.courseName?.charAt(0) || '?'}
                          </div>
                          <div className="min-w-0 flex-1">
                            <div
                              className="truncate text-[13.5px] font-medium text-neutral-900"
                              title={course.courseName || ''}
                              data-testid="course-name"
                            >
                              {course.courseName || '-'}
                            </div>
                            {course.hours != null && (
                              <div className="mt-0.5 text-[11px] text-neutral-400">
                                {course.hours} 课时
                              </div>
                            )}
                          </div>
                        </div>

                        {/* 教师 / 班级 / 教室 / 学期 */}
                        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-5 gap-y-1.5">
                          {isAdmin && <MetaItem label="教师" value={course.teacherName} />}
                          <MetaItem label="班级" value={course.className} />
                          <MetaItem label="教室" value={course.classroom} />
                          {course.semester && <MetaItem label="学期" value={course.semester} chip />}
                        </div>

                        {/* 上课时间 */}
                        <div className="lg:w-[300px] lg:shrink-0">
                          <div className="mb-1 text-[11px] text-neutral-400 lg:hidden">上课时间</div>
                          <ScheduleCell text={formatTime(course)} />
                        </div>

                        {/* 状态 + 操作 */}
                        <div className="flex items-center justify-between gap-4 lg:w-[232px] lg:shrink-0 lg:justify-end">
                          <div className="flex items-center gap-2 whitespace-nowrap">
                            <Switch
                              checked={active}
                              onCheckedChange={(checked) => handleToggleStatus(course, checked)}
                            />
                            <span
                              className={`text-xs font-medium ${
                                active ? 'text-success' : 'text-neutral-400'
                              }`}
                            >
                              {active ? '已上架' : '已下架'}
                            </span>
                          </div>
                          <div className="flex items-center gap-2 whitespace-nowrap">
                            <Button
                              variant="outline"
                              size="sm"
                              className="h-8 px-3 text-xs"
                              disabled={actionLoading}
                              onClick={() => { openPlanner(course) }}
                            >
                              {course.classes.some((c) => c.scheduled) ? '调课' : '排课'}
                            </Button>
                            {isAdmin && (
                              <Button
                                variant="outline"
                                size="sm"
                                className="h-8 px-3 text-xs text-danger"
                                disabled={actionLoading}
                                onClick={() => { handleRemove(course) }}
                              >
                                删除
                              </Button>
                            )}
                          </div>
                        </div>
                      </div>
                    )
                  })}
                  {pagedCourses.length === 0 && (
                    <div className="px-6 py-12 text-center text-neutral-400">暂无课程数据</div>
                  )}
                </div>
              </div>

              <div className="flex items-center justify-between px-6 py-4 border-t border-neutral-100">
                <div className="text-sm text-neutral-500">
                  共 {filteredCourses.length} 条记录
                </div>
                <div className="flex items-center gap-1">
                  <Button
                    variant="outline"
                    size="icon"
                    className="w-8 h-8 rounded-lg"
                    data-testid="pagination-prev"
                    onClick={() => setPage(Math.max(1, currentPage - 1))}
                    disabled={currentPage === 1}
                  >
                    <ChevronLeft className="w-4 h-4" />
                  </Button>
                  <Button
                    variant="default"
                    size="sm"
                    className="w-8 h-8 p-0 rounded-lg"
                  >
                    {currentPage}
                  </Button>
                  <Button
                    variant="outline"
                    size="icon"
                    className="w-8 h-8 rounded-lg"
                    data-testid="pagination-next"
                    onClick={() => setPage(Math.min(totalPages, currentPage + 1))}
                    disabled={currentPage >= totalPages}
                  >
                    <ChevronRight className="w-4 h-4" />
                  </Button>
                </div>
              </div>
            </Card>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
              {pagedCourses.map((course, idx) => {
                const active = course.status === 1
                const key = `${course.courseName}-${idx}`
                return (
                  <Card key={key} className="overflow-hidden">
                    <div
                      className={`h-36 bg-gradient-to-br ${getCoverGradient(
                        course.courseName || ''
                      )} relative`}
                    >
                      <div className="absolute top-3 right-3">
                        <Badge
                          variant={active ? 'success' : 'secondary'}
                          className="text-xs"
                        >
                          {active ? '已上架' : '已下架'}
                        </Badge>
                      </div>
                      <div className="absolute bottom-3 left-4 right-4">
                        <div className="text-xl font-bold text-white truncate">
                          {course.courseName || '-'}
                        </div>
                        <div className="text-sm text-white/80 truncate">
                          {course.teacherName || '未知教师'}
                        </div>
                      </div>
                    </div>
                    <CardContent className="p-5 space-y-3">
                      <div className="flex items-center gap-4 text-sm text-neutral-600 flex-wrap">
                        {course.className && (
                          <div>
                            <span className="text-neutral-400">班级</span>
                            <span className="ml-1 font-medium">
                              {course.className}
                            </span>
                          </div>
                        )}
                        {course.classroom && (
                          <>
                            <div className="w-px h-4 bg-neutral-200"></div>
                            <div>
                              <span className="text-neutral-400">教室</span>
                              <span className="ml-1 font-medium">
                                {course.classroom}
                              </span>
                            </div>
                          </>
                        )}
                        {course.hours != null && (
                          <>
                            <div className="w-px h-4 bg-neutral-200"></div>
                            <div>
                              <span className="text-neutral-400">课时</span>
                              <span className="ml-1 font-medium">
                                {course.hours}
                              </span>
                            </div>
                          </>
                        )}
                      </div>
                      {formatTime(course) && (
                        <div className="text-sm text-neutral-600">
                          <span className="text-neutral-400">时间：</span>
                          <span className="font-medium">{formatTime(course)}</span>
                        </div>
                      )}
                      {course.semester && (
                        <div className="text-sm text-neutral-600">
                          <span className="text-neutral-400">学期：</span>
                          <span className="font-medium">{course.semester}</span>
                        </div>
                      )}
                      <div className="flex items-center justify-between pt-3 border-t border-neutral-100">
                        <div className="flex items-center gap-2">
                          <Switch
                            checked={active}
                            onCheckedChange={(checked) =>
                              handleToggleStatus(course, checked)
                            }
                          />
                          <span className="text-xs">
                            {active ? '已上架' : '已下架'}
                          </span>
                        </div>
                        <div className="flex gap-2 flex-wrap justify-end">
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={actionLoading}
                            onClick={() => { openPlanner(course) }}
                          >
                            {course.classes.some((c) => c.scheduled) ? '调课' : '排课'}
                          </Button>
                          {isAdmin && (
                            <Button
                              variant="outline"
                              size="sm"
                              className="text-danger"
                              disabled={actionLoading}
                              onClick={() => { handleRemove(course) }}
                            >
                              删除课程
                            </Button>
                          )}
                        </div>
                      </div>
                    </CardContent>
                  </Card>
                )
              })}
              {pagedCourses.length === 0 && (
                <div className="col-span-full text-center py-12 text-neutral-400">
                  暂无课程数据
                </div>
              )}
            </div>
          )}
        </TabsContent>
      </Tabs>

      <SchedulePlanner
        open={plannerOpen}
        onOpenChange={setPlannerOpen}
        courseName={plannerData.courseName}
        classId={plannerData.classId}
        className={plannerData.className}
        courseId={plannerData.courseId}
        scheduled={plannerData.scheduled}
        totalCredit={plannerData.totalCredit}
        semester={plannerData.semester}
        onSuccess={fetchCourses}
      />

    </div>
  )
}

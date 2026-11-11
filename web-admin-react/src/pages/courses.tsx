import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, LayoutList, Grid3X3, Search, Loader2 } from 'lucide-react'
import type { ColumnDef } from '@tanstack/react-table'
import { Button } from '@/components/ui/button'
import { SegmentedControl } from '@/components/ui/segmented-control'
import { DataTable, type DataTableColumnMeta } from '@/components/ui/data-table'
import { Pagination } from '@/components/ui/pagination'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Select } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Skeleton } from '@/components/ui/skeleton'
import { useRole } from '@/hooks/use-role'
import { useDialog } from '@/hooks/use-dialog'
import {
  getTeacherCourses,
  hideCourse,
  unhideCourse,
  removeCourse,
  invalidateCoursesCache,
} from '@/api/courses'
import { SchedulePlanner } from '@/components/schedule-planner'
import { getTeacherClasses } from '@/api/teacher-class'
import type { CourseItem } from '@/types'

// 班级信息（来自后端 classes 数组）
interface ClassInfo {
  classId: number
  className: string
  scheduled: boolean
  scheduleId?: number
}

type CourseRow = CourseItem & { classes: ClassInfo[] }

const COVER_GRADIENTS = [
  'from-blue-400 to-blue-600',
  'from-emerald-400 to-emerald-600',
  'from-green-400 to-green-600',
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

/** 元信息：小号标签 + 值，超长省略；空值直接不渲染，避免「-」无效占位 */
function MetaItem({
  label,
  value,
  chip,
  className,
}: {
  label: string
  value?: string | null
  chip?: boolean
  className?: string
}) {
  const text = value && String(value).trim() ? String(value) : ''
  if (!text) return null
  return (
    <div className={`flex min-w-0 items-baseline gap-1.5 ${className || ''}`}>
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

/** 时段字符串 → 紧凑标签：无时段不渲染；最多展示 maxShown 个时段，其余折叠进提示 */
function ScheduleCell({ text, maxShown = 2 }: { text?: string; maxShown?: number }) {
  if (!text || text === '-') return null
  // 后端 scheduleInfo 可能带「等 N 个时段」/「等N个时段」后缀，先剥离，避免污染标签
  const cleaned = text.replace(/[，,]?\s*等\s*\d+\s*个?时段\s*$/, '').trim()
  const items = cleaned
    .split(/[；;、]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => s.replace(/(\d{2}:\d{2}):\d{2}/g, '$1'))
  if (items.length === 0) return null
  const shown = items.slice(0, maxShown)
  return (
    <span
      className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1 text-body-2-regular text-ink-secondary"
      title={items.join('\n')}
    >
      {shown.map((it, i) => (
        <span key={i} className="inline-flex items-center gap-1.5 whitespace-nowrap tabular-nums">
          {i > 0 && <span className="text-ink-tertiary">·</span>}
          {it}
        </span>
      ))}
      {items.length > shown.length && (
        <span className="text-caption-1-regular text-ink-tertiary whitespace-nowrap">
          +{items.length - shown.length} 时段
        </span>
      )}
    </span>
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
  const { isAdmin } = useRole()
  const navigate = useNavigate()
  const { alert, confirm, select, DialogComponent } = useDialog()
  const [viewMode, setViewMode] = useState<'list' | 'grid'>('list')
  const [searchText, setSearchText] = useState('')
  // 专业 / 班级筛选：与学习统计页的筛选方式一致，用于按班级查课程
  const [teacherClasses, setTeacherClasses] = useState<
    { id: number; className: string; major?: string | null }[]
  >([])
  const [majorFilter, setMajorFilter] = useState('all')
  const [classFilter, setClassFilter] = useState('all')
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [density, setDensity] = useState<'normal' | 'compact'>('normal')
  const [activeTab, setActiveTab] = useState<'all' | 'active' | 'inactive'>('all')

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

  const fetchCourses = async (force = false) => {
    if (force) invalidateCoursesCache()
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

  // 加载班级列表：专业/班级下拉的数据源（管理员为全校，教师为任教班级）
  useEffect(() => {
    getTeacherClasses()
      .then((data) =>
        setTeacherClasses(
          (data || []).map((c) => ({ id: c.id, className: c.className, major: c.major }))
        )
      )
      .catch(() => setTeacherClasses([]))
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

  // 专业/班级筛选：与学习统计页一致，先选专业缩小班级范围，再选具体班级
  const majorOptions = Array.from(
    new Set(teacherClasses.map((c) => c.major).filter((m): m is string => !!m))
  ).sort((a, b) => a.localeCompare(b, 'zh-CN'))

  const classOptions =
    majorFilter === 'all'
      ? teacherClasses
      : teacherClasses.filter((c) => c.major === majorFilter)

  const majorClassIds =
    majorFilter === 'all'
      ? null
      : new Set(
          teacherClasses.filter((c) => c.major === majorFilter).map((c) => String(c.id))
        )

  const classFilteredCourses = courses.filter((c) => {
    if (classFilter !== 'all') {
      return (c.classes || []).some((cls) => String(cls.classId) === classFilter)
    }
    if (majorClassIds) {
      return (c.classes || []).some((cls) => majorClassIds.has(String(cls.classId)))
    }
    return true
  })

  const searchedCourses = classFilteredCourses.filter((c) => {
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
  }, [activeTab, searchText, majorFilter, classFilter])

  // 列定义（BoardUI Data Table）：键值列可排序，状态/操作列固定
  const courseColumns: ColumnDef<CourseRow, any>[] = [
    {
      id: 'course',
      accessorFn: (row) => row.courseName || '',
      header: '课程',
      cell: ({ row }) => {
        const course = row.original
        return (
          <div className="flex min-w-0 items-center gap-2.5">
            <div
              className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-[10px] bg-gradient-to-br ${getCoverGradient(
                course.courseName || ''
              )} text-xs font-semibold text-white`}
            >
              {course.courseName?.charAt(0) || '?'}
            </div>
            <div className="min-w-0">
              <div
                className="truncate text-body-2-medium text-ink-primary"
                title={course.courseName || ''}
                data-testid="course-name"
              >
                {course.courseName || '-'}
              </div>
              {course.hours != null && (
                <div className="text-caption-2-medium text-ink-tertiary">{course.hours} 课时</div>
              )}
            </div>
          </div>
        )
      },
      meta: { className: 'min-w-[190px]' } as DataTableColumnMeta,
    },
    ...(isAdmin
      ? [
          {
            id: 'teacher',
            accessorFn: (row: CourseRow) => row.teacherName || '',
            header: '教师',
            cell: ({ row }: { row: { original: CourseRow } }) => (
              <span className="text-body-2-regular text-ink-secondary">
                {row.original.teacherName || '-'}
              </span>
            ),
          },
        ]
      : []),
    {
      id: 'className',
      accessorFn: (row) => row.className || '',
      header: '班级',
      cell: ({ row }) => (
        <span className="text-body-2-regular text-ink-secondary">{row.original.className || '-'}</span>
      ),
      meta: { className: 'min-w-[140px]' } as DataTableColumnMeta,
    },
    {
      id: 'classroom',
      accessorFn: (row) => row.classroom || '',
      header: '教室',
      cell: ({ row }) => (
        <span className="text-body-2-regular text-ink-tertiary">{row.original.classroom || '-'}</span>
      ),
      enableSorting: false,
    },
    {
      id: 'semester',
      accessorFn: (row) => row.semester || '',
      header: '学期',
      cell: ({ row }) =>
        row.original.semester ? (
          <span className="whitespace-nowrap text-body-2-regular text-ink-secondary tnum">
            {row.original.semester}
          </span>
        ) : (
          <span className="text-body-2-regular text-ink-tertiary">-</span>
        ),
    },
    {
      id: 'time',
      header: '上课时间',
      enableSorting: false,
      cell: ({ row }) => <ScheduleCell text={formatTime(row.original)} maxShown={2} />,
      meta: { className: 'min-w-[180px]' } as DataTableColumnMeta,
    },
    {
      id: 'status',
      header: '状态',
      enableSorting: false,
      cell: ({ row }) => {
        const active = row.original.status === 1
        return (
          <div className="flex items-center gap-2 whitespace-nowrap">
            <Switch
              checked={active}
              onCheckedChange={(checked) => handleToggleStatus(row.original, checked)}
            />
            <span className={`text-caption-1-medium ${active ? 'text-success' : 'text-ink-tertiary'}`}>
              {active ? '已上架' : '已下架'}
            </span>
          </div>
        )
      },
    },
    {
      id: 'actions',
      header: '操作',
      enableSorting: false,
      cell: ({ row }) => (
        <div className="flex items-center justify-end gap-1.5 whitespace-nowrap">
          <Button
            variant="outline"
            size="sm"
            className="h-7 px-2.5 text-xs"
            disabled={actionLoading}
            onClick={() => { openPlanner(row.original) }}
          >
            {row.original.classes.some((c) => c.scheduled) ? '调课' : '排课'}
          </Button>
          {isAdmin && (
            <Button
              variant="outline"
              size="sm"
              className="h-7 px-2.5 text-xs text-danger"
              disabled={actionLoading}
              onClick={() => { handleRemove(row.original) }}
            >
              删除
            </Button>
          )}
        </div>
      ),
      meta: { align: 'right' } as DataTableColumnMeta,
    },
  ]

  if (loading) {
    return (
      <div className="space-y-6">
        <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
          <Skeleton className="h-10 w-40" />
          <Skeleton className="h-9 w-28" />
        </div>
        <Skeleton className="h-9 w-72 rounded-lg" />
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Skeleton className="h-9 w-full sm:w-80" />
          <Skeleton className="h-9 w-40 rounded-lg" />
        </div>
        <Skeleton className="h-[460px] rounded-3xl" />
        <div className="flex items-center gap-2 text-body-2-regular text-ink-tertiary">
          <Loader2 className="h-4 w-4 animate-spin" />
          正在加载课程，请稍候…
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[400px] gap-4">
        <div className="text-danger">{error}</div>
        <Button variant="outline" onClick={() => fetchCourses(true)}>
          重新加载
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {DialogComponent}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <h1 className="text-display-4-medium text-ink-primary">
          {isAdmin ? '课程管理' : '我的课程'}
        </h1>
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

        <TabsContent value={activeTab} className="mt-6 space-y-4">
          {/* 搜索 + 专业/班级筛选 + 视图切换：列表/卡片两种视图共用，避免切换后控件消失 */}
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <div className="flex flex-col gap-2 md:flex-row md:items-center">
              <div className="relative w-full md:w-72">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-tertiary" />
                <input
                  value={searchText}
                  onChange={(e) => setSearchText(e.target.value)}
                  placeholder={isAdmin ? '搜索课程名称、教师、班级...' : '搜索课程名称...'}
                  className="h-9 w-full rounded-[10px] bg-surface-primary pl-9 pr-3 text-body-2-regular text-ink-primary shadow-card outline-hidden transition-shadow placeholder:text-ink-tertiary focus-visible:shadow-focus-double"
                />
              </div>
              <div className="w-[150px] shrink-0">
                <Select
                  value={majorFilter}
                  onChange={(e) => {
                    setMajorFilter(e.target.value)
                    setClassFilter('all')
                  }}
                  aria-label="按专业筛选课程"
                  className="w-full"
                >
                  <option value="all">全部专业</option>
                  {majorOptions.map((major) => (
                    <option key={major} value={major}>
                      {major}
                    </option>
                  ))}
                </Select>
              </div>
              <div className="w-[170px] shrink-0">
                <Select
                  value={classFilter}
                  onChange={(e) => setClassFilter(e.target.value)}
                  aria-label="按班级筛选课程"
                  className="w-full"
                >
                  <option value="all">全部班级</option>
                  {classOptions.map((c) => (
                    <option key={c.id} value={String(c.id)}>
                      {c.className}
                    </option>
                  ))}
                </Select>
              </div>
            </div>
            <div className="flex items-center gap-2">
              {viewMode === 'list' && (
                <SegmentedControl
                  size="sm"
                  ariaLabel="表格密度"
                  value={density}
                  onChange={setDensity}
                  options={[
                    { value: 'normal', label: '正常' },
                    { value: 'compact', label: '紧凑' },
                  ]}
                />
              )}
              <SegmentedControl
                ariaLabel="视图模式"
                value={viewMode}
                onChange={(next) => setViewMode(next)}
                options={[
                  { value: 'list', icon: LayoutList, ariaLabel: '列表视图' },
                  { value: 'grid', icon: Grid3X3, ariaLabel: '卡片视图' },
                ]}
              />
            </div>
          </div>

          {viewMode === 'list' ? (
            <DataTable<CourseRow>
              data={filteredCourses}
              columns={courseColumns}
              getRowId={(row) => `${row.courseName}-${row.classId ?? 'x'}-${row.className ?? ''}`}
              enableSelection
              density={density}
              pagination={{ pageIndex: currentPage - 1, pageSize }}
              onPaginationChange={({ pageIndex, pageSize: nextSize }) => {
                setPageSize(nextSize)
                setPage(pageIndex + 1)
              }}
              totalRows={filteredCourses.length}
              resetSelectionKey={`${activeTab}-${searchText}-${majorFilter}-${classFilter}-${viewMode}`}
              emptyText="暂无课程数据"
            />
          ) : (
            <>
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
            {/* 卡片视图页脚：与表格视图保持一致的分页入口 */}
            <div className="flex items-center justify-end">
              <Pagination page={currentPage} totalPages={totalPages} onChange={setPage} />
            </div>
            </>
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
        onSuccess={() => fetchCourses()}
      />

    </div>
  )
}

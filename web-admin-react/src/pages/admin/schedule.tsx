import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { Select } from '@/components/ui/select'
import {
  CalendarClock,
  Play,
  RotateCcw,
  Plus,
  Pencil,
  Trash2,
  AlertCircle,
  CheckCircle2,
  Clock,
  Building2,
  BookOpen,
  Loader2,
  X,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { useDialog } from '@/hooks/use-dialog'
import {
  addClassroom,
  batchImportTasks,
  clearTeachingTasks,
  deleteClassroom,
  deleteTeachingTask,
  getAutoGenerateJob,
  getClassrooms,
  getRunningAutoGenerateJob,
  getScheduleStats,
  getTeachingTasks,
  startAutoGenerateJob,
  updateClassroom,
  type AutoGenerateJob,
  type Classroom,
  type ScheduleResult,
  type ScheduleStats,
  type TeachingTask,
} from '@/api/schedule'
import { getCurrentSemester } from '@/api/semester'
import { getTeacherCourses } from '@/api/courses'
import type { CourseItem } from '@/types'

const ROOM_TYPE_LABELS: Record<string, string> = {
  normal: '普通教室',
  lab: '专业实训室',
  computer: '计算机机房',
  music: '琴房',
  dance: '舞蹈室',
  art: '美术室',
  sports: '运动场',
}

const PERIOD_LABELS: Record<string, string> = {
  any: '不限',
  morning: '上午',
  afternoon: '下午',
}

const STATUS_LABELS: Record<string, { text: string; variant: 'default' | 'success' | 'danger' | 'warning' | 'secondary' }> = {
  pending: { text: '待排', variant: 'default' },
  scheduled: { text: '已排', variant: 'success' },
  failed: { text: '失败', variant: 'danger' },
  locked: { text: '锁定', variant: 'warning' },
}

export function SchedulePage() {
  const { alert, confirm, DialogComponent } = useDialog()
  const [activeTab, setActiveTab] = useState('overview')
  const [loading, setLoading] = useState(false)
  const [semester, setSemester] = useState('')
  const [stats, setStats] = useState<ScheduleStats | null>(null)
  const [tasks, setTasks] = useState<TeachingTask[]>([])
  const [classrooms, setClassrooms] = useState<Classroom[]>([])
  const [courses, setCourses] = useState<CourseItem[]>([])
  /** 课程列表加载中：避免在课程未就绪时点「从课程生成任务」误报“没有可用课程” */
  const [coursesLoading, setCoursesLoading] = useState(true)
  const [lastResult, setLastResult] = useState<ScheduleResult | null>(null)
  /** 教室新增/编辑弹窗 */
  const [roomDialogOpen, setRoomDialogOpen] = useState(false)
  const [editingRoom, setEditingRoom] = useState<Classroom | null>(null)
  const [roomForm, setRoomForm] = useState({
    name: '',
    type: 'normal',
    capacity: '50',
    building: '',
    floor: '',
    isActive: 1,
  })
  const [roomError, setRoomError] = useState('')
  const [roomSaving, setRoomSaving] = useState(false)
  // 与登录按钮同理：setState 要等渲染才生效，用同步 ref 防止连点重复创建
  const roomSavingRef = useRef(false)
  /** 正在执行的异步排课任务（null 表示没有） */
  const [autoJob, setAutoJob] = useState<AutoGenerateJob | null>(null)
  // 点击后立刻置位，避免连点重复创建任务（setAutoJob 要等下一次渲染才生效）
  const autoStartingRef = useRef(false)
  const autoRunning = autoJob?.status === 'running'
  /** 展开查看失败原因的任务 id */
  const [expandedTaskId, setExpandedTaskId] = useState<number | null>(null)
  const failedTaskCount = tasks.filter((t) => t.status === 'failed').length

  const fetchSemester = async (): Promise<{ name: string } | null> => {
    try {
      const data = await getCurrentSemester()
      if (data?.name) {
        setSemester(data.name)
        return { name: data.name }
      }
      return null
    } catch {
      return null
    }
  }

  const fetchStats = async () => {
    if (!semester) return
    try {
      const data = await getScheduleStats(semester)
      setStats(data)
    } catch (err: any) {
      console.error('stats error', err)
    }
  }

  const fetchTasks = async () => {
    if (!semester) return
    try {
      const data = await getTeachingTasks({ semester })
      setTasks(data || [])
    } catch (err: any) {
      console.error('tasks error', err)
    }
  }

  const fetchClassrooms = async () => {
    try {
      // 管理端需要看到已停用教室，便于重新启用
      const data = await getClassrooms(undefined, true)
      setClassrooms(data || [])
    } catch (err: any) {
      console.error('classrooms error', err)
    }
  }

  const fetchCourses = async (targetSemester?: string) => {
    const sem = targetSemester || semester
    setCoursesLoading(true)
    try {
      const data = await getTeacherCourses()
      // 已下架课程不参与教学任务生成
      setCourses(
        (data || []).filter((c: CourseItem) => c.semester === sem && c.active !== false)
      )
    } catch (err: any) {
      console.error('courses error', err)
    } finally {
      setCoursesLoading(false)
    }
  }

  const refreshAll = async (targetSemester?: string) => {
    const sem = targetSemester || semester
    await Promise.all([
      fetchStats(),
      fetchTasks(),
      fetchClassrooms(),
    ])
  }

  useEffect(() => {
    fetchSemester().then((data) => {
      const sem = data?.name || ''
      refreshAll(sem)
      fetchCourses(sem)
    })
  }, [])

  useEffect(() => {
    if (semester) {
      refreshAll(semester)
      fetchCourses(semester)
    }
  }, [semester])

  /**
   * 根据当前学期已导入课程生成教学任务，返回生成结果数量信息
   */
  const generateTasksFromCourses = async (): Promise<{ success: boolean; count: number; message?: string }> => {
    const pendingTasks: TeachingTask[] = []
    courses.forEach((c) => {
      const classes = (c as any).classes || []
      const baseHours = c.hours ?? (c.credit ? Number(c.credit) : 8)
      // 默认每周2课时、2连堂；总课时<=4则按总课时一次排完
      const weeklyHours = baseHours <= 4 ? baseHours : 2
      const consecutive = weeklyHours >= 2 ? 2 : 1
      // 根据课程名智能判断教室类型
      const name = (c.courseName || '').toLowerCase()
      const preferredRoomType =
        name.includes('python') || name.includes('计算机') || name.includes('机器学习') || name.includes('深度学习')
          ? 'computer'
          : 'normal'

      if (classes.length > 0) {
        classes.forEach((cls: any) => {
          pendingTasks.push({
            semester: c.semester || semester,
            classId: cls.classId || 0,
            courseId: c.courseId || 0,
            teacherId: c.teacherId,
            weeklyHours,
            consecutive,
            preferredRoomType,
            preferredPeriod: 'any',
            priority: 5,
          })
        })
      } else if (c.classId) {
        pendingTasks.push({
          semester: c.semester || semester,
          classId: c.classId,
          courseId: c.courseId || 0,
          teacherId: c.teacherId,
          weeklyHours,
          consecutive,
          preferredRoomType,
          preferredPeriod: 'any',
          priority: 5,
        })
      }
    })

    if (pendingTasks.length === 0) {
      return { success: false, count: 0, message: '当前学期没有可用课程，请先到课程导入页导入课程' }
    }

    const result = await batchImportTasks(pendingTasks)
    await refreshAll()
    return {
      success: true,
      count: pendingTasks.length,
      message: `${result.message}${result.errors.length > 0 ? '\n失败：' + result.errors.join('；') : ''}`,
    }
  }

  /** 排课任务完成：展示结果并刷新页面数据 */
  const finishAutoJob = async (job: AutoGenerateJob) => {
    const result = job.result
    if (result) {
      setLastResult(result)
      await alert({
        title: result.failed > 0 ? '排课完成（含失败）' : '排课成功',
        description:
          `总数 ${result.totalTasks} · 成功 ${result.scheduled} · 失败 ${result.failed} · 部分排 ${result.partialScheduled} · 耗时 ${result.elapsedMs}ms` +
          (result.failed > 0 ? '；失败详情见页面「最近一次排课结果」卡片' : ''),
      })
    } else {
      await alert({ title: '排课完成', description: job.error || '任务已完成' })
    }
    await refreshAll()
    setAutoJob(null)
  }

  // 轮询排课任务进度；排课在后台执行，页面保持可操作，避免长时间同步请求卡死页面
  useEffect(() => {
    if (!autoJob || autoJob.status !== 'running') return
    let cancelled = false
    let finished = false
    let failures = 0
    const timer = window.setInterval(async () => {
      try {
        const job = await getAutoGenerateJob(autoJob.jobId)
        if (cancelled || finished) return
        failures = 0
        setAutoJob(job)
        if (job.status === 'done') {
          finished = true
          window.clearInterval(timer)
          await finishAutoJob(job)
        } else if (job.status === 'failed') {
          finished = true
          window.clearInterval(timer)
          await alert({
            title: '排课失败',
            description: job.error || '排课执行失败，请查看后端日志',
          })
          await refreshAll()
          setAutoJob(null)
        }
      } catch (err: any) {
        if (cancelled || finished) return
        failures++
        // 偶发网络抖动不立刻中断，连续失败才提示
        if (failures >= 3) {
          finished = true
          window.clearInterval(timer)
          setAutoJob(null)
          await alert({
            title: '进度查询失败',
            description: err?.response?.data?.error || err?.message || '无法获取排课进度',
          })
        }
      }
    }, 1200)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [autoJob?.jobId, autoJob?.status, semester])

  // 页面刷新/重新进入时，如果后台仍有排课任务，自动接上进度
  useEffect(() => {
    let cancelled = false
    getRunningAutoGenerateJob()
      .then((job) => {
        if (!cancelled && job && job.status === 'running') {
          setAutoJob(job as AutoGenerateJob)
        }
      })
      .catch(() => {
        // 无运行中任务或接口不可用时静默处理
      })
    return () => {
      cancelled = true
    }
  }, [])

  const handleAutoSchedule = async () => {
    if (autoStartingRef.current || autoRunning) return
    // 在弹出确认框之前就上锁，避免连点弹出多个确认框
    autoStartingRef.current = true
    try {
      const confirmed = await confirm({
        title: '一键自动排课',
        description: `将对 ${semester || '当前学期'} 执行自动排课，已锁定的课不会被覆盖。是否继续？`,
        confirmText: '开始排课',
        cancelText: '取消',
      })
      if (!confirmed || autoRunning) return

      setLoading(true)
      setLastResult(null)

      // 没有任何教学任务时，先从课程生成任务，实现“一键”闭环；
      // 已有任务时直接全量重排（已排任务也会重新参与），不再重复生成
      if (tasks.length === 0) {
        const generated = await generateTasksFromCourses()
        if (!generated.success) {
          await alert({ description: generated.message || '没有可用课程' })
          return
        }
        // 生成成功后 tasks 状态已刷新，但当前 tasks 变量未更新，重新获取一次确保后续排课使用最新数据
        await fetchTasks()
      }

      // 异步任务：接口立即返回 jobId，前端轮询进度，页面不会被长时间请求阻塞
      const started = await startAutoGenerateJob(semester || undefined, true)
      setAutoJob({
        jobId: started.jobId,
        semester: semester || undefined,
        status: 'running',
        total: 0,
        processed: 0,
      })
    } catch (err: any) {
      await alert({
        title: '排课启动失败',
        description:
          err?.response?.data?.error || err?.response?.data?.message || err.message || '未知错误',
      })
    } finally {
      autoStartingRef.current = false
      setLoading(false)
    }
  }

  const handleGenerateTasks = async () => {
    const confirmed = await confirm({
      title: '从课程生成教学任务',
      description: `将根据 ${semester || '当前学期'} 的已导入课程自动生成教学任务。是否继续？`,
      confirmText: '生成',
      cancelText: '取消',
    })
    if (!confirmed) return

    setLoading(true)
    try {
      const result = await generateTasksFromCourses()
      if (!result.success) {
        await alert({ description: result.message || '生成失败' })
        return
      }
      await alert({
        title: '生成完成',
        description: result.message,
      })
    } catch (err: any) {
      await alert({
        description: err?.response?.data?.message || err?.response?.data?.error || err.message || '生成失败',
      })
    } finally {
      setLoading(false)
    }
  }

  const handleClearTasks = async () => {
    const confirmed = await confirm({
      title: '清空教学任务',
      description: `确定清空 ${semester || '当前学期'} 的所有教学任务吗？此操作不可恢复。`,
      confirmText: '清空',
      cancelText: '取消',
      variant: 'danger',
    })
    if (!confirmed) return

    setLoading(true)
    try {
      await clearTeachingTasks(semester || undefined)
      await alert({ description: '教学任务已清空' })
      await refreshAll()
    } catch (err: any) {
      await alert({
        description: err?.response?.data?.message || err?.response?.data?.error || err.message || '清空失败',
      })
    } finally {
      setLoading(false)
    }
  }

  const handleDeleteTask = async (task: TeachingTask) => {
    if (!task.id) return
    const confirmed = await confirm({
      title: '删除教学任务',
      description: `确定删除「${task.courseName || ''}」的教学任务吗？`,
      confirmText: '删除',
      cancelText: '取消',
      variant: 'danger',
    })
    if (!confirmed) return

    setLoading(true)
    try {
      await deleteTeachingTask(task.id)
      await refreshAll()
    } catch (err: any) {
      await alert({
        description: err?.response?.data?.message || err?.response?.data?.error || err.message || '删除失败',
      })
    } finally {
      setLoading(false)
    }
  }

  const openCreateRoom = () => {
    setEditingRoom(null)
    setRoomForm({ name: '', type: 'normal', capacity: '50', building: '', floor: '', isActive: 1 })
    setRoomError('')
    setRoomDialogOpen(true)
  }

  const openEditRoom = (room: Classroom) => {
    setEditingRoom(room)
    setRoomForm({
      name: room.name,
      type: room.type,
      capacity: room.capacity == null ? '' : String(room.capacity),
      building: room.building || '',
      floor: room.floor == null ? '' : String(room.floor),
      isActive: room.isActive ?? 1,
    })
    setRoomError('')
    setRoomDialogOpen(true)
  }

  const saveRoom = async () => {
    if (roomSavingRef.current) return
    const name = roomForm.name.trim()
    if (!name) {
      setRoomError('请填写教室名称')
      return
    }
    const capacity = Number(roomForm.capacity)
    if (!Number.isInteger(capacity) || capacity < 1) {
      setRoomError('容量必须是大于 0 的整数')
      return
    }
    const floorText = roomForm.floor.trim()
    const floor = floorText === '' ? undefined : Number(floorText)
    if (floor !== undefined && !Number.isInteger(floor)) {
      setRoomError('楼层必须是整数')
      return
    }
    const duplicated = classrooms.some(
      (r) => r.id !== editingRoom?.id && r.name.trim().toLowerCase() === name.toLowerCase()
    )
    if (duplicated) {
      setRoomError(`已存在同名教室「${name}」`)
      return
    }

    roomSavingRef.current = true
    setRoomSaving(true)
    setRoomError('')
    try {
      const payload = {
        name,
        type: roomForm.type,
        capacity,
        building: roomForm.building.trim(),
        floor,
        isActive: roomForm.isActive,
        equipment: editingRoom?.equipment,
      }
      if (editingRoom) {
        await updateClassroom(editingRoom.id, payload)
      } else {
        await addClassroom(payload as Omit<Classroom, 'id'>)
      }
      setRoomDialogOpen(false)
      await fetchClassrooms()
    } catch (err: any) {
      setRoomError(err?.response?.data?.message || err?.response?.data?.error || err?.message || '保存失败')
    } finally {
      roomSavingRef.current = false
      setRoomSaving(false)
    }
  }

  const handleDeleteRoom = async (room: Classroom) => {
    const confirmed = await confirm({
      title: '删除教室',
      description: `确定删除「${room.name}」？删除后该教室不再参与排课。`,
      confirmText: '删除',
      variant: 'danger',
    })
    if (!confirmed) return
    try {
      await deleteClassroom(room.id)
      await fetchClassrooms()
    } catch (err: any) {
      await alert({
        description: err?.response?.data?.message || err?.response?.data?.error || err.message || '删除失败',
      })
    }
  }

  const pendingCount = useMemo(() => tasks.filter((t) => t.status === 'pending').length, [tasks])
  const failedCount = useMemo(() => tasks.filter((t) => t.status === 'failed').length, [tasks])

  return (
    <div className="space-y-6">
      {DialogComponent}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <div>
          {/* 副标题（不展示）：管理员录入教学任务，系统自动排课，师生按角色查看课表 */}
          <h1 className="text-display-4-medium text-ink-primary">排课管理</h1>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            className="gap-2"
            onClick={() => refreshAll()}
            disabled={loading}
          >
            <RotateCcw className="w-4 h-4" />
            刷新
          </Button>
          <Button
            variant="default"
            className="gap-2"
            onClick={handleAutoSchedule}
            disabled={loading || autoRunning || !semester}
          >
            {autoRunning || loading ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Play className="w-4 h-4" />
            )}
            {autoRunning
              ? autoJob?.total
                ? `排课中 ${autoJob.processed}/${autoJob.total}`
                : '排课中…'
              : '一键自动排课'}
          </Button>
        </div>
      </div>

      {/* 排课进度：后台执行，页面保持可操作 */}
      {autoRunning && (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50/60 px-4 py-3 text-sm text-emerald-700">
          <div className="flex items-center gap-2">
            <Loader2 className="w-4 h-4 animate-spin" />
            <span>
              正在自动排课：
              {autoJob?.total ? `${autoJob.processed} / ${autoJob.total} 个任务` : '正在加载任务…'}
            </span>
          </div>
          <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-emerald-100">
            <div
              className="h-full bg-primary-500 transition-all duration-500"
              style={{
                width: `${
                  autoJob?.total
                    ? Math.min(100, Math.round((autoJob.processed / autoJob.total) * 100))
                    : 5
                }%`,
              }}
            />
          </div>
          <p className="mt-1.5 text-xs text-primary-600">
            排课在后台执行，可继续操作页面；刷新后会自动接上进度。
          </p>
        </div>
      )}

      {/* 统计卡片 */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <Card>
          <CardContent className="p-5 flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-neutral-100 flex items-center justify-center text-ink-secondary">
              <BookOpen className="w-6 h-6" />
            </div>
            <div>
              <div className="text-2xl font-bold text-neutral-900">{stats?.total ?? '-'}</div>
              <div className="text-sm text-neutral-500">教学任务总数</div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-5 flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-success/10 flex items-center justify-center text-success">
              <CheckCircle2 className="w-6 h-6" />
            </div>
            <div>
              <div className="text-2xl font-bold text-neutral-900">{stats?.scheduled ?? '-'}</div>
              <div className="text-sm text-neutral-500">已排课</div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-5 flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-warning/10 flex items-center justify-center text-warning">
              <Clock className="w-6 h-6" />
            </div>
            <div>
              <div className="text-2xl font-bold text-neutral-900">{stats?.pending ?? '-'}</div>
              <div className="text-sm text-neutral-500">待排课</div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-5 flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-danger/10 flex items-center justify-center text-danger">
              <AlertCircle className="w-6 h-6" />
            </div>
            <div>
              <div className="text-2xl font-bold text-neutral-900">{stats?.failed ?? '-'}</div>
              <div className="text-sm text-neutral-500">排课失败</div>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* 学期提示 */}
      <div className="flex items-center gap-2 text-sm text-neutral-600 bg-surface-primary border border-neutral-200 rounded-xl px-4 py-3">
        <CalendarClock className="w-4 h-4 text-primary-500" />
        当前操作学期：
        <span className="font-medium text-neutral-900">{semester || '未设置'}</span>
      </div>

      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList>
          <TabsTrigger value="overview">任务与结果</TabsTrigger>
          <TabsTrigger value="classrooms">教室资源</TabsTrigger>
        </TabsList>

        <TabsContent value="overview" className="mt-6 space-y-6">
          {/* 操作区 */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">教学任务维护</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex flex-wrap gap-3">
                <Button
                  variant="outline"
                  className="gap-2"
                  onClick={handleGenerateTasks}
                  disabled={loading || autoRunning || !semester || coursesLoading}
                >
                  <Plus className="w-4 h-4" />
                  从课程生成任务
                </Button>
                <Button
                  variant="outline"
                  className="gap-2 text-danger hover:text-danger"
                  onClick={handleClearTasks}
                  disabled={loading || autoRunning || tasks.length === 0}
                >
                  <Trash2 className="w-4 h-4" />
                  清空任务
                </Button>
              </div>
              <p className="text-xs text-neutral-500">
                提示：点击"从课程生成任务"会读取当前学期已导入的课程，按默认规则生成教学任务；您也可以在后端直接维护 teaching_task 表后刷新查看。
              </p>
            </CardContent>
          </Card>

          {/* 排课结果 */}
          {lastResult && (
            <Card className={lastResult.failed > 0 ? 'border-warning' : 'border-success'}>
              <CardHeader className="flex flex-row items-center justify-between">
                <CardTitle className="text-base">最近一次排课结果</CardTitle>
                <Button variant="ghost" size="icon" className="w-8 h-8" onClick={() => setLastResult(null)}>
                  <X className="w-4 h-4" />
                </Button>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="text-sm text-neutral-700">{lastResult.message}</div>
                {lastResult.failures && lastResult.failures.length > 0 && (
                  <div className="space-y-2">
                    <div className="text-sm font-medium text-neutral-900">失败详情</div>
                    <div className="bg-neutral-50 rounded-lg border border-neutral-200 divide-y divide-neutral-200 max-h-60 overflow-auto">
                      {lastResult.failures.map((f, idx) => (
                        <div key={idx} className="px-4 py-3 text-sm">
                          <div className="font-medium text-danger">{f.courseName}</div>
                          <div className="text-neutral-500">{f.reason}</div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {/* 任务列表 */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">
                教学任务列表
                <span className="ml-2 text-sm font-normal text-neutral-500">({tasks.length})</span>
                {failedTaskCount > 0 && (
                  <span className="ml-2 text-sm font-normal text-danger">
                    失败 {failedTaskCount}，点行尾「失败原因」查看
                  </span>
                )}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className="border-b border-neutral-200">
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">课程</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">班级</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">教师</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">周课时</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">连堂</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">教室偏好</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">时段偏好</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">状态</th>
                      <th className="text-right px-4 py-3 text-sm font-medium text-neutral-500">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tasks.map((task, idx) => {
                      const status = STATUS_LABELS[task.status || 'pending']
                      const isFailed = task.status === 'failed'
                      const expanded = task.id != null && expandedTaskId === task.id
                      return (
                        <Fragment key={task.id ?? idx}>
                          <tr className="border-b border-neutral-100 hover:bg-neutral-50">
                            <td className="px-4 py-3 text-sm text-neutral-900">
                              <div>{task.courseName || '-'}</div>
                              {isFailed && task.failReason && (
                                <div
                                  className="mt-0.5 max-w-[260px] truncate text-[11px] text-danger"
                                  title={task.failReason}
                                >
                                  {task.failReason}
                                </div>
                              )}
                            </td>
                            <td className="px-4 py-3 text-sm text-neutral-700">{task.classId || '-'}</td>
                            <td className="px-4 py-3 text-sm text-neutral-700">{task.teacherName || '-'}</td>
                            <td className="px-4 py-3 text-sm text-neutral-700">{task.weeklyHours} 节</td>
                            <td className="px-4 py-3 text-sm text-neutral-700">{task.consecutive} 节</td>
                            <td className="px-4 py-3 text-sm text-neutral-700">
                              {ROOM_TYPE_LABELS[task.preferredRoomType || 'normal'] || task.preferredRoomType}
                            </td>
                            <td className="px-4 py-3 text-sm text-neutral-700">
                              {PERIOD_LABELS[task.preferredPeriod || 'any'] || task.preferredPeriod}
                            </td>
                            <td className="px-4 py-3">
                              <Badge variant={status.variant}>{status.text}</Badge>
                            </td>
                            <td className="px-4 py-3 text-right whitespace-nowrap">
                              {isFailed && (
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  className="h-7 px-2 text-[11px] text-danger"
                                  onClick={() =>
                                    setExpandedTaskId(expanded ? null : (task.id ?? null))
                                  }
                                >
                                  {expanded ? '收起' : '失败原因'}
                                </Button>
                              )}
                              <Button
                                variant="ghost"
                                size="icon"
                                className="w-8 h-8 text-danger"
                                onClick={() => handleDeleteTask(task)}
                                disabled={loading}
                              >
                                <Trash2 className="w-4 h-4" />
                              </Button>
                            </td>
                          </tr>
                          {expanded && (
                            <tr className="border-b border-neutral-100 bg-red-50/60">
                              <td colSpan={9} className="px-4 py-3 text-xs text-neutral-700">
                                <div className="font-medium text-danger">失败原因</div>
                                <div className="mt-1 whitespace-pre-wrap">
                                  {task.failReason || '未记录原因'}
                                </div>
                                <div className="mt-1 text-neutral-400">
                                  课程：{task.courseName || '-'} ｜ 班级ID：{task.classId || '-'} ｜
                                  教师：{task.teacherName || '-'} ｜ 教室偏好：
                                  {ROOM_TYPE_LABELS[task.preferredRoomType || 'normal'] ||
                                    task.preferredRoomType ||
                                    '-'}
                                </div>
                              </td>
                            </tr>
                          )}
                        </Fragment>
                      )
                    })}
                    {tasks.length === 0 && (
                      <tr>
                        <td colSpan={9} className="px-4 py-12 text-center text-neutral-400">
                          暂无教学任务，点击上方"从课程生成任务"或手动录入
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="classrooms" className="mt-6">
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-base flex items-center gap-2">
                  <Building2 className="w-5 h-5" />
                  教室资源
                </CardTitle>
                <Button size="sm" onClick={openCreateRoom}>
                  <Plus className="w-4 h-4 mr-1" />
                  新增教室
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className="border-b border-neutral-200">
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">教室名称</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">类型</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">容量</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">楼宇</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">楼层</th>
                      <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">状态</th>
                      <th className="text-right px-4 py-3 text-sm font-medium text-neutral-500">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {classrooms.map((room) => (
                      <tr key={room.id} className="border-b border-neutral-100 hover:bg-neutral-50">
                        <td className={`px-4 py-3 text-sm ${room.isActive === 1 ? 'text-neutral-900' : 'text-neutral-400'}`}>
                          {room.name}
                        </td>
                        <td className="px-4 py-3 text-sm text-neutral-700">
                          {ROOM_TYPE_LABELS[room.type] || room.type}
                        </td>
                        <td className="px-4 py-3 text-sm text-neutral-700">{room.capacity} 人</td>
                        <td className="px-4 py-3 text-sm text-neutral-700">{room.building || '-'}</td>
                        <td className="px-4 py-3 text-sm text-neutral-700">{room.floor ?? '-'}</td>
                        <td className="px-4 py-3">
                          <Badge variant={room.isActive === 1 ? 'success' : 'secondary'}>
                            {room.isActive === 1 ? '启用' : '已停用'}
                          </Badge>
                        </td>
                        <td className="px-4 py-3 text-right whitespace-nowrap">
                          <Button
                            variant="ghost"
                            size="icon"
                            className="w-8 h-8 text-neutral-500"
                            title="编辑"
                            onClick={() => openEditRoom(room)}
                          >
                            <Pencil className="w-4 h-4" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            className="w-8 h-8 text-danger"
                            title="删除"
                            onClick={() => handleDeleteRoom(room)}
                          >
                            <Trash2 className="w-4 h-4" />
                          </Button>
                        </td>
                      </tr>
                    ))}
                    {classrooms.length === 0 && (
                      <tr>
                        <td colSpan={7} className="px-4 py-12 text-center text-neutral-400">
                          暂无教室数据
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </CardContent>
          </Card>

          <Dialog
            open={roomDialogOpen}
            onOpenChange={(open) => {
              if (!roomSaving) setRoomDialogOpen(open)
            }}
          >
            <DialogContent className="sm:max-w-md">
              <DialogHeader>
                <DialogTitle>{editingRoom ? '编辑教室' : '新增教室'}</DialogTitle>
              </DialogHeader>
              <div className="space-y-3">
                <div className="space-y-1.5">
                  <label htmlFor="room-name" className="text-xs font-medium text-neutral-600">
                    名称
                  </label>
                  <Input
                    id="room-name"
                    value={roomForm.name}
                    onChange={(e) => setRoomForm({ ...roomForm, name: e.target.value })}
                    placeholder="如 A301"
                  />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1.5">
                    <label htmlFor="room-type" className="text-xs font-medium text-neutral-600">
                      类型
                    </label>
                    <Select
                      id="room-type"
                      value={roomForm.type}
                      onChange={(e) => setRoomForm({ ...roomForm, type: e.target.value })}
                      className="h-11 w-full rounded-xl border border-neutral-200 bg-surface-primary px-3 text-sm text-neutral-900 focus:outline-hidden focus:ring-2 focus:ring-emerald-300"
                    >
                      {Object.entries(ROOM_TYPE_LABELS).map(([value, label]) => (
                        <option key={value} value={value}>
                          {label}
                        </option>
                      ))}
                    </Select>
                  </div>
                  <div className="space-y-1.5">
                    <label htmlFor="room-capacity" className="text-xs font-medium text-neutral-600">
                      容量
                    </label>
                    <Input
                      id="room-capacity"
                      type="number"
                      min={1}
                      value={roomForm.capacity}
                      onChange={(e) => setRoomForm({ ...roomForm, capacity: e.target.value })}
                    />
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1.5">
                    <label htmlFor="room-building" className="text-xs font-medium text-neutral-600">
                      楼宇
                    </label>
                    <Input
                      id="room-building"
                      value={roomForm.building}
                      onChange={(e) => setRoomForm({ ...roomForm, building: e.target.value })}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <label htmlFor="room-floor" className="text-xs font-medium text-neutral-600">
                      楼层
                    </label>
                    <Input
                      id="room-floor"
                      type="number"
                      value={roomForm.floor}
                      onChange={(e) => setRoomForm({ ...roomForm, floor: e.target.value })}
                    />
                  </div>
                </div>
                {editingRoom && (
                  <div className="space-y-1.5">
                    <label htmlFor="room-status" className="text-xs font-medium text-neutral-600">
                      状态
                    </label>
                    <Select
                      id="room-status"
                      value={roomForm.isActive}
                      onChange={(e) => setRoomForm({ ...roomForm, isActive: Number(e.target.value) })}
                      className="h-11 w-full rounded-xl border border-neutral-200 bg-surface-primary px-3 text-sm text-neutral-900 focus:outline-hidden focus:ring-2 focus:ring-emerald-300"
                    >
                      <option value={1}>启用</option>
                      <option value={0}>停用</option>
                    </Select>
                  </div>
                )}
                {roomError && <div className="text-xs text-danger">{roomError}</div>}
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setRoomDialogOpen(false)} disabled={roomSaving}>
                  取消
                </Button>
                <Button onClick={saveRoom} disabled={roomSaving}>
                  {roomSaving ? '保存中...' : '保存'}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </TabsContent>
      </Tabs>
    </div>
  )
}

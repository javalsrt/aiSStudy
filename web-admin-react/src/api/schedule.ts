import request from './request'

export interface Classroom {
  id: number
  name: string
  type: string
  capacity: number
  building?: string
  floor?: number
  equipment?: string
  isActive: number
}

export interface TeachingTask {
  id?: number
  semester: string
  classId: number
  courseId: number
  courseName?: string
  teacherId?: number
  teacherName?: string
  weeklyHours: number
  consecutive: number
  preferredRoomType?: string
  preferredPeriod?: string
  priority?: number
  status?: string
  failReason?: string
}

export interface ScheduleResult {
  totalTasks: number
  scheduled: number
  failed: number
  partialScheduled: number
  elapsedMs: number
  message: string
  failures: Array<{
    taskId: number
    courseName: string
    classId: number
    reason: string
  }>
}

export interface ScheduleStats {
  semester: string
  total: number
  scheduled: number
  failed: number
  pending: number
  locked: number
}

export interface BatchImportResult {
  created: number
  updated: number
  errors: string[]
  message: string
}

// 教室管理
export const getClassrooms = (type?: string, includeInactive?: boolean) =>
  request.get<any, Classroom[]>('/admin/schedule/classrooms', {
    params: {
      ...(type ? { type } : {}),
      ...(includeInactive ? { includeInactive: true } : {}),
    },
  })

export const addClassroom = (data: Omit<Classroom, 'id'>) =>
  request.post<any, { id: number; message: string }>('/admin/schedule/classroom', data)

export const updateClassroom = (id: number, data: Partial<Classroom>) =>
  request.put<any, { message: string }>(`/admin/schedule/classroom/${id}`, data)

export const deleteClassroom = (id: number) =>
  request.delete<any, { message: string }>(`/admin/schedule/classroom/${id}`)

// 教学任务管理
export const getTeachingTasks = (params?: { semester?: string; classId?: number; status?: string }) =>
  request.get<any, TeachingTask[]>('/admin/schedule/tasks', { params })

export const batchImportTasks = (tasks: TeachingTask[]) =>
  request.post<any, BatchImportResult>('/admin/schedule/tasks/batch', tasks)

export const deleteTeachingTask = (id: number) =>
  request.delete<any, { message: string }>(`/admin/schedule/task/${id}`)

export const clearTeachingTasks = (semester?: string) =>
  request.delete<any, { message: string }>('/admin/schedule/tasks/clear', { params: semester ? { semester } : undefined })

// 一键自动排课
export const autoGenerateSchedule = (semester?: string, clearExisting = true) =>
  request.post<any, ScheduleResult>('/admin/schedule/auto-generate', null, {
    params: { semester, clearExisting },
  })

export interface AutoGenerateJob {
  jobId: string
  semester?: string
  status: 'running' | 'done' | 'failed'
  total: number
  processed: number
  elapsedMs?: number
  result?: ScheduleResult
  error?: string
}

/** 启动异步排课任务：立即返回 jobId，避免长时间同步请求卡住页面 */
export const startAutoGenerateJob = (semester?: string, clearExisting = true) =>
  request.post<any, { jobId: string; status: string; message: string }>(
    '/admin/schedule/auto-generate/jobs',
    null,
    { params: { semester, clearExisting } }
  )

/** 查询排课任务进度/结果 */
export const getAutoGenerateJob = (jobId: string) =>
  request.get<any, AutoGenerateJob>(`/admin/schedule/auto-generate/jobs/${jobId}`)

/** 查询当前正在执行的排课任务（页面刷新后可重新接上进度） */
export const getRunningAutoGenerateJob = () =>
  request.get<any, AutoGenerateJob | { status: 'idle' }>('/admin/schedule/auto-generate/jobs/running')

// 排课统计
export const getScheduleStats = (semester?: string) =>
  request.get<any, ScheduleStats>('/admin/schedule/stats', { params: semester ? { semester } : undefined })

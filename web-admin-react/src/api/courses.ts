import request from './request'
import type { CourseItem } from '@/types'

// 课程列表接口聚合了排课信息，单次耗时较长；
// 短缓存 + 并发去重，避免课程管理/章节管理重复请求和 StrictMode 双调用。
const COURSE_CACHE_TTL = 15_000
let teacherCoursesCache: { at: number; token: string | null; data: CourseItem[] } | null = null
let teacherCoursesInFlight: { token: string | null; promise: Promise<CourseItem[]> } | null = null

const currentToken = () => localStorage.getItem('token')

export function invalidateCoursesCache() {
  teacherCoursesCache = null
  teacherCoursesInFlight = null
}

// 教师课程列表（管理员看所有，教师看自己的）
export const getTeacherCourses = () => {
  const token = currentToken()
  if (
    teacherCoursesCache &&
    teacherCoursesCache.token === token &&
    Date.now() - teacherCoursesCache.at < COURSE_CACHE_TTL
  ) {
    return Promise.resolve(teacherCoursesCache.data)
  }
  if (teacherCoursesInFlight && teacherCoursesInFlight.token === token) {
    return teacherCoursesInFlight.promise
  }
  const promise = request
    .get<any, CourseItem[]>('/schedule/teacher/courses')
    .then((data) => {
      if (currentToken() === token) {
        teacherCoursesCache = { at: Date.now(), token, data: data || [] }
      }
      return data
    })
    .finally(() => {
      if (teacherCoursesInFlight?.token === token) {
        teacherCoursesInFlight = null
      }
    })
  teacherCoursesInFlight = { token, promise }
  return promise
}

// 学生课程列表（学生看自己已选课程）
export const getStudentCourses = () => {
  return request.get<any, CourseItem[]>('/schedule/student/courses')
}

// 查询已下架课程
export const getHiddenCourses = () => {
  return request.get<any, CourseItem[]>('/schedule/import/hidden')
}

// 下架课程（必须指定班级，避免影响多班级同名课程）
export const hideCourse = (courseName: string, classId: number) => {
  return request.post('/schedule/import/hide', { courseName, classId }).then((res) => {
    invalidateCoursesCache()
    return res
  })
}

// 上架 / 排课（必须指定班级）
// 支持跨周移动：传 clearCells 会先删除源周单元格，再写入 slots 对应周次
export const unhideCourse = (data: {
  courseName: string
  slots?: any[]
  classId: number
  requestId?: string
  clearCells?: { week: number; dayOfWeek: number; startNode: number }[]
}) => {
  return request.post('/schedule/import/unhide', data).then((res) => {
    invalidateCoursesCache()
    return res
  })
}

// 清除指定课程在指定班级的排课。
// 传 cells 则精确删除指定单元格（week + dayOfWeek + startNode）；
// 只传 week 则清除该周全部排课；
// 都不传则清除全部排课。
export const clearClassSchedule = (data: {
  courseName: string
  classId: number
  week?: number
  cells?: { week: number; dayOfWeek: number; startNode: number }[]
}) => {
  return request.post('/schedule/import/clear-class-schedule', data).then((res) => {
    invalidateCoursesCache()
    return res
  })
}

// 彻底删除课程（必须指定班级，按班级粒度移除）
export const removeCourse = (courseName: string, classId: number) => {
  return request.post('/schedule/import/remove', { courseName, classId }).then((res) => {
    invalidateCoursesCache()
    return res
  })
}

// 班级课表（按周查询）
export const getClassSchedule = (classId: number, week: number, courseName?: string) => {
  return request.get<
    any,
    { schedules: ScheduleRecord[]; error?: string; maxWeek?: number; courseMaxWeek?: number }
  >('/schedule/teacher/class-schedule', { params: { classId, week, courseName } })
}

// 课表记录（后端返回）
export interface ScheduleRecord {
  id: number
  user_id: number
  course_id: number
  course_name: string
  day_of_week: number
  start_time: string
  end_time: string
  start_node: number
  step: number
  classroom: string
  semester: string
  weeks: string
  status: number
}

// 排课提交用的 slot 结构
export interface ScheduleSlot {
  week: number
  dayOfWeek: number
  startNode: number
  step: number
  startTime: string
  endTime: string
  credit: number
  classroom: string
  semester: string
  weeks: string
}

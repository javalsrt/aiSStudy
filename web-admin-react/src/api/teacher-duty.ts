import request from './request'

export interface TeacherDutyItem {
  id: number
  name: string
  teacherNo?: string | null
  title?: string | null
  duty: string
  isLeader: number
  phone?: string | null
  status?: number
  headClasses: string[]
  headClassCount: number
}

export interface ClassHeadItem {
  id: number
  className: string
  grade?: string | null
  major?: string | null
  department?: string | null
  headTeacherId?: number | null
  headTeacherName?: string | null
  headTeacherDuty?: string | null
  headTeacherIsLeader?: number | null
  studentCount: number
}

/** 教师列表（含职务、领导标记、所带班级） */
export const getTeacherDutyList = (keyword?: string) => {
  return request.get<any, TeacherDutyItem[]>('/admin/teacher-duty/teachers', {
    params: keyword ? { keyword } : {},
  })
}

/** 修改教师职务 / 领导标记 */
export const updateTeacherDuty = (id: number, data: { duty?: string; isLeader?: number | boolean }) => {
  return request.post<any, { msg: string; duty: string; isLeader: number }>(`/admin/teacher-duty/teachers/${id}`, data)
}

/** 班级列表（含当前班主任） */
export const getClassHeadList = () => {
  return request.get<any, ClassHeadItem[]>('/admin/teacher-duty/classes')
}

/** 指定班级班主任 */
export const setClassHeadTeacher = (classId: number, teacherId: number) => {
  return request.post<any, { msg: string; headTeacherName: string }>(
    `/admin/teacher-duty/classes/${classId}/head-teacher`,
    { teacherId }
  )
}

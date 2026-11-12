import request from './request'

export interface TeacherRole {
  code: string
  name: string
  category: 'title' | 'position' | 'leader' | 'middle' | 'support'
  teaches: boolean
}

export interface TeacherRoleCategory {
  key: string
  label: string
  multi: boolean
}

export interface TeacherRoleCatalog {
  categories: TeacherRoleCategory[]
  roles: TeacherRole[]
}

export interface TeacherDutyItem {
  id: number
  name: string
  teacherNo?: string | null
  title?: string | null
  duty: string
  isLeader: number
  phone?: string | null
  status?: number
  dept?: string | null
  roles?: TeacherRole[]
  roleNames?: string[]
  canTeach?: boolean
  headClasses: string[]
  headClassCount: number
}

export interface StaffRelationTeacher {
  id: number
  name: string
  teacherNo?: string | null
  title?: string | null
  duty?: string | null
  isLeader?: number
  status?: number
  dept?: string | null
  roles: TeacherRole[]
  roleNames: string[]
  canTeach: boolean
}

export interface StaffRelationClass {
  id: number
  className: string
  grade?: string | null
  major?: string | null
  department?: string | null
  headTeacherId?: number | null
  headTeacherName?: string | null
  studentCount: number
}

export interface StaffRelationTeaching {
  teacherId: number
  teacherName: string
  classId: number
  className: string
  courseName: string
}

export interface StaffRelations {
  roleCatalog: TeacherRoleCatalog
  teachers: StaffRelationTeacher[]
  classes: StaffRelationClass[]
  teachings: StaffRelationTeaching[]
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

/** 修改教师职务 / 领导标记（兼容旧接口） */
export const updateTeacherDuty = (id: number, data: { duty?: string; isLeader?: number | boolean }) => {
  return request.post<any, { msg: string; duty: string; isLeader: number }>(`/admin/teacher-duty/teachers/${id}`, data)
}

/** 教师身份目录：职称 / 岗位角色 / 校级领导 / 中层领导 / 教辅岗位 */
export const getTeacherRoleCatalog = () => {
  return request.get<any, TeacherRoleCatalog>('/admin/teacher-duty/roles')
}

/** 覆盖式保存教师身份（支持一人多身份） */
export const updateTeacherRoles = (id: number, roleCodes: string[]) => {
  return request.post<any, { msg: string; roles: TeacherRole[]; canTeach: boolean }>(
    `/admin/teacher-duty/teachers/${id}/roles`,
    { roleCodes },
  )
}

/** 教务关系：教师 + 身份 + 班级 + 授课关系 */
export const getStaffRelations = () => {
  return request.get<any, StaffRelations>('/admin/teacher-duty/relations')
}

/** 班级列表（含当前班主任） */
export const getClassHeadList = () => {
  return request.get<any, ClassHeadItem[]>('/admin/teacher-duty/classes')
}

/** 指定班级班主任（teacherId 传 null/0 表示取消） */
export const setClassHeadTeacher = (classId: number, teacherId: number | null) => {
  return request.post<any, { msg: string; headTeacherName?: string | null }>(
    `/admin/teacher-duty/classes/${classId}/head-teacher`,
    { teacherId }
  )
}

export interface HeadTeacherLog {
  id: number
  operatorName?: string | null
  classId: number
  className?: string | null
  action: 'SET' | 'CLEAR'
  oldTeacher?: string | null
  newTeacher?: string | null
  createdAt: string
}

/** 班主任调整记录 */
export const getHeadTeacherLogs = (limit = 20) => {
  return request.get<any, HeadTeacherLog[]>('/admin/teacher-duty/logs', { params: { limit } })
}

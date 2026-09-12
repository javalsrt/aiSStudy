import request from './request'
import type { UserListResult, ClassInfo, UserListItem } from '@/types'

// 分页查询用户列表
export const getUserList = (params: {
  pageNum?: number
  pageSize?: number
  role?: number // 1学生 2教师 3管理员
  keyword?: string
  searchField?: string // 搜索维度：all/realName/username/studentNo/phone/major/grade/className
  classId?: number
}) => {
  return request.get<any, UserListResult>('/admin/user/list', { params })
}

// 新增用户
export const createUser = (data: {
  username: string
  password: string
  realName: string
  role: number
  studentNo?: string
  email?: string
  phone?: string
  classId?: number
  major?: string
  grade?: string
}) => {
  return request.post('/admin/user', data)
}

// 修改用户
export const updateUser = (id: number, data: Partial<UserListItem> & { password?: string }) => {
  return request.put(`/admin/user/${id}`, data)
}

// 重置密码
export const resetPassword = (id: number, password: string) => {
  return request.put(`/admin/user/${id}/reset-password`, { password })
}

// 删除用户（软删除）
export const deleteUser = (id: number) => {
  return request.delete(`/admin/user/${id}`)
}

// 所有班级列表
export const getClasses = () => {
  return request.get<any, ClassInfo[]>('/admin/user/classes')
}

// 创建班级（管理员复用教师端通用创建接口）
export const createClass = (data: {
  className: string
  major?: string
  grade?: string
  department?: string
}) => {
  return request.post<any, { classId: number; className: string; msg: string }>(
    '/teacher/class/create',
    data,
  )
}

// 人员概览统计
export const getUserOverview = (params: { role: number; status?: number }) => {
  return request.get<any, {
    total: number
    enabled: number
    disabled: number
    majors: { name: string; count: number }[]
    classes: { id: number; name: string; major: string; count: number }[]
  }>('/admin/user/overview', { params })
}

// 批量导入学生（Excel）
export const importStudents = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/admin/user/import-students', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// 教师批量导入（Excel：账号 | 姓名 | 密码 | 手机号 | 邮箱 | 院系）
export const importTeachers = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/admin/user/import-teachers', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// 查询教师所教课程及班级
export const getTeacherCourses = (params: { teacherUserId: number; semester?: string }) => {
  return request.get<any, {
    course_id: number
    course_name: string
    course_type: string | null
    credit: number | null
    semester: string
    class_id: number
    class_name: string
  }[]>('/admin/user/teacher-courses', { params })
}

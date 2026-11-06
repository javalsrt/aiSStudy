import request from './request'

export interface TeacherClassItem {
  id: number
  className: string
  major?: string | null
  grade?: string | null
  department?: string | null
  studentCount: number
}

/** 教师任教的班级列表 */
export const getTeacherClasses = () => {
  return request.get<any, TeacherClassItem[]>('/teacher/class/list')
}

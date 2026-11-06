import request from './request'

export interface PositionOptions {
  cadres: string[]
  adminOnly: string[]
}

export interface ClassStudentPosition {
  id: number
  name: string
  studentNo?: string | null
  position?: string | null
  status?: number
}

/** 可选职务（教师只能看到班干部列表） */
export const getPositionOptions = () => {
  return request.get<any, PositionOptions>('/student-position/options')
}

/** 某班学生及当前职务 */
export const getClassPositions = (classId: number) => {
  return request.get<any, ClassStudentPosition[]>(`/student-position/class/${classId}`)
}

/** 设置/清除学生职务 */
export const setStudentPosition = (userId: number, position: string) => {
  return request.post<any, { msg: string; position: string }>(`/student-position/${userId}`, { position })
}

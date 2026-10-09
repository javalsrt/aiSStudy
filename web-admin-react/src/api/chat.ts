import request from './request'

/**
 * 课程聊天房间键。
 * 优先用课程ID（数字，推荐）：同名课程（如 4 门「英语」）互相隔离；
 * 也兼容课程名（字符串，旧调用），服务端会按当前账号身份收敛到自己的课程。
 */
export type CourseKey = number | string

/** 按房间键生成请求参数 */
const keyOf = (course: CourseKey) =>
  typeof course === 'number' ? { courseId: course } : { courseName: course }

/** 公开聊天地址：有 courseId 走精确接口，否则走兼容接口 */
const publicMessagesUrl = (course: CourseKey) =>
  typeof course === 'number'
    ? `/chat/by-course/${course}/public`
    : `/chat/${encodeURIComponent(course)}/public`

const courseStudentsUrl = (course: CourseKey) =>
  typeof course === 'number'
    ? `/chat/by-course/${course}/students`
    : `/chat/${encodeURIComponent(course)}/students`

/** 聊天消息（与后端 ChatMessageDTO 对应） */
export interface ChatMessage {
  id: number
  courseName: string
  courseId?: number
  userId: number
  senderName?: string
  senderRole: 'student' | 'teacher' | 'ai' | 'system'
  content?: string
  createdAt?: string
  bizType?: string
  bizId?: number
}

/** 课程下的学生（用于 @ 点名） */
export interface CourseStudent {
  id: number
  realName: string
}

/** 教师未读聊天通知 */
export interface TeacherChatNotification {
  id: number
  courseName: string
  courseId?: number
  senderName?: string
  senderRole: 'student' | 'teacher' | 'ai' | 'system'
  content?: string
  preview?: string
  createdAt?: string
}

/** 获取教师未读聊天通知 */
export const getTeacherUnreadNotifications = () => {
  return request.get<any, TeacherChatNotification[]>('/chat/teacher/unread')
}

/** 教师进入课程聊天时，将该课程下学生和 AI 消息标记为已读 */
export const markTeacherRead = (course: CourseKey) => {
  return request.post<any, { msg: string; rows: string }>('/chat/teacher/read', keyOf(course))
}

/** 获取课程公开聊天（教师端群聊，可看到本课程全员消息） */
export const getPublicMessages = (course: CourseKey) => {
  return request.get<any, ChatMessage[]>(publicMessagesUrl(course))
}

/** 发送消息（senderRole='teacher' 表示教师身份，全体学生可见） */
export const sendChatMessage = (course: CourseKey, content: string, senderRole = 'teacher') => {
  return request.post<any, ChatMessage>('/chat/send', { ...keyOf(course), content, senderRole })
}

/** 上传聊天文件（图片/文档），返回 URL */
export const uploadChatFile = (course: CourseKey, file: File) => {
  const form = new FormData()
  form.append('file', file)
  const key = keyOf(course)
  if (key.courseId !== undefined) form.append('courseId', String(key.courseId))
  else form.append('courseName', String(key.courseName))
  return request.post<any, { url: string; fileName: string }>('/chat/upload-file', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

/** 获取该课程的学生列表（用于 @ 点名） */
export const getCourseStudents = (course: CourseKey) => {
  return request.get<any, CourseStudent[]>(courseStudentsUrl(course))
}

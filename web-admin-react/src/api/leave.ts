import request from './request'

export interface LeaveItem {
  id: number
  userId: number
  studentName?: string | null
  studentNo?: string | null
  className?: string | null
  type: 'LEAVE' | 'OUTING'
  typeText: string
  reason: string
  destination?: string | null
  startText: string
  endText: string
  days: number
  parentNotified: number
  parentPhone?: string | null
  smsSent: number
  status: string
  statusText: string
  /** 该班班主任姓名 */
  headTeacherName?: string | null
  /** 当前登录人是否可审批该条 */
  canReview?: boolean
  /** 当前审批环节：班主任审批 / 领导终审 */
  reviewStage?: string
  teacherReviewerRole?: string | null
  leaderReviewerRole?: string | null
  teacherComment?: string | null
  leaderComment?: string | null
  returnPhoto?: string | null
  returnAt?: string | null
  createdText: string
}

/** 待审批列表 */
export const getLeavePending = () => {
  return request.get<any, LeaveItem[]>('/leave/pending')
}

/** 审批：approve | reject */
export const reviewLeave = (id: number, action: 'approve' | 'reject', comment?: string) => {
  return request.post<any, { msg: string; status: string }>(`/leave/${id}/review`, { action, comment })
}

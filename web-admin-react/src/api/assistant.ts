import request from './request'
import type {
  AssistantMeta,
  AssistantRecordPage,
  AssistantReply,
} from '@/types/assistant'

/** 管理端 AI 助手欢迎页元数据（按角色返回能力卡片与推荐问题） */
export const getAssistantMeta = () => {
  return request.get<any, AssistantMeta>('/manage/assistant/meta')
}

/** 发送对话 / 指令 */
export const chatWithAssistant = (data: {
  sessionId: string
  message: string
}) => {
  return request.post<any, AssistantReply>('/manage/assistant/chat', data)
}

/** 确认或取消待执行的删除操作 */
export const confirmAssistantAction = (data: {
  sessionId: string
  token: string
  approved: boolean
}) => {
  return request.post<any, AssistantReply>('/manage/assistant/confirm', data)
}

/** AI 操作记录分页查询 */
export const getAssistantRecords = (params: {
  pageNum?: number
  pageSize?: number
  keyword?: string
  status?: string
  actionType?: string
}) => {
  return request.get<any, AssistantRecordPage>('/manage/assistant/records', { params })
}

/** 恢复某条删除记录对应的软删除数据 */
export const restoreAssistantRecord = (id: number) => {
  return request.post<any, {
    message: string
    restored: boolean
    targetType?: string
    targetName?: string
  }>(`/manage/assistant/records/${id}/restore`)
}

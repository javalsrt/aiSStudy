import request from './request'
import type { AssistantMeta, AssistantMode, AssistantReply } from '@/types/assistant'

/** 管理端 AI 助手欢迎页元数据（按角色返回能力卡片与推荐问题） */
export const getAssistantMeta = () => {
  return request.get<any, AssistantMeta>('/manage/assistant/meta')
}

/** 发送对话 / 指令 */
export const chatWithAssistant = (data: {
  sessionId: string
  mode: AssistantMode
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

export interface AssistantCapability {
  key: string
  title: string
  desc: string
}

export interface AssistantMeta {
  greeting: string
  subtitle: string
  role: 'admin' | 'teacher' | string
  capabilities: AssistantCapability[]
  suggestions: string[]
}

export interface AssistantActionField {
  label: string
  value: string
}

export interface AssistantActionCard {
  name: string
  label: string
  status: 'success' | 'failed' | 'pending_confirm' | 'canceled'
  summary: string
  fields?: AssistantActionField[]
  items?: Record<string, unknown>[]
  link?: string
  confirmToken?: string
  expiresIn?: number
}

export interface AssistantReply {
  sessionId: string
  content: string
  action?: AssistantActionCard
  suggestions?: string[]
  needConfirm?: boolean
}

export interface AssistantMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  action?: AssistantActionCard
  suggestions?: string[]
  error?: boolean
}

export interface AssistantRecord {
  id: number
  userId: number
  operatorName?: string
  operatorUsername?: string
  userRole?: string
  sessionId?: string
  mode?: string
  userMessage?: string
  actionName?: string
  actionLabel?: string
  args?: Record<string, unknown>
  status?: string
  summary?: string
  durationMs?: number
  errorCode?: string
  createdAt?: string
  restorable?: boolean
  restored?: boolean
  targetType?: 'student' | 'teacher' | string
  targetName?: string
  restoreReason?: string
}

export interface AssistantRecordPage {
  total: number
  pageNum: number
  pageSize: number
  list: AssistantRecord[]
}

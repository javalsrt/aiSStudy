export type AssistantMode = 'agent' | 'chat'

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
  agentSuggestions: string[]
  chatSuggestions: string[]
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
  mode?: AssistantMode
  error?: boolean
}

import request from './request'

export interface SchoolInfoItem {
  id: number
  category: string
  title: string
  content: string
  keywords?: string | null
  sort: number
  status: number
  updatedBy?: string | null
  updatedAt?: string | null
}

export interface SchoolInfoList {
  total: number
  items: SchoolInfoItem[]
  categories: string[]
}

/** 校园信息列表 */
export const getSchoolInfoList = (params: { category?: string } = {}) => {
  return request.get<any, SchoolInfoList>('/admin/school-info', { params })
}

/** 新增/修改（id 为空=新增） */
export const saveSchoolInfo = (data: Partial<SchoolInfoItem>) => {
  return request.post<any, { msg: string; id: number }>('/admin/school-info', data)
}

/** 批量导入（## 标题 分段） */
export const bulkImportSchoolInfo = (data: { category?: string; text: string }) => {
  return request.post<any, { msg: string; imported: number }>('/admin/school-info/bulk', data)
}

/** 一键导入新规：支持 docx/txt/md（WPS 请另存为 docx） */
export const importSchoolInfoFile = (file: File, category?: string) => {
  const form = new FormData()
  form.append('file', file)
  if (category) form.append('category', category)
  return request.post<any, { msg: string; imported: number }>('/admin/school-info/import-file', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

/* ===== 规定概览 & 变更记录 ===== */

export interface SchoolInfoCategoryStat {
  category: string
  count: number
  enabled: number
  updatedAt?: string | null
}

export interface SchoolInfoOverview {
  total: number
  enabled: number
  disabled: number
  categories: SchoolInfoCategoryStat[]
  recent: Array<{
    id: number
    title: string
    category: string
    status: number
    updatedAt?: string | null
    updatedBy?: string | null
  }>
}

export interface SchoolInfoLog {
  id: number
  operatorName?: string | null
  action: 'CREATE' | 'UPDATE' | 'DELETE' | 'IMPORT' | 'STATUS'
  itemId?: number | null
  title?: string | null
  category?: string | null
  detail?: string | null
  createdAt: string
}

/** 规定概览统计 */
export const getSchoolInfoOverview = () => {
  return request.get<any, SchoolInfoOverview>('/admin/school-info/overview')
}

/** 最近变更记录 */
export const getSchoolInfoLogs = (limit = 10) => {
  return request.get<any, SchoolInfoLog[]>('/admin/school-info/logs', { params: { limit } })
}

/** 删除 */
export const deleteSchoolInfo = (id: number) => {
  return request.delete<any, { msg: string }>(`/admin/school-info/${id}`)
}

/** 启用/停用 */
export const toggleSchoolInfo = (id: number, status: number) => {
  return request.post<any, { msg: string }>(`/admin/school-info/${id}/status`, { status })
}

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

/** 删除 */
export const deleteSchoolInfo = (id: number) => {
  return request.delete<any, { msg: string }>(`/admin/school-info/${id}`)
}

/** 启用/停用 */
export const toggleSchoolInfo = (id: number, status: number) => {
  return request.post<any, { msg: string }>(`/admin/school-info/${id}/status`, { status })
}

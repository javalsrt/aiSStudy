import request from './request'
import { invalidateCoursesCache } from './courses'
import type { ImportPreviewResult, ImportConfirmResult, ImportPreviewItem, CourseImportRecord } from '@/types'

// 上传文件 → AI 提取课表 → 预览
// className：照片导入必填（照片中没有班级信息），Excel/Word/PDF 可不传
export const previewImport = (file: File, className?: string) => {
  const formData = new FormData()
  formData.append('file', file)
  if (className) formData.append('className', className)
  return request.post<any, ImportPreviewResult>('/schedule/import/preview', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// 确认导入
export const confirmImport = (data: ImportPreviewItem[], fileName?: string) => {
  return request
    .post<any, ImportConfirmResult>('/schedule/import/confirm', { items: data, fileName })
    .then((res) => {
      invalidateCoursesCache()
      return res
    })
}

// 查询导入记录
export const getImportRecords = () => {
  return request.get<any, CourseImportRecord[]>('/schedule/import/records')
}

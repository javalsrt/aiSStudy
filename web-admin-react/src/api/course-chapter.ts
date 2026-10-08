import request from './request'
import type { Chapter, ChapterImportResult, ChapterSaveRequest, Lesson, LessonSaveRequest } from '@/types'

// 查询某课程下的章节列表（含课时）
export const getChaptersByCourse = (courseId: number) => {
  return request.get<any, Chapter[]>(`/course-chapter/course/${courseId}/chapters`)
}

// 查询单个章节详情（含课时）
export const getChapterDetail = (chapterId: number) => {
  return request.get<any, Chapter>(`/course-chapter/chapters/${chapterId}`)
}

// 保存章节（新增/更新）
export const saveChapter = (data: ChapterSaveRequest) => {
  return request.post('/course-chapter/chapters', data)
}

// 删除章节
export const deleteChapter = (chapterId: number) => {
  return request.delete(`/course-chapter/chapters/${chapterId}`)
}

// 保存课时/资源（新增/更新）
export const saveLesson = (data: LessonSaveRequest) => {
  return request.post('/course-chapter/lessons', data)
}

// 删除课时/资源
export const deleteLesson = (lessonId: number) => {
  return request.delete(`/course-chapter/lessons/${lessonId}`)
}

// Excel 导入课程章节和课时
export const importChapters = (courseId: number, file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('courseId', String(courseId))
  return request.post<any, { success: boolean; message: string; data: ChapterImportResult }>(
    '/course-chapter/import-excel',
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  )
}

// Word 导入课程章节和课时
export const importWordChapters = (courseId: number, file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('courseId', String(courseId))
  return request.post<any, { success: boolean; message: string; data: ChapterImportResult }>(
    '/course-chapter/import-word',
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  )
}

// AI 一键生成整课程章节（长耗时，需要单独设超时）
export interface GenerateOptions {
  chapterCount?: number
  difficulty?: string
  notes?: string
}

export const generateChapters = (courseId: number, options?: GenerateOptions) => {
  return request.post<any, { message: string; data: { chapterCount: number; lessonCount: number } }>(
    `/course-chapter/generate/${courseId}`,
    options ?? {},
    { timeout: 300000 }
  )
}

import request from './request'
import type {
  ClassStats,
  StudentStats,
  TeacherStats,
  TrendItem,
  ClassSummaryItem,
  StudentFocusQuiz,
  StudentCourseChapterProgress,
  StudentExamRecord,
  StudentExamDetail,
  QuizReportDetail,
} from '@/types'

// ===== BKT 学情追踪 =====

export type MasteryStudent = {
  userId: number
  name: string
  mastery: number
  attempts: number
  weakCount: number
  evidence: 'enough' | 'low'
}

export type MasteryKnowledge = {
  knowledgeId: number
  name: string
  mastery: number
  attempts: number
  correct: number
}

export type MasteryDetail = {
  userId: number
  courseId: number
  mastery: number
  attempts: number
  weakCount: number
  knowledge: MasteryKnowledge[]
}

export type MasteryPoint = {
  sessionId: number
  before: number
  after: number
  questionCount: number
  correctCount: number
  time: string
}

export type MasteryDistribution = {
  knowledgeId: number
  name: string
  count: number
  lowCount: number
  min: number
  max: number
  p25: number
  p50: number
  p75: number
}

export type MasteryCompareStudent = {
  userId: number
  mastery: number
  knowledge: MasteryKnowledge[]
}

export const getMasteryCourses = () => {
  return request.get<any, { id: number; name: string }[]>('/teacher/mastery/courses')
}

export const getMasteryStudents = (courseId: number, keyword?: string) => {
  const q = keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''
  return request.get<any, { students: MasteryStudent[] }>(
    `/teacher/mastery/students?courseId=${courseId}${q}`,
  )
}

export const getMasteryStudent = (userId: number, courseId: number) => {
  return request.get<any, MasteryDetail>(
    `/teacher/mastery/student/${userId}?courseId=${courseId}`,
  )
}

export const getMasteryHistory = (studentId: number, knowledgeId: number) => {
  return request.get<any, { points: MasteryPoint[] }>(
    `/teacher/mastery/history?studentId=${studentId}&knowledgeId=${knowledgeId}`,
  )
}

export const getMasteryCompare = (studentIds: number[], courseId: number) => {
  return request.get<any, { students: MasteryCompareStudent[] }>(
    `/teacher/mastery/compare?studentIds=${studentIds.join(',')}&courseId=${courseId}`,
  )
}

export const getMasteryDistribution = (courseId: number) => {
  return request.get<any, { knowledge: MasteryDistribution[] }>(
    `/teacher/mastery/distribution?courseId=${courseId}`,
  )
}

export const resetMastery = (userId: number, courseId: number) => {
  return request.post<any, { msg: string }>(
    `/teacher/mastery/reset?userId=${userId}&courseId=${courseId}`,
  )
}

// 教师数据总览
export const getTeacherStats = () => {
  return request.get<any, TeacherStats>('/teacher/stats')
}

// 近7天趋势
export const getTeacherTrend = () => {
  return request.get<any, TrendItem[]>('/teacher/trend')
}

// 班级学习概览（多指标汇总）
export const getClassSummary = () => {
  return request.get<any, ClassSummaryItem[]>('/teacher/class-summary')
}

// 班级统计
export const getClassStats = (classId: number) => {
  return request.get<any, ClassStats>(`/teacher/class-stats/${classId}`)
}

// 学生个人统计（含六维雷达图）
export const getStudentStats = (studentId: number) => {
  return request.get<any, StudentStats>(`/teacher/student-stats/${studentId}`)
}

// 学生专注与刷题记录
export const getStudentFocusQuiz = (studentId: number) => {
  return request.get<any, StudentFocusQuiz>(`/teacher/student/${studentId}/focus-quiz`)
}

// 学生章节学习进度
export const getStudentChapterProgress = (studentId: number) => {
  return request.get<any, StudentCourseChapterProgress[]>(`/teacher/student/${studentId}/chapters`)
}

// 学生考试/作业提交记录
export const getStudentExamRecords = (studentId: number) => {
  return request.get<any, StudentExamRecord[]>(`/teacher/student/${studentId}/exams`)
}

// 学生某次考试/作业的每题作答明细
export const getStudentExamDetails = (studentId: number, examId: number) => {
  return request.get<any, StudentExamDetail>(`/teacher/student/${studentId}/exam/${examId}/details`)
}

// 学生单次测评报告详情（教师查看）
export const getQuizSessionReport = (sessionId: number) => {
  return request.get<any, QuizReportDetail>(`/teacher/quiz-session/${sessionId}/report`)
}

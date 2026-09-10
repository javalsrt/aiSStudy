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

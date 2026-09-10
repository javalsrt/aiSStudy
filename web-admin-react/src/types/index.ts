export interface User {
  id: number
  username: string
  role: 'admin' | 'teacher' | 'student'
  realName?: string
  avatar?: string
  avatarUrl?: string
  /** RBAC 角色编码列表 */
  roles?: string[]
  /** RBAC 权限编码列表 */
  permissions?: string[]
}

// 后端登录返回
export interface LoginResponse {
  token: string
  realName: string
  username: string
  role: string // "1"学生 / "2"教师 / "3"管理员
  userId: number
  avatarUrl?: string
  message?: string
  /** RBAC 角色编码列表，如 ["admin"] */
  roles?: string[]
  /** RBAC 权限编码列表，如 ["course:view"] */
  permissions?: string[]
}

// Dashboard 统计
export interface TeacherStats {
  totalStudents: number
  onlineToday: number
  avgFocusMinutes: number
  quizAccuracy: number
  /** 各班级平均学习时长排行（管理员视角） */
  classFocusRanking: { name: string; avgMinutes: number; studentCount: number }[]
  students: {
    id: number
    realName: string
    studentNo: string
    className: string
    todaySeconds: number
    totalSeconds: number
    online: boolean
  }[]
}

// 班级学习概览（未选中学生时的右侧面板）
export interface ClassSummaryItem {
  classId: number
  name: string
  grade?: string | null
  major?: string | null
  studentCount: number
  avgMinutes: number
  accuracy: number
  completionRate: number
  examAvgScore: number
  onlineCount: number
  courseCount: number
}

export interface TrendItem {
  date: string
  minutes: number
}

// 课程
export interface CourseItem {
  courseName: string
  teacherName?: string
  className?: string
  semester?: string
  dayOfWeek?: number
  startTime?: string
  endTime?: string
  startNode?: number
  step?: number
  classroom?: string
  weeks?: string
  status?: number // 1正常 0下架
  credit?: number
  hours?: number
  scheduleId?: number // 课表记录ID（用于调课）
  classId?: number // 班级ID
  courseId?: number // 课程ID（来自 TeacherCourseDTO）
  teacherId?: number // 教师ID（来自 TeacherCourseDTO）
  scheduleInfo?: string // 聚合排课摘要（来自 TeacherCourseDTO）
}

// 人员
export interface UserListResult {
  total: number
  pageNum: number
  pageSize: number
  list: UserListItem[]
}

export interface UserListItem {
  id: number
  username: string
  studentNo?: string
  realName: string
  email?: string
  phone?: string
  role: number // 1学生 2教师 3管理员
  classId?: number
  major?: string
  grade?: string
  status: number
  createdAt?: string
  lastLogin?: string
}

// 学期
export interface Semester {
  id?: number
  name: string
  startDate: string
  endDate?: string
  weekCount?: number
  isCurrent?: boolean
  semesterType?: 'NORMAL' | 'EXTRA'
  classIds?: number[]
  classNames?: string[]
}

export interface SemesterCurrent {
  name: string
  startDate: string
  status: 'before' | 'ongoing' | 'ended'
  notice: string
}

// 班级
export interface ClassInfo {
  id: number
  className: string
  major?: string
  grade?: string
  department?: string
}

// 课程导入教师建议
export interface TeacherSuggestion {
  teacherId: number
  teacherName: string
  distance: number
}

// 课程导入预览
export interface ImportPreviewItem {
  courseName: string
  teacherName?: string
  className?: string
  dayOfWeek: number
  startTime: string
  endTime: string
  startNode: number
  step: number
  classroom: string
  weeks: string
  semester?: string
  credit?: number
  status?: string
  errorMsg?: string
  /** 教师匹配状态：matched 精确匹配 / fuzzy 模糊匹配 / unmatched 未匹配 */
  teacherMatchStatus?: 'matched' | 'fuzzy' | 'unmatched'
  /** 当前选中的教师ID（预览阶段可修正） */
  teacherId?: number
  /** 匹配到的教师姓名 */
  matchedTeacherName?: string
  /** 候选教师列表 */
  teacherSuggestions?: TeacherSuggestion[]
}

export interface ImportPreviewError {
  row: number
  courseName: string
  errors: string[]
}

export interface ImportPreviewResult {
  total: number
  success: number
  errors: ImportPreviewError[]
  preview: ImportPreviewItem[]
}

export interface ImportConfirmResult {
  imported: number
  autoFilled?: number
  skipped: number
  messages: string[]
}

export interface CourseImportRecord {
  id: number
  fileName?: string
  importedBy?: number
  importedByName?: string
  semester?: string
  totalCount?: number
  successCount?: number
  skipCount?: number
  messages?: string
  createdAt?: string
}

// 课程章节
export interface Chapter {
  id: number
  courseId: number
  courseName?: string
  chapterNo: number
  chapterName: string
  description?: string
  sortOrder: number
  status: number
  lessons?: Lesson[]
}

// 章节保存请求
export interface ChapterSaveRequest {
  id?: number
  courseId: number
  chapterNo: number
  chapterName: string
  description?: string
  sortOrder?: number
  status?: number
}

// 课时/资源
export interface Lesson {
  id: number
  chapterId: number
  lessonNo: number
  lessonName: string
  resourceType: 'video' | 'document' | 'quiz' | 'link'
  resourceUrl?: string
  duration?: number
  content?: string
  sortOrder: number
  status: number
}

// 课时保存请求
export interface LessonSaveRequest {
  id?: number
  chapterId: number
  lessonNo: number
  lessonName: string
  resourceType: 'video' | 'document' | 'quiz' | 'link'
  resourceUrl?: string
  duration?: number
  content?: string
  sortOrder?: number
  status?: number
}

// 章节导入失败行
export interface ChapterImportFailure {
  row: number
  reason: string
}

// 章节导入结果
export interface ChapterImportResult {
  chapterCount: number
  lessonCount: number
  failCount: number
  failures: ChapterImportFailure[]
}

// 班级统计
export interface ClassStats {
  focusRank: { id: number; name: string; studentNo: string; totalSeconds: number }[]
  courseQuestions: { name: string; count: number }[]
  totalStudents: number
}

// 学生个人统计
export interface StudentStats {
  info: { realName: string; studentNo: string; className: string }
  focusTrend: { date: string; minutes: number }[]
  totalFocusMinutes: number
  questions: { name: string; count: number }[]
  loginDays: number
  classAvgMinutes: number
  scores: { name: string; value: number }[]
}

// 学生专注会话
export interface FocusSessionItem {
  id: number
  durationSeconds: number
  durationMinutes: number
  startedAt: string
  finishedAt: string
  createdAt: string
}

// 学生刷题会话
export interface QuizSessionItem {
  id: number
  subject: string
  difficulty: number
  sessionNo: number
  totalQuestions: number
  answeredCount: number
  correctCount: number
  skipCount: number
  totalDurationSec: number
  accuracy: string
  scores?: Record<string, number>
  createdAt: string
}

// 学生专注与刷题记录
export interface StudentFocusQuiz {
  focusSessions: FocusSessionItem[]
  quizSessions: QuizSessionItem[]
}

// 学生章节进度
export interface ChapterProgressItem {
  chapterId: number
  chapterNo: number
  chapterName: string
  description?: string
  completed: number // 0 或 1
  completedAt?: string
}

export interface StudentCourseChapterProgress {
  courseId: number
  courseName: string
  totalChapters: number
  completedChapters: number
  completionRate: number
  chapters: ChapterProgressItem[]
}

// 学生考试/作业记录
export interface StudentExamRecord {
  examId: number
  type: 'exam' | 'homework'
  title: string
  courseId?: number
  courseName?: string
  classId?: number
  className?: string
  totalScore: number
  passScore: number
  startTime: string
  endTime: string
  status: number
  submissionId?: number
  score?: number | null
  durationSec?: number
  submittedAt?: string
  submissionStatus?: string
  isPass?: boolean
  hasSubmitted?: boolean
}

// 考试每题作答明细
export interface StudentExamAnswerDetail {
  questionIndex: number
  questionType: string
  question: string
  options?: string
  userAnswer?: string
  correctAnswer?: string
  isCorrect: number // 1对 0错 -1不会 -2跳过
  userScore?: number
  maxScore?: number
  aiScore?: number
  aiComment?: string
  teacherComment?: string
}

export interface StudentExamDetail {
  examInfo: {
    type: 'exam' | 'homework'
    title: string
    totalScore: number
    passScore: number
  }
  submission: {
    id: number
    totalScore: number
    durationSec: number
    submittedAt: string
  }
  answers: StudentExamAnswerDetail[]
}

// 学生单次测评报告（教师查看）
export interface QuizReportSession {
  subject: string
  difficulty: number
  sessionNo: number
  totalQuestions: number
  answeredCount: number
  correctCount: number
  skipCount: number
  totalDurationSec: number
  scores: { name: string; value: number }[]
  strengths: string[]
  weaknesses: string[]
  suggestion?: string
  studyPlan: string[]
  status: string
  createdAt: string
}

export interface QuizReportAnswer {
  questionIndex: number
  questionType: string
  question: string
  options?: string
  userAnswer?: string
  correctAnswer?: string
  isCorrect: number // 1对 0错 -1不会 -2跳过
  durationSec: number
  modifiedCount: number
}

export interface QuizReportDetail {
  session: QuizReportSession
  student: { realName?: string; studentNo?: string; className?: string }
  answers: QuizReportAnswer[]
}

// 考试作业
export interface ExamHomeworkItem {
  id: number
  type: 'exam' | 'homework'
  title: string
  description?: string
  classId: number
  className: string
  courseId?: number
  courseName?: string
  startTime: string
  endTime: string
  timeLimit: number
  totalScore: number
  passScore: number
  status: number // 0 未发布/草稿 1 进行中 2 已结束 3 已下架
  statusLabel?: string
  publishMode: 'immediate' | 'scheduled'
  scheduledTime?: string
  questionMode: 'ai-range' | 'ai-document'
  questionCount: number
  questionTypes: string[]
  difficulty: string
  editCount?: number
  maxEditCount?: number
  createdAt?: string
}

export interface ExamHomeworkListParams {
  classId?: number | string
  type?: 'exam' | 'homework'
  status?: 'published' | 'draft' | 'ended'
  keyword?: string
}

export interface ExamHomeworkListResult {
  list: ExamHomeworkItem[]
  total: number
}

export interface QuestionPreview {
  id?: number
  type: string
  content: string
  options?: string[]
  answer?: string
  score: number
  difficulty?: string
}

export interface ExamHomeworkPublishRequest {
  type: 'exam' | 'homework'
  classId: number
  title: string
  description?: string
  startTime: string
  endTime: string
  timeLimit: number
  totalScore: number
  passScore: number
  publishMode: 'immediate' | 'scheduled'
  scheduledTime?: string
  questionMode: 'ai-range' | 'ai-document'
  courseId?: number
  chapterIds?: number[]
  questionTypes: string[]
  difficulty: string
  questionCount: number
  questions: QuestionPreview[]
}

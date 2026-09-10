import { createBrowserRouter, Navigate, useLocation } from 'react-router-dom'
import { AppLayout } from '@/components/layout/app-layout'
import { LoginPage } from '@/pages/login'
import { DashboardPage } from '@/pages/dashboard'
import { CoursesPage } from '@/pages/courses'
import { StatsPage } from '@/pages/stats'
import { QuizReportPage } from '@/pages/quiz-report'
import { StaffPage } from '@/pages/admin/staff'
import { SemesterPage } from '@/pages/admin/semester'
import { CourseImportPage } from '@/pages/admin/course-import'
import { SchedulePage } from '@/pages/admin/schedule'
import { CourseChaptersPage } from '@/pages/course-chapters'
import { ExamHomeworkPage } from '@/pages/exam-homework'
import ExamTakePage from '@/pages/exam-take'
import { TeacherChatPage } from '@/pages/teacher-chat'
import { useAuthStore } from '@/store/auth'
import { useEffect, useState } from 'react'

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, init, user } = useAuthStore()
  const [initialized, setInitialized] = useState(false)

  useEffect(() => {
    init()
    setInitialized(true)
  }, [init])

  // 等待本地存储中的登录态恢复完成，避免初始化前误判为未登录
  if (!initialized) {
    return (
      <div className="flex items-center justify-center min-h-screen text-neutral-400">
        加载中...
      </div>
    )
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  // 教师管理端禁止学生访问
  if (user?.role === 'student') {
    return <Navigate to="/login" replace />
  }

  return <>{children}</>
}

function AdminRoute({ children }: { children: React.ReactNode }) {
  const { user, isAuthenticated } = useAuthStore()
  const location = useLocation()

  // 未登录或不是管理员都重定向到仪表盘
  if (!isAuthenticated || user?.role !== 'admin') {
    return <Navigate to="/dashboard" replace state={{ from: location }} />
  }

  return <>{children}</>
}

function TeacherRoute({ children }: { children: React.ReactNode }) {
  const { user, isAuthenticated } = useAuthStore()
  const location = useLocation()

  // 仅教师可访问课程聊天；管理员/学生重定向到仪表盘
  if (!isAuthenticated || user?.role !== 'teacher') {
    return <Navigate to="/dashboard" replace state={{ from: location }} />
  }

  return <>{children}</>
}

function AuthOnlyRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, init } = useAuthStore()
  const [initialized, setInitialized] = useState(false)
  useEffect(() => {
    init()
    setInitialized(true)
  }, [init])
  if (!initialized) {
    return (
      <div className="flex items-center justify-center min-h-screen text-neutral-400">
        加载中...
      </div>
    )
  }
  if (!isAuthenticated) return <Navigate to="/login" replace />
  return <>{children}</>
}

export const router = createBrowserRouter(
  [
    {
      path: '/login',
      element: <LoginPage />,
    },
    {
      path: '/exam-take/:id',
      element: (
        <AuthOnlyRoute>
          <ExamTakePage />
        </AuthOnlyRoute>
      ),
    },
    {
      path: '/',
      element: (
        <ProtectedRoute>
          <AppLayout />
        </ProtectedRoute>
      ),
      children: [
        {
          index: true,
          element: <Navigate to="/dashboard" replace />,
        },
        {
          path: 'dashboard',
          element: <DashboardPage />,
        },
        {
          path: 'courses',
          element: <CoursesPage />,
        },
        {
          path: 'stats',
          element: <StatsPage />,
        },
        {
          path: 'quiz-report/:sessionId',
          element: <QuizReportPage />,
        },
        {
          path: 'course-chapters',
          element: <CourseChaptersPage />,
        },
        {
          path: 'exam-homework',
          element: <ExamHomeworkPage />,
        },
        {
          path: 'teacher-chat',
          element: (
            <TeacherRoute>
              <TeacherChatPage />
            </TeacherRoute>
          ),
        },
        {
          path: 'admin/staff',
          element: (
            <AdminRoute>
              <StaffPage />
            </AdminRoute>
          ),
        },
        {
          path: 'admin/students',
          element: (
            <AdminRoute>
              <StaffPage />
            </AdminRoute>
          ),
        },
        {
          path: 'admin/semester',
          element: (
            <AdminRoute>
              <SemesterPage />
            </AdminRoute>
          ),
        },
        {
          path: 'admin/course-import',
          element: (
            <AdminRoute>
              <CourseImportPage />
            </AdminRoute>
          ),
        },
        {
          path: 'admin/schedule',
          element: (
            <AdminRoute>
              <SchedulePage />
            </AdminRoute>
          ),
        },
      ],
    },
  ],
  {
    future: {
      v7_startTransition: true,
      v7_relativeSplatPath: true,
    } as any,
  }
)

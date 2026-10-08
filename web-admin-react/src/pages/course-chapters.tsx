import { useEffect, useMemo, useState } from 'react'
import {
  BookOpen,
  ChevronDown,
  ChevronRight,
  FileText,
  Film,
  Link as LinkIcon,
  MoreHorizontal,
  Plus,
  Trash2,
  HelpCircle,
  Upload,
  Download,
  FileSpreadsheet,
  Sparkles,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { MarkdownView } from '@/components/markdown-view'
import { Badge } from '@/components/ui/badge'
import { Switch } from '@/components/ui/switch'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useRole } from '@/hooks/use-role'
import { useDialog } from '@/hooks/use-dialog'
import { getTeacherCourses, getStudentCourses } from '@/api/courses'
import {
  getChaptersByCourse,
  saveChapter,
  saveLesson,
  deleteChapter,
  deleteLesson,
  importChapters,
  importWordChapters,
  generateChapters,
} from '@/api/course-chapter'
import type { Chapter, ChapterImportResult, CourseItem, Lesson, ChapterSaveRequest, LessonSaveRequest } from '@/types'

interface CourseOption {
  courseId: number
  courseName: string
}

const RESOURCE_LABELS: Record<string, string> = {
  video: '视频',
  document: '文档',
  quiz: '测验',
  link: '链接',
}

const RESOURCE_ICONS: Record<string, React.ReactNode> = {
  video: <Film className="w-4 h-4" />,
  document: <FileText className="w-4 h-4" />,
  quiz: <HelpCircle className="w-4 h-4" />,
  link: <LinkIcon className="w-4 h-4" />,
}

const emptyChapter = (): ChapterSaveRequest => ({
  courseId: 0,
  chapterNo: 1,
  chapterName: '',
  description: '',
  sortOrder: 1,
  status: 1,
})

const emptyLesson = (): LessonSaveRequest => ({
  chapterId: 0,
  lessonNo: 1,
  lessonName: '',
  resourceType: 'video',
  resourceUrl: '',
  duration: undefined,
  content: '',
  sortOrder: 1,
  status: 1,
})

export function CourseChaptersPage() {
  const { isAdmin, isTeacher, user, hasPermission } = useRole()
  const { alert, confirm, DialogComponent } = useDialog()

  const [courses, setCourses] = useState<CourseOption[]>([])
  const [selectedCourseId, setSelectedCourseId] = useState<number | null>(null)
  const [chapters, setChapters] = useState<Chapter[]>([])
  const [expandedChapterIds, setExpandedChapterIds] = useState<Set<number>>(new Set())
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [courseLoading, setCourseLoading] = useState(false)

  const [chapterDialogOpen, setChapterDialogOpen] = useState(false)
  const [editingChapter, setEditingChapter] = useState<ChapterSaveRequest | null>(null)

  const [lessonDialogOpen, setLessonDialogOpen] = useState(false)
  const [editingLesson, setEditingLesson] = useState<LessonSaveRequest | null>(null)
  const [viewingLesson, setViewingLesson] = useState<Lesson | null>(null)

  const [importDialogOpen, setImportDialogOpen] = useState(false)
  const [importType, setImportType] = useState<'excel' | 'word'>('excel')
  const [importFile, setImportFile] = useState<File | null>(null)
  const [importLoading, setImportLoading] = useState(false)
  const [importResult, setImportResult] = useState<ChapterImportResult | null>(null)

  const [generating, setGenerating] = useState(false)
  // 一键生成设置弹窗：生成范围 + 难度 + 备注
  const [genOpen, setGenOpen] = useState(false)
  const [genChapterCount, setGenChapterCount] = useState(6)
  const [genDifficulty, setGenDifficulty] = useState('基础')
  const [genNotes, setGenNotes] = useState('')

  // 更多菜单展开状态
  const [openMenuId, setOpenMenuId] = useState<number | null>(null)

  const canEdit = useMemo(
    () => hasPermission('chapter:create') || hasPermission('chapter:edit:self') || hasPermission('chapter:edit:all'),
    [hasPermission]
  )
  const canDeleteChapter = useMemo(() => hasPermission('chapter:delete'), [hasPermission])
  const canEditResource = useMemo(
    () => hasPermission('resource:create') || hasPermission('resource:edit:self') || hasPermission('resource:edit:all'),
    [hasPermission]
  )
  const canDeleteResource = useMemo(() => hasPermission('resource:delete'), [hasPermission])
  const canImport = useMemo(() => hasPermission('chapter:import'), [hasPermission])

  const fetchCourses = async () => {
    setCourseLoading(true)
    try {
      const data = isAdmin || isTeacher ? await getTeacherCourses() : await getStudentCourses()
      const list = (data || []).map((c: CourseItem) => ({
        courseId: c.courseId || 0,
        courseName: c.courseName,
      }))
      // 去重
      const map = new Map<number, string>()
      list.forEach((item) => {
        if (item.courseId && !map.has(item.courseId)) {
          map.set(item.courseId, item.courseName)
        }
      })
      const options: CourseOption[] = []
      map.forEach((name, id) => options.push({ courseId: id, courseName: name }))
      setCourses(options)
      if (options.length > 0 && selectedCourseId == null) {
        setSelectedCourseId(options[0].courseId)
      } else if (options.length === 0) {
        setSelectedCourseId(null)
      }
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.response?.data?.error || '课程列表加载失败')
    } finally {
      setCourseLoading(false)
    }
  }

  const fetchChapters = async (courseId: number) => {
    setLoading(true)
    setError('')
    try {
      const data = await getChaptersByCourse(courseId)
      setChapters(data || [])
      // 默认展开第一个
      if (data && data.length > 0) {
        setExpandedChapterIds(new Set([data[0].id]))
      } else {
        setExpandedChapterIds(new Set())
      }
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.response?.data?.error || '章节数据加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchCourses()
  }, [])

  useEffect(() => {
    if (selectedCourseId) {
      fetchChapters(selectedCourseId)
    }
  }, [selectedCourseId])

  const toggleExpand = (id: number) => {
    setExpandedChapterIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const handleAddChapter = async () => {
    if (!selectedCourseId) {
      await alert({ description: '请先选择课程' })
      return
    }
    if (!canEdit) {
      await alert({ description: '您没有创建章节的权限' })
      return
    }
    setEditingChapter({ ...emptyChapter(), courseId: selectedCourseId })
    setChapterDialogOpen(true)
  }

  const handleEditChapter = async (chapter: Chapter) => {
    if (!canEdit) {
      await alert({ description: '您没有编辑章节的权限' })
      return
    }
    setEditingChapter({
      id: chapter.id,
      courseId: chapter.courseId,
      chapterNo: chapter.chapterNo,
      chapterName: chapter.chapterName,
      description: chapter.description || '',
      sortOrder: chapter.sortOrder,
      status: chapter.status,
    })
    setChapterDialogOpen(true)
  }

  const handleSaveChapter = async () => {
    if (!editingChapter) return
    if (!editingChapter.chapterName.trim()) {
      await alert({ description: '章节名称不能为空' })
      return
    }
    if (editingChapter.chapterName.length > 200) {
      await alert({ description: '章节名称不能超过 200 个字符' })
      return
    }
    if (!editingChapter.chapterNo || editingChapter.chapterNo < 1) {
      await alert({ description: '章节序号必须大于 0' })
      return
    }
    try {
      await saveChapter(editingChapter)
      setChapterDialogOpen(false)
      if (selectedCourseId) fetchChapters(selectedCourseId)
    } catch (err: any) {
      await alert({ description: err?.response?.data?.message || err?.response?.data?.error || '保存失败' })
    }
  }

  const handleDeleteChapter = async (chapter: Chapter) => {
    if (!canDeleteChapter) {
      await alert({ description: '您没有删除章节的权限' })
      return
    }
    const confirmed = await confirm({
      title: '删除章节',
      description: `确定删除章节「${chapter.chapterName}」吗？下属课时将一并删除。`,
      confirmText: '删除',
      cancelText: '取消',
      variant: 'danger',
    })
    if (!confirmed) return
    try {
      await deleteChapter(chapter.id)
      if (selectedCourseId) fetchChapters(selectedCourseId)
    } catch (err: any) {
      await alert({ description: err?.response?.data?.message || err?.response?.data?.error || '删除失败' })
    }
  }

  const handleAddLesson = async (chapterId: number) => {
    if (!canEditResource) {
      await alert({ description: '您没有创建课时资源的权限' })
      return
    }
    setEditingLesson({ ...emptyLesson(), chapterId })
    setLessonDialogOpen(true)
  }

  const handleEditLesson = async (lesson: Lesson) => {
    if (!canEditResource) {
      await alert({ description: '您没有编辑课时资源的权限' })
      return
    }
    setEditingLesson({
      id: lesson.id,
      chapterId: lesson.chapterId,
      lessonNo: lesson.lessonNo,
      lessonName: lesson.lessonName,
      resourceType: lesson.resourceType,
      resourceUrl: lesson.resourceUrl || '',
      duration: lesson.duration,
      content: lesson.content || '',
      sortOrder: lesson.sortOrder,
      status: lesson.status,
    })
    setLessonDialogOpen(true)
  }

  const handleSaveLesson = async () => {
    if (!editingLesson) return
    if (!editingLesson.lessonName.trim()) {
      await alert({ description: '课时名称不能为空' })
      return
    }
    if (!editingLesson.lessonNo || editingLesson.lessonNo < 1) {
      await alert({ description: '课时序号必须大于 0' })
      return
    }
    try {
      await saveLesson(editingLesson)
      setLessonDialogOpen(false)
      if (selectedCourseId) fetchChapters(selectedCourseId)
    } catch (err: any) {
      await alert({ description: err?.response?.data?.message || err?.response?.data?.error || '保存失败' })
    }
  }

  const handleDeleteLesson = async (lesson: Lesson) => {
    if (!canDeleteResource) {
      await alert({ description: '您没有删除课时资源的权限' })
      return
    }
    const confirmed = await confirm({
      title: '删除课时',
      description: `确定删除课时「${lesson.lessonName}」吗？`,
      confirmText: '删除',
      cancelText: '取消',
      variant: 'danger',
    })
    if (!confirmed) return
    try {
      await deleteLesson(lesson.id)
      if (selectedCourseId) fetchChapters(selectedCourseId)
    } catch (err: any) {
      await alert({ description: err?.response?.data?.message || err?.response?.data?.error || '删除失败' })
    }
  }

  const handleOpenImport = async (type: 'excel' | 'word' = 'excel') => {
    if (!selectedCourseId) {
      await alert({ description: '请先选择课程' })
      return
    }
    if (!canImport) {
      await alert({ description: '您没有导入章节的权限' })
      return
    }
    setImportType(type)
    setImportFile(null)
    setImportResult(null)
    setImportDialogOpen(true)
  }

  const handleImport = async () => {
    if (!selectedCourseId || !importFile) return
    setImportLoading(true)
    setImportResult(null)
    try {
      const res = importType === 'word'
        ? await importWordChapters(selectedCourseId, importFile)
        : await importChapters(selectedCourseId, importFile)
      setImportResult(res.data)
      if (res.success && res.data.failCount === 0) {
        if (selectedCourseId) fetchChapters(selectedCourseId)
      }
    } catch (err: any) {
      await alert({ description: err?.response?.data?.message || err?.response?.data?.error || '导入失败' })
    } finally {
      setImportLoading(false)
    }
  }

  const handleGenerate = async () => {
    if (!selectedCourseId) {
      await alert({ description: '请先选择课程' })
      return
    }
    if (!canEdit) {
      await alert({ description: '您没有创建章节的权限' })
      return
    }
    // 打开生成设置弹窗（生成范围 / 难度 / 备注）
    setGenNotes('')
    setGenOpen(true)
  }

  const handleGenerateConfirm = async () => {
    if (!selectedCourseId) return
    const notes = genNotes.trim()
    if (notes.length > 500) {
      await alert({ description: '备注内容不能超过 500 字' })
      return
    }
    setGenOpen(false)
    setGenerating(true)
    try {
      const res = await generateChapters(selectedCourseId, {
        chapterCount: genChapterCount,
        difficulty: genDifficulty,
        notes: notes || undefined,
      })
      const { chapterCount, lessonCount } = res.data
      await alert({
        description: `生成成功！共 ${chapterCount} 个章节、${lessonCount} 个课时（内容向量化在后台进行）。`,
      })
      fetchChapters(selectedCourseId)
    } catch (err: any) {
      await alert({
        description: err?.response?.data?.message || err?.response?.data?.error || '生成失败',
      })
    } finally {
      setGenerating(false)
    }
  }

  const selectedCourseName = useMemo(
    () => courses.find((c) => c.courseId === selectedCourseId)?.courseName || '',
    [courses, selectedCourseId]
  )

  return (
    <div className="space-y-6">
      {DialogComponent}

      {/* 一键生成设置弹窗：生成范围 + 难度 + 备注 */}
      <Dialog open={genOpen} onOpenChange={setGenOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>一键生成内容</DialogTitle>
            <DialogDescription>
              将为「{selectedCourseName}」通过 AI 自动生成完整的章节+课时+内容。
              注意：需要该课程当前无任何章节；生成约 60-90 秒，请耐心等待。
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label>生成范围（章节数量）</Label>
              <select
                value={genChapterCount}
                onChange={(e) => setGenChapterCount(Number(e.target.value))}
                className="h-10 w-full pl-3 pr-10 rounded-lg border border-neutral-200 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
              >
                {[3, 4, 5, 6, 7, 8, 9, 10, 11, 12].map((n) => (
                  <option key={n} value={n}>{n} 章（每章 2-3 课时）</option>
                ))}
              </select>
            </div>
            <div className="space-y-1.5">
              <Label>基础难易程度</Label>
              <select
                value={genDifficulty}
                onChange={(e) => setGenDifficulty(e.target.value)}
                className="h-10 w-full pl-3 pr-10 rounded-lg border border-neutral-200 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
              >
                <option value="入门">入门（零基础，例子多、铺垫细）</option>
                <option value="基础">基础（标准大学课程深度）</option>
                <option value="进阶">进阶（原理推导 + 综合实战）</option>
              </select>
            </div>
            <div className="space-y-1.5">
              <Label>内容备注（可选）</Label>
              <Textarea
                value={genNotes}
                onChange={(e) => setGenNotes(e.target.value)}
                rows={4}
                maxLength={500}
                placeholder="例如：只覆盖第 1-5 章基础内容，侧重集合框架与泛型；不要包含 GUI 章节……"
              />
              <p className="text-xs text-neutral-400">{genNotes.length}/500</p>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setGenOpen(false)} disabled={generating}>
              取消
            </Button>
            <Button onClick={handleGenerateConfirm} className="bg-indigo-600 hover:bg-indigo-700">
              确定
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-neutral-900">章节管理</h1>
          <p className="text-neutral-500 mt-1 text-sm">
            {isAdmin ? '管理所有课程的章节与课时资源' : `你好，${user?.realName || '老师'}，管理你所授课程的章节`}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <div className="relative">
            <select
              value={selectedCourseId ?? ''}
              onChange={(e) => setSelectedCourseId(Number(e.target.value))}
              className="h-10 pl-4 pr-10 rounded-lg border border-neutral-200 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              {courses.map((c) => (
                <option key={c.courseId} value={c.courseId}>
                  {c.courseName}
                </option>
              ))}
            </select>
          </div>
          {canImport && (
            <>
              <Button variant="outline" onClick={() => handleOpenImport('excel')} className="gap-2">
                <FileSpreadsheet className="w-4 h-4" />
                导入 Excel
              </Button>
              <Button variant="outline" onClick={() => handleOpenImport('word')} className="gap-2">
                <FileText className="w-4 h-4" />
                导入 Word
              </Button>
            </>
          )}
          {canEdit && (
            <Button
              variant="default"
              onClick={handleGenerate}
              disabled={generating || !selectedCourseId}
              className="gap-2 bg-indigo-600 hover:bg-indigo-700"
            >
              {generating ? (
                <>
                  <span className="inline-block w-4 h-4 rounded-full border-2 border-white border-t-transparent animate-spin" />
                  生成中...
                </>
              ) : (
                <>
                  <Sparkles className="w-4 h-4" />
                  一键生成内容
                </>
              )}
            </Button>
          )}
          <Button onClick={handleAddChapter} className="gap-2">
            <Plus className="w-4 h-4" />
            新增章节
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base font-medium flex items-center gap-2">
            <BookOpen className="w-5 h-5 text-primary-500" />
            {selectedCourseName || '请选择课程'}
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          {courseLoading ? (
            <div className="flex items-center justify-center py-20 text-neutral-400">课程列表加载中...</div>
          ) : courses.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 gap-4">
              <div className="text-neutral-400">
                {isAdmin || isTeacher ? '暂无可管理课程' : '你还没有选修任何课程'}
              </div>
              <Button variant="outline" onClick={fetchCourses}>
                重新加载
              </Button>
            </div>
          ) : loading ? (
            <div className="flex items-center justify-center py-20 text-neutral-400">章节加载中...</div>
          ) : error ? (
            <div className="flex flex-col items-center justify-center py-20 gap-4">
              <div className="text-danger">{error}</div>
              <Button variant="outline" onClick={() => selectedCourseId && fetchChapters(selectedCourseId)}>
                重新加载
              </Button>
            </div>
          ) : chapters.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 gap-4">
              <div className="text-neutral-400">该课程暂无章节</div>
              {canEdit && (
                <Button onClick={handleAddChapter} variant="outline" className="gap-2">
                  <Plus className="w-4 h-4" />
                  创建第一个章节
                </Button>
              )}
            </div>
          ) : (
            <div className="divide-y divide-neutral-100">
              {chapters.map((chapter) => {
                const expanded = expandedChapterIds.has(chapter.id)
                return (
                  <div key={chapter.id} className="group">
                    <div className="flex items-center justify-between px-6 py-4 hover:bg-neutral-50 transition-colors">
                      <button
                        onClick={() => toggleExpand(chapter.id)}
                        className="flex items-center gap-3 flex-1 text-left"
                      >
                        {expanded ? (
                          <ChevronDown className="w-4 h-4 text-neutral-400" />
                        ) : (
                          <ChevronRight className="w-4 h-4 text-neutral-400" />
                        )}
                        <div>
                          <div className="font-medium text-neutral-900">
                            第{chapter.chapterNo}章 {chapter.chapterName}
                          </div>
                          {chapter.description && (
                            <div className="text-xs text-neutral-500 mt-0.5 line-clamp-1">{chapter.description}</div>
                          )}
                        </div>
                      </button>
                      <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                        <Button
                          variant="ghost"
                          size="sm"
                          className="gap-1"
                          onClick={() => handleAddLesson(chapter.id)}
                        >
                          <Plus className="w-4 h-4" />
                          课时
                        </Button>
                        <DropdownMenu
                          open={openMenuId === chapter.id}
                          onOpenChange={(open) => setOpenMenuId(open ? chapter.id : null)}
                        >
                          <DropdownMenuTrigger asChild>
                            <Button variant="ghost" size="icon" className="w-8 h-8">
                              <MoreHorizontal className="w-4 h-4" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end" className="w-32">
                            <DropdownMenuItem
                              onSelect={(e) => {
                                e.preventDefault()
                                setOpenMenuId(null)
                                handleEditChapter(chapter)
                              }}
                            >
                              编辑章节
                            </DropdownMenuItem>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                              className="text-danger"
                              onSelect={(e) => {
                                e.preventDefault()
                                setOpenMenuId(null)
                                handleDeleteChapter(chapter)
                              }}
                            >
                              <Trash2 className="w-4 h-4 mr-2" />
                              删除章节
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </div>
                    </div>

                    {expanded && (
                      <div className="bg-neutral-50/50 px-6 pb-4">
                        {(chapter.lessons || []).length === 0 ? (
                          <div className="py-6 text-center text-sm text-neutral-400">暂无课时资源</div>
                        ) : (
                          <div className="space-y-2 pt-2">
                            {(chapter.lessons || []).map((lesson) => (
                              <div
                                key={lesson.id}
                                className="flex items-center justify-between bg-white border border-neutral-100 rounded-xl px-4 py-3 group/lesson hover:border-primary-200 transition-colors"
                              >
                                <div
                                  className="flex items-center gap-3 flex-1 cursor-pointer"
                                  onClick={() => setViewingLesson(lesson)}
                                >
                                  <div className="w-8 h-8 rounded-lg bg-primary-50 text-primary-600 flex items-center justify-center">
                                    {RESOURCE_ICONS[lesson.resourceType] || <FileText className="w-4 h-4" />}
                                  </div>
                                  <div>
                                    <div className="text-sm font-medium text-neutral-900">
                                      {lesson.lessonNo}. {lesson.lessonName}
                                    </div>
                                    <div className="text-xs text-neutral-500 flex items-center gap-2 mt-0.5">
                                      <Badge variant="outline" className="text-xs font-normal px-1.5 py-0">
                                        {RESOURCE_LABELS[lesson.resourceType] || lesson.resourceType}
                                      </Badge>
                                      {lesson.duration != null && lesson.duration > 0 && (
                                        <span>{Math.ceil(lesson.duration / 60)} 分钟</span>
                                      )}
                                    </div>
                                  </div>
                                </div>
                                <div className="flex items-center gap-1 opacity-0 group-hover/lesson:opacity-100 transition-opacity">
                                  <Button
                                    variant="ghost"
                                    size="sm"
                                    onClick={() => handleEditLesson(lesson)}
                                  >
                                    编辑
                                  </Button>
                                  <Button
                                    variant="ghost"
                                    size="icon"
                                    className="w-8 h-8 text-danger"
                                    onClick={() => handleDeleteLesson(lesson)}
                                  >
                                    <Trash2 className="w-4 h-4" />
                                  </Button>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </CardContent>
      </Card>

      {/* 章节编辑弹窗 */}
      <Dialog open={chapterDialogOpen} onOpenChange={setChapterDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editingChapter?.id ? '编辑章节' : '新增章节'}</DialogTitle>
            <DialogDescription>维护章节基本信息与排序</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="chapterNo">章节序号</Label>
                <Input
                  id="chapterNo"
                  type="number"
                  min={1}
                  value={editingChapter?.chapterNo || ''}
                  onChange={(e) =>
                    setEditingChapter((prev) => (prev ? { ...prev, chapterNo: Number(e.target.value) } : prev))
                  }
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="sortOrder">排序</Label>
                <Input
                  id="sortOrder"
                  type="number"
                  value={editingChapter?.sortOrder ?? ''}
                  onChange={(e) =>
                    setEditingChapter((prev) => (prev ? { ...prev, sortOrder: Number(e.target.value) } : prev))
                  }
                />
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="chapterName">章节名称</Label>
              <Input
                id="chapterName"
                maxLength={200}
                value={editingChapter?.chapterName || ''}
                onChange={(e) =>
                  setEditingChapter((prev) => (prev ? { ...prev, chapterName: e.target.value } : prev))
                }
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="description">章节描述</Label>
              <Input
                id="description"
                value={editingChapter?.description || ''}
                onChange={(e) =>
                  setEditingChapter((prev) => (prev ? { ...prev, description: e.target.value } : prev))
                }
              />
            </div>
            <div className="flex items-center gap-3 pt-2">
              <Switch
                id="chapterStatus"
                checked={(editingChapter?.status ?? 1) === 1}
                onCheckedChange={(checked) =>
                  setEditingChapter((prev) => (prev ? { ...prev, status: checked ? 1 : 0 } : prev))
                }
              />
              <Label htmlFor="chapterStatus" className="cursor-pointer">
                {(editingChapter?.status ?? 1) === 1 ? '已启用' : '已禁用'}
              </Label>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setChapterDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleSaveChapter}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 章节导入弹窗 */}
      <Dialog open={importDialogOpen} onOpenChange={setImportDialogOpen}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>导入章节</DialogTitle>
            <DialogDescription>
              导入「{selectedCourseName}」的章节和课时数据
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="flex rounded-lg border border-neutral-200 p-1 bg-neutral-50">
              <button
                type="button"
                onClick={() => { setImportType('excel'); setImportFile(null); setImportResult(null) }}
                className={`flex-1 py-1.5 text-sm rounded-md transition-colors ${
                  importType === 'excel'
                    ? 'bg-white text-primary-600 shadow-sm'
                    : 'text-neutral-500 hover:text-neutral-700'
                }`}
              >
                Excel
              </button>
              <button
                type="button"
                onClick={() => { setImportType('word'); setImportFile(null); setImportResult(null) }}
                className={`flex-1 py-1.5 text-sm rounded-md transition-colors ${
                  importType === 'word'
                    ? 'bg-white text-primary-600 shadow-sm'
                    : 'text-neutral-500 hover:text-neutral-700'
                }`}
              >
                Word
              </button>
            </div>
            <div className="rounded-lg border border-dashed border-neutral-300 bg-neutral-50 p-6 text-center">
              {importType === 'excel' ? (
                <FileSpreadsheet className="w-8 h-8 text-primary-500 mx-auto mb-3" />
              ) : (
                <FileText className="w-8 h-8 text-primary-500 mx-auto mb-3" />
              )}
              <div className="text-sm font-medium text-neutral-900 mb-1">
                上传 {importType === 'excel' ? 'Excel' : 'Word'} 文件
              </div>
              <div className="text-xs text-neutral-500 mb-4">
                支持 {importType === 'excel' ? '.xlsx' : '.docx'} 格式，单次最多 1000 行
              </div>
              <Input
                type="file"
                accept={importType === 'excel'
                  ? '.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
                  : '.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document'}
                onChange={(e) => setImportFile(e.target.files?.[0] || null)}
                className="bg-white"
              />
              {importFile && (
                <div className="mt-3 text-xs text-neutral-600">已选择：{importFile.name}</div>
              )}
            </div>
            <div className="flex items-center justify-between">
              <a
                href={importType === 'excel'
                  ? '/template/chapter-import-template.xlsx'
                  : '/template/chapter-import-template.docx'}
                download
                className="inline-flex items-center gap-1.5 text-sm text-primary-600 hover:text-primary-700"
              >
                <Download className="w-4 h-4" />
                下载导入模板
              </a>
            </div>
            {importResult && (
              <div className="rounded-lg border border-neutral-200 bg-white p-4 space-y-3">
                <div className="flex items-center gap-4 text-sm">
                  <span className="text-neutral-600">章节：<strong className="text-neutral-900">{importResult.chapterCount}</strong></span>
                  <span className="text-neutral-600">课时：<strong className="text-neutral-900">{importResult.lessonCount}</strong></span>
                  <span className="text-neutral-600">失败：<strong className={importResult.failCount > 0 ? 'text-danger' : 'text-neutral-900'}>{importResult.failCount}</strong></span>
                </div>
                {importResult.failures.length > 0 && (
                  <div className="max-h-40 overflow-y-auto text-xs space-y-1">
                    {importResult.failures.map((f, idx) => (
                      <div key={idx} className="text-danger">
                        第 {f.row} 行：{f.reason}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setImportDialogOpen(false)} disabled={importLoading}>
              取消
            </Button>
            <Button onClick={handleImport} disabled={!importFile || importLoading} className="gap-2">
              {importLoading ? '导入中...' : '开始导入'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 课时编辑弹窗 */}
      <Dialog open={lessonDialogOpen} onOpenChange={setLessonDialogOpen}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>{editingLesson?.id ? '编辑课时' : '新增课时'}</DialogTitle>
            <DialogDescription>维护课时资源信息</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="lessonNo">课时序号</Label>
                <Input
                  id="lessonNo"
                  type="number"
                  min={1}
                  value={editingLesson?.lessonNo || ''}
                  onChange={(e) =>
                    setEditingLesson((prev) => (prev ? { ...prev, lessonNo: Number(e.target.value) } : prev))
                  }
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="resourceType">资源类型</Label>
                <select
                  id="resourceType"
                  value={editingLesson?.resourceType || 'video'}
                  onChange={(e) =>
                    setEditingLesson((prev) =>
                      prev ? { ...prev, resourceType: e.target.value as LessonSaveRequest['resourceType'] } : prev
                    )
                  }
                  className="w-full h-10 px-3 rounded-lg border border-neutral-200 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                >
                  <option value="video">视频</option>
                  <option value="document">文档</option>
                  <option value="quiz">测验</option>
                  <option value="link">链接</option>
                </select>
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="lessonName">课时名称</Label>
              <Input
                id="lessonName"
                value={editingLesson?.lessonName || ''}
                onChange={(e) => setEditingLesson((prev) => (prev ? { ...prev, lessonName: e.target.value } : prev))}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="resourceUrl">资源 URL</Label>
              <Input
                id="resourceUrl"
                placeholder="视频地址、文档链接或外部网址"
                value={editingLesson?.resourceUrl || ''}
                onChange={(e) => setEditingLesson((prev) => (prev ? { ...prev, resourceUrl: e.target.value } : prev))}
              />
            </div>
            {editingLesson?.resourceType === 'video' && (
              <div className="space-y-2">
                <Label htmlFor="duration">时长（秒）</Label>
                <Input
                  id="duration"
                  type="number"
                  min={0}
                  value={editingLesson?.duration ?? ''}
                  onChange={(e) =>
                    setEditingLesson((prev) =>
                      prev ? { ...prev, duration: e.target.value ? Number(e.target.value) : undefined } : prev
                    )
                  }
                />
              </div>
            )}
            <div className="space-y-2">
              <Label htmlFor="content">内容/备注</Label>
              <Input
                id="content"
                placeholder="富文本内容或补充说明"
                value={editingLesson?.content || ''}
                onChange={(e) => setEditingLesson((prev) => (prev ? { ...prev, content: e.target.value } : prev))}
              />
            </div>
            <div className="flex items-center gap-3 pt-2">
              <Switch
                id="lessonStatus"
                checked={(editingLesson?.status ?? 1) === 1}
                onCheckedChange={(checked) =>
                  setEditingLesson((prev) => (prev ? { ...prev, status: checked ? 1 : 0 } : prev))
                }
              />
              <Label htmlFor="lessonStatus" className="cursor-pointer">
                {(editingLesson?.status ?? 1) === 1 ? '已启用' : '已禁用'}
              </Label>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setLessonDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleSaveLesson}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 课时内容查看弹窗 */}
      <Dialog open={!!viewingLesson} onOpenChange={(open) => !open && setViewingLesson(null)}>
        <DialogContent className="max-w-2xl max-h-[80vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{viewingLesson ? `${viewingLesson.lessonNo}. ${viewingLesson.lessonName}` : '课时内容'}</DialogTitle>
            <DialogDescription>
              {viewingLesson && (
                <Badge variant="outline" className="mt-1">
                  {RESOURCE_LABELS[viewingLesson.resourceType] || viewingLesson.resourceType}
                </Badge>
              )}
            </DialogDescription>
          </DialogHeader>
          <div className="py-4">
            {viewingLesson?.content ? (
              <MarkdownView
                className="text-sm text-neutral-800 leading-relaxed"
                content={viewingLesson.content}
              />
            ) : (
              <div className="text-sm text-neutral-400 text-center py-8">
                暂无内容，请点击「编辑」补充
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setViewingLesson(null)}>
              关闭
            </Button>
            {viewingLesson && canEditResource && (
              <Button
                onClick={() => {
                  setViewingLesson(null)
                  handleEditLesson(viewingLesson)
                }}
              >
                编辑内容
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* AI 生成中全局遮罩：禁止点击、切换课程等操作 */}
      {generating && (
        <div className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-black/40 backdrop-blur-sm">
          <div className="inline-flex items-center gap-3 px-6 py-4 rounded-2xl bg-white shadow-xl">
            <span className="inline-block w-5 h-5 rounded-full border-2 border-indigo-600 border-t-transparent animate-spin" />
            <span className="text-sm font-medium text-neutral-800">AI 生成中，请勿关闭或切换页面…</span>
          </div>
          <p className="mt-3 text-xs text-white/90">生成过程约 60-90 秒，关闭浏览器可能导致生成中断</p>
        </div>
      )}
    </div>
  )
}

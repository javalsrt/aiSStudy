import React, { useState, useRef } from 'react'
import {
  Upload,
  FileSpreadsheet,
  Sparkles,
  Check,
  AlertCircle,
  Download,
  RefreshCw,
  FileText,
  Image as ImageIcon,
  Loader2,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Select } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
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
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { previewImport, confirmImport, getImportRecords } from '@/api/course-import'
import { getTeacherClasses } from '@/api/teacher-class'
import { useDialog } from '@/hooks/use-dialog'
import type { ImportPreviewResult, ImportConfirmResult, CourseImportRecord } from '@/types'
import { useEffect } from 'react'

const ACCEPTED_FORMATS = '.xlsx,.docx,.pdf,.jpg,.png'
const ALLOWED_EXTS = ['xlsx', 'docx', 'pdf', 'jpg', 'jpeg', 'png']
const MAX_FILE_SIZE = 10 * 1024 * 1024 // 10MB

/** 连续周次数组压缩成区间显示，避免「[1,2,3,...21]」把预览表格撑开 */
function formatWeeks(weeks?: string | null) {
  if (!weeks) return '-'
  const nums = Array.from(new Set((weeks.match(/\d+/g) || []).map(Number))).sort((a, b) => a - b)
  if (nums.length === 0) return '-'
  const continuous = nums.every((n, i) => i === 0 || n === nums[i - 1] + 1)
  if (nums.length > 6 && continuous) return `${nums[0]}-${nums[nums.length - 1]}`
  return weeks
}

function getFileIcon(fileName: string) {
  const ext = fileName.split('.').pop()?.toLowerCase()
  if (ext === 'xlsx' || ext === 'xls') return <FileSpreadsheet className="w-6 h-6 text-emerald-500" />
  if (ext === 'docx' || ext === 'doc') return <FileText className="w-6 h-6 text-blue-500" />
  if (ext === 'pdf') return <FileText className="w-6 h-6 text-red-500" />
  if (ext === 'jpg' || ext === 'jpeg' || ext === 'png') return <ImageIcon className="w-6 h-6 text-green-500" />
  return <FileText className="w-6 h-6 text-neutral-500" />
}

export function CourseImportPage() {
  const { alert, DialogComponent } = useDialog()
  const [isDragging, setIsDragging] = useState(false)
  const [files, setFiles] = useState<File[]>([])
  const [uploading, setUploading] = useState(false)
  const [preview, setPreview] = useState<ImportPreviewResult | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [confirmResult, setConfirmResult] = useState<ImportConfirmResult | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // 默认班级：仅当文件/照片里识别不到班级时兜底，可留空
  const [classes, setClasses] = useState<{ id: number; className: string }[]>([])
  const [importClass, setImportClass] = useState('')

  // 导入记录
  const [records, setRecords] = useState<CourseImportRecord[]>([])
  const [recordsLoading, setRecordsLoading] = useState(false)
  const [recordsError, setRecordsError] = useState<string | null>(null)
  const [expandedRecords, setExpandedRecords] = useState<Set<number>>(new Set())

  const resetState = () => {
    setFiles([])
    setPreview(null)
    setConfirmResult(null)
    setError(null)
    setUploading(false)
    setConfirming(false)
    setConfirmOpen(false)
  }

  const fetchRecords = async () => {
    setRecordsLoading(true)
    setRecordsError(null)
    try {
      const data = await getImportRecords()
      setRecords(data || [])
    } catch (err: any) {
      setRecordsError(err?.response?.data?.message || err?.response?.data?.error || '导入记录加载失败')
    } finally {
      setRecordsLoading(false)
    }
  }

  const toggleRecord = (id: number) => {
    setExpandedRecords((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  useEffect(() => {
    fetchRecords()
  }, [])

  // 加载可选班级（管理员为全校班级，教师为任教班级）
  useEffect(() => {
    getTeacherClasses()
      .then((data) => setClasses(data || []))
      .catch(() => setClasses([]))
  }, [])

  /**
   * 批量上传：一次可选择多张课表照片/多个文件，逐个解析后合并预览。
   * 班级由文件内容（班级列/文件名/图片标题）自动识别，页面上的默认班级仅在识别不到时兜底。
   */
  const handleFiles = async (selectedFiles: File[]) => {
    if (!selectedFiles.length) return

    const validFiles: File[] = []
    const invalidMessages: string[] = []
    for (const f of selectedFiles) {
      const ext = f.name.split('.').pop()?.toLowerCase()
      if (!ext || !ALLOWED_EXTS.includes(ext)) {
        invalidMessages.push(`${f.name}：不支持 .${ext || '未知'} 格式`)
      } else if (f.size > MAX_FILE_SIZE) {
        invalidMessages.push(`${f.name}：超过 10MB`)
      } else {
        validFiles.push(f)
      }
    }
    setError(invalidMessages.length ? invalidMessages.join('；') : null)
    if (!validFiles.length) return

    setConfirmResult(null)
    setPreview(null)
    setFiles(validFiles)
    setUploading(true)

    const merged: ImportPreviewResult = { total: 0, success: 0, errors: [], preview: [] }
    const fileErrors: string[] = []
    for (const f of validFiles) {
      try {
        const result = await previewImport(f, importClass || undefined)
        merged.total += result.total || 0
        merged.success += result.success || 0
        merged.errors = [...merged.errors, ...(result.errors || [])]
        merged.preview = [...merged.preview, ...(result.preview || [])]
      } catch (err: unknown) {
        // 优先展示后端返回的中文原因，避免只显示 “Request failed with status code 400”
        const backendMsg = (err as any)?.response?.data?.error
        const msg = backendMsg || (err instanceof Error ? err.message : '文件解析失败，请稍后重试')
        fileErrors.push(`${f.name}：${msg}`)
      }
    }

    if (fileErrors.length) setError(fileErrors.join('；'))
    if (merged.total > 0) {
      setPreview(merged)
    } else if (!fileErrors.length) {
      setError('未能从所选文件中解析出课程，请检查文件内容')
    }
    setUploading(false)
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setIsDragging(false)
    handleFiles(Array.from(e.dataTransfer.files || []))
  }

  const handleFileSelect = () => {
    fileInputRef.current?.click()
  }

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    handleFiles(Array.from(e.target.files || []))
    e.target.value = ''
  }

  const handleConfirm = async () => {
    if (!preview) return
    setConfirming(true)
    setError(null)
    try {
      const result = await confirmImport(preview.preview, files.map((f) => f.name).join('、'))
      setConfirmResult(result)
      setConfirmOpen(false)
      // 导入成功后刷新导入记录
      fetchRecords()
    } catch (err: unknown) {
      const backendMsg = (err as any)?.response?.data?.error
      const msg = backendMsg || (err instanceof Error ? err.message : '导入失败，请稍后重试')
      setError(msg)
    } finally {
      setConfirming(false)
    }
  }

  const handleSelectTeacher = (index: number, teacherId: number, teacherName: string) => {
    if (!preview) return
    const next = { ...preview, preview: [...preview.preview] }
    const item = { ...next.preview[index] }
    item.teacherId = teacherId
    item.matchedTeacherName = teacherName
    item.teacherMatchStatus = 'matched'
    next.preview[index] = item
    setPreview(next)
  }

  const errorCount = preview ? preview.total - preview.success : 0
  const showPreview = preview && !confirmResult

  return (
    <div className="space-y-6">
      {DialogComponent}
      {/* Page header */}
      <div>
        {/* 副标题（不展示）：通过文件批量导入课程和排课信息，AI 自动识别解析 */}
        <h1 className="text-display-4-medium text-ink-primary">课程导入</h1>
      </div>

      <Tabs defaultValue="upload">
        <TabsList>
          <TabsTrigger value="upload">文件导入</TabsTrigger>
          <TabsTrigger value="history">导入记录</TabsTrigger>
        </TabsList>

        <TabsContent value="upload" className="mt-6 space-y-6">
          {/* Error banner */}
          {error && (
            <Card className="border-danger/30 bg-danger/5">
              <CardContent className="p-4 flex items-start gap-3">
                <AlertCircle className="w-5 h-5 text-danger shrink-0 mt-0.5" />
                <div className="flex-1">
                  <div className="text-sm font-medium text-danger">操作失败</div>
                  <div className="text-sm text-danger/80 mt-0.5">{error}</div>
                </div>
                <Button variant="ghost" size="sm" onClick={() => setError(null)}>
                  关闭
                </Button>
              </CardContent>
            </Card>
          )}

          {/* Import result */}
          {confirmResult && (
            <Card className="border-success/30 bg-success/5">
              <CardContent className="p-6">
                <div className="flex items-start gap-4">
                  <div className="w-12 h-12 rounded-xl bg-success/15 flex items-center justify-center shrink-0">
                    <Check className="w-6 h-6 text-success" />
                  </div>
                  <div className="flex-1">
                    <div className="flex items-center justify-between mb-2">
                      <span className="font-semibold text-neutral-900">导入完成</span>
                      <Button variant="ghost" size="sm" onClick={resetState} className="gap-1">
                        <RefreshCw className="w-4 h-4" />
                        继续导入
                      </Button>
                    </div>
                    <div className="flex flex-wrap gap-2 mb-3">
                      <Badge variant="success" className="text-xs">
                        成功导入 {confirmResult.imported} 条
                      </Badge>
                      {(confirmResult.autoFilled ?? 0) > 0 && (
                        <Badge variant="success" className="text-xs">
                          自动补充 {confirmResult.autoFilled} 条
                        </Badge>
                      )}
                      {confirmResult.skipped > 0 && (
                        <Badge variant="warning" className="text-xs">
                          跳过 {confirmResult.skipped} 条
                        </Badge>
                      )}
                    </div>
                    {confirmResult.messages.length > 0 && (
                      <div className="mt-3 space-y-1.5">
                        {confirmResult.messages.map((msg, idx) => (
                          <div key={idx} className="text-sm text-neutral-600 flex items-start gap-2">
                            <span className="w-1.5 h-1.5 rounded-full bg-neutral-400 mt-1.5 shrink-0"></span>
                            <span>{msg}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              </CardContent>
            </Card>
          )}

          {files.length === 0 ? (
            <Card>
              <CardContent className="p-8">
                {/* 班级由文件内容自动识别；默认班级仅在识别不到时兜底，可留空 */}
                <div className="mb-5 flex flex-col gap-2 sm:flex-row sm:items-center">
                  <span className="shrink-0 text-sm text-neutral-600">默认班级</span>
                  <div className="w-full sm:w-[260px]">
                    <Select
                      value={importClass}
                      onChange={(e) => setImportClass(e.target.value)}
                      aria-label="选择默认班级"
                      className="w-full"
                    >
                      <option value="">自动识别（可选）</option>
                      {classes.map((c) => (
                        <option key={c.id} value={c.className}>
                          {c.className}
                        </option>
                      ))}
                    </Select>
                  </div>
                  <span className="text-xs text-neutral-500">
                    文件/照片里识别不到班级时使用
                  </span>
                </div>
                <div
                  className={`border-2 border-dashed rounded-2xl p-12 text-center transition-all cursor-pointer ${
                    isDragging
                      ? 'border-emerald-300 bg-emerald-50'
                      : 'border-neutral-200 hover:border-emerald-300 hover:bg-neutral-50'
                  }`}
                  onDragOver={(e) => {
                    e.preventDefault()
                    setIsDragging(true)
                  }}
                  onDragLeave={() => setIsDragging(false)}
                  onDrop={handleDrop}
                  onClick={handleFileSelect}
                >
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept={ACCEPTED_FORMATS}
                    multiple
                    className="hidden"
                    onChange={handleInputChange}
                  />
                  <div className="w-20 h-20 mx-auto mb-6 rounded-2xl bg-emerald-50 flex items-center justify-center">
                    <Upload className="w-10 h-10 text-emerald-600" />
                  </div>
                  <h3 className="text-lg font-semibold text-neutral-900 mb-2">
                    拖拽文件到此处，或点击上传（支持多选）
                  </h3>
                  <p className="text-neutral-500 text-sm mb-4">
                    支持 .xlsx、.docx、.pdf 文件，以及 .jpg / .png 课表照片，单文件不超过 10MB
                  </p>
                  <Button variant="default" className="gap-2">
                    <FileSpreadsheet className="w-4 h-4" />
                    选择文件
                  </Button>
                </div>

                <div className="mt-8 p-5 bg-neutral-50 rounded-xl">
                  <div className="flex items-center gap-2 mb-3">
                    <Sparkles className="w-4 h-4 text-primary-550" />
                    <span className="font-medium text-neutral-900 text-sm">导入说明</span>
                  </div>
                  <ul className="text-sm text-neutral-600 space-y-2">
                    <li className="flex items-start gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-neutral-400 mt-1.5 shrink-0"></span>
                      文件需包含：课程名称、授课教师、班级、星期、节次、教室、周次等字段
                    </li>
                    <li className="flex items-start gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-neutral-400 mt-1.5 shrink-0"></span>
                      AI 将自动识别文件内容并匹配系统中的教师和班级，照片可从文件名或图片标题识别班级
                    </li>
                    <li className="flex items-start gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-neutral-400 mt-1.5 shrink-0"></span>
                      支持一次上传多张课表照片或多个文件，自动按班级分别导入
                    </li>
                    <li className="flex items-start gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-neutral-400 mt-1.5 shrink-0"></span>
                      导入前可预览数据，确认无误后再提交
                    </li>
                    <li className="flex items-start gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-primary-400 mt-1.5 shrink-0"></span>
                      导入后会按课程总课时自动补充剩余课时，确保「要求上多少课时就排多少课时」
                    </li>
                  </ul>
                  <div className="mt-4 flex flex-wrap gap-3">
                    <a
                      href="/template/schedule-import-template.xlsx"
                      download
                      className="inline-flex items-center gap-2 h-8 px-4 text-xs rounded-full border border-neutral-200 bg-surface-primary text-neutral-900 hover:bg-neutral-100 hover:border-neutral-300 transition-all"
                    >
                      <Download className="w-4 h-4" />
                      下载空白模板
                    </a>
                  </div>
                </div>
              </CardContent>
            </Card>
          ) : (
            <>
              {/* Upload status */}
              <Card>
                <CardContent className="p-6">
                  <div className="flex items-center gap-4">
                    <div className="w-12 h-12 rounded-xl bg-emerald-50 flex items-center justify-center shrink-0">
                      {getFileIcon(files[0]?.name || '')}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between mb-1">
                        <span className="font-medium text-neutral-900 truncate">
                          {files.length === 1
                            ? files[0].name
                            : `${files[0].name} 等 ${files.length} 个文件`}
                        </span>
                        <Badge
                          variant={uploading ? 'warning' : preview ? 'success' : 'danger'}
                          className="text-xs ml-2 shrink-0"
                        >
                          {uploading ? 'AI 解析中...' : preview ? '解析完成' : '解析失败'}
                        </Badge>
                      </div>
                      <div className="text-sm text-neutral-500">
                        {uploading
                          ? `正在解析 ${files.length} 个文件，AI 会按班级自动整理课程...`
                          : preview
                          ? `共识别 ${preview.total} 条课程数据，${preview.success} 条成功，${errorCount} 条异常`
                          : '请重新选择文件'}
                      </div>
                    </div>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={resetState}
                      disabled={uploading || confirming}
                    >
                      <RefreshCw className="w-4 h-4" />
                    </Button>
                  </div>
                  {uploading && (
                    <div className="mt-4">
                      <div className="h-2 bg-neutral-100 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-emerald-500 rounded-full transition-all animate-pulse"
                          style={{ width: '60%' }}
                        ></div>
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>

              {/* Preview table */}
              {showPreview && (
                <Card>
                  <CardHeader className="flex flex-row items-start justify-between space-y-0">
                    <div>
                      <CardTitle className="text-lg">导入预览</CardTitle>
                      <p className="text-sm text-neutral-500 mt-1">
                        请核对导入数据，异常数据请修正后再确认导入
                      </p>
                      <p className="text-xs text-neutral-500 mt-1">
                        照片导入时，未匹配或未标注教师的课程会自动归属该班班主任
                      </p>
                      <p className="text-xs text-primary-600 mt-1.5 flex items-center gap-1">
                        <Sparkles className="w-3.5 h-3.5" />
                        确认导入后，系统将自动按课程总课时补充剩余课时
                      </p>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge variant="secondary" className="text-xs">
                        总计 {preview.total} 条
                      </Badge>
                      <Badge variant="success" className="text-xs">
                        成功 {preview.success} 条
                      </Badge>
                      {errorCount > 0 && (
                        <Badge variant="danger" className="text-xs">
                          异常 {errorCount} 条
                        </Badge>
                      )}
                    </div>
                  </CardHeader>

                  <div className="overflow-x-auto">
                    <table className="w-full">
                      <thead>
                        <tr className="border-t border-b border-neutral-200 bg-neutral-50/50">
                          <th className="text-left px-6 py-3 text-sm font-medium text-neutral-500">课程名称</th>
                          <th className="text-left px-6 py-3 text-sm font-medium text-neutral-500">授课教师</th>
                          <th className="text-left px-6 py-3 text-sm font-medium text-neutral-500">班级</th>
                          <th className="text-left px-6 py-3 text-sm font-medium text-neutral-500">星期/时间</th>
                          <th className="text-left px-6 py-3 text-sm font-medium text-neutral-500">教室</th>
                          <th className="text-left px-6 py-3 text-sm font-medium text-neutral-500">周次</th>
                          <th className="text-left px-6 py-3 text-sm font-medium text-neutral-500">学分</th>
                          <th className="text-left px-6 py-3 text-sm font-medium text-neutral-500">状态</th>
                        </tr>
                      </thead>
                      <tbody>
                        {preview.preview.map((item, idx) => (
                            <tr key={idx} className="border-b border-neutral-100 hover:bg-neutral-50/50">
                              <td className="px-6 py-3.5 text-sm font-medium text-neutral-900">
                                {item.courseName}
                              </td>
                              <td className="px-6 py-3.5 text-sm text-neutral-700">
                                {item.teacherMatchStatus === 'matched' ? (
                                  <div className="flex items-center gap-1.5">
                                    <span>{item.matchedTeacherName || item.teacherName || '-'}</span>
                                    <Check className="w-3.5 h-3.5 text-success" />
                                  </div>
                                ) : item.teacherMatchStatus === 'fallback' ? (
                                  <div className="flex items-center gap-1.5" title={item.teacherFallbackReason}>
                                    <span>{item.matchedTeacherName || '-'}</span>
                                    <Badge variant="warning" className="text-[10px] px-1.5 py-0">
                                      班主任
                                    </Badge>
                                  </div>
                                ) : item.teacherMatchStatus === 'fuzzy' ? (
                                  <div className="flex items-center gap-1.5">
                                    <span className="text-warning">{item.teacherName || '-'}</span>
                                    <DropdownMenu>
                                      <DropdownMenuTrigger asChild>
                                        <Button
                                          variant="ghost"
                                          size="sm"
                                          className="h-6 px-1.5 text-xs text-warning gap-1"
                                        >
                                          <AlertCircle className="w-3.5 h-3.5" />
                                          推荐：{item.matchedTeacherName}
                                        </Button>
                                      </DropdownMenuTrigger>
                                      <DropdownMenuContent align="start">
                                        {item.teacherSuggestions?.map((s) => (
                                          <DropdownMenuItem
                                            key={s.teacherId}
                                            onSelect={() =>
                                              handleSelectTeacher(idx, s.teacherId, s.teacherName)
                                            }
                                          >
                                            {s.teacherName}
                                            {s.distance === 1 ? '（疑似错别字）' : `（差异 ${s.distance}）`}
                                          </DropdownMenuItem>
                                        ))}
                                      </DropdownMenuContent>
                                    </DropdownMenu>
                                  </div>
                                ) : (
                                  <span>{item.teacherName || '-'}</span>
                                )}
                              </td>
                              <td className="px-6 py-3.5 text-sm text-neutral-700">
                                {item.className || '-'}
                              </td>
                              <td className="px-6 py-3.5 text-sm text-neutral-700">
                                <div>周{item.dayOfWeek}</div>
                                {item.startTime && item.endTime && (
                                  <div className="text-xs text-neutral-400">
                                    {item.startTime} - {item.endTime}
                                  </div>
                                )}
                              </td>
                              <td className="px-6 py-3.5 text-sm text-neutral-700">
                                {item.classroom || '-'}
                              </td>
                              <td className="px-6 py-3.5 text-sm text-neutral-700">
                                {formatWeeks(item.weeks)}
                              </td>
                              <td className="px-6 py-3.5 text-sm text-neutral-700">
                                {item.credit ?? '-'}
                              </td>
                              <td className="px-6 py-3.5">
                                {item.teacherMatchStatus === 'fuzzy' ? (
                                  <div className="flex items-center gap-1 text-warning">
                                    <AlertCircle className="w-4 h-4" />
                                    <span className="text-sm">教师待确认</span>
                                  </div>
                                ) : item.teacherMatchStatus === 'fallback' ? (
                                  <div
                                    className="flex items-center gap-1 text-warning"
                                    title={item.teacherFallbackReason}
                                  >
                                    <AlertCircle className="w-4 h-4" />
                                    <span className="text-sm">归属班主任</span>
                                  </div>
                                ) : (
                                  <div className="flex items-center gap-1 text-success">
                                    <Check className="w-4 h-4" />
                                    <span className="text-sm">正常</span>
                                  </div>
                                )}
                              </td>
                            </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>

                  {/* Error list */}
                  {preview.errors.length > 0 && (
                    <div className="p-6 border-t border-neutral-100 bg-warning/5">
                      <div className="flex items-center gap-2 mb-3">
                        <AlertCircle className="w-4 h-4 text-warning" />
                        <span className="text-sm font-medium text-neutral-900">
                          解析错误信息（{preview.errors.length}）
                        </span>
                      </div>
                      <ul className="space-y-1.5">
                        {preview.errors.map((err, idx) => (
                          <li key={idx} className="text-sm text-neutral-600 flex items-start gap-2">
                            <span className="w-1.5 h-1.5 rounded-full bg-warning mt-1.5 shrink-0"></span>
                            <span>
                              第 {err.row} 行 {err.courseName ? `「${err.courseName}」` : ''}
                              {err.errors.map((e, i) => (
                                <span key={i} className="block ml-0">{e}</span>
                              ))}
                            </span>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}

                  <div className="p-6 pt-4 flex items-center justify-between border-t border-neutral-100">
                    <div className="text-sm text-neutral-500">
                      将导入 <span className="font-medium text-neutral-900">{preview.success}</span> 条课程数据
                    </div>
                    <div className="flex gap-2">
                      <Button variant="outline" onClick={resetState} disabled={confirming}>
                        取消
                      </Button>
                      <Button
                        variant="default"
                        disabled={confirming || preview.success === 0}
                        onClick={() => setConfirmOpen(true)}
                      >
                        确认导入
                      </Button>
                      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
                        <DialogContent>
                          <DialogHeader>
                            <DialogTitle>确认导入</DialogTitle>
                            <DialogDescription>
                              确定要导入这 {preview.success} 条课程数据吗？
                              {errorCount > 0 && ` 其中 ${errorCount} 条异常数据将被跳过。`}
                              <br />
                              <span className="text-primary-600">
                                导入后将自动按课程总课时补充剩余课时，确保排满。
                              </span>
                            </DialogDescription>
                          </DialogHeader>
                          <DialogFooter>
                            <Button
                              variant="outline"
                              onClick={() => setConfirmOpen(false)}
                              disabled={confirming}
                            >
                              取消
                            </Button>
                            <Button variant="default" onClick={handleConfirm} disabled={confirming}>
                              {confirming ? (
                                <>
                                  <Loader2 className="w-4 h-4 mr-1 animate-spin" />
                                  导入中...
                                </>
                              ) : (
                                '确认导入'
                              )}
                            </Button>
                          </DialogFooter>
                        </DialogContent>
                      </Dialog>
                    </div>
                  </div>
                </Card>
              )}
            </>
          )}
        </TabsContent>

        <TabsContent value="history" className="mt-6">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0">
              <div>
                <CardTitle className="text-lg">导入记录</CardTitle>
                <p className="text-sm text-neutral-500 mt-1">
                  查看历史课程导入操作及结果详情
                </p>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={fetchRecords}
                disabled={recordsLoading}
                className="gap-1"
              >
                {recordsLoading ? (
                  <Loader2 className="w-4 h-4 animate-spin" />
                ) : (
                  <RefreshCw className="w-4 h-4" />
                )}
                刷新
              </Button>
            </CardHeader>
            <CardContent className="pt-0">
              {recordsError && (
                <div className="mb-4 p-4 rounded-lg border border-danger/30 bg-danger/5 flex items-start gap-3">
                  <AlertCircle className="w-5 h-5 text-danger shrink-0 mt-0.5" />
                  <div className="flex-1">
                    <div className="text-sm font-medium text-danger">加载失败</div>
                    <div className="text-sm text-danger/80 mt-0.5">{recordsError}</div>
                  </div>
                  <Button variant="ghost" size="sm" onClick={fetchRecords}>
                    重试
                  </Button>
                </div>
              )}

              {recordsLoading && records.length === 0 ? (
                <div className="p-12 text-center text-neutral-500">
                  <Loader2 className="w-8 h-8 mx-auto mb-3 animate-spin text-primary-500" />
                  正在加载导入记录...
                </div>
              ) : records.length === 0 ? (
                <div className="p-12 text-center text-neutral-500">
                  <FileText className="w-12 h-12 mx-auto mb-3 text-neutral-300" />
                  <div>暂无导入记录</div>
                  <div className="text-sm mt-1">在「文件导入」标签页上传并确认导入后将显示在此</div>
                </div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead>
                      <tr className="border-t border-b border-neutral-200 bg-neutral-50/50">
                        <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">文件名</th>
                        <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">导入时间</th>
                        <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">导入人</th>
                        <th className="text-left px-4 py-3 text-sm font-medium text-neutral-500">学期</th>
                        <th className="text-right px-4 py-3 text-sm font-medium text-neutral-500">总数</th>
                        <th className="text-right px-4 py-3 text-sm font-medium text-neutral-500">成功</th>
                        <th className="text-right px-4 py-3 text-sm font-medium text-neutral-500">跳过</th>
                        <th className="text-center px-4 py-3 text-sm font-medium text-neutral-500">详情</th>
                      </tr>
                    </thead>
                    <tbody>
                      {records.map((record) => {
                        const isExpanded = expandedRecords.has(record.id)
                        let messages: string[] = []
                        try {
                          messages = record.messages ? JSON.parse(record.messages) : []
                        } catch {
                          messages = record.messages ? [record.messages] : []
                        }
                        return (
                          <React.Fragment key={record.id}>
                            <tr className="border-b border-neutral-100 hover:bg-neutral-50/50">
                              <td className="px-4 py-3.5 text-sm text-neutral-900">
                                <div className="flex items-center gap-2">
                                  {getFileIcon(record.fileName || '')}
                                  <span className="font-medium truncate max-w-[200px]">
                                    {record.fileName || '未命名文件'}
                                  </span>
                                </div>
                              </td>
                              <td className="px-4 py-3.5 text-sm text-neutral-700 whitespace-nowrap">
                                {record.createdAt
                                  ? new Date(record.createdAt).toLocaleString('zh-CN')
                                  : '-'}
                              </td>
                              <td className="px-4 py-3.5 text-sm text-neutral-700">
                                {record.importedByName || `用户 ${record.importedBy}` || '-'}
                              </td>
                              <td className="px-4 py-3.5 text-sm text-neutral-700">
                                {record.semester || '-'}
                              </td>
                              <td className="px-4 py-3.5 text-sm text-neutral-700 text-right">
                                {record.totalCount ?? '-'}
                              </td>
                              <td className="px-4 py-3.5 text-sm text-success text-right font-medium">
                                {record.successCount ?? '-'}
                              </td>
                              <td className="px-4 py-3.5 text-sm text-warning text-right font-medium">
                                {record.skipCount ?? 0}
                              </td>
                              <td className="px-4 py-3.5 text-center">
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  onClick={() => toggleRecord(record.id)}
                                  className="h-7 px-2 text-xs"
                                >
                                  {isExpanded ? '收起' : '展开'}
                                </Button>
                              </td>
                            </tr>
                            {isExpanded && (
                              <tr className="border-b border-neutral-100 bg-neutral-50/30">
                                <td colSpan={8} className="px-4 py-4">
                                  <div className="text-sm font-medium text-neutral-900 mb-2">导入结果详情</div>
                                  {messages.length === 0 ? (
                                    <div className="text-sm text-neutral-500">无详细消息</div>
                                  ) : (
                                    <ul className="space-y-1.5 max-h-60 overflow-y-auto">
                                      {messages.map((msg, idx) => (
                                        <li key={idx} className="text-sm text-neutral-600 flex items-start gap-2">
                                          <span className="w-1.5 h-1.5 rounded-full bg-neutral-400 mt-1.5 shrink-0"></span>
                                          <span>{msg}</span>
                                        </li>
                                      ))}
                                    </ul>
                                  )}
                                </td>
                              </tr>
                            )}
                          </React.Fragment>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}

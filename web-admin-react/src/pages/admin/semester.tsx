import { useEffect, useRef, useState } from 'react'
import { Select } from '@/components/ui/select'
import {
  Plus,
  MoreHorizontal,
  Calendar,
  RefreshCw,
  AlertCircle,
} from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetFooter,
  SheetTrigger,
} from '@/components/ui/sheet'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  getCurrentSemester,
  getSemesterList,
  createSemester,
  updateSemester,
  switchSemester,
  deleteSemester,
} from '@/api/semester'
import { getClasses } from '@/api/staff'
import type { Semester, SemesterCurrent, ClassInfo } from '@/types'

const STATUS_META: Record<
  SemesterCurrent['status'],
  { label: string; variant: 'warning' | 'success' | 'secondary' }
> = {
  before: { label: '未开始', variant: 'warning' },
  ongoing: { label: '进行中', variant: 'success' },
  ended: { label: '已结束', variant: 'secondary' },
}

const emptyForm = {
  name: '',
  startDate: '',
  endDate: '',
  weekCount: 20,
  semesterType: 'NORMAL' as 'NORMAL' | 'EXTRA',
  classIds: [] as number[],
}

export function SemesterPage() {
  const [sheetOpen, setSheetOpen] = useState(false)
  const [current, setCurrent] = useState<SemesterCurrent | null>(null)
  const [list, setList] = useState<Semester[]>([])
  const [classes, setClasses] = useState<ClassInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [actionError, setActionError] = useState('')

  // 新建/编辑学期表单
  const [form, setForm] = useState({ ...emptyForm })
  const [editingId, setEditingId] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // 确认弹窗
  const [switchTarget, setSwitchTarget] = useState<Semester | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Semester | null>(null)
  const [switching, setSwitching] = useState(false)
  // 与 switching 并行的同步锁：避免快速连点在重新渲染前重复提交
  const switchGuard = useRef(false)
  const deleteGuard = useRef(false)
  const [deleting, setDeleting] = useState(false)

  // 更多菜单展开状态
  const [openMenuId, setOpenMenuId] = useState<number | null>(null)

  const loadAll = async () => {
    setLoading(true)
    setError('')
    try {
      const [cur, lst, cls] = await Promise.all([
        getCurrentSemester(),
        getSemesterList(),
        getClasses(),
      ])
      setCurrent(cur)
      setList(lst || [])
      setClasses(cls || [])
    } catch (err: any) {
      setError(
        err.response?.data?.message ||
          err.response?.data?.error ||
          '数据加载失败'
      )
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  const resetForm = () => {
    setForm({ ...emptyForm })
    setEditingId(null)
  }

  const openCreateSheet = () => {
    resetForm()
    setSheetOpen(true)
  }

  const openEditSheet = (semester: Semester) => {
    setEditingId(semester.id || null)
    setForm({
      name: semester.name,
      startDate: semester.startDate,
      endDate: semester.endDate || '',
      weekCount: semester.weekCount || 20,
      semesterType: semester.semesterType || 'NORMAL',
      classIds: semester.classIds || [],
    })
    setSheetOpen(true)
  }

  const handleSubmit = async () => {
    if (!form.name.trim() || !form.startDate) {
      setActionError('请填写学期名称和开始日期')
      return
    }
    setSubmitting(true)
    setActionError('')
    try {
      const payload = {
        name: form.name.trim(),
        startDate: form.startDate,
        endDate: form.endDate || undefined,
        weekCount: form.weekCount || undefined,
        semesterType: form.semesterType,
        classIds:
          form.semesterType === 'EXTRA' && form.classIds.length > 0
            ? form.classIds
            : undefined,
      }
      if (editingId) {
        await updateSemester(editingId, payload)
      } else {
        await createSemester(payload)
      }
      setSheetOpen(false)
      resetForm()
      await loadAll()
    } catch (err: any) {
      setActionError(
        err.response?.data?.message || err.response?.data?.error || '保存失败'
      )
    } finally {
      setSubmitting(false)
    }
  }

  const handleSwitch = async () => {
    if (!switchTarget?.id) return
    // React 的 setSwitching 要等下一次渲染才生效，快速连点会重复发请求，用同步 ref 上锁
    if (switchGuard.current) return
    switchGuard.current = true
    setSwitching(true)
    setActionError('')
    try {
      await switchSemester(switchTarget.id)
      setSwitchTarget(null)
      await loadAll()
    } catch (err: any) {
      setActionError(
        err.response?.data?.message || err.response?.data?.error || '切换失败'
      )
    } finally {
      switchGuard.current = false
      setSwitching(false)
    }
  }

  const handleDelete = async () => {
    if (!deleteTarget?.id) return
    if (deleteGuard.current) return
    deleteGuard.current = true
    setDeleting(true)
    setActionError('')
    try {
      await deleteSemester(deleteTarget.id)
      setDeleteTarget(null)
      await loadAll()
    } catch (err: any) {
      setActionError(
        err.response?.data?.message || err.response?.data?.error || '删除失败'
      )
    } finally {
      deleteGuard.current = false
      setDeleting(false)
    }
  }

  if (loading) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-10 w-36" />
        <Skeleton className="h-[180px] rounded-3xl" />
        <Skeleton className="h-[320px] rounded-3xl" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[400px] gap-3">
        <div className="text-danger">{error}</div>
        <Button variant="outline" size="sm" onClick={loadAll} className="gap-2">
          <RefreshCw className="w-4 h-4" />
          重新加载
        </Button>
      </div>
    )
  }

  const statusMeta = current ? STATUS_META[current.status] : null

  return (
    <div className="space-y-6">
      {/* 操作错误提示 */}
      {actionError && (
        <div className="flex items-start gap-2 p-3 rounded-xl bg-danger/10 text-danger text-sm">
          <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {/* Page header */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
        <div>
          {/* 副标题（不展示）：管理学期信息，包括正常学期和假期培训 */}
          <h1 className="text-display-4-medium text-ink-primary">学期管理</h1>
        </div>
        <Sheet
          open={sheetOpen}
          onOpenChange={(o) => {
            setSheetOpen(o)
            if (!o) {
              resetForm()
              setActionError('')
            }
          }}
        >
          <SheetTrigger asChild>
            <Button variant="default" className="gap-2" onClick={openCreateSheet}>
              <Plus className="w-4 h-4" />
              新建学期
            </Button>
          </SheetTrigger>
          <SheetContent side="right">
            <SheetHeader>
              <SheetTitle>{editingId ? '编辑学期' : '新建学期'}</SheetTitle>
            </SheetHeader>
            <div className="scrollbar-thin flex-1 overflow-y-auto space-y-5 px-6 py-5">
              <div className="space-y-2">
                <Label>学期名称</Label>
                <Input
                  placeholder="例如：2024-2025学年第一学期"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <Label>开始日期</Label>
                  <Input
                    type="date"
                    value={form.startDate}
                    onChange={(e) =>
                      setForm({ ...form, startDate: e.target.value })
                    }
                  />
                </div>
                <div className="space-y-2">
                  <Label>结束日期</Label>
                  <Input
                    type="date"
                    value={form.endDate}
                    onChange={(e) =>
                      setForm({ ...form, endDate: e.target.value })
                    }
                  />
                </div>
              </div>
              <div className="space-y-2">
                <Label>总周数</Label>
                <div className="h-10 flex items-center px-3 rounded-md border border-input bg-neutral-50 text-sm text-neutral-700">
                  {form.startDate && form.endDate
                    ? `${Math.max(
                        1,
                        Math.floor(
                          (new Date(form.endDate).getTime() -
                            new Date(form.startDate).getTime()) /
                            (1000 * 60 * 60 * 24 * 7)
                        ) + 1
                      )} 周`
                    : '请选择开始和结束日期'}
                </div>
                <p className="text-xs text-neutral-400">根据起止日期自动计算</p>
              </div>
              <div className="space-y-2">
                <Label>学期类型</Label>
                <Select
                  className="w-full h-10 rounded-md border border-input bg-background px-3 text-sm"
                  value={form.semesterType}
                  onChange={(e) =>
                    setForm({
                      ...form,
                      semesterType: e.target.value as 'NORMAL' | 'EXTRA',
                      classIds: e.target.value === 'NORMAL' ? [] : form.classIds,
                    })
                  }
                >
                  <option value="NORMAL">正常学期（所有班级通用）</option>
                  <option value="EXTRA">假期培训（指定班级）</option>
                </Select>
              </div>
              {form.semesterType === 'EXTRA' && (
                <div className="space-y-2">
                  <Label>关联班级</Label>
                  <div className="max-h-48 overflow-y-auto rounded-xl border border-input p-3 space-y-2">
                    {classes.length === 0 ? (
                      <div className="text-sm text-neutral-400">暂无班级</div>
                    ) : (
                      classes.map((cls) => (
                        <label
                          key={cls.id}
                          className="flex items-center gap-2 text-sm cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            className="rounded border-neutral-300"
                            checked={form.classIds.includes(cls.id)}
                            onChange={(e) => {
                              const ids = new Set(form.classIds)
                              if (e.target.checked) {
                                ids.add(cls.id)
                              } else {
                                ids.delete(cls.id)
                              }
                              setForm({ ...form, classIds: Array.from(ids) })
                            }}
                          />
                          <span>{cls.className}</span>
                        </label>
                      ))
                    )}
                  </div>
                </div>
              )}
            </div>
            <SheetFooter>
              <Button
                variant="outline"
                onClick={() => setSheetOpen(false)}
                disabled={submitting}
              >
                取消
              </Button>
              <Button
                variant="default"
                onClick={handleSubmit}
                disabled={submitting}
              >
                {submitting ? '保存中...' : editingId ? '保存' : '创建'}
              </Button>
            </SheetFooter>
          </SheetContent>
        </Sheet>
      </div>

      {/* 当前学期信息卡片：左上浅蓝 → 右下浅绿渐变 */}
      {current && statusMeta && (
        <Card className="overflow-hidden bg-gradient-to-br from-sky-100 via-white to-emerald-100 dark:from-sky-900/30 dark:via-surface-primary dark:to-emerald-900/30">
          <div className="relative h-28">
            <div className="absolute right-4 top-4">
              <span className="text-caption-1-medium text-ink-secondary">{statusMeta.label}</span>
            </div>
            <div className="absolute bottom-4 left-5">
              <div className="text-caption-1-regular text-ink-tertiary">当前学期</div>
              <div className="mt-0.5 text-title-2-semibold text-ink-primary">
                {current.name}
              </div>
            </div>
          </div>
          <CardContent className="p-5">
            <div className="mb-3 flex items-center gap-2 text-body-2-regular text-ink-tertiary">
              <Calendar className="w-4 h-4" />
              <span>开始日期：{current.startDate}</span>
            </div>
            {current.notice && (
              <div className="flex items-start gap-2 rounded-xl bg-neutral-50 p-3 text-body-2-regular text-ink-secondary">
                <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
                <span>{current.notice}</span>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {/* 学期列表表格 */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle>学期列表</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-neutral-50 text-caption-1-medium text-ink-tertiary">
                <tr>
                  <th className="text-left px-6 py-3 font-medium">
                    学期名称
                  </th>
                  <th className="text-left px-6 py-3 font-medium">
                    起止日期
                  </th>
                  <th className="text-left px-6 py-3 font-medium">
                    周数
                  </th>
                  <th className="text-left px-6 py-3 font-medium">
                    状态
                  </th>
                  <th className="text-right px-6 py-3 font-medium">
                    操作
                  </th>
                </tr>
              </thead>
              <tbody>
                {list.length === 0 ? (
                  <tr>
                    <td
                      colSpan={5}
                      className="px-6 py-12 text-center text-neutral-400"
                    >
                      暂无学期数据
                    </td>
                  </tr>
                ) : (
                  list.map((semester) => (
                    <tr
                      key={semester.id ?? semester.name}
                      className="border-t border-line-separator transition-colors last:border-0 hover:bg-neutral-50/60"
                    >
                      <td className="px-6 py-4">
                        <span className="font-medium text-ink-primary">{semester.name}</span>
                      </td>
                      <td className="px-6 py-4 text-sm text-neutral-700">
                        {semester.startDate} ~ {semester.endDate || '未设置'}
                      </td>
                      <td className="px-6 py-4 text-sm text-neutral-700">
                        {semester.weekCount ? `${semester.weekCount} 周` : '-'}
                      </td>
                      <td className="px-6 py-4">
                        {semester.isCurrent ? (
                          <Badge variant="success">进行中</Badge>
                        ) : (
                          <Badge variant="secondary">历史</Badge>
                        )}
                      </td>
                      <td className="px-6 py-4 text-right">
                        <div className="flex items-center justify-end gap-1">
                          {!semester.isCurrent && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => {
                                setActionError('')
                                setSwitchTarget(semester)
                              }}
                            >
                              设为当前
                            </Button>
                          )}
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => {
                              setActionError('')
                              openEditSheet(semester)
                            }}
                          >
                            编辑
                          </Button>
                          <DropdownMenu
                            open={openMenuId === semester.id}
                            onOpenChange={(open) => setOpenMenuId(open ? (semester.id ?? null) : null)}
                          >
                            <DropdownMenuTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                className="w-8 h-8"
                              >
                                <MoreHorizontal className="w-4 h-4" />
                              </Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end" className="w-36">
                              <DropdownMenuItem
                                onSelect={(e) => {
                                  e.preventDefault()
                                  setOpenMenuId(null)
                                  openEditSheet(semester)
                                }}
                              >
                                编辑
                              </DropdownMenuItem>
                              {!semester.isCurrent && (
                                <DropdownMenuItem
                                  onSelect={(e) => {
                                    e.preventDefault()
                                    setOpenMenuId(null)
                                    setSwitchTarget(semester)
                                  }}
                                >
                                  切换为当前
                                </DropdownMenuItem>
                              )}
                              <DropdownMenuSeparator />
                              <DropdownMenuItem
                                className="text-danger"
                                onSelect={(e) => {
                                  e.preventDefault()
                                  setOpenMenuId(null)
                                  if (semester.isCurrent) {
                                    // 当前学期不可删除，给出明确原因，而不是静默无响应
                                    setActionError('当前学期不能删除，请先将其他学期设为当前学期')
                                    return
                                  }
                                  setActionError('')
                                  setDeleteTarget(semester)
                                }}
                              >
                                删除
                              </DropdownMenuItem>
                            </DropdownMenuContent>
                          </DropdownMenu>
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      {/* 切换当前学期确认弹窗 */}
      <Dialog
        open={!!switchTarget}
        onOpenChange={(o) => !o && setSwitchTarget(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>切换当前学期</DialogTitle>
            <DialogDescription className="space-y-2">
              <p>确定要将「{switchTarget?.name}」设为当前学期吗？</p>
              <p className="text-neutral-500">
                切换后全校师生看到的「当前学期」都会改变，课表、课程、聊天记录等数据按学期隔离，原当前学期将变为历史学期。
              </p>
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setSwitchTarget(null)}
              disabled={switching}
            >
              取消
            </Button>
            <Button
              variant="default"
              onClick={handleSwitch}
              disabled={switching}
            >
              {switching ? '切换中...' : '确认切换'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 删除学期确认弹窗 */}
      <Dialog
        open={!!deleteTarget}
        onOpenChange={(o) => !o && setDeleteTarget(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>删除学期</DialogTitle>
            <DialogDescription>
              确定要删除学期「{deleteTarget?.name}」吗？该操作不可恢复，相关数据将被永久移除。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setDeleteTarget(null)}
              disabled={deleting}
            >
              取消
            </Button>
            <Button
              variant="destructive"
              onClick={handleDelete}
              disabled={deleting}
            >
              {deleting ? '删除中...' : '确认删除'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

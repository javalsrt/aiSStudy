import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Select } from '@/components/ui/select'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'


import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Users2, Search, Save, Crown, GraduationCap, UserCheck } from 'lucide-react'
import {
  getClassHeadList,
  getHeadTeacherLogs,
  getTeacherDutyList,
  setClassHeadTeacher,
  updateTeacherDuty,
  type ClassHeadItem,
  type HeadTeacherLog,
  type TeacherDutyItem,
} from '@/api/teacher-duty'

const DUTIES = ['教师', '班主任', '教导主任', '副校长']

/**
 * 教师职务与班主任设置：
 * - 职称（讲师/副教授）来自 teacher.title，此处维护「职务」与「是否校领导」
 * - 每个班级指定一名班主任（任意教师均可，含领导）
 */
export function TeacherDutyPage() {
  const [teachers, setTeachers] = useState<TeacherDutyItem[]>([])
  const [classes, setClasses] = useState<ClassHeadItem[]>([])
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState<number | null>(null)
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)
  /** 班级 → 选中的教师ID（未保存的临时选择） */
  const [picked, setPicked] = useState<Record<number, number | ''>>({})
  /** 当前展开「更换班主任」的班级（同时只开一个，避免整页都是下拉框） */
  const [editingClass, setEditingClass] = useState<number | null>(null)
  const [classKeyword, setClassKeyword] = useState('')
  const [gradeFilter, setGradeFilter] = useState('')
  const [logs, setLogs] = useState<HeadTeacherLog[]>([])
  /** 待确认取消班主任的班级：用页面内 Dialog 而非 window.confirm（后者在无头浏览器会被自动 dismiss） */
  const [clearTarget, setClearTarget] = useState<ClassHeadItem | null>(null)
  const [clearing, setClearing] = useState(false)
  // clearTarget 的 setState 要等下一次渲染才生效，快速连点会重复发请求，用同步 ref 上锁
  const clearGuard = useRef(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      // 记录接口失败不应拖垮整页（例如数据库尚未执行迁移 24）
      const [tsR, csR, lgR] = await Promise.allSettled([
        getTeacherDutyList(),
        getClassHeadList(),
        getHeadTeacherLogs(20),
      ])
      if (tsR.status === 'rejected' && csR.status === 'rejected') {
        throw tsR.reason
      }
      const ts = tsR.status === 'fulfilled' ? tsR.value : []
      const cs = csR.status === 'fulfilled' ? csR.value : []
      setTeachers(ts)
      setClasses(cs)
      setLogs(lgR.status === 'fulfilled' ? lgR.value || [] : [])
      setPicked(Object.fromEntries(cs.map((c) => [c.id, c.headTeacherId ?? ''])))
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '加载失败' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const filtered = useMemo(() => {
    const k = keyword.trim()
    if (!k) return teachers
    return teachers.filter((t) => (t.name || '').includes(k) || (t.teacherNo || '').includes(k))
  }, [teachers, keyword])

  const grades = useMemo(
    () => Array.from(new Set(classes.map((c) => c.grade || '').filter(Boolean))).sort(),
    [classes]
  )

  const filteredClasses = useMemo(() => {
    const k = classKeyword.trim()
    return classes.filter((c) => {
      if (gradeFilter && (c.grade || '') !== gradeFilter) return false
      if (!k) return true
      return (
        (c.className || '').includes(k) ||
        (c.major || '').includes(k) ||
        (c.headTeacherName || '').includes(k)
      )
    })
  }, [classes, classKeyword, gradeFilter])

  const leaders = filtered.filter((t) => t.isLeader === 1)
  const headTeachers = filtered.filter((t) => t.isLeader !== 1 && t.headClassCount > 0)
  const others = filtered.filter((t) => t.isLeader !== 1 && t.headClassCount === 0)

  const changeDuty = async (t: TeacherDutyItem, duty: string) => {
    setSaving(t.id)
    try {
      await updateTeacherDuty(t.id, { duty })
      setMessage({ type: 'ok', text: `已更新「${t.name}」职务为 ${duty}` })
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '保存失败' })
    } finally {
      setSaving(null)
    }
  }

  const toggleLeader = async (t: TeacherDutyItem, isLeader: boolean) => {
    setSaving(t.id)
    try {
      await updateTeacherDuty(t.id, { isLeader: isLeader ? 1 : 0, duty: isLeader ? '教导主任' : '教师' })
      setMessage({ type: 'ok', text: isLeader ? `已将「${t.name}」设为校级领导` : `已取消「${t.name}」领导身份` })
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '操作失败' })
    } finally {
      setSaving(null)
    }
  }

  const assign = async (c: ClassHeadItem) => {
    const tid = picked[c.id]
    if (!tid) {
      setMessage({ type: 'err', text: '请先选择一名教师' })
      return
    }
    setSaving(c.id)
    try {
      const res = await setClassHeadTeacher(c.id, Number(tid))
      setMessage({ type: 'ok', text: res.msg })
      setEditingClass(null)
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '设置失败' })
    } finally {
      setSaving(null)
    }
  }

  /** 取消班主任：先打开页面内确认弹窗 */
  const clearHead = (c: ClassHeadItem) => {
    setMessage(null)
    setClearTarget(c)
  }

  /** 确认取消班主任（真正发请求） */
  const confirmClear = async () => {
    if (!clearTarget) return
    if (clearGuard.current) return
    clearGuard.current = true
    setClearing(true)
    try {
      const res = await setClassHeadTeacher(clearTarget.id, null)
      setMessage({ type: 'ok', text: res.msg })
      setClearTarget(null)
      setEditingClass(null)
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '取消失败' })
      setClearTarget(null)
    } finally {
      clearGuard.current = false
      setClearing(false)
    }
  }

  const teacherLabel = (t: TeacherDutyItem) =>
    `${t.name}${t.isLeader === 1 ? '（领导）' : t.headClassCount > 0 ? '（班主任）' : ''}`

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold text-neutral-900">
            <Users2 className="h-6 w-6 text-primary-500" />
            教师职务与班主任
          </h1>
          <p className="mt-1 text-sm text-neutral-500">
            设置教师职务与校级领导；为每个班级指定班主任。请假 3 天内由班主任审批，超过 3 天由领导终审。
          </p>
          <p className="mt-1 text-xs text-primary-600">
            更换班主任：在下方「班级班主任设置」中搜索班级 → 点「更换」→ 选择教师（含领导）→ 保存；点「取消班主任」可清空。
          </p>
        </div>
        <div className="relative">
          <Search className="absolute left-2 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-400" />
          <input
            className="h-9 w-64 rounded-md border border-neutral-200 pl-8 pr-2 text-sm focus:outline-hidden focus:ring-2 focus:ring-emerald-300"
            placeholder="搜索教师姓名 / 工号"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
        </div>
      </div>

      {message && (
        <div
          className={
            message.type === 'ok'
              ? 'rounded-lg bg-emerald-50 px-3 py-2 text-xs text-emerald-700'
              : 'rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600'
          }
        >
          {message.text}
        </div>
      )}

      {loading && <div className="py-10 text-center text-sm text-neutral-400">加载中…</div>}

      {/* 班级班主任设置 */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="flex items-center gap-2 text-base">
            <Users2 className="h-4 w-4 text-neutral-400" />
            班级班主任设置
            <span className="text-xs font-normal text-neutral-400">
              {filteredClasses.length} / {classes.length} 个班
            </span>
          </CardTitle>
          <span className="text-xs text-neutral-400">点击「更换」展开选择，同一时间只展开一个</span>
        </CardHeader>
        <CardContent className="space-y-3">
          {/* 筛选：年级 + 关键字（班级/专业/班主任） */}
          <div className="flex flex-wrap items-center gap-2">
            <Select
              className="h-8 rounded-md border border-neutral-200 px-2 text-xs"
              value={gradeFilter}
              onChange={(e) => setGradeFilter(e.target.value)}
            >
              <option value="">全部年级</option>
              {grades.map((g) => (
                <option key={g} value={g}>
                  {g}
                </option>
              ))}
            </Select>
            <div className="relative">
              <Search className="absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-neutral-400" />
              <input
                className="h-8 w-56 rounded-md border border-neutral-200 pl-7 pr-2 text-xs focus:outline-hidden focus:ring-2 focus:ring-emerald-300"
                placeholder="搜索班级 / 专业 / 班主任"
                value={classKeyword}
                onChange={(e) => setClassKeyword(e.target.value)}
              />
            </div>
            {(gradeFilter || classKeyword) && (
              <Button
                variant="ghost"
                size="sm"
                className="h-8 px-2 text-xs text-neutral-500"
                onClick={() => {
                  setGradeFilter('')
                  setClassKeyword('')
                }}
              >
                清除
              </Button>
            )}
          </div>

          <div className="grid grid-cols-1 gap-2 md:grid-cols-2 xl:grid-cols-3">
            {filteredClasses.map((c) => (
              <div key={c.id} className="rounded-lg border border-neutral-200 px-3 py-2">
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-[13px] font-medium text-neutral-900" title={c.className}>
                    {c.className}
                  </span>
                  <span className="shrink-0 text-[11px] text-neutral-400">{c.studentCount} 人</span>
                </div>
                <div className="mt-0.5 truncate text-[11px] text-neutral-400" title={`${c.grade || ''} ${c.major || ''}`}>
                  {c.grade || '-'} · {c.major || '-'}
                </div>

                <div className="mt-1.5 flex items-center justify-between gap-2">
                  <span className="min-w-0 truncate text-xs text-neutral-600">
                    班主任：
                    {c.headTeacherName ? (
                      <>
                        <span className="font-medium text-neutral-800">{c.headTeacherName}</span>
                        <span
                          className={`ml-1 rounded px-1.5 py-0.5 text-[10px] ${
                            c.headTeacherIsLeader === 1
                              ? 'bg-amber-100 text-amber-700'
                              : 'bg-neutral-100 text-ink-secondary'
                          }`}
                        >
                          {c.headTeacherDuty || '班主任'}
                        </span>
                      </>
                    ) : (
                      <span className="text-red-500">未设置</span>
                    )}
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 shrink-0 px-2 text-[11px] text-primary-600"
                    onClick={() => setEditingClass(editingClass === c.id ? null : c.id)}
                  >
                    {editingClass === c.id ? '收起' : '更换'}
                  </Button>
                </div>

                {editingClass === c.id && (
                  <div className="mt-2 flex items-center gap-1.5 border-t border-dashed border-neutral-200 pt-2">
                    <Select
                      className="h-7 min-w-0 flex-1 rounded-md border border-neutral-200 px-1.5 text-[11px]"
                      value={picked[c.id] ?? ''}
                      onChange={(e) =>
                        setPicked({ ...picked, [c.id]: e.target.value ? Number(e.target.value) : '' })
                      }
                    >
                      <option value="">选择教师…</option>
                      {teachers.map((t) => (
                        <option key={t.id} value={t.id}>
                          {teacherLabel(t)}
                        </option>
                      ))}
                    </Select>
                    <Button
                      size="sm"
                      className="h-7 px-2 text-[11px]"
                      disabled={saving === c.id || !picked[c.id]}
                      onClick={() => assign(c)}
                    >
                      <Save className="mr-1 h-3 w-3" />
                      保存
                    </Button>
                    {c.headTeacherName && (
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-7 shrink-0 px-2 text-[11px] text-danger"
                        disabled={saving === c.id}
                        onClick={() => clearHead(c)}
                      >
                        取消班主任
                      </Button>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>

          {filteredClasses.length === 0 && (
            <div className="py-8 text-center text-xs text-neutral-400">没有匹配的班级</div>
          )}
        </CardContent>
      </Card>

      {/* 校级领导 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <Crown className="h-4 w-4 text-amber-500" />
            校级领导（{leaders.length}）
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {leaders.length === 0 && <div className="text-xs text-neutral-400">暂无领导，可在下方教师中勾选「设为领导」</div>}
          {leaders.map((t) => (
            <div key={t.id} className="flex flex-wrap items-center gap-3 rounded-lg border border-amber-100 bg-amber-50/40 px-3 py-2">
              <span className="text-sm font-medium text-neutral-900">{t.name}</span>
              <span className="rounded bg-amber-100 px-2 py-0.5 text-[11px] text-amber-700">{t.duty}</span>
              {t.headClasses.length > 0 && (
                <span className="text-[11px] text-neutral-500">兼班主任：{t.headClasses.join('、')}</span>
              )}
              <div className="ml-auto flex items-center gap-2">
                <Select
                  className="h-8 rounded-md border border-neutral-200 px-2 text-xs"
                  value={t.duty}
                  disabled={saving === t.id}
                  onChange={(e) => changeDuty(t, e.target.value)}
                >
                  {DUTIES.map((d) => (
                    <option key={d} value={d}>
                      {d}
                    </option>
                  ))}
                </Select>
                <Button
                  variant="outline"
                  size="sm"
                  className="h-8 px-3 text-xs"
                  disabled={saving === t.id}
                  onClick={() => toggleLeader(t, false)}
                >
                  取消领导身份
                </Button>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* 班主任列表 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <UserCheck className="h-4 w-4 text-primary-500" />
            班主任（{headTeachers.length}）
          </CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-1 gap-2 md:grid-cols-2 xl:grid-cols-3">
          {headTeachers.map((t) => (
            <div key={t.id} className="rounded-lg border border-neutral-200 px-3 py-2">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-medium text-neutral-900">{t.name}</span>
                <span className="text-[11px] text-neutral-400">{t.title || '未填职称'}</span>
              </div>
              <div className="mt-1 text-[11px] text-neutral-500">带班：{t.headClasses.join('、') || '-'}</div>
              <div className="mt-2 flex items-center justify-end gap-2">
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-[11px]"
                  onClick={() => toggleLeader(t, true)}
                  disabled={saving === t.id}
                >
                  设为领导
                </Button>
              </div>
            </div>
          ))}
          {headTeachers.length === 0 && <div className="text-xs text-neutral-400">暂无班主任</div>}
        </CardContent>
      </Card>

      {/* 全部教师 */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <GraduationCap className="h-4 w-4 text-neutral-400" />
            其他教师（{others.length}）
          </CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-1 gap-2 md:grid-cols-2 xl:grid-cols-3">
          {others.map((t) => (
            <div key={t.id} className="flex items-center justify-between gap-2 rounded-lg border border-neutral-100 px-3 py-2">
              <div className="min-w-0">
                <div className="truncate text-sm text-neutral-800">{t.name}</div>
                <div className="text-[11px] text-neutral-400">{t.duty} · {t.title || '未填职称'}</div>
              </div>
              <div className="flex items-center gap-1">
                <Select
                  className="h-7 rounded-md border border-neutral-200 px-1 text-[11px]"
                  value={t.duty}
                  disabled={saving === t.id}
                  onChange={(e) => changeDuty(t, e.target.value)}
                >
                  {DUTIES.map((d) => (
                    <option key={d} value={d}>
                      {d}
                    </option>
                  ))}
                </Select>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-[11px]"
                  onClick={() => toggleLeader(t, true)}
                  disabled={saving === t.id}
                >
                  设为领导
                </Button>
              </div>
            </div>
          ))}
          {others.length === 0 && <div className="text-xs text-neutral-400">无</div>}
        </CardContent>
      </Card>



      {/* 班主任调整记录 */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">班主任调整记录</CardTitle>
          <span className="text-xs text-neutral-400">最近 20 条</span>
        </CardHeader>
        <CardContent>
          {logs.length === 0 ? (
            <div className="py-6 text-center text-xs text-neutral-400">暂无记录</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-neutral-200 bg-neutral-50/60">
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400 whitespace-nowrap">时间</th>
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400 whitespace-nowrap">操作人</th>
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400">班级</th>
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400 whitespace-nowrap">操作</th>
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400">变更</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map((l) => (
                    <tr key={l.id} className="border-b border-neutral-100 last:border-0">
                      <td className="px-3 py-2 text-xs text-neutral-500 tabular-nums whitespace-nowrap">{l.createdAt}</td>
                      <td className="px-3 py-2 text-xs text-neutral-700 whitespace-nowrap">{l.operatorName || '-'}</td>
                      <td className="px-3 py-2 text-xs text-neutral-700 whitespace-nowrap">{l.className || '-'}</td>
                      <td className="px-3 py-2 text-xs whitespace-nowrap">
                        <span
                          className={`rounded px-1.5 py-0.5 ${
                            l.action === 'CLEAR' ? 'bg-red-50 text-red-600' : 'bg-emerald-50 text-emerald-700'
                          }`}
                        >
                          {l.action === 'CLEAR' ? '取消' : '设置'}
                        </span>
                      </td>
                      <td className="px-3 py-2 text-xs text-neutral-600">
                        {l.action === 'CLEAR'
                          ? `${l.oldTeacher || '-'} → 空`
                          : `${l.oldTeacher || '空'} → ${l.newTeacher || '-'}`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* 审批链规则（原蓝色横幅）：3 天内班主任审批，超过 3 天班主任初审 + 校级终审 */}
      <p className="text-xs text-neutral-400">
        请假 3 天内由班主任审批，超过 3 天需校级领导终审
      </p>

      {/* 取消班主任确认弹窗 */}
      <Dialog
        open={!!clearTarget}
        onOpenChange={(o) => {
          if (!o && !clearing) setClearTarget(null)
        }}
      >
        <DialogContent className="max-w-sm" showClose={false}>
          <DialogHeader>
            <DialogTitle>取消班主任</DialogTitle>
            <DialogDescription>
              确定取消「{clearTarget?.className}」的班主任
              {clearTarget?.headTeacherName ? `（${clearTarget.headTeacherName}）` : ''}？
              取消后该班将没有班主任，相关请假审批需重新指派。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              size="sm"
              disabled={clearing}
              onClick={() => setClearTarget(null)}
            >
              再想想
            </Button>
            <Button
              variant="destructive"
              size="sm"
              disabled={clearing}
              onClick={confirmClear}
            >
              {clearing ? '取消中…' : '确认取消'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

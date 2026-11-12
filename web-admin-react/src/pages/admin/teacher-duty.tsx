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
  getTeacherRoleCatalog,
  setClassHeadTeacher,
  updateTeacherRoles,
  type ClassHeadItem,
  type HeadTeacherLog,
  type TeacherDutyItem,
  type TeacherRole,
  type TeacherRoleCatalog,
} from '@/api/teacher-duty'
import { TEACHER_ROLE_CATEGORIES, TEACHER_ROLES } from '@/config/teacher-roles'

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

  // 身份目录与身份编辑弹窗
  const [catalog, setCatalog] = useState<TeacherRoleCatalog>({
    categories: TEACHER_ROLE_CATEGORIES,
    roles: TEACHER_ROLES,
  })
  const [roleTarget, setRoleTarget] = useState<TeacherDutyItem | null>(null)
  const [rolePicked, setRolePicked] = useState<string[]>([])
  const [roleSaving, setRoleSaving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      // 记录接口失败不应拖垮整页（例如数据库尚未执行迁移 24）
      const [tsR, csR, lgR, catR] = await Promise.allSettled([
        getTeacherDutyList(),
        getClassHeadList(),
        getHeadTeacherLogs(20),
        getTeacherRoleCatalog(),
      ])
      if (tsR.status === 'rejected' && csR.status === 'rejected') {
        throw tsR.reason
      }
      const ts = tsR.status === 'fulfilled' ? tsR.value : []
      const cs = csR.status === 'fulfilled' ? csR.value : []
      setTeachers(ts)
      setClasses(cs)
      setLogs(lgR.status === 'fulfilled' ? lgR.value || [] : [])
      if (catR.status === 'fulfilled' && catR.value?.roles?.length) {
        setCatalog(catR.value)
      }
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

  const rolesOf = (t: TeacherDutyItem) => t.roles || []
  const isManagement = (t: TeacherDutyItem) =>
    rolesOf(t).some(
      (r) =>
        r.category === 'leader' ||
        r.category === 'middle' ||
        r.code === 'position_grade_leader' ||
        r.code === 'position_research_leader',
    )

  const leaders = filtered.filter(isManagement)
  const headTeachers = filtered.filter(
    (t) =>
      !isManagement(t) &&
      (t.headClassCount > 0 || rolesOf(t).some((r) => r.code === 'position_head_teacher')),
  )
  const others = filtered.filter((t) => !isManagement(t) && !headTeachers.includes(t))

  const openRoleEditor = (t: TeacherDutyItem) => {
    setRoleTarget(t)
    setRolePicked(rolesOf(t).map((r) => r.code))
  }

  const toggleRole = (role: TeacherRole) => {
    setRolePicked((prev) => {
      if (role.category === 'title') {
        const withoutTitle = prev.filter(
          (code) => catalog.roles.find((r) => r.code === code)?.category !== 'title',
        )
        return prev.includes(role.code) ? withoutTitle : [...withoutTitle, role.code]
      }
      return prev.includes(role.code) ? prev.filter((c) => c !== role.code) : [...prev, role.code]
    })
  }

  const saveRoles = async () => {
    if (!roleTarget) return
    setRoleSaving(true)
    try {
      await updateTeacherRoles(roleTarget.id, rolePicked)
      setMessage({ type: 'ok', text: `已更新「${roleTarget.name}」的身份` })
      setRoleTarget(null)
      await load()
    } catch (e: any) {
      setMessage({
        type: 'err',
        text: e?.response?.data?.error || e?.message || '保存失败',
      })
    } finally {
      setRoleSaving(false)
    }
  }

  const roleBadgeClass = (role: TeacherRole) => {
    switch (role.category) {
      case 'title':
        return 'bg-blue-50 text-blue-700'
      case 'position':
        return 'bg-emerald-50 text-emerald-700'
      case 'leader':
        return 'bg-amber-50 text-amber-700'
      case 'middle':
        return 'bg-purple-50 text-purple-700'
      default:
        return 'bg-neutral-100 text-neutral-600'
    }
  }

  const roleBadges = (t: TeacherDutyItem) => {
    const roles = rolesOf(t)
    if (roles.length === 0) {
      return <span className="text-[10px] text-neutral-400">未设置身份</span>
    }
    return roles.map((role) => (
      <span
        key={role.code}
        title={role.teaches ? '承担课堂教学' : '不排常规课'}
        className={`rounded px-1.5 py-0.5 text-[10px] ${roleBadgeClass(role)}`}
      >
        {role.name}
        {!role.teaches && <span className="opacity-60">·不排课</span>}
      </span>
    ))
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
    `${t.name}${
      t.roleNames && t.roleNames.length > 0 ? `（${t.roleNames.slice(0, 2).join('、')}）` : ''
    }`

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold text-neutral-900">
            <Users2 className="h-6 w-6 text-primary-500" />
            教师身份与班主任
          </h1>
          <p className="mt-1 text-sm text-neutral-500">
            支持一人多身份：教师职称、校内岗位角色、校级/中层领导、教辅岗位可同时设置；为每个班级指定班主任。
          </p>
          <p className="mt-1 text-xs text-neutral-400">
            有任一教学岗身份即视为可上课；纪委书记、工会主席、总务/办公室主任、校医、图书管理员、实验员默认不排常规课。
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
            管理岗教师（校级 / 中层 / 组长）（{leaders.length}）
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {leaders.length === 0 && <div className="text-xs text-neutral-400">暂无管理岗教师，可在下方教师中设置身份</div>}
          {leaders.map((t) => (
            <div key={t.id} className="flex flex-wrap items-center gap-2 rounded-lg border border-amber-100 bg-amber-50/40 px-3 py-2">
              <span className="text-sm font-medium text-neutral-900">{t.name}</span>
              <span className="flex flex-wrap items-center gap-1">{roleBadges(t)}</span>
              {t.headClasses.length > 0 && (
                <span className="text-[11px] text-neutral-500">兼班主任：{t.headClasses.join('、')}</span>
              )}
              <Button
                variant="outline"
                size="sm"
                className="ml-auto h-8 px-3 text-xs"
                onClick={() => openRoleEditor(t)}
              >
                身份设置
              </Button>
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
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-[11px]"
                  onClick={() => openRoleEditor(t)}
                >
                  身份设置
                </Button>
              </div>
              <div className="mt-1 flex flex-wrap items-center gap-1">{roleBadges(t)}</div>
              <div className="mt-1 text-[11px] text-neutral-500">带班：{t.headClasses.join('、') || '-'}</div>
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
            <div key={t.id} className="rounded-lg border border-neutral-100 px-3 py-2">
              <div className="flex items-center justify-between gap-2">
                <div className="min-w-0">
                  <div className="truncate text-sm text-neutral-800">{t.name}</div>
                  <div className="text-[11px] text-neutral-400">{t.dept || '未分配院系'}</div>
                </div>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 shrink-0 px-2 text-[11px]"
                  onClick={() => openRoleEditor(t)}
                >
                  身份设置
                </Button>
              </div>
              <div className="mt-1 flex flex-wrap items-center gap-1">{roleBadges(t)}</div>
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

      {/* 教师身份设置：职称单选 + 其他身份多选 */}
      <Dialog
        open={!!roleTarget}
        onOpenChange={(o) => {
          if (!o && !roleSaving) setRoleTarget(null)
        }}
      >
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>设置「{roleTarget?.name}」的身份</DialogTitle>
            <DialogDescription>
              可同时选择职称、岗位角色、领导职务和教辅岗位；有任一教学岗即视为可上课。
            </DialogDescription>
          </DialogHeader>

          <div className="max-h-[60vh] space-y-4 overflow-y-auto py-1">
            {catalog.categories.map((cat) => (
              <div key={cat.key}>
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium text-neutral-700">{cat.label}</span>
                  <span className="text-[10px] text-neutral-400">
                    {cat.multi ? '可多选' : '单选'}
                  </span>
                </div>
                <div className="mt-2 flex flex-wrap gap-2">
                  {catalog.roles
                    .filter((role) => role.category === cat.key)
                    .map((role) => {
                      const active = rolePicked.includes(role.code)
                      return (
                        <button
                          key={role.code}
                          type="button"
                          onClick={() => toggleRole(role)}
                          className={`rounded-full border px-3 py-1 text-xs transition-colors ${
                            active
                              ? 'border-primary bg-primary text-white'
                              : 'border-neutral-200 text-neutral-600 hover:border-primary/40 hover:bg-primary-50'
                          }`}
                        >
                          {role.name}
                          {!role.teaches && (
                            <span className={`ml-1 ${active ? 'text-white/70' : 'text-neutral-400'}`}>
                              ·不排课
                            </span>
                          )}
                        </button>
                      )
                    })}
                </div>
              </div>
            ))}

            <div className="rounded-lg bg-neutral-50 px-3 py-2 text-[11px] leading-5 text-neutral-500">
              提示：教师职称和校内岗位角色默认承担教学；纪委书记、工会主席、总务/办公室主任、校医、图书管理员、实验员默认不排常规课。
              领导职务属管理岗，教师职称属专业技术岗，两者可「双肩挑」。
            </div>
          </div>

          <DialogFooter>
            <Button
              variant="outline"
              disabled={roleSaving}
              onClick={() => setRoleTarget(null)}
            >
              取消
            </Button>
            <Button disabled={roleSaving} onClick={saveRoles}>
              <Save className="mr-1 h-4 w-4" />
              {roleSaving ? '保存中…' : '保存身份'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

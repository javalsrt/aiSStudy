import { useCallback, useEffect, useMemo, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Users2, Search, Save, Crown, GraduationCap, UserCheck } from 'lucide-react'
import {
  getClassHeadList,
  getTeacherDutyList,
  setClassHeadTeacher,
  updateTeacherDuty,
  type ClassHeadItem,
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

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [ts, cs] = await Promise.all([getTeacherDutyList(), getClassHeadList()])
      setTeachers(ts)
      setClasses(cs)
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
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '设置失败' })
    } finally {
      setSaving(null)
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
        </div>
        <div className="relative">
          <Search className="absolute left-2 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-400" />
          <input
            className="h-9 w-64 rounded-md border border-neutral-200 pl-8 pr-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
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
                <select
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
                </select>
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
                <select
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
                </select>
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

      {/* 班级班主任设置 */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="flex items-center gap-2 text-base">
            <Users2 className="h-4 w-4 text-neutral-400" />
            班级班主任设置（{classes.length} 个班）
          </CardTitle>
          <span className="text-xs text-neutral-400">任意教师均可设为班主任，领导兼任时会标注身份</span>
        </CardHeader>
        <CardContent className="space-y-2">
          {classes.map((c) => (
            <div key={c.id} className="flex flex-wrap items-center gap-3 rounded-lg border border-neutral-100 px-3 py-2">
              <div className="min-w-[160px]">
                <div className="text-sm font-medium text-neutral-900">{c.className}</div>
                <div className="text-[11px] text-neutral-400">
                  {c.grade || '-'} · {c.major || '-'} · {c.studentCount} 人
                </div>
              </div>

              <div className="text-xs text-neutral-500">
                当前班主任：
                {c.headTeacherName ? (
                  <span className="ml-1 inline-flex items-center gap-1">
                    <span className="font-medium text-neutral-800">{c.headTeacherName}</span>
                    <span className={`rounded px-1.5 py-0.5 text-[10px] ${c.headTeacherIsLeader === 1 ? 'bg-amber-100 text-amber-700' : 'bg-primary-50 text-primary-700'}`}>
                      {c.headTeacherDuty || '班主任'}
                    </span>
                  </span>
                ) : (
                  <span className="ml-1 text-red-500">未设置</span>
                )}
              </div>

              <div className="ml-auto flex items-center gap-2">
                <select
                  className="h-8 w-56 rounded-md border border-neutral-200 px-2 text-xs"
                  value={picked[c.id] ?? ''}
                  onChange={(e) => setPicked({ ...picked, [c.id]: e.target.value ? Number(e.target.value) : '' })}
                >
                  <option value="">选择教师…</option>
                  {teachers.map((t) => (
                    <option key={t.id} value={t.id}>
                      {teacherLabel(t)}
                    </option>
                  ))}
                </select>
                <Button
                  size="sm"
                  className="h-8 px-3 text-xs"
                  disabled={saving === c.id || !picked[c.id]}
                  onClick={() => assign(c)}
                >
                  <Save className="mr-1 h-3.5 w-3.5" />
                  设为班主任
                </Button>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      <div className="flex items-start gap-2 rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-700">
        <Label className="mt-0.5 shrink-0 text-xs text-blue-700">规则</Label>
        <span>
          请假 3 天以内由本班班主任审批；超过 3 天需班主任初审后由校级领导（如教导主任）终审。审批时系统会记录审批人身份（班主任 / 教导主任 / 管理员）。
        </span>
      </div>
    </div>
  )
}

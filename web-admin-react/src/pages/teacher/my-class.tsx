import { useCallback, useEffect, useMemo, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Users, GraduationCap, RefreshCw, UserCog, History } from 'lucide-react'
import { getTeacherClasses, type TeacherClassItem } from '@/api/teacher-class'
import {
  getClassPositions,
  getPositionOptions,
  getPositionLogs,
  setStudentPosition,
  type ClassStudentPosition,
  type PositionLog,
} from '@/api/student-position'

/**
 * 教师「我的班级」：查看任教班级的学生名单，并为学生设置班干部职务。
 * 权限由后端校验：班主任 / 任课教师只能操作自己任教班级的学生。
 */
export function MyClassPage() {
  const [classes, setClasses] = useState<TeacherClassItem[]>([])
  const [classId, setClassId] = useState<number | null>(null)
  const [students, setStudents] = useState<ClassStudentPosition[]>([])
  const [cadres, setCadres] = useState<string[]>([])
  const [logs, setLogs] = useState<PositionLog[]>([])
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState<number | null>(null)
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)

  /** 班级列表 + 职务字典 */
  const loadClasses = useCallback(async () => {
    setLoading(true)
    try {
      const [cs, opts] = await Promise.all([getTeacherClasses(), getPositionOptions()])
      setClasses(cs || [])
      setCadres(opts?.cadres || [])
      setClassId((prev) => prev ?? (cs && cs.length ? cs[0].id : null))
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '加载班级失败' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadClasses()
  }, [loadClasses])

  /** 班级学生 + 调整记录 */
  const loadClass = useCallback(async (id: number) => {
    setLoading(true)
    try {
      const [list, logRes] = await Promise.allSettled([getClassPositions(id), getPositionLogs(20)])
      if (list.status === 'fulfilled') setStudents(list.value || [])
      else setStudents([])
      setLogs(logRes.status === 'fulfilled' ? logRes.value || [] : [])
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '加载学生失败' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (classId) loadClass(classId)
  }, [classId, loadClass])

  const currentClass = classes.find((c) => c.id === classId)

  /** 已有职务的学生，按职务排序 */
  const cadreBoard = useMemo(() => {
    const items = students.filter((s) => (s.position || '').trim())
    return items.sort((a, b) => {
      const ia = cadres.indexOf(a.position || '')
      const ib = cadres.indexOf(b.position || '')
      return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
    })
  }, [students, cadres])

  const filtered = useMemo(() => {
    const k = keyword.trim()
    if (!k) return students
    return students.filter(
      (s) => (s.name || '').includes(k) || (s.studentNo || '').includes(k) || (s.position || '').includes(k)
    )
  }, [students, keyword])

  const changePosition = async (s: ClassStudentPosition, position: string) => {
    setSaving(s.id)
    try {
      const res = await setStudentPosition(s.id, position)
      setMessage({ type: 'ok', text: `${s.name}：${res.msg}` })
      if (classId) await loadClass(classId)
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '保存失败' })
    } finally {
      setSaving(null)
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold text-neutral-900">
            <Users className="h-6 w-6 text-primary-500" />
            我的班级
          </h1>
          <p className="mt-1 text-sm text-neutral-500">
            查看任教班级的学生名单，为学生设置班长、学习委员等班干部职务。职务变更会自动记录。
          </p>
        </div>
        <Button variant="outline" onClick={() => (classId ? loadClass(classId) : loadClasses())} disabled={loading}>
          <RefreshCw className="mr-1 h-4 w-4" />
          刷新
        </Button>
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

      {!loading && classes.length === 0 && (
        <Card>
          <CardContent className="py-12 text-center text-sm text-neutral-400">
            暂无任教班级。班级来自课程与班级的关联，请联系管理员在「课程管理」中配置。
          </CardContent>
        </Card>
      )}

      {classes.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {classes.map((c) => (
            <button
              key={c.id}
              onClick={() => setClassId(c.id)}
              className={
                'rounded-lg px-3 py-2 text-left text-xs transition ' +
                (c.id === classId
                  ? 'bg-primary-500 text-white shadow-sm'
                  : 'bg-white text-neutral-600 ring-1 ring-neutral-200 hover:bg-neutral-50')
              }
            >
              <div className="text-[13px] font-medium">{c.className}</div>
              <div className={c.id === classId ? 'text-white/80' : 'text-neutral-400'}>
                {c.grade || '-'} · {c.studentCount} 人
              </div>
            </button>
          ))}
        </div>
      )}

      {currentClass && (
        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0">
            <CardTitle className="flex items-center gap-2 text-base">
              <UserCog className="h-4 w-4 text-neutral-400" />
              班干部
            </CardTitle>
            <span className="text-xs text-neutral-400">
              {cadreBoard.length} / {students.length} 人已设置
            </span>
          </CardHeader>
          <CardContent>
            {cadreBoard.length === 0 ? (
              <div className="text-sm text-neutral-400">本班暂未设置班干部，可在下方名单中为学生指定职务。</div>
            ) : (
              <div className="flex flex-wrap gap-2">
                {cadreBoard.map((s) => (
                  <span
                    key={s.id}
                    className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-3 py-1 text-xs text-emerald-700"
                  >
                    <span className="font-medium">{s.position}</span>
                    <span className="text-emerald-600">{s.name}</span>
                  </span>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {currentClass && (
        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0">
            <CardTitle className="flex items-center gap-2 text-base">
              <GraduationCap className="h-4 w-4 text-neutral-400" />
              {currentClass.className} · 学生名单
            </CardTitle>
            <Input
              className="h-8 w-40 text-xs"
              placeholder="搜索姓名 / 学号"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </CardHeader>
          <CardContent className="p-0">
            <div className="max-h-[520px] overflow-auto">
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-neutral-50 text-xs text-neutral-500">
                  <tr>
                    <th className="px-4 py-2 text-left font-normal">姓名</th>
                    <th className="px-4 py-2 text-left font-normal">学号</th>
                    <th className="px-4 py-2 text-left font-normal">职务</th>
                    <th className="px-4 py-2 text-right font-normal">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((s) => (
                    <tr key={s.id} className="border-t border-neutral-100">
                      <td className="px-4 py-2 text-neutral-900">{s.name}</td>
                      <td className="px-4 py-2 text-neutral-500">{s.studentNo || '-'}</td>
                      <td className="px-4 py-2">
                        <select
                          className="h-8 min-w-[110px] rounded-md border border-neutral-200 bg-white px-2 text-xs focus:outline-none focus:ring-2 focus:ring-primary-500"
                          value={s.position || ''}
                          disabled={saving === s.id}
                          onChange={(e) => changePosition(s, e.target.value)}
                        >
                          <option value="">无</option>
                          {cadres.map((c) => (
                            <option key={c} value={c}>
                              {c}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td className="px-4 py-2 text-right">
                        {s.position ? (
                          <Button
                            variant="outline"
                            size="sm"
                            className="h-7 px-2 text-xs"
                            disabled={saving === s.id}
                            onClick={() => changePosition(s, '')}
                          >
                            清除
                          </Button>
                        ) : (
                          <span className="text-xs text-neutral-300">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                  {filtered.length === 0 && (
                    <tr>
                      <td colSpan={4} className="px-4 py-10 text-center text-sm text-neutral-400">
                        没有匹配的学生
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <History className="h-4 w-4 text-neutral-400" />
            调整记录
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {logs.length === 0 && <div className="text-sm text-neutral-400">暂无记录</div>}
          {logs.map((l) => (
            <div key={l.id} className="flex flex-wrap items-center gap-2 text-xs text-neutral-500">
              <span className="text-neutral-400">{l.createdAt}</span>
              <span className="text-neutral-700">{l.studentName}</span>
              <span>{l.className || ''}</span>
              <span className="rounded bg-neutral-100 px-1.5 py-0.5">{l.oldPosition || '无'}</span>
              <span>→</span>
              <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-emerald-700">{l.newPosition || '无'}</span>
              <span className="ml-auto">操作人：{l.operatorName || '-'}</span>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  )
}

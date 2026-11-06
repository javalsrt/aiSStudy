import { useCallback, useEffect, useMemo, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Search, UserCheck, Users } from 'lucide-react'
import { getClassHeadList, type ClassHeadItem } from '@/api/teacher-duty'
import {
  getClassPositions,
  getPositionOptions,
  setStudentPosition,
  type ClassStudentPosition,
} from '@/api/student-position'

/**
 * 班干部设置（人员管理内的标签页）。
 * 管理员可设任意职务；教师只能为本人班级学生设置班干部。
 */
export function ClassCadrePage() {
  const [classes, setClasses] = useState<ClassHeadItem[]>([])
  const [classId, setClassId] = useState<number | ''>('')
  const [students, setStudents] = useState<ClassStudentPosition[]>([])
  const [options, setOptions] = useState<string[]>([])
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState<number | null>(null)
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)

  /** 初始化：班级列表 + 职务选项 */
  useEffect(() => {
    (async () => {
      try {
        const [cs, opt] = await Promise.all([getClassHeadList(), getPositionOptions()])
        setClasses(cs)
        setOptions([...(opt.cadres || []), ...(opt.adminOnly || [])])
        if (cs.length > 0) {
          setClassId(cs[0].id)
          loadStudents(cs[0].id)
        }
      } catch (e: any) {
        setMessage({ type: 'err', text: e?.message || '加载失败' })
      }
    })()
  }, [])

  const loadStudents = useCallback(async (cid: number) => {
    setLoading(true)
    try {
      setStudents(await getClassPositions(cid))
    } catch (e: any) {
      setStudents([])
      setMessage({ type: 'err', text: e?.response?.data?.error || '加载学生失败' })
    } finally {
      setLoading(false)
    }
  }, [])

  const changePosition = async (s: ClassStudentPosition, position: string) => {
    setSaving(s.id)
    try {
      const r = await setStudentPosition(s.id, position)
      setStudents((prev) => prev.map((x) => (x.id === s.id ? { ...x, position: r.position } : x)))
      setMessage({ type: 'ok', text: `${s.name}：${r.msg}` })
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || '设置失败' })
    } finally {
      setSaving(null)
    }
  }

  const filtered = useMemo(() => {
    const k = keyword.trim()
    if (!k) return students
    return students.filter((s) => (s.name || '').includes(k) || (s.studentNo || '').includes(k))
  }, [students, keyword])

  const currentClass = classes.find((c) => c.id === classId)
  const assigned = students.filter((s) => s.position).length

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <select
          className="h-9 w-64 rounded-md border border-neutral-200 px-2 text-sm"
          value={classId}
          onChange={(e) => {
            const v = e.target.value ? Number(e.target.value) : ''
            setClassId(v)
            if (v) loadStudents(Number(v))
            else setStudents([])
          }}
        >
          <option value="">选择班级…</option>
          {classes.map((c) => (
            <option key={c.id} value={c.id}>
              {c.className}
              {c.grade ? `（${c.grade}）` : ''}
            </option>
          ))}
        </select>

        <div className="relative">
          <Search className="absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-neutral-400" />
          <input
            className="h-9 w-56 rounded-md border border-neutral-200 pl-7 pr-2 text-xs focus:outline-none focus:ring-2 focus:ring-primary-500"
            placeholder="搜索学生姓名 / 学号"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
        </div>

        {currentClass && (
          <span className="text-xs text-neutral-500">
            {currentClass.className} · 班主任 {currentClass.headTeacherName || '未设置'} · 已设职务{' '}
            <span className="font-medium text-neutral-800 tabular-nums">{assigned}</span> / {students.length} 人
          </span>
        )}
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

      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="flex items-center gap-2 text-base">
            <UserCheck className="h-4 w-4 text-neutral-400" />
            班级职务
          </CardTitle>
          <span className="text-xs text-neutral-400">修改后立即生效，无需整页保存</span>
        </CardHeader>
        <CardContent>
          {loading && <div className="py-10 text-center text-xs text-neutral-400">加载中…</div>}

          {!loading && !classId && (
            <div className="py-12 text-center text-xs text-neutral-400">
              <Users className="mx-auto mb-2 h-6 w-6 text-neutral-300" />
              请先选择班级
            </div>
          )}

          {!loading && classId && filtered.length === 0 && (
            <div className="py-10 text-center text-xs text-neutral-400">该班暂无学生</div>
          )}

          <div className="grid grid-cols-1 gap-2 md:grid-cols-2 xl:grid-cols-3">
            {filtered.map((s) => (
              <div
                key={s.id}
                className="flex items-center justify-between gap-3 rounded-lg border border-neutral-200 px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="truncate text-[13px] font-medium text-neutral-900" title={s.name}>
                    {s.name}
                  </div>
                  <div className="text-[11px] text-neutral-400">{s.studentNo || '-'}</div>
                </div>
                <select
                  className={`h-8 w-36 shrink-0 rounded-md border px-2 text-xs ${
                    s.position ? 'border-primary-200 bg-primary-50 text-primary-700' : 'border-neutral-200'
                  }`}
                  value={s.position || ''}
                  disabled={saving === s.id}
                  onChange={(e) => changePosition(s, e.target.value)}
                >
                  <option value="">普通学生</option>
                  {options.map((p) => (
                    <option key={p} value={p}>
                      {p}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      <div className="rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-700">
        管理员可设置全部职务；教师只能为本人担任班主任或授课班级的学生设置班干部职务（班长、学习委员等）。
      </div>
    </div>
  )
}

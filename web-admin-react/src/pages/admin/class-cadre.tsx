import { useCallback, useEffect, useMemo, useState } from 'react'
import { Select } from '@/components/ui/select'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Search, UserCheck, Users } from 'lucide-react'
import { getClassHeadList, type ClassHeadItem } from '@/api/teacher-duty'
import {
  getClassPositions,
  getPositionLogs,
  getPositionOptions,
  setStudentPosition,
  type ClassStudentPosition,
  type PositionLog,
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
  const [classSearch, setClassSearch] = useState('')
  const [logs, setLogs] = useState<PositionLog[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState<number | null>(null)
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)

  /** 初始化：班级列表 + 职务选项 */
  useEffect(() => {
    (async () => {
      try {
        const [csR, optR, lgR] = await Promise.allSettled([
          getClassHeadList(),
          getPositionOptions(),
          getPositionLogs(20),
        ])
        if (csR.status === 'rejected' && optR.status === 'rejected') {
          throw csR.reason
        }
        const cs = csR.status === 'fulfilled' ? csR.value : []
        const opt = optR.status === 'fulfilled' ? optR.value : { cadres: [], adminOnly: [] }
        setClasses(cs)
        setOptions([...(opt.cadres || []), ...(opt.adminOnly || [])])
        setLogs(lgR.status === 'fulfilled' ? lgR.value || [] : [])
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
      getPositionLogs(20).then(setLogs).catch(() => {})
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

  /** 班级搜索：过滤下拉选项（班级名/年级/专业/班主任） */
  const filteredClasses = useMemo(() => {
    const k = classSearch.trim()
    if (!k) return classes
    return classes.filter(
      (c) =>
        (c.className || '').includes(k) ||
        (c.grade || '').includes(k) ||
        (c.major || '').includes(k) ||
        (c.headTeacherName || '').includes(k)
    )
  }, [classes, classSearch])

  const currentClass = classes.find((c) => c.id === classId)
  const assigned = students.filter((s) => s.position).length

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative">
          <Search className="absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-neutral-400" />
          <input
            className="h-9 w-52 rounded-md border border-neutral-200 pl-7 pr-2 text-xs focus:outline-hidden focus:ring-2 focus:ring-emerald-300"
            placeholder="搜索班级 / 年级 / 专业"
            value={classSearch}
            onChange={(e) => setClassSearch(e.target.value)}
          />
        </div>
        <Select
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
          {filteredClasses.map((c) => (
            <option key={c.id} value={c.id}>
              {c.className}
              {c.grade ? `（${c.grade}）` : ''}
            </option>
          ))}
        </Select>
        {classSearch && (
          <span className="text-[11px] text-neutral-400">匹配 {filteredClasses.length} 个班</span>
        )}

        <div className="relative">
          <Search className="absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-neutral-400" />
          <input
            className="h-9 w-56 rounded-md border border-neutral-200 pl-7 pr-2 text-xs focus:outline-hidden focus:ring-2 focus:ring-emerald-300"
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
                className="flex items-center justify-between gap-3 rounded-xl border border-line-separator bg-surface-primary px-4 py-3 transition-colors hover:border-neutral-300"
              >
                <div className="min-w-0">
                  <div className="truncate text-[13px] font-medium text-ink-primary" title={s.name}>
                    {s.name}
                  </div>
                  <div className="text-[11px] text-ink-tertiary">{s.studentNo || '-'}</div>
                </div>
                <Select
                  className={`h-8 w-36 shrink-0 rounded-lg border px-2 text-xs transition-colors focus:border-emerald-300 focus:outline-hidden focus:ring-2 focus:ring-emerald-200 ${
                    s.position ? 'border-emerald-300 bg-surface-primary text-ink-primary' : 'border-line-separator bg-surface-primary text-ink-secondary'
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
                </Select>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* 调整记录 */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">班干部调整记录</CardTitle>
          <span className="text-xs text-neutral-400">最近 20 条</span>
        </CardHeader>
        <CardContent>
          {logs.length === 0 ? (
            <div className="py-6 text-center text-xs text-neutral-400">暂无记录</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-neutral-50 text-caption-1-medium text-ink-tertiary">
                  <tr>
                    <th className="h-10 px-3 text-left font-medium whitespace-nowrap">时间</th>
                    <th className="h-10 px-3 text-left font-medium whitespace-nowrap">操作人</th>
                    <th className="h-10 px-3 text-left font-medium">学生</th>
                    <th className="h-10 px-3 text-left font-medium whitespace-nowrap">班级</th>
                    <th className="h-10 px-3 text-left font-medium">变更</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map((l) => (
                    <tr
                      key={l.id}
                      className="border-t border-line-separator transition-colors last:border-0 hover:bg-neutral-50/60"
                    >
                      <td className="px-3 py-2.5 text-xs text-ink-tertiary tabular-nums whitespace-nowrap">{l.createdAt}</td>
                      <td className="px-3 py-2.5 text-xs text-ink-secondary whitespace-nowrap">{l.operatorName || '-'}</td>
                      <td className="px-3 py-2.5 text-xs text-ink-primary whitespace-nowrap">{l.studentName || '-'}</td>
                      <td className="px-3 py-2.5 text-xs text-ink-secondary whitespace-nowrap">{l.className || '-'}</td>
                      <td className="px-3 py-2.5 text-xs text-ink-secondary">
                        {l.oldPosition || '普通学生'} → <span className="font-medium text-ink-primary">{l.newPosition || '普通学生'}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* 权限规则（原蓝色横幅）：管理员可设置全部职务；教师只能为本人担任班主任或授课班级的学生设置班干部职务 */}
      <p className="text-xs text-neutral-400">职务范围按角色自动限制，无权限项不可选</p>
    </div>
  )
}

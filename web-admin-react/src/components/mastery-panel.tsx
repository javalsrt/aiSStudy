import { useEffect, useMemo, useState } from 'react'
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select } from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/components/ui/dialog'
import {
  getMasteryCourses,
  getMasteryStudents,
  getMasteryStudent,
  getMasteryHistory,
  getMasteryCompare,
  getMasteryDistribution,
  resetMastery,
  type MasteryStudent,
  type MasteryDetail,
  type MasteryKnowledge,
  type MasteryPoint,
  type MasteryDistribution,
  type MasteryCompareStudent,
} from '@/api/stats'

const BRAND = '#0A84FF'
const COMPARE_COLORS = ['#0A84FF', '#34C759', '#FF9500']

function MasteryBar({ value }: { value: number }) {
  const pct = Math.round(Math.max(0, Math.min(1, value)) * 100)
  return (
    <div className="flex items-center gap-3">
      <div className="h-2 w-24 overflow-hidden rounded-full bg-[#EDEFF3]">
        <div
          className="h-full rounded-full transition-all"
          style={{ width: `${pct}%`, backgroundColor: BRAND }}
        />
      </div>
      <span className="text-caption-1-regular tnum text-ink-secondary">{pct}%</span>
    </div>
  )
}

/**
 * 学情追踪：按知识点查看 BKT 掌握度、变化曲线和薄弱点。
 * 数据来自教师端 /api/teacher/mastery/*，每次交卷写一条快照。
 */
export function MasteryPanel() {
  const [courses, setCourses] = useState<any[]>([])
  const [courseId, setCourseId] = useState<number | null>(null)
  const [students, setStudents] = useState<MasteryStudent[]>([])
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [detail, setDetail] = useState<MasteryDetail | null>(null)
  const [activeUser, setActiveUser] = useState<number | null>(null)
  const [activeName, setActiveName] = useState('')
  const [activeKp, setActiveKp] = useState<MasteryKnowledge | null>(null)
  const [points, setPoints] = useState<MasteryPoint[]>([])
  const [distribution, setDistribution] = useState<MasteryDistribution[]>([])
  const [selected, setSelected] = useState<number[]>([])
  const [compareOpen, setCompareOpen] = useState(false)
  const [compareLoading, setCompareLoading] = useState(false)
  const [compareData, setCompareData] = useState<MasteryCompareStudent[]>([])

  useEffect(() => {
    getMasteryCourses()
      .then((list) => {
        const arr = (list || []) as any[]
        setCourses(arr)
        const math = arr.find((c) => String(c.name ?? c.courseName ?? '').includes('数学'))
        const first = math || arr[0]
        if (first) setCourseId(first.id ?? first.courseId)
      })
      .catch(() => setCourses([]))
  }, [])

  useEffect(() => {
    if (!courseId) return
    setSelected([])
    getMasteryDistribution(courseId)
      .then((r) => setDistribution(r.knowledge || []))
      .catch(() => setDistribution([]))
  }, [courseId])

  // 学生搜索走后端，避免演示账号等无作答记录的学生被 LIMIT 截断
  useEffect(() => {
    if (!courseId) return
    const timer = window.setTimeout(() => {
      setLoading(true)
      getMasteryStudents(courseId, keyword.trim() || undefined)
        .then((r) => setStudents(r.students || []))
        .catch(() => setStudents([]))
        .finally(() => setLoading(false))
    }, 250)
    return () => window.clearTimeout(timer)
  }, [courseId, keyword])

  const openStudent = async (s: MasteryStudent) => {
    if (!courseId) return
    setOpen(true)
    setActiveUser(s.userId)
    setActiveName(s.name)
    setDetail(null)
    setPoints([])
    setActiveKp(null)
    try {
      const d = await getMasteryStudent(s.userId, courseId)
      setDetail(d)
      const weakest = [...(d.knowledge || [])].sort((a, b) => a.mastery - b.mastery)[0]
      if (weakest) {
        setActiveKp(weakest)
        const h = await getMasteryHistory(s.userId, weakest.knowledgeId)
        setPoints(h.points || [])
      }
    } catch {
      setDetail(null)
    }
  }

  const selectKp = async (kp: MasteryKnowledge) => {
    if (!activeUser) return
    setActiveKp(kp)
    try {
      const h = await getMasteryHistory(activeUser, kp.knowledgeId)
      setPoints(h.points || [])
    } catch {
      setPoints([])
    }
  }

  const toggleSelect = (userId: number) => {
    setSelected((prev) =>
      prev.includes(userId) ? prev.filter((id) => id !== userId) : prev.length >= 3 ? prev : [...prev, userId],
    )
  }

  const openCompare = async () => {
    if (!courseId || selected.length < 2) return
    setCompareOpen(true)
    setCompareLoading(true)
    try {
      const r = await getMasteryCompare(selected, courseId)
      setCompareData(r.students || [])
    } catch {
      setCompareData([])
    } finally {
      setCompareLoading(false)
    }
  }

  const handleReset = async () => {
    if (!activeUser || !courseId) return
    if (!window.confirm(`确定重置「${activeName}」在本科目的 BKT 掌握度与快照？`)) return
    try {
      await resetMastery(activeUser, courseId)
      setOpen(false)
      setDetail(null)
      setPoints([])
      const r = await getMasteryStudents(courseId)
      setStudents(r.students || [])
      const d = await getMasteryDistribution(courseId)
      setDistribution(d.knowledge || [])
    } catch {
      window.alert('重置失败，请稍后重试')
    }
  }

  const nameOf = (userId: number) => students.find((s) => s.userId === userId)?.name || `#${userId}`

  // 预警：作答 ≥3 次且掌握度 <40% 人数最多的知识点
  const warning = useMemo(() => {
    const list = [...distribution].filter((d) => d.lowCount > 0).sort((a, b) => b.lowCount - a.lowCount)
    return list[0] || null
  }, [distribution])

  // 对比模式：知识点名称并集 + 差距最大的 3 个知识点
  const compareKnowledge = useMemo(() => {
    const names = new Set<string>()
    compareData.forEach((s) => (s.knowledge || []).forEach((k) => names.add(k.name)))
    return Array.from(names)
  }, [compareData])

  const compareDiffs = useMemo(() => {
    const rows: { name: string; max: number; min: number; leader: string; lagger: string; diff: number }[] = []
    compareKnowledge.forEach((name) => {
      let max = -1
      let min = 2
      let leader = ''
      let lagger = ''
      compareData.forEach((s) => {
        const k = (s.knowledge || []).find((x) => x.name === name)
        if (!k) return
        if (k.mastery > max) {
          max = k.mastery
          leader = nameOf(s.userId)
        }
        if (k.mastery < min) {
          min = k.mastery
          lagger = nameOf(s.userId)
        }
      })
      if (max >= 0 && min <= 1) rows.push({ name, max, min, leader, lagger, diff: max - min })
    })
    return rows.sort((a, b) => b.diff - a.diff).slice(0, 3)
  }, [compareKnowledge, compareData])

  const chartData = useMemo(
    () =>
      points.map((p, i) => ({
        name: `第${i + 1}次`,
        mastery: Math.round(p.after * 100),
      })),
    [points],
  )

  const visibleStudents = useMemo(
    () => students.filter((s) => !keyword.trim() || s.name.includes(keyword.trim())),
    [students, keyword],
  )

  const sortedKnowledge = useMemo(
    () => [...(detail?.knowledge || [])].sort((a, b) => a.mastery - b.mastery),
    [detail],
  )

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <div>
          <CardTitle>学情追踪</CardTitle>
          <p className="mt-1 text-caption-1-regular text-ink-tertiary">
            每个知识点维护一个掌握概率，答对上升、答错下降但不会归零
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Select
            value={courseId ? String(courseId) : ''}
            onChange={(e) => setCourseId(Number(e.target.value))}
            aria-label="选择课程"
            className="w-48"
          >
            {courses.length === 0 && <option value="">暂无可选课程</option>}
            {courses.map((c) => {
              const id = c.id ?? c.courseId
              return (
                <option key={id} value={String(id)}>
                  {c.name ?? c.courseName}
                </option>
              )
            })}
          </Select>
          <button
            type="button"
            onClick={openCompare}
            disabled={selected.length < 2}
            className="h-9 rounded-lg border border-[#E8EAEE] px-3 text-caption-1-regular text-ink-secondary transition-colors hover:border-[#0A84FF] hover:text-[#0A84FF] disabled:cursor-not-allowed disabled:opacity-40"
          >
            对比选中{selected.length > 0 ? ` (${selected.length})` : ''}
          </button>
        </div>
      </CardHeader>
      <CardContent>
        {warning && (
          <div className="mb-3 flex items-start gap-2 rounded-lg bg-[#FFF7E6] px-3 py-2 text-caption-1-regular text-[#B26A00]">
            <span className="font-medium">学情预警</span>
            <span>
              {warning.lowCount} 名学生在「{warning.name}」上掌握度低于 40%，建议安排一次集中讲解。
            </span>
          </div>
        )}
        <div className="mb-3">
          <input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="搜索学生姓名"
            className="h-9 w-56 rounded-lg border border-[#E8EAEE] px-3 text-caption-1-regular outline-none focus:border-[#0A84FF]"
          />
          <span className="ml-3 text-caption-1-regular text-ink-tertiary">
            勾选 2~3 名学生可横向对比
          </span>
        </div>
        <div className="mb-2 grid grid-cols-[1.5fr_1fr_0.6fr_0.6fr_0.8fr] gap-3 px-3 text-caption-1-regular text-ink-tertiary">
          <span>学生</span>
          <span>科目掌握度</span>
          <span>作答</span>
          <span>薄弱点</span>
          <span>状态</span>
        </div>
        <div className="divide-y divide-[#EEF0F3]">
          {loading && <div className="py-10 text-center text-ink-tertiary">加载中…</div>}
          {!loading && visibleStudents.length === 0 && (
            <div className="py-10 text-center text-ink-tertiary">暂无可统计的学生数据</div>
          )}
          {visibleStudents.map((s) => (
            <div
              key={s.userId}
              role="button"
              tabIndex={0}
              onClick={() => openStudent(s)}
              onKeyDown={(e) => e.key === 'Enter' && openStudent(s)}
              className="grid w-full cursor-pointer grid-cols-[1.5fr_1fr_0.6fr_0.6fr_0.8fr] items-center gap-3 px-3 py-3 text-left transition-colors hover:bg-[#F7F8FA]"
            >
              <span className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={selected.includes(s.userId)}
                  onClick={(e) => e.stopPropagation()}
                  onChange={() => toggleSelect(s.userId)}
                  aria-label={`选择 ${s.name}`}
                  className="h-4 w-4 accent-[#0A84FF]"
                />
                <span className="font-medium text-ink-primary">{s.name}</span>
              </span>
              <MasteryBar value={s.mastery} />
              <span className="text-caption-1-regular tnum text-ink-secondary">{s.attempts}</span>
              <span className="text-caption-1-regular tnum text-ink-secondary">{s.weakCount}</span>
              <span>
                {s.evidence === 'low' ? (
                  <Badge variant="outline">证据不足</Badge>
                ) : s.weakCount > 0 ? (
                  <Badge variant="outline">需关注</Badge>
                ) : (
                  <Badge variant="outline">正常</Badge>
                )}
              </span>
            </div>
          ))}
        </div>

        {distribution.length > 0 && (
          <div className="mt-5 rounded-xl border border-[#E8EAEE] p-4">
            <div className="mb-1 flex items-center justify-between">
              <span className="text-body-2-medium text-ink-primary">班级知识点分布</span>
              <span className="text-caption-1-regular text-ink-tertiary">仅统计作答 ≥3 次的学生</span>
            </div>
            <p className="mb-3 text-caption-1-regular text-ink-tertiary">
              浅色为最低~最高范围，深色为中间 50% 学生（P25~P75），竖线为中位数
            </p>
            {distribution.map((d) => (
              <div key={d.knowledgeId} className="mb-3 last:mb-0">
                <div className="mb-1 flex items-center justify-between text-caption-1-regular">
                  <span className="text-ink-secondary">{d.name}</span>
                  <span className="tnum text-ink-tertiary">
                    {d.count} 人 · 中位 {Math.round(d.p50 * 100)}%
                    {d.lowCount > 0 ? ` · ${d.lowCount} 人 <40%` : ''}
                  </span>
                </div>
                <div className="relative h-2 rounded-full bg-[#EDEFF3]">
                  <div
                    className="absolute top-0 h-full rounded-full bg-[#D6E6FF]"
                    style={{
                      left: `${Math.max(0, Math.min(100, d.min * 100))}%`,
                      width: `${Math.max(1, (d.max - d.min) * 100)}%`,
                    }}
                  />
                  <div
                    className="absolute top-0 h-full rounded-full bg-[#0A84FF]"
                    style={{
                      left: `${Math.max(0, Math.min(100, d.p25 * 100))}%`,
                      width: `${Math.max(2, (d.p75 - d.p25) * 100)}%`,
                    }}
                  />
                  <div
                    className="absolute -top-1 h-4 w-0.5 bg-[#0A84FF]"
                    style={{ left: `${Math.max(0, Math.min(100, d.p50 * 100))}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle className="flex items-center justify-between gap-3">
              <span>{activeName} · 知识点掌握度</span>
              <button
                type="button"
                onClick={handleReset}
                className="text-caption-1-regular font-normal text-[#FF3B30] hover:underline"
              >
                重置数据
              </button>
            </DialogTitle>
            <DialogDescription>
              {detail
                ? `综合掌握度 ${Math.round(detail.mastery * 100)}% · 累计作答 ${detail.attempts} 题 · 薄弱知识点 ${detail.weakCount} 个`
                : '加载中…'}
            </DialogDescription>
          </DialogHeader>

          <div className="mt-2 grid grid-cols-1 gap-3 sm:grid-cols-2">
            {sortedKnowledge.map((k) => (
              <button
                key={k.knowledgeId}
                type="button"
                onClick={() => selectKp(k)}
                className={`rounded-xl border p-3 text-left transition-colors ${
                  activeKp?.knowledgeId === k.knowledgeId
                    ? 'border-[#0A84FF] bg-[#F3F8FF]'
                    : 'border-[#E8EAEE] hover:bg-[#F7F8FA]'
                }`}
              >
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-body-2-medium text-ink-primary">{k.name}</span>
                  <span className="text-caption-1-regular tnum text-ink-tertiary">
                    {k.correct}/{k.attempts}
                  </span>
                </div>
                <MasteryBar value={k.mastery} />
              </button>
            ))}
          </div>

          <div className="mt-4 rounded-xl border border-[#E8EAEE] p-3">
            <div className="mb-2 text-caption-1-regular text-ink-tertiary">
              {activeKp ? `${activeKp.name} · 每次刷题后的掌握度` : '掌握度变化'}
            </div>
            {chartData.length === 0 ? (
              <div className="flex h-40 items-center justify-center text-ink-tertiary">
                还没有作答记录，刷一次题后这里会出现曲线
              </div>
            ) : (
              <div className="relative h-48">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartData} margin={{ top: 8, right: 12, bottom: 0, left: -18 }}>
                    <CartesianGrid stroke="#EEF0F3" vertical={false} />
                    <XAxis dataKey="name" tick={{ fontSize: 12, fill: '#8A8F98' }} axisLine={false} tickLine={false} />
                    <YAxis domain={[0, 100]} tick={{ fontSize: 12, fill: '#8A8F98' }} axisLine={false} tickLine={false} />
                    <Tooltip formatter={(v: any) => [`${v}%`, '掌握度']} />
                    <Line
                      type="monotone"
                      dataKey="mastery"
                      stroke={BRAND}
                      strokeWidth={2.5}
                      dot={{ r: 3, fill: BRAND }}
                      activeDot={{ r: 5 }}
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>

      {/* 对比模式：2~3 名学生的知识点掌握度并排 */}
      <Dialog open={compareOpen} onOpenChange={setCompareOpen}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>学生掌握度对比</DialogTitle>
            <DialogDescription>
              {selected.map((id) => nameOf(id)).join(' vs ')}
            </DialogDescription>
          </DialogHeader>

          {compareLoading ? (
            <div className="py-12 text-center text-ink-tertiary">加载中…</div>
          ) : compareData.length === 0 ? (
            <div className="py-12 text-center text-ink-tertiary">暂无可对比的数据</div>
          ) : (
            <>
              <div className="mt-2 space-y-3">
                {compareKnowledge.map((name) => (
                  <div key={name}>
                    <div className="mb-1 text-caption-1-regular text-ink-secondary">{name}</div>
                    {compareData.map((s, i) => {
                      const k = (s.knowledge || []).find((x) => x.name === name)
                      const pct = k ? Math.round(k.mastery * 100) : null
                      return (
                        <div key={s.userId} className="mb-1 flex items-center gap-2">
                          <span className="w-16 truncate text-caption-1-regular text-ink-secondary">
                            {nameOf(s.userId)}
                          </span>
                          <div className="h-2 flex-1 overflow-hidden rounded-full bg-[#EDEFF3]">
                            <div
                              className="h-full rounded-full"
                              style={{
                                width: `${pct ?? 0}%`,
                                backgroundColor: COMPARE_COLORS[i % COMPARE_COLORS.length],
                              }}
                            />
                          </div>
                          <span className="w-10 text-right text-caption-1-regular tnum text-ink-secondary">
                            {pct === null ? '—' : `${pct}%`}
                          </span>
                        </div>
                      )
                    })}
                  </div>
                ))}
              </div>

              {compareDiffs.length > 0 && (
                <div className="mt-4 rounded-xl bg-[#F7F8FA] p-3">
                  <div className="mb-2 text-caption-1-regular text-ink-tertiary">差距最大的知识点</div>
                  {compareDiffs.map((d) => (
                    <div key={d.name} className="mb-1 flex items-center justify-between text-caption-1-regular">
                      <span className="text-ink-secondary">{d.name}</span>
                      <span className="tnum text-ink-tertiary">
                        差距 {Math.round(d.diff * 100)}% · 领先 {d.leader} / 落后 {d.lagger}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </DialogContent>
      </Dialog>
    </Card>
  )
}

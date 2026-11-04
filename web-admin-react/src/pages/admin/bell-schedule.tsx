import { useCallback, useEffect, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Clock, RotateCcw, Save, Info, Plus, Trash2 } from 'lucide-react'
import { getClasses } from '@/api/staff'
import { getAdminBell, saveAdminBell, type BellPeriodRow } from '@/api/bell'
import type { ClassInfo } from '@/types'

/**
 * 作息时间设置：可对「默认作息」或指定班级单独配置每节起止时间。
 * 保存后自动同步该范围内已排课记录的时间，并刷新后端缓存，即刻生效。
 */
export function BellSchedulePage() {
  const [classes, setClasses] = useState<ClassInfo[]>([])
  const [classId, setClassId] = useState<string>('')
  const [semester, setSemester] = useState('')
  const [rows, setRows] = useState<BellPeriodRow[]>([])
  const [effective, setEffective] = useState<BellPeriodRow[]>([])
  const [ownCount, setOwnCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)

  useEffect(() => {
    getClasses()
      .then(setClasses)
      .catch(() => setClasses([]))
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setMessage(null)
    try {
      const data = await getAdminBell({
        classId: classId ? Number(classId) : undefined,
      })
      setSemester(data.semester || '')
      setOwnCount(data.periods.length)
      setEffective(data.effective || [])
      // 优先展示本范围已有配置；没有配置则用当前生效值兜底（便于在此基础上修改）
      const initial =
        data.periods.length > 0 ? data.periods : data.effective.length > 0 ? data.effective : data.preset
      setRows(normalize(initial))
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '加载作息失败' })
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [classId])

  useEffect(() => {
    load()
  }, [load])

  const updateRow = (index: number, key: 'startTime' | 'endTime', value: string) => {
    setRows((prev) => prev.map((r, i) => (i === index ? { ...r, [key]: value } : r)))
  }

  const fillPreset = async () => {
    try {
      const data = await getAdminBell({ classId: classId ? Number(classId) : undefined })
      setRows(normalize(data.preset))
      setMessage({ type: 'ok', text: '已填入标准作息，记得点击保存' })
    } catch {
      setMessage({ type: 'err', text: '获取标准作息失败' })
    }
  }

  const addRow = () => {
    setRows((prev) => {
      const used = new Set(prev.map((r) => r.node))
      let node = 1
      while (used.has(node) && node <= 12) node++
      if (node > 12) return prev
      const last = prev[prev.length - 1]
      return [
        ...prev,
        { node, startTime: last?.endTime || '08:00', endTime: last?.endTime || '08:40' },
      ].sort((a, b) => a.node - b.node)
    })
  }

  const removeRow = (index: number) => {
    setRows((prev) => prev.filter((_, i) => i !== index))
  }

  const submit = async () => {
    setMessage(null)
    if (rows.length === 0) {
      setMessage({ type: 'err', text: '至少保留一个节次' })
      return
    }
    for (const r of rows) {
      if (!r.startTime || !r.endTime) {
        setMessage({ type: 'err', text: `第 ${r.node} 节时间未填写完整` })
        return
      }
      if (r.startTime >= r.endTime) {
        setMessage({ type: 'err', text: `第 ${r.node} 节开始时间必须早于结束时间` })
        return
      }
    }
    setSaving(true)
    try {
      const res = await saveAdminBell({
        classId: classId ? Number(classId) : null,
        semester,
        periods: rows,
      })
      setMessage({
        type: 'ok',
        text:
          res.msg +
          `（${res.saved} 个节次）` +
          (res.syncedSchedules > 0
            ? `，已同步 ${res.syncedSchedules} 条课表的上下课时间`
            : '，当前没有需要同步的已排课记录'),
      })
      setOwnCount(rows.length)
      const data = await getAdminBell({ classId: classId ? Number(classId) : undefined })
      setEffective(data.effective || [])
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '保存失败，请稍后重试' })
    } finally {
      setSaving(false)
    }
  }

  const scopeLabel = classId
    ? classes.find((c) => String(c.id) === classId)?.className || '该班级'
    : '默认作息（未单独配置的班级）'

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-neutral-900 flex items-center gap-2">
          <Clock className="w-6 h-6 text-primary-500" />
          作息时间设置
        </h1>
        <p className="text-sm text-neutral-500 mt-1">
          按班级维护每节课的起止时间。保存后自动同步该范围内已排课的时间，并立即生效。
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">选择配置范围</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>班级</Label>
              <select
                className="w-full h-10 px-3 rounded-md border border-neutral-200 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                value={classId}
                onChange={(e) => setClassId(e.target.value)}
              >
                <option value="">默认作息（作用于未单独配置的班级）</option>
                {classes.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.className}
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-2">
              <Label>学期</Label>
              <div className="h-10 px-3 flex items-center rounded-md border border-neutral-200 bg-neutral-50 text-sm text-neutral-600">
                {semester || '当前学期'}
              </div>
            </div>
          </div>
          <div className="flex items-start gap-2 rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-700">
            <Info className="w-4 h-4 mt-0.5 flex-shrink-0" />
            <span>
              优先级：班级专属 &gt; 年级 &gt; 默认。当前范围「{scopeLabel}」
              {ownCount > 0 ? `已有 ${ownCount} 个节次的独立配置` : '暂无独立配置，下方展示的是继承来的作息'}
              。
            </span>
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
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">节次时间</CardTitle>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={fillPreset} disabled={loading}>
              <RotateCcw className="w-4 h-4 mr-1" />
              填入标准作息
            </Button>
            <Button variant="outline" size="sm" onClick={addRow} disabled={loading || rows.length >= 12}>
              <Plus className="w-4 h-4 mr-1" />
              增加节次
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-200 bg-neutral-50/60">
                  <th className="text-left px-4 py-2.5 text-xs font-semibold text-neutral-500 w-[120px]">
                    节次
                  </th>
                  <th className="text-left px-4 py-2.5 text-xs font-semibold text-neutral-500">
                    开始时间
                  </th>
                  <th className="text-left px-4 py-2.5 text-xs font-semibold text-neutral-500">
                    结束时间
                  </th>
                  <th className="text-right px-4 py-2.5 text-xs font-semibold text-neutral-500 w-[100px]">
                    操作
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row, i) => (
                  <tr key={row.node} className="border-b border-neutral-100 last:border-0">
                    <td className="px-4 py-2.5 font-medium text-neutral-800">第 {row.node} 节</td>
                    <td className="px-4 py-2.5">
                      <input
                        type="time"
                        value={row.startTime?.slice(0, 5) || ''}
                        onChange={(e) => updateRow(i, 'startTime', e.target.value)}
                        className="h-9 px-2 rounded-md border border-neutral-200 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                      />
                    </td>
                    <td className="px-4 py-2.5">
                      <input
                        type="time"
                        value={row.endTime?.slice(0, 5) || ''}
                        onChange={(e) => updateRow(i, 'endTime', e.target.value)}
                        className="h-9 px-2 rounded-md border border-neutral-200 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                      />
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-red-500 hover:text-red-600"
                        onClick={() => removeRow(i)}
                      >
                        <Trash2 className="w-4 h-4" />
                      </Button>
                    </td>
                  </tr>
                ))}
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={4} className="px-4 py-10 text-center text-neutral-400">
                      {loading ? '加载中…' : '暂无节次，请点击「填入标准作息」'}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="mt-5 flex items-center justify-between">
            <div className="text-xs text-neutral-400">
              提示：单双周差异作息可通过「班级 + 单/双周」分别配置（当前页面维护通用作息）。
            </div>
            <Button onClick={submit} disabled={saving || loading}>
              <Save className="w-4 h-4 mr-1" />
              {saving ? '保存中…' : '保存并同步课表'}
            </Button>
          </div>
        </CardContent>
      </Card>

      {effective.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">
              当前生效作息（{classId ? '本班' : '默认/继承'}）
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {effective.map((p) => (
                <span
                  key={p.node}
                  className="inline-flex items-center rounded-md bg-neutral-100 px-2.5 py-1 text-xs text-neutral-600"
                >
                  第{p.node}节 {p.startTime?.slice(0, 5)}–{p.endTime?.slice(0, 5)}
                </span>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}

/** 统一成 HH:mm、按节次排序、去重 */
function normalize(list: BellPeriodRow[]): BellPeriodRow[] {
  const map = new Map<number, BellPeriodRow>()
  for (const r of list || []) {
    if (!r || r.node == null) continue
    map.set(r.node, {
      node: r.node,
      startTime: (r.startTime || '').slice(0, 5),
      endTime: (r.endTime || '').slice(0, 5),
    })
  }
  return Array.from(map.values()).sort((a, b) => a.node - b.node)
}

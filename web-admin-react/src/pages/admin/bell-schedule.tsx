import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  Clock,
  RotateCcw,
  Save,
  Info,
  Plus,
  Trash2,
  Globe2,
  Users,
  Check,
  ScanLine,
  Layers,
  History,
} from 'lucide-react'
import { getClasses } from '@/api/staff'
import {
  getAdminBell,
  getBellLogs,
  getBellOverview,
  recognizeBellImage,
  saveAdminBell,
  type BellChangeLog,
  type BellOverview,
  type BellPeriodRow,
} from '@/api/bell'
import type { ClassInfo } from '@/types'

/**
 * 作息时间设置：
 * - 范围选择可视化：默认作息 / 按班级（支持多选，可整年级全选）
 * - 每节起止时间可编辑，内置标准作息一键填充
 * - 保存后自动同步所选班级已排课时间，并清空后端缓存立即生效
 */
export function BellSchedulePage() {
  const [classes, setClasses] = useState<ClassInfo[]>([])
  const [scope, setScope] = useState<'default' | 'classes'>('default')
  const [selected, setSelected] = useState<number[]>([])
  const [semester, setSemester] = useState('')
  const [rows, setRows] = useState<BellPeriodRow[]>([])
  const [effective, setEffective] = useState<BellPeriodRow[]>([])
  const [ownCount, setOwnCount] = useState(0)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [recognizing, setRecognizing] = useState(false)
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)
  const imageRef = useRef<HTMLInputElement>(null)
  const [overview, setOverview] = useState<BellOverview | null>(null)
  const [logs, setLogs] = useState<BellChangeLog[]>([])
  /** 最近一次保存结果（同时显示在保存按钮旁，避免用户以为没反馈） */
  const [lastSaved, setLastSaved] = useState<string | null>(null)

  const loadOverview = useCallback(async () => {
    try {
      const [ov, lg] = await Promise.all([getBellOverview(), getBellLogs(8)])
      setOverview(ov)
      setLogs(lg || [])
    } catch {
      // 概览失败不影响主流程
    }
  }, [])

  useEffect(() => {
    loadOverview()
  }, [loadOverview])

  useEffect(() => {
    getClasses()
      .then((list) => setClasses(list || []))
      .catch(() => setClasses([]))
  }, [])

  // 按年级分组，方便成组勾选
  const grouped = useMemo(() => {
    const map = new Map<string, ClassInfo[]>()
    for (const c of classes) {
      const key = (c as any).grade || '未分年级'
      if (!map.has(key)) map.set(key, [])
      map.get(key)!.push(c)
    }
    return Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0], 'zh'))
  }, [classes])

  /** 当前用于读取/预览模板的班级（多选时取第一个） */
  const previewClassId = scope === 'classes' && selected.length > 0 ? selected[0] : null

  const load = useCallback(async () => {
    setLoading(true)
    setMessage(null)
    try {
      const data = await getAdminBell({ classId: previewClassId ?? undefined })
      setSemester(data.semester || '')
      setOwnCount(data.periods.length)
      setEffective(data.effective || [])
      const initial =
        data.periods.length > 0 ? data.periods : data.effective.length > 0 ? data.effective : data.preset
      setRows(normalize(initial))
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '加载作息失败' })
      setRows([])
    } finally {
      setLoading(false)
    }
  }, [previewClassId])

  useEffect(() => {
    load()
  }, [load])

  const toggleClass = (id: number) => {
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  }

  const toggleGrade = (list: ClassInfo[]) => {
    const ids = list.map((c) => Number(c.id))
    const allSelected = ids.every((id) => selected.includes(id))
    setSelected((prev) => (allSelected ? prev.filter((id) => !ids.includes(id)) : Array.from(new Set([...prev, ...ids]))))
  }

  const updateRow = (index: number, key: 'startTime' | 'endTime', value: string) => {
    setRows((prev) => prev.map((r, i) => (i === index ? { ...r, [key]: value } : r)))
  }

  /** 图片识别：上传作息表照片 → AI 视觉提取节次时间并填入表格 */
  const onImageSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setRecognizing(true)
    setMessage(null)
    try {
      const res = await recognizeBellImage(file)
      if (!res.periods || res.periods.length === 0) {
        setMessage({ type: 'err', text: res.error || '未识别到教学节次时间，请换一张更清晰的图片' })
        return
      }
      setRows((prev) => {
        const map = new Map(prev.map((p) => [p.node, p]))
        for (const p of res.periods!) {
          map.set(p.node, {
            node: p.node,
            startTime: (p.startTime || '').slice(0, 5),
            endTime: (p.endTime || '').slice(0, 5),
          })
        }
        return Array.from(map.values()).sort((a, b) => a.node - b.node)
      })
      setMessage({
        type: 'ok',
        text: `识别到 ${res.count ?? res.periods.length} 个节次，已填入下方表格，请核对后保存`,
      })
    } catch (err: any) {
      setMessage({
        type: 'err',
        text: err?.response?.data?.error || err?.message || '识别失败，请稍后重试',
      })
    } finally {
      setRecognizing(false)
    }
  }

  const fillPreset = async () => {
    try {
      const data = await getAdminBell({ classId: previewClassId ?? undefined })
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
      return [...prev, { node, startTime: last?.endTime || '08:00', endTime: last?.endTime || '08:40' }].sort(
        (a, b) => a.node - b.node
      )
    })
  }

  const removeRow = (index: number) => {
    setRows((prev) => prev.filter((_, i) => i !== index))
  }

  const submit = async () => {
    setMessage(null)
    if (scope === 'classes' && selected.length === 0) {
      setMessage({ type: 'err', text: '请先选择至少一个班级' })
      return
    }
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
    setLastSaved(null)
    try {
      const res = await saveAdminBell({
        classIds: scope === 'classes' ? selected : undefined,
        classId: scope === 'default' ? null : undefined,
        semester,
        periods: rows,
      })
      const okText =
        res.msg +
        `（${res.saved} 个节次）` +
        (res.syncedSchedules > 0
          ? `，已同步 ${res.syncedSchedules} 条课表的上下课时间`
          : '，当前没有需要同步的已排课记录')
      setMessage({ type: 'ok', text: okText + '，已更新下方「各班作息概览」与变更记录' })
      setLastSaved(`✓ 已保存 · ${res.saved} 个节次 · 同步 ${res.syncedSchedules} 条课表`)
      const data = await getAdminBell({ classId: previewClassId ?? undefined })
      setEffective(data.effective || [])
      // 刷新方案概览与变更记录，让「保存结果」可追溯
      loadOverview()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '保存失败，请稍后重试' })
    } finally {
      setSaving(false)
    }
  }

  /** 概览里点击班级：切到按班级配置并选中它 */
  const pickClass = (id: number) => {
    setScope('classes')
    setSelected([id])
    setMessage(null)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const scopeText =
    scope === 'default'
      ? '默认作息（作用于所有未单独配置的班级）'
      : selected.length === 0
        ? '尚未选择班级'
        : `${selected.length} 个班级：` +
          classes
            .filter((c) => selected.includes(Number(c.id)))
            .map((c) => c.className)
            .slice(0, 4)
            .join('、') +
          (selected.length > 4 ? ' 等' : '')

  return (
    <div className="space-y-6">
      <div>
        <h1 className="flex items-center gap-2 text-2xl font-bold text-neutral-900">
          <Clock className="h-6 w-6 text-primary-500" />
          作息时间设置
        </h1>
        <p className="mt-1 text-sm text-neutral-500">
          选择作用范围（支持一次配置多个班级），维护每节课的起止时间。保存后自动同步已排课时间并立即生效。
        </p>
      </div>

      {/* ===== 范围选择（可视化） ===== */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">选择配置范围</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <button
              type="button"
              onClick={() => setScope('default')}
              className={`flex items-start gap-3 rounded-xl border p-4 text-left transition-all ${
                scope === 'default'
                  ? 'border-primary-400 bg-primary-50/60 ring-2 ring-primary-200'
                  : 'border-neutral-200 bg-white hover:border-neutral-300 hover:bg-neutral-50'
              }`}
            >
              <div
                className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${
                  scope === 'default' ? 'bg-primary-500 text-white' : 'bg-neutral-100 text-neutral-500'
                }`}
              >
                <Globe2 className="h-4 w-4" />
              </div>
              <div className="min-w-0">
                <div className="flex items-center gap-2 text-sm font-medium text-neutral-900">
                  默认作息
                  {scope === 'default' && <Check className="h-4 w-4 text-primary-500" />}
                </div>
                <div className="mt-0.5 text-xs leading-5 text-neutral-500">
                  作为全局兜底，对所有「没有单独配置」的班级生效
                </div>
              </div>
            </button>

            <button
              type="button"
              onClick={() => setScope('classes')}
              className={`flex items-start gap-3 rounded-xl border p-4 text-left transition-all ${
                scope === 'classes'
                  ? 'border-primary-400 bg-primary-50/60 ring-2 ring-primary-200'
                  : 'border-neutral-200 bg-white hover:border-neutral-300 hover:bg-neutral-50'
              }`}
            >
              <div
                className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${
                  scope === 'classes' ? 'bg-primary-500 text-white' : 'bg-neutral-100 text-neutral-500'
                }`}
              >
                <Users className="h-4 w-4" />
              </div>
              <div className="min-w-0">
                <div className="flex items-center gap-2 text-sm font-medium text-neutral-900">
                  按班级配置
                  {scope === 'classes' && <Check className="h-4 w-4 text-primary-500" />}
                </div>
                <div className="mt-0.5 text-xs leading-5 text-neutral-500">
                  可多选班级，一次保存应用到所有选中班级
                </div>
              </div>
            </button>
          </div>

          {/* 班级多选 */}
          {scope === 'classes' && (
            <div className="rounded-xl border border-neutral-200 bg-neutral-50/50 p-4">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <div className="text-xs text-neutral-500">
                  已选 <span className="font-semibold text-primary-600">{selected.length}</span> 个班级
                  <button
                    type="button"
                    className="ml-2 text-primary-600 hover:underline disabled:text-neutral-400"
                    disabled={selected.length === 0}
                    onClick={() => setSelected([])}
                  >
                    清空
                  </button>
                </div>
                <div className="text-xs text-neutral-400">按年级分组，点年级可整组全选</div>
              </div>

              {grouped.length === 0 && (
                <div className="py-6 text-center text-xs text-neutral-400">暂无班级数据</div>
              )}

              <div className="space-y-3">
                {grouped.map(([grade, list]) => {
                  const ids = list.map((c) => Number(c.id))
                  const allSelected = ids.length > 0 && ids.every((id) => selected.includes(id))
                  return (
                    <div key={grade}>
                      <button
                        type="button"
                        onClick={() => toggleGrade(list)}
                        className={`mb-2 inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-[11px] font-medium transition-colors ${
                          allSelected
                            ? 'bg-primary-100 text-primary-700'
                            : 'bg-neutral-200/70 text-neutral-600 hover:bg-neutral-200'
                        }`}
                      >
                        {grade}
                        <span className="opacity-60">
                          ({(list.filter((c) => selected.includes(Number(c.id))).length)}/{ids.length})
                        </span>
                      </button>
                      <div className="flex flex-wrap gap-2">
                        {list.map((c) => {
                          const id = Number(c.id)
                          const on = selected.includes(id)
                          return (
                            <button
                              key={id}
                              type="button"
                              onClick={() => toggleClass(id)}
                              title={`${c.className}${(c as any).major ? ' · ' + (c as any).major : ''}`}
                              className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs transition-all ${
                                on
                                  ? 'border-primary-400 bg-primary-500 text-white shadow-sm'
                                  : 'border-neutral-200 bg-white text-neutral-600 hover:border-primary-300 hover:text-primary-600'
                              }`}
                            >
                              {on && <Check className="h-3.5 w-3.5" />}
                              {c.className}
                            </button>
                          )
                        })}
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          <div className="flex items-start gap-2 rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-700">
            <Info className="mt-0.5 h-4 w-4 flex-shrink-0" />
            <span>
              当前范围：<span className="font-medium">{scopeText}</span>
              {scope === 'classes' && previewClassId
                ? `（表格以 ${classes.find((c) => Number(c.id) === previewClassId)?.className || '首个选中班级'} 的作息为编辑基础，保存后覆盖全部选中班级）`
                : ''}
              {scope === 'default' && ownCount > 0
                ? `，默认作息已有 ${ownCount} 个节次配置`
                : ''}
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

      {/* ===== 各班作息概览（相同时间自动归组） ===== */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="flex items-center gap-2 text-base">
            <Layers className="h-4 w-4 text-neutral-400" />
            各班作息概览
          </CardTitle>
          <span className="text-xs text-neutral-400">
            共 {overview?.totalClasses ?? 0} 个班级 · 时间完全相同的自动归为一组
          </span>
        </CardHeader>
        <CardContent className="space-y-3">
          {!overview && <div className="py-6 text-center text-xs text-neutral-400">加载中…</div>}
          {overview?.groups.map((g) => (
            <div key={g.signature} className="rounded-xl border border-neutral-200 p-3">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <span
                  className={`rounded-md px-2 py-0.5 text-xs font-medium ${
                    g.isDefault ? 'bg-neutral-100 text-neutral-600' : 'bg-primary-50 text-primary-700'
                  }`}
                >
                  {g.label}
                </span>
                <span className="text-xs text-neutral-400">{g.classCount} 个班级</span>
                <span className="text-[11px] text-neutral-400">
                  {g.periods
                    .slice(0, 4)
                    .map((p) => `第${p.node}节 ${p.startTime?.slice(0, 5)}–${p.endTime?.slice(0, 5)}`)
                    .join('  ')}
                  {g.periods.length > 4 ? ` 等 ${g.periods.length} 节` : ''}
                </span>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {g.classes.map((c) => (
                  <button
                    key={c.id}
                    type="button"
                    onClick={() => pickClass(c.id)}
                    title="点击编辑该班级作息"
                    className={`rounded-full border px-2.5 py-1 text-xs transition-colors ${
                      selected.includes(c.id)
                        ? 'border-primary-400 bg-primary-500 text-white'
                        : 'border-neutral-200 bg-white text-neutral-600 hover:border-primary-300 hover:text-primary-600'
                    }`}
                  >
                    {c.name}
                    {c.source === 'class' && (
                      <span className={`ml-1 text-[10px] ${selected.includes(c.id) ? 'text-white/80' : 'text-primary-500'}`}>
                        自定义
                      </span>
                    )}
                  </button>
                ))}
                {g.classes.length === 0 && <span className="text-xs text-neutral-400">（暂无班级）</span>}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* ===== 节次编辑 ===== */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="flex items-center gap-2 text-base">
            节次时间
            <span className="text-xs font-normal text-neutral-400">
              {semester || '当前学期'} · {rows.length} 节
            </span>
          </CardTitle>
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => imageRef.current?.click()}
              disabled={recognizing || loading}
              title="上传作息表照片/截图，自动识别教学节次时间（本地 OCR，离线可用）"
            >
              <ScanLine className="mr-1 h-4 w-4" />
              {recognizing ? '识别中…' : '图片识别'}
            </Button>
            <input
              ref={imageRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={onImageSelected}
            />
            <Button variant="outline" size="sm" onClick={fillPreset} disabled={loading}>
              <RotateCcw className="mr-1 h-4 w-4" />
              填入标准作息
            </Button>
            <Button variant="outline" size="sm" onClick={addRow} disabled={loading || rows.length >= 12}>
              <Plus className="mr-1 h-4 w-4" />
              增加节次
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-4">
            {rows.map((row, i) => (
              <div
                key={row.node}
                className="flex items-center gap-2 rounded-lg border border-neutral-200 bg-white px-3 py-2"
              >
                <span className="w-12 shrink-0 text-xs font-medium text-neutral-500">
                  第{row.node}节
                </span>
                <input
                  type="time"
                  value={row.startTime?.slice(0, 5) || ''}
                  onChange={(e) => updateRow(i, 'startTime', e.target.value)}
                  className="h-8 min-w-0 flex-1 rounded-md border border-neutral-200 px-1.5 text-center text-xs tabular-nums focus:outline-none focus:ring-2 focus:ring-primary-500"
                />
                <span className="text-neutral-300">–</span>
                <input
                  type="time"
                  value={row.endTime?.slice(0, 5) || ''}
                  onChange={(e) => updateRow(i, 'endTime', e.target.value)}
                  className="h-8 min-w-0 flex-1 rounded-md border border-neutral-200 px-1.5 text-center text-xs tabular-nums focus:outline-none focus:ring-2 focus:ring-primary-500"
                />
                <button
                  type="button"
                  onClick={() => removeRow(i)}
                  className="shrink-0 rounded p-1 text-neutral-300 transition-colors hover:bg-red-50 hover:text-red-500"
                  title="删除该节次"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            ))}
            {rows.length === 0 && (
              <div className="col-span-full py-10 text-center text-sm text-neutral-400">
                {loading ? '加载中…' : '暂无节次，请点击「填入标准作息」'}
              </div>
            )}
          </div>

          <div className="mt-5 flex flex-wrap items-center justify-between gap-3">
            <div className="text-xs text-neutral-400">
              保存将覆盖所选范围内原有的通用作息；「图片识别」支持拍照/截图，自动忽略起床铃、早读、午休等非教学时段（本地 OCR，无需联网）。
            </div>
            <div className="flex items-center gap-3">
              {lastSaved && <span className="text-xs font-medium text-emerald-600">{lastSaved}</span>}
              <Button onClick={submit} disabled={saving || loading}>
                <Save className="mr-1 h-4 w-4" />
                {saving ? '保存中…' : scope === 'classes' ? `保存到 ${selected.length} 个班级` : '保存默认作息'}
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* ===== 最近变更记录 ===== */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <History className="h-4 w-4 text-neutral-400" />
            最近变更记录
          </CardTitle>
        </CardHeader>
        <CardContent>
          {logs.length === 0 ? (
            <div className="py-6 text-center text-xs text-neutral-400">暂无记录，保存一次作息后会出现在这里</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-neutral-200 bg-neutral-50/60">
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400 whitespace-nowrap">时间</th>
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400 whitespace-nowrap">操作人</th>
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400">范围</th>
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400 whitespace-nowrap">节次</th>
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400 whitespace-nowrap">同步课表</th>
                    <th className="px-3 py-2 text-left text-[11px] font-medium text-neutral-400 whitespace-nowrap">学期</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map((l) => (
                    <tr key={l.id} className="border-b border-neutral-100 last:border-0">
                      <td className="px-3 py-2 text-xs text-neutral-500 tabular-nums whitespace-nowrap">{l.createdAt}</td>
                      <td className="px-3 py-2 text-xs text-neutral-700 whitespace-nowrap">{l.operatorName || '-'}</td>
                      <td className="px-3 py-2 text-xs text-neutral-700">
                        {l.scopeType === 'DEFAULT' ? (
                          <span className="rounded bg-neutral-100 px-1.5 py-0.5">默认作息</span>
                        ) : (
                          <span className="rounded bg-primary-50 px-1.5 py-0.5 text-primary-700">
                            {l.classNames || `${l.classCount} 个班级`}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-xs text-neutral-600 tabular-nums whitespace-nowrap">{l.nodeCount} 节</td>
                      <td className="px-3 py-2 text-xs text-neutral-600 tabular-nums whitespace-nowrap">
                        {l.syncedSchedules} 条
                      </td>
                      <td className="px-3 py-2 text-xs text-neutral-500 tabular-nums whitespace-nowrap">
                        {l.semester || '-'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {effective.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">
              当前生效作息（{scope === 'default' ? '默认 / 继承' : '首个选中班级'}）
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {effective.map((p) => (
                <span
                  key={p.node}
                  className="inline-flex items-center rounded-md bg-neutral-100 px-2.5 py-1 text-xs text-neutral-600 tabular-nums"
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

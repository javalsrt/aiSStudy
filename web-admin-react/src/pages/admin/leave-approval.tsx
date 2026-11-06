import { useCallback, useEffect, useMemo, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import {
  CalendarCheck,
  Check,
  X,
  RefreshCw,
  Image as ImageIcon,
  ShieldCheck,
  Users2,
  Download,
  MapPin,
  CheckCheck,
} from 'lucide-react'
import {
  batchReviewLeave,
  getLeavePending,
  getLeaveRecords,
  reviewLeave,
  type LeaveItem,
} from '@/api/leave'

type Tab = 'pending' | 'history'

/** 导出 CSV（带 BOM，Excel 直接打开不乱码） */
function downloadCsv(filename: string, rows: (string | number | null | undefined)[][]) {
  const esc = (v: string | number | null | undefined) => {
    const s = v === null || v === undefined ? '' : String(v)
    return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  const csv = rows.map((r) => r.map(esc).join(',')).join('\r\n')
  const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

const statusStyle = (status: string) => {
  switch (status) {
    case 'APPROVED':
      return 'bg-emerald-50 text-emerald-700'
    case 'RETURNED':
      return 'bg-primary-50 text-primary-700'
    case 'REJECTED':
      return 'bg-red-50 text-red-600'
    case 'CANCELED':
      return 'bg-neutral-100 text-neutral-500'
    case 'PENDING_LEADER':
      return 'bg-amber-50 text-amber-700'
    default:
      return 'bg-primary-50 text-primary-700'
  }
}

/**
 * 请假 / 临时外出审批。
 * 权级：3 天内班主任审批；超过 3 天班主任初审 → 校级领导终审。
 * 管理端可跨班审批，支持批量审批、已处理记录（含返校打卡）与表格导出。
 */
export function LeaveApprovalPage() {
  const [tab, setTab] = useState<Tab>('pending')
  const [items, setItems] = useState<LeaveItem[]>([])
  const [records, setRecords] = useState<LeaveItem[]>([])
  const [loading, setLoading] = useState(true)
  const [comment, setComment] = useState<Record<number, string>>({})
  const [busy, setBusy] = useState<number | null>(null)
  const [batching, setBatching] = useState(false)
  const [selected, setSelected] = useState<number[]>([])
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [keyword, setKeyword] = useState('')
  /** 待审批：按班级 / 关键字筛选 */
  const [pendingClass, setPendingClass] = useState('ALL')
  const [pendingKeyword, setPendingKeyword] = useState('')
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [pendingR, recordsR] = await Promise.allSettled([getLeavePending(), getLeaveRecords(300)])
      if (pendingR.status === 'rejected' && recordsR.status === 'rejected') {
        throw pendingR.reason
      }
      setItems(pendingR.status === 'fulfilled' ? pendingR.value || [] : [])
      setRecords(recordsR.status === 'fulfilled' ? recordsR.value || [] : [])
      setSelected([])
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '加载失败' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  // 切换筛选条件时清空勾选，避免批量操作误伤看不见的条目
  useEffect(() => {
    setSelected([])
  }, [pendingClass, pendingKeyword])

  /** 待审批可选的班级列表 */
  const pendingClasses = useMemo(
    () => Array.from(new Set(items.map((it) => it.className || '未知班级'))).sort((a, b) => a.localeCompare(b, 'zh')),
    [items]
  )

  /** 待审批：班级 + 关键字筛选 */
  const filteredPending = useMemo(() => {
    const k = pendingKeyword.trim()
    return items.filter((it) => {
      if (pendingClass !== 'ALL' && (it.className || '未知班级') !== pendingClass) return false
      if (!k) return true
      return (
        (it.studentName || '').includes(k) ||
        (it.studentNo || '').includes(k) ||
        (it.className || '').includes(k) ||
        (it.reason || '').includes(k) ||
        (it.destination || '').includes(k)
      )
    })
  }, [items, pendingClass, pendingKeyword])

  /** 按班级分组（待审批） */
  const groups = useMemo(() => {
    const map = new Map<string, { className: string; headTeacher?: string | null; list: LeaveItem[] }>()
    for (const it of filteredPending) {
      const key = it.className || '未知班级'
      if (!map.has(key)) map.set(key, { className: key, headTeacher: it.headTeacherName, list: [] })
      map.get(key)!.list.push(it)
    }
    return Array.from(map.values()).sort((a, b) => a.className.localeCompare(b.className, 'zh'))
  }, [filteredPending])

  const selectableIds = useMemo(
    () => filteredPending.filter((it) => it.canReview !== false).map((it) => it.id),
    [filteredPending]
  )
  const allSelected = selectableIds.length > 0 && selected.length === selectableIds.length

  const toggleOne = (id: number) => {
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  }

  const toggleAll = () => {
    setSelected(allSelected ? [] : selectableIds)
  }

  const review = async (item: LeaveItem, action: 'approve' | 'reject') => {
    if (item.canReview === false) return
    setBusy(item.id)
    try {
      const res = await reviewLeave(item.id, action, comment[item.id] || '')
      setMessage({ type: 'ok', text: `${item.studentName || '该学生'}：${res.msg}` })
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '操作失败' })
    } finally {
      setBusy(null)
    }
  }

  const batchReview = async (action: 'approve' | 'reject') => {
    if (selected.length === 0) {
      setMessage({ type: 'err', text: '请先勾选要审批的申请' })
      return
    }
    const verb = action === 'approve' ? '通过' : '驳回'
    if (!window.confirm(`确定批量${verb} ${selected.length} 条申请？`)) return
    setBatching(true)
    try {
      const res = await batchReviewLeave(selected, action, '')
      setMessage({
        type: res.failed.length ? 'err' : 'ok',
        text: res.msg + (res.failed.length ? `（${res.failed[0].error}）` : ''),
      })
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.response?.data?.error || e?.message || '批量操作失败' })
    } finally {
      setBatching(false)
    }
  }

  const stageBadge = (item: LeaveItem) => {
    if (item.status === 'PENDING_LEADER') {
      return <span className="rounded-md bg-amber-50 px-2 py-1 text-xs text-amber-700">待领导终审</span>
    }
    return <span className="rounded-md bg-primary-50 px-2 py-1 text-xs text-primary-700">待班主任审批</span>
  }

  /** 已处理记录：筛选 + 关键字 */
  const filteredRecords = useMemo(() => {
    const k = keyword.trim()
    return records.filter((r) => {
      if (statusFilter !== 'ALL' && r.status !== statusFilter) return false
      if (!k) return true
      return (
        (r.studentName || '').includes(k) ||
        (r.studentNo || '').includes(k) ||
        (r.className || '').includes(k) ||
        (r.reason || '').includes(k)
      )
    })
  }, [records, statusFilter, keyword])

  /** 导出当前列表 */
  const exportCsv = () => {
    const today = new Date()
    const stamp = `${today.getFullYear()}${String(today.getMonth() + 1).padStart(2, '0')}${String(
      today.getDate()
    ).padStart(2, '0')}`
    if (tab === 'pending') {
      const rows: (string | number | null | undefined)[][] = [
        ['学号', '姓名', '班级', '类型', '出校时间', '预计回校', '天数', '状态', '事由', '去向', '家长电话', '已告知家长', '申请时间'],
        ...filteredPending.map((it) => [
          it.studentNo,
          it.studentName,
          it.className,
          it.typeText,
          it.startText,
          it.endText,
          it.days,
          it.statusText,
          it.reason,
          it.destination,
          it.parentPhone,
          it.parentNotified === 1 ? '是' : '否',
          it.createdText,
        ]),
      ]
      downloadCsv(`请假待审批_${stamp}.csv`, rows)
      return
    }
    const rows: (string | number | null | undefined)[][] = [
      [
        '学号',
        '姓名',
        '班级',
        '类型',
        '出校时间',
        '预计回校',
        '天数',
        '状态',
        '初审人',
        '初审身份',
        '初审意见',
        '终审人',
        '终审身份',
        '终审意见',
        '返校打卡时间',
        '打卡纬度',
        '打卡经度',
        '打卡照片',
        '事由',
        '申请时间',
      ],
      ...filteredRecords.map((r) => [
        r.studentNo,
        r.studentName,
        r.className,
        r.typeText,
        r.startText,
        r.endText,
        r.days,
        r.statusText,
        r.teacherName,
        r.teacherReviewerRole,
        r.teacherComment,
        r.leaderName,
        r.leaderReviewerRole,
        r.leaderComment,
        r.returnAtText,
        r.returnLat,
        r.returnLng,
        r.returnPhoto,
        r.reason,
        r.createdText,
      ]),
    ]
    downloadCsv(`请假已处理_${stamp}.csv`, rows)
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold text-neutral-900">
            <CalendarCheck className="h-6 w-6 text-primary-500" />
            请假与外出审批
          </h1>
          <p className="mt-1 text-sm text-neutral-500">
            请假 3 天以内由本班班主任审批；超过 3 天班主任初审后由校级领导（如教导主任）终审。审批会记录审批人身份，
            审批完成学生返校打卡的照片与定位可在「已处理」中查看。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={exportCsv} disabled={loading}>
            <Download className="mr-1 h-4 w-4" />
            导出表格
          </Button>
          <Button variant="outline" onClick={load} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
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

      {/* 页签 */}
      <div className="flex items-center gap-2 border-b border-neutral-200">
        <button
          onClick={() => setTab('pending')}
          className={
            'relative px-3 py-2 text-sm transition ' +
            (tab === 'pending' ? 'font-medium text-primary-600' : 'text-neutral-500 hover:text-neutral-800')
          }
        >
          待审批
          <span className="ml-1 rounded-full bg-neutral-100 px-1.5 py-0.5 text-[11px] text-neutral-500">
            {items.length}
          </span>
          {tab === 'pending' && <span className="absolute inset-x-2 -bottom-px h-0.5 rounded bg-primary-500" />}
        </button>
        <button
          onClick={() => setTab('history')}
          className={
            'relative px-3 py-2 text-sm transition ' +
            (tab === 'history' ? 'font-medium text-primary-600' : 'text-neutral-500 hover:text-neutral-800')
          }
        >
          已处理
          <span className="ml-1 rounded-full bg-neutral-100 px-1.5 py-0.5 text-[11px] text-neutral-500">
            {records.length}
          </span>
          {tab === 'history' && <span className="absolute inset-x-2 -bottom-px h-0.5 rounded bg-primary-500" />}
        </button>
      </div>

      {loading && <div className="py-10 text-center text-sm text-neutral-400">加载中…</div>}

      {/* ============ 待审批 ============ */}
      {!loading && tab === 'pending' && (
        <>
          {items.length === 0 ? (
            <Card>
              <CardContent className="py-12 text-center text-sm text-neutral-400">暂无待审批申请</CardContent>
            </Card>
          ) : (
            <>
              {/* 筛选：班级 + 关键字 */}
              <div className="flex flex-wrap items-center gap-2 rounded-lg bg-white px-3 py-2 ring-1 ring-neutral-200">
                <select
                  className="h-8 rounded-md border border-neutral-200 bg-white px-2 text-xs focus:outline-none focus:ring-2 focus:ring-primary-500"
                  value={pendingClass}
                  onChange={(e) => setPendingClass(e.target.value)}
                >
                  <option value="ALL">全部班级</option>
                  {pendingClasses.map((c) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                </select>
                <input
                  className="h-8 w-52 rounded-md border border-neutral-200 px-2 text-xs focus:outline-none focus:ring-2 focus:ring-primary-500"
                  placeholder="搜索姓名 / 学号 / 事由"
                  value={pendingKeyword}
                  onChange={(e) => setPendingKeyword(e.target.value)}
                />
                {(pendingClass !== 'ALL' || pendingKeyword.trim() !== '') && (
                  <button
                    className="h-8 rounded-md px-2 text-xs text-primary-600 hover:bg-primary-50"
                    onClick={() => {
                      setPendingClass('ALL')
                      setPendingKeyword('')
                    }}
                  >
                    重置
                  </button>
                )}
                <span className="ml-auto text-xs text-neutral-400">
                  筛选出 {filteredPending.length} / {items.length} 条
                </span>
              </div>

              {/* 批量操作 */}
              <div className="flex flex-wrap items-center gap-3 rounded-lg bg-white px-3 py-2 ring-1 ring-neutral-200">
                <label className="flex items-center gap-2 text-xs text-neutral-600">
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-primary-500"
                    checked={allSelected}
                    disabled={selectableIds.length === 0}
                    onChange={toggleAll}
                  />
                  全选（可审批 {selectableIds.length} 条）
                </label>
                <span className="text-xs text-neutral-400">已选 {selected.length} 条</span>
                <div className="ml-auto flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-8 px-3 text-xs text-danger"
                    disabled={batching || selected.length === 0}
                    onClick={() => batchReview('reject')}
                  >
                    <X className="mr-1 h-4 w-4" />
                    批量驳回
                  </Button>
                  <Button
                    size="sm"
                    className="h-8 px-3 text-xs"
                    disabled={batching || selected.length === 0}
                    onClick={() => batchReview('approve')}
                  >
                    <CheckCheck className="mr-1 h-4 w-4" />
                    批量通过
                  </Button>
                </div>
              </div>
            </>
          )}

          {items.length > 0 && filteredPending.length === 0 && (
            <Card>
              <CardContent className="py-12 text-center text-sm text-neutral-400">
                没有符合条件的申请，换个班级或关键字试试
              </CardContent>
            </Card>
          )}

          {groups.map((g) => (
            <Card key={g.className}>
              <CardHeader className="flex-row items-center justify-between space-y-0">
                <CardTitle className="flex items-center gap-2 text-base">
                  <Users2 className="h-4 w-4 text-neutral-400" />
                  {g.className}
                  <span className="text-xs font-normal text-neutral-400">
                    班主任：{g.headTeacher || '未设置'}
                  </span>
                </CardTitle>
                <span className="text-xs text-neutral-400">{g.list.length} 条待审批</span>
              </CardHeader>
              <CardContent className="space-y-3">
                {g.list.map((item) => (
                  <div key={item.id} className="rounded-xl border border-neutral-200 p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-primary-500"
                        checked={selected.includes(item.id)}
                        disabled={item.canReview === false}
                        onChange={() => toggleOne(item.id)}
                      />
                      <span className="text-sm font-medium text-neutral-900">{item.studentName || '未知学生'}</span>
                      <span className="text-xs text-neutral-400">学号 {item.studentNo || '-'}</span>
                      <span className="rounded bg-neutral-100 px-2 py-0.5 text-xs text-neutral-600">{item.typeText}</span>
                      <span className="rounded bg-neutral-100 px-2 py-0.5 text-xs text-neutral-600">共 {item.days} 天</span>
                      {item.parentNotified === 1 && (
                        <span className="rounded bg-emerald-50 px-2 py-0.5 text-xs text-emerald-700">已告知家长</span>
                      )}
                      {item.smsSent === 1 && (
                        <span className="rounded bg-emerald-50 px-2 py-0.5 text-xs text-emerald-700">已发短信</span>
                      )}
                      <span className="ml-auto">{stageBadge(item)}</span>
                    </div>

                    <div className="mt-2 grid grid-cols-1 gap-1 text-[13px] text-neutral-700 md:grid-cols-2">
                      <div>
                        <span className="text-neutral-400">出校：</span>
                        {item.startText}
                      </div>
                      <div>
                        <span className="text-neutral-400">回校：</span>
                        {item.endText}
                      </div>
                      <div>
                        <span className="text-neutral-400">去向：</span>
                        {item.destination || '-'}
                      </div>
                      <div>
                        <span className="text-neutral-400">家长电话：</span>
                        {item.parentPhone || '-'}
                      </div>
                      <div className="md:col-span-2">
                        <span className="text-neutral-400">事由：</span>
                        {item.reason}
                      </div>
                      {item.teacherReviewerRole && (
                        <div className="md:col-span-2 text-xs text-neutral-500">
                          初审身份：{item.teacherReviewerRole}
                          {item.teacherComment ? ` · 意见：${item.teacherComment}` : ''}
                        </div>
                      )}
                    </div>

                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      <input
                        className="h-8 min-w-[220px] flex-1 rounded-md border border-neutral-200 px-2 text-xs focus:outline-none focus:ring-2 focus:ring-primary-500"
                        placeholder={item.canReview === false ? '你无权审批该申请' : '审批意见（可选）'}
                        disabled={item.canReview === false}
                        value={comment[item.id] || ''}
                        onChange={(e) => setComment({ ...comment, [item.id]: e.target.value })}
                      />
                      <Button
                        variant="outline"
                        size="sm"
                        className="h-8 px-3 text-xs text-danger"
                        disabled={busy === item.id || item.canReview === false}
                        onClick={() => review(item, 'reject')}
                      >
                        <X className="mr-1 h-4 w-4" />
                        驳回
                      </Button>
                      <Button
                        size="sm"
                        className="h-8 px-3 text-xs"
                        disabled={busy === item.id || item.canReview === false}
                        onClick={() => review(item, 'approve')}
                      >
                        <Check className="mr-1 h-4 w-4" />
                        {item.status === 'PENDING_LEADER' ? '终审通过' : '通过'}
                      </Button>
                    </div>

                    {item.canReview === false && (
                      <div className="mt-2 flex items-center gap-1 text-[11px] text-neutral-400">
                        <ShieldCheck className="h-3.5 w-3.5" />
                        仅本班班主任、校级领导或管理员可审批
                      </div>
                    )}
                  </div>
                ))}
              </CardContent>
            </Card>
          ))}
        </>
      )}

      {/* ============ 已处理 ============ */}
      {!loading && tab === 'history' && (
        <Card>
          <CardHeader className="flex-row flex-wrap items-center justify-between gap-2 space-y-0">
            <CardTitle className="text-base">已处理记录 · {filteredRecords.length}</CardTitle>
            <div className="flex flex-wrap items-center gap-2">
              <input
                className="h-8 w-40 rounded-md border border-neutral-200 px-2 text-xs focus:outline-none focus:ring-2 focus:ring-primary-500"
                placeholder="搜索姓名 / 学号 / 班级"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
              <select
                className="h-8 rounded-md border border-neutral-200 px-2 text-xs"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
              >
                <option value="ALL">全部状态</option>
                <option value="APPROVED">已通过</option>
                <option value="RETURNED">已返校</option>
                <option value="REJECTED">已驳回</option>
                <option value="CANCELED">已取消</option>
              </select>
            </div>
          </CardHeader>
          <CardContent className="p-0">
            <div className="max-h-[560px] overflow-auto">
              <table className="w-full text-sm">
                <thead className="sticky top-0 z-10 bg-neutral-50 text-xs text-neutral-500">
                  <tr>
                    <th className="px-3 py-2 text-left font-normal">学生</th>
                    <th className="px-3 py-2 text-left font-normal">班级</th>
                    <th className="px-3 py-2 text-left font-normal">类型</th>
                    <th className="px-3 py-2 text-left font-normal">起止时间</th>
                    <th className="px-3 py-2 text-left font-normal">天数</th>
                    <th className="px-3 py-2 text-left font-normal">状态</th>
                    <th className="px-3 py-2 text-left font-normal">审批</th>
                    <th className="px-3 py-2 text-left font-normal">返校打卡</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredRecords.map((r) => (
                    <tr key={r.id} className="border-t border-neutral-100 align-top">
                      <td className="px-3 py-2">
                        <div className="text-neutral-900">{r.studentName || '-'}</div>
                        <div className="text-[11px] text-neutral-400">{r.studentNo || '-'}</div>
                      </td>
                      <td className="px-3 py-2 text-neutral-600">{r.className || '-'}</td>
                      <td className="px-3 py-2 text-neutral-600">{r.typeText}</td>
                      <td className="px-3 py-2 text-xs text-neutral-600">
                        <div>{r.startText}</div>
                        <div className="text-neutral-400">至 {r.endText}</div>
                      </td>
                      <td className="px-3 py-2 text-neutral-600">{r.days}</td>
                      <td className="px-3 py-2">
                        <span className={'rounded px-2 py-0.5 text-xs ' + statusStyle(r.status)}>{r.statusText}</span>
                      </td>
                      <td className="px-3 py-2 text-xs text-neutral-600">
                        <div>
                          初审：{r.teacherName || '-'}
                          {r.teacherReviewerRole ? `（${r.teacherReviewerRole}）` : ''}
                        </div>
                        {r.leaderName && (
                          <div className="text-neutral-400">
                            终审：{r.leaderName}
                            {r.leaderReviewerRole ? `（${r.leaderReviewerRole}）` : ''}
                          </div>
                        )}
                      </td>
                      <td className="px-3 py-2 text-xs">
                        {r.hasCheckIn ? (
                          <div className="space-y-1">
                            <div className="text-neutral-700">{r.returnAtText}</div>
                            {r.returnLat != null && r.returnLng != null && (
                              <a
                                href={`https://uri.amap.com/marker?position=${r.returnLng},${r.returnLat}&name=返校打卡`}
                                target="_blank"
                                rel="noreferrer"
                                className="inline-flex items-center gap-1 text-primary-600 hover:underline"
                              >
                                <MapPin className="h-3.5 w-3.5" />
                                {Number(r.returnLat).toFixed(5)}, {Number(r.returnLng).toFixed(5)}
                              </a>
                            )}
                            {r.returnPhoto && (
                              <a
                                href={r.returnPhoto}
                                target="_blank"
                                rel="noreferrer"
                                className="flex items-center gap-1 text-primary-600 hover:underline"
                              >
                                <ImageIcon className="h-3.5 w-3.5" />
                                查看打卡照片
                              </a>
                            )}
                          </div>
                        ) : (
                          <span className="text-neutral-300">未打卡</span>
                        )}
                      </td>
                    </tr>
                  ))}
                  {filteredRecords.length === 0 && (
                    <tr>
                      <td colSpan={8} className="px-3 py-12 text-center text-sm text-neutral-400">
                        暂无已处理记录
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}

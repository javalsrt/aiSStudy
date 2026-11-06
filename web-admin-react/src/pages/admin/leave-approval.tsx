import { useCallback, useEffect, useMemo, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { CalendarCheck, Check, X, RefreshCw, Image as ImageIcon, ShieldCheck, Users2 } from 'lucide-react'
import { getLeavePending, reviewLeave, type LeaveItem } from '@/api/leave'

/**
 * 请假 / 临时外出审批（按班级分组）。
 * 权级：3 天内班主任审批；超过 3 天班主任初审 → 校级领导终审。
 * 管理员可跨班审批，审批结果会记录审批人身份。
 */
export function LeaveApprovalPage() {
  const [items, setItems] = useState<LeaveItem[]>([])
  const [loading, setLoading] = useState(true)
  const [comment, setComment] = useState<Record<number, string>>({})
  const [busy, setBusy] = useState<number | null>(null)
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setItems(await getLeavePending())
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '加载失败' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  /** 按班级分组 */
  const groups = useMemo(() => {
    const map = new Map<string, { className: string; headTeacher?: string | null; list: LeaveItem[] }>()
    for (const it of items) {
      const key = it.className || '未知班级'
      if (!map.has(key)) map.set(key, { className: key, headTeacher: it.headTeacherName, list: [] })
      map.get(key)!.list.push(it)
    }
    return Array.from(map.values()).sort((a, b) => a.className.localeCompare(b.className, 'zh'))
  }, [items])

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

  const stageBadge = (item: LeaveItem) => {
    if (item.status === 'PENDING_LEADER') {
      return <span className="rounded-md bg-amber-50 px-2 py-1 text-xs text-amber-700">待领导终审</span>
    }
    return <span className="rounded-md bg-primary-50 px-2 py-1 text-xs text-primary-700">待班主任审批</span>
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
            请假 3 天以内由本班班主任审批；超过 3 天班主任初审后由校级领导（如教导主任）终审。审批会记录审批人身份。
          </p>
        </div>
        <Button variant="outline" onClick={load} disabled={loading}>
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

      {loading && <div className="py-10 text-center text-sm text-neutral-400">加载中…</div>}

      {!loading && items.length === 0 && (
        <Card>
          <CardContent className="py-12 text-center text-sm text-neutral-400">暂无待审批申请</CardContent>
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
                  {item.leaderReviewerRole && (
                    <div className="md:col-span-2 text-xs text-neutral-500">
                      终审身份：{item.leaderReviewerRole}
                      {item.leaderComment ? ` · 意见：${item.leaderComment}` : ''}
                    </div>
                  )}
                </div>

                {item.returnPhoto && (
                  <a
                    href={item.returnPhoto}
                    target="_blank"
                    rel="noreferrer"
                    className="mt-2 inline-flex items-center gap-1 text-xs text-primary-600 hover:underline"
                  >
                    <ImageIcon className="h-3.5 w-3.5" />
                    查看返校打卡照片
                  </a>
                )}

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
    </div>
  )
}

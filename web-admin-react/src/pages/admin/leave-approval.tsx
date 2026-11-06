import { useCallback, useEffect, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { CalendarCheck, Check, X, RefreshCw, Image as ImageIcon } from 'lucide-react'
import { getLeavePending, reviewLeave, type LeaveItem } from '@/api/leave'

/**
 * 请假 / 临时外出审批。
 * 规则：请假 ≤3 天由班主任审批；>3 天班主任初审后进入「待学生处终审」。
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

  const review = async (item: LeaveItem, action: 'approve' | 'reject') => {
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

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold text-neutral-900">
            <CalendarCheck className="h-6 w-6 text-primary-500" />
            请假与外出审批
          </h1>
          <p className="mt-1 text-sm text-neutral-500">
            请假不超过 3 天由班主任审批；超过 3 天需班主任初审后由学生处终审。返校需学生定位拍照打卡。
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

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        {items.map((item) => (
          <Card key={item.id}>
            <CardHeader className="flex-row items-start justify-between space-y-0">
              <div>
                <CardTitle className="text-base">
                  {item.studentName || '未知学生'}
                  <span className="ml-2 text-xs font-normal text-neutral-400">
                    {item.className || '-'} · 学号 {item.studentNo || '-'}
                  </span>
                </CardTitle>
                <div className="mt-1 text-xs text-neutral-400">提交于 {item.createdText}</div>
              </div>
              <span
                className={`rounded-md px-2 py-1 text-xs ${
                  item.status === 'PENDING_LEADER'
                    ? 'bg-amber-50 text-amber-700'
                    : 'bg-primary-50 text-primary-700'
                }`}
              >
                {item.statusText}
              </span>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <div className="flex flex-wrap gap-2 text-xs">
                <span className="rounded bg-neutral-100 px-2 py-1">{item.typeText}</span>
                <span className="rounded bg-neutral-100 px-2 py-1">共 {item.days} 天</span>
                {item.parentNotified === 1 && (
                  <span className="rounded bg-emerald-50 px-2 py-1 text-emerald-700">已告知家长</span>
                )}
                {item.smsSent === 1 && (
                  <span className="rounded bg-emerald-50 px-2 py-1 text-emerald-700">已发短信</span>
                )}
              </div>

              <div className="grid grid-cols-1 gap-1.5 text-[13px] text-neutral-700">
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
                <div>
                  <span className="text-neutral-400">事由：</span>
                  {item.reason}
                </div>
                {item.teacherComment && (
                  <div>
                    <span className="text-neutral-400">班主任意见：</span>
                    {item.teacherComment}
                  </div>
                )}
              </div>

              {item.returnPhoto && (
                <a
                  href={item.returnPhoto}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 text-xs text-primary-600 hover:underline"
                >
                  <ImageIcon className="h-3.5 w-3.5" />
                  查看返校打卡照片
                </a>
              )}

              <input
                className="h-9 w-full rounded-md border border-neutral-200 px-2 text-xs focus:outline-none focus:ring-2 focus:ring-primary-500"
                placeholder="审批意见（可选）"
                value={comment[item.id] || ''}
                onChange={(e) => setComment({ ...comment, [item.id]: e.target.value })}
              />

              <div className="flex justify-end gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="h-8 px-3 text-xs text-danger"
                  disabled={busy === item.id}
                  onClick={() => review(item, 'reject')}
                >
                  <X className="mr-1 h-4 w-4" />
                  驳回
                </Button>
                <Button
                  size="sm"
                  className="h-8 px-3 text-xs"
                  disabled={busy === item.id}
                  onClick={() => review(item, 'approve')}
                >
                  <Check className="mr-1 h-4 w-4" />
                  {item.status === 'PENDING_LEADER' ? '终审通过' : '通过'}
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )
}

import { useEffect, useState, type ReactNode } from 'react'
import { Eye, History, RefreshCw, RotateCcw, Search } from 'lucide-react'
import {
  getAssistantRecords,
  restoreAssistantRecord,
} from '@/api/assistant'
import { useAuthStore } from '@/store/auth'
import { useDialog } from '@/hooks/use-dialog'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Pagination } from '@/components/ui/pagination'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import type { AssistantRecord } from '@/types/assistant'

const PAGE_SIZE = 15

const STATUS_META: Record<string, { label: string; className: string }> = {
  success: { label: '成功', className: 'bg-emerald-50 text-emerald-700' },
  failed: { label: '失败', className: 'bg-red-50 text-red-600' },
  pending_confirm: { label: '待确认', className: 'bg-amber-50 text-amber-700' },
  canceled: { label: '已取消', className: 'bg-neutral-100 text-ink-secondary' },
}

const ACTION_OPTIONS = [
  { value: 'all', label: '全部操作' },
  { value: 'query', label: '查询' },
  { value: 'create', label: '新增' },
  { value: 'update', label: '修改' },
  { value: 'delete', label: '删除' },
  { value: 'review', label: '审批' },
  { value: 'restore', label: '恢复' },
]

const STATUS_OPTIONS = [
  { value: 'all', label: '全部状态' },
  { value: 'success', label: '成功' },
  { value: 'failed', label: '失败' },
  { value: 'pending_confirm', label: '待确认' },
  { value: 'canceled', label: '已取消' },
]

interface QueryState {
  page: number
  keyword: string
  status: string
  actionType: string
}

function errorMessage(error: unknown) {
  const e = error as {
    response?: { data?: { error?: string; message?: string } }
    message?: string
  }
  return e?.response?.data?.error || e?.response?.data?.message || e?.message || '请求失败，请稍后再试'
}

/**
 * AI 操作记录：管理员看全部，教师看自己。
 * 对软删除的学生/教师记录提供「恢复」操作。
 */
export function AiRecordsPage() {
  const user = useAuthStore((state) => state.user)
  const { confirm, DialogComponent } = useDialog()
  const [list, setList] = useState<AssistantRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [restoringId, setRestoringId] = useState<number | null>(null)
  const [detail, setDetail] = useState<AssistantRecord | null>(null)
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)
  const [draft, setDraft] = useState({ keyword: '', status: 'all', actionType: 'all' })
  const [query, setQuery] = useState<QueryState>({
    page: 1,
    keyword: '',
    status: 'all',
    actionType: 'all',
  })
  const [refreshKey, setRefreshKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setMessage(null)
    getAssistantRecords({
      pageNum: query.page,
      pageSize: PAGE_SIZE,
      keyword: query.keyword || undefined,
      status: query.status === 'all' ? undefined : query.status,
      actionType: query.actionType === 'all' ? undefined : query.actionType,
    })
      .then((data) => {
        if (cancelled) return
        setList(data.list || [])
        setTotal(data.total || 0)
      })
      .catch((error) => {
        if (!cancelled) setMessage({ type: 'err', text: errorMessage(error) })
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [query, refreshKey])

  const applyFilters = () => {
    setQuery({ page: 1, ...draft })
  }

  const resetFilters = () => {
    const empty = { keyword: '', status: 'all', actionType: 'all' }
    setDraft(empty)
    setQuery({ page: 1, ...empty })
  }

  const handleRestore = async (record: AssistantRecord) => {
    const targetLabel = record.targetType === 'teacher' ? '教师' : '学生'
    const ok = await confirm({
      title: `恢复${targetLabel}账号`,
      description: `确定恢复${targetLabel}「${record.targetName || '未知'}」吗？恢复后账号可以重新登录。`,
      confirmText: '确认恢复',
    })
    if (!ok) return
    setRestoringId(record.id)
    setMessage(null)
    try {
      const result = await restoreAssistantRecord(record.id)
      setMessage({ type: 'ok', text: result.message })
      setRefreshKey((value) => value + 1)
    } catch (error) {
      setMessage({ type: 'err', text: errorMessage(error) })
    } finally {
      setRestoringId(null)
    }
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-title-2-semibold text-ink-primary">AI 操作记录</h1>
          <p className="mt-1 text-caption-1-regular text-ink-tertiary">
            {user?.role === 'admin'
              ? '查看全校 AI 助手的查询、修改与删除记录；被删除的学生/教师可在这里恢复。'
              : '查看你自己发起的 AI 操作；被你删除的学生可在这里恢复。'}
          </p>
        </div>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() => setRefreshKey((value) => value + 1)}
        >
          <RefreshCw className={cn('h-3.5 w-3.5', loading && 'animate-spin')} />
          刷新
        </Button>
      </div>

      <Card className="overflow-hidden">
        <div className="border-b border-line-separator p-4">
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative min-w-[220px] flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-tertiary" />
              <Input
                value={draft.keyword}
                onChange={(event) => setDraft((value) => ({ ...value, keyword: event.target.value }))}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') applyFilters()
                }}
                placeholder="搜索操作人、用户指令、操作或结果"
                className="h-9 pl-9"
              />
            </div>
            <Select
              value={draft.status}
              onChange={(event) => setDraft((value) => ({ ...value, status: event.target.value }))}
              className="w-[128px]"
              aria-label="状态筛选"
            >
              {STATUS_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
            <Select
              value={draft.actionType}
              onChange={(event) => setDraft((value) => ({ ...value, actionType: event.target.value }))}
              className="w-[128px]"
              aria-label="操作类型筛选"
            >
              {ACTION_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
            <Button type="button" size="sm" onClick={applyFilters}>
              <Search className="h-3.5 w-3.5" />
              查询
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={resetFilters}>
              重置
            </Button>
          </div>

          {message && (
            <div
              className={cn(
                'mt-3 rounded-xl px-3 py-2 text-caption-1-regular',
                message.type === 'ok'
                  ? 'bg-emerald-50 text-emerald-700'
                  : 'bg-red-50 text-red-600'
              )}
            >
              {message.text}
            </div>
          )}
        </div>

        <div className="overflow-x-auto">
          <table className="w-full min-w-[1080px] border-collapse text-body-2-regular">
            <thead>
              <tr className="border-b border-line-separator text-left text-caption-1-medium text-ink-tertiary">
                <th className="px-4 py-3 font-medium">时间</th>
                <th className="px-4 py-3 font-medium">操作人</th>
                <th className="px-4 py-3 font-medium">用户指令</th>
                <th className="px-4 py-3 font-medium">AI 操作</th>
                <th className="px-4 py-3 font-medium">状态</th>
                <th className="px-4 py-3 font-medium">结果摘要</th>
                <th className="px-4 py-3 font-medium">耗时</th>
                <th className="px-4 py-3 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-separator">
              {loading ? (
                <SkeletonRows />
              ) : list.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-4 py-16 text-center">
                    <div className="flex flex-col items-center gap-2 text-ink-tertiary">
                      <History className="h-7 w-7" />
                      <span className="text-caption-1-regular">暂无操作记录</span>
                    </div>
                  </td>
                </tr>
              ) : (
                list.map((record) => {
                  const status = STATUS_META[record.status || ''] || {
                    label: record.status || '-',
                    className: 'bg-neutral-100 text-ink-secondary',
                  }
                  const isDelete = record.actionName?.endsWith('.delete')
                  return (
                    <tr key={record.id} className="transition-colors hover:bg-neutral-50/70">
                      <td className="whitespace-nowrap px-4 py-3 text-caption-1-regular text-ink-secondary">
                        {record.createdAt || '-'}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3">
                        <div className="text-caption-1-medium text-ink-primary">
                          {record.operatorName || record.operatorUsername || `用户 #${record.userId}`}
                        </div>
                        <div className="text-[11px] text-ink-tertiary">
                          {record.userRole === 'ADMIN' ? '管理员' : '教师'}
                        </div>
                      </td>
                      <td className="max-w-[220px] px-4 py-3 text-caption-1-regular text-ink-secondary">
                        <div className="truncate" title={record.userMessage || ''}>
                          {record.userMessage || '-'}
                        </div>
                      </td>
                      <td className="whitespace-nowrap px-4 py-3">
                        <div className="text-caption-1-medium text-ink-primary">
                          {record.actionLabel || 'AI 对话'}
                        </div>
                        {record.actionName && (
                          <div className="text-[11px] text-ink-tertiary">{record.actionName}</div>
                        )}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3">
                        <span className={cn('inline-flex rounded-full px-2 py-0.5 text-caption-2-medium', status.className)}>
                          {status.label}
                        </span>
                      </td>
                      <td className="max-w-[260px] px-4 py-3 text-caption-1-regular text-ink-secondary">
                        <div className="truncate" title={record.summary || ''}>
                          {record.summary || '-'}
                        </div>
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-caption-1-regular text-ink-tertiary">
                        {record.durationMs == null ? '-' : `${record.durationMs} ms`}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          {record.restorable && (
                            <Button
                              type="button"
                              variant="secondary"
                              size="xs"
                              disabled={restoringId === record.id}
                              onClick={() => handleRestore(record)}
                            >
                              <RotateCcw className={cn('h-3 w-3', restoringId === record.id && 'animate-spin')} />
                              恢复
                            </Button>
                          )}
                          {!record.restorable && record.restored && (
                            <span className="inline-flex items-center rounded-full bg-emerald-50 px-2 py-0.5 text-caption-2-medium text-emerald-700">
                              已恢复
                            </span>
                          )}
                          {!record.restorable && !record.restored && isDelete && record.restoreReason && (
                            <span
                              className="max-w-[110px] truncate text-[11px] text-ink-tertiary"
                              title={record.restoreReason}
                            >
                              不可恢复
                            </span>
                          )}
                          <Button
                            type="button"
                            variant="ghost"
                            size="xs"
                            onClick={() => setDetail(record)}
                          >
                            <Eye className="h-3 w-3" />
                            详情
                          </Button>
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line-separator px-4 py-3">
          <div className="text-caption-1-regular text-ink-tertiary">共 {total} 条记录</div>
          <Pagination
            page={query.page}
            totalPages={totalPages}
            onChange={(page) => setQuery((value) => ({ ...value, page }))}
          />
        </div>
      </Card>

      <Dialog open={Boolean(detail)} onOpenChange={(open) => !open && setDetail(null)}>
        <DialogContent className="max-h-[82vh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>操作详情</DialogTitle>
            <DialogDescription>
              #{detail?.id} · {detail?.createdAt || '-'}
            </DialogDescription>
          </DialogHeader>
          {detail && (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3 text-caption-1-regular">
                <DetailItem label="操作人" value={`${detail.operatorName || '-'}（${detail.userRole === 'ADMIN' ? '管理员' : '教师'}）`} />
                <DetailItem label="状态" value={STATUS_META[detail.status || '']?.label || detail.status || '-'} />
                <DetailItem label="AI 操作" value={detail.actionLabel || detail.actionName || 'AI 对话'} />
                <DetailItem label="耗时" value={detail.durationMs == null ? '-' : `${detail.durationMs} ms`} />
              </div>
              <DetailBlock label="用户指令">{detail.userMessage || '-'}</DetailBlock>
              <DetailBlock label="结果摘要">{detail.summary || '-'}</DetailBlock>
              <div>
                <div className="mb-1.5 text-caption-1-medium text-ink-tertiary">执行参数</div>
                <pre className="max-h-56 overflow-auto rounded-xl bg-neutral-100 p-3 text-[11px] leading-5 text-ink-secondary">
                  {JSON.stringify(detail.args || {}, null, 2)}
                </pre>
              </div>
              {(detail.errorCode || detail.sessionId) && (
                <div className="grid grid-cols-2 gap-3 text-caption-1-regular">
                  <DetailItem label="错误码" value={detail.errorCode || '-'} />
                  <DetailItem label="会话 ID" value={detail.sessionId || '-'} />
                </div>
              )}
            </div>
          )}
          <DialogFooter>
            <Button type="button" variant="secondary" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {DialogComponent}
    </div>
  )
}

function SkeletonRows() {
  return (
    <>
      {Array.from({ length: 6 }).map((_, index) => (
        <tr key={index}>
          {Array.from({ length: 8 }).map((__, cell) => (
            <td key={cell} className="px-4 py-3">
              <div className="h-3 animate-pulse rounded-full bg-neutral-100" />
            </td>
          ))}
        </tr>
      ))}
    </>
  )
}

function DetailItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-line-separator px-3 py-2">
      <div className="text-[11px] text-ink-tertiary">{label}</div>
      <div className="mt-0.5 break-all text-caption-1-medium text-ink-primary">{value}</div>
    </div>
  )
}

function DetailBlock({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <div className="mb-1.5 text-caption-1-medium text-ink-tertiary">{label}</div>
      <div className="whitespace-pre-wrap rounded-xl border border-line-separator px-3 py-2 text-caption-1-regular leading-6 text-ink-primary">
        {children}
      </div>
    </div>
  )
}

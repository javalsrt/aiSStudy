import { useCallback, useEffect, useMemo, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { School, Plus, Save, Trash2, Upload, Info, Search, Power } from 'lucide-react'
import {
  bulkImportSchoolInfo,
  deleteSchoolInfo,
  getSchoolInfoList,
  saveSchoolInfo,
  toggleSchoolInfo,
  type SchoolInfoItem,
} from '@/api/school-info'

/**
 * 校园信息设置：管理员维护校规/校内制度，作为 AI 助手的**底层知识**。
 * 学生提问校内问题时，助手优先命中这里的条目作答。
 */
export function SchoolInfoPage() {
  const [items, setItems] = useState<SchoolInfoItem[]>([])
  const [categories, setCategories] = useState<string[]>([])
  const [category, setCategory] = useState('')
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)

  // 编辑表单
  const [editingId, setEditingId] = useState<number | null>(null)
  const [form, setForm] = useState<Partial<SchoolInfoItem>>({
    category: '校园概况',
    title: '',
    content: '',
    keywords: '',
    sort: 0,
    status: 1,
  })

  // 批量导入
  const [bulkOpen, setBulkOpen] = useState(false)
  const [bulkText, setBulkText] = useState('')
  const [bulkCategory, setBulkCategory] = useState('规章制度')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getSchoolInfoList({ category: category || undefined })
      setItems(data.items || [])
      setCategories(data.categories || [])
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '加载失败' })
    } finally {
      setLoading(false)
    }
  }, [category])

  useEffect(() => {
    load()
  }, [load])

  const filtered = useMemo(() => {
    const k = keyword.trim()
    if (!k) return items
    return items.filter(
      (i) => i.title.includes(k) || (i.content || '').includes(k) || (i.keywords || '').includes(k)
    )
  }, [items, keyword])

  const startEdit = (item: SchoolInfoItem) => {
    setEditingId(item.id)
    setForm({ ...item })
    setMessage(null)
  }

  const startNew = () => {
    setEditingId(null)
    setForm({
      category: category || '校园概况',
      title: '',
      content: '',
      keywords: '',
      sort: 0,
      status: 1,
    })
    setMessage(null)
  }

  const submit = async () => {
    if (!form.title?.trim() || !form.content?.trim()) {
      setMessage({ type: 'err', text: '标题和内容不能为空' })
      return
    }
    setSaving(true)
    try {
      const res = await saveSchoolInfo({ ...form, id: editingId ?? undefined })
      setMessage({ type: 'ok', text: res.msg })
      setEditingId(res.id)
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '保存失败' })
    } finally {
      setSaving(false)
    }
  }

  const remove = async (item: SchoolInfoItem) => {
    if (!window.confirm(`确定删除「${item.title}」？删除后 AI 将不再使用该内容。`)) return
    try {
      await deleteSchoolInfo(item.id)
      if (editingId === item.id) startNew()
      await load()
      setMessage({ type: 'ok', text: '已删除' })
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '删除失败' })
    }
  }

  const toggle = async (item: SchoolInfoItem) => {
    try {
      await toggleSchoolInfo(item.id, item.status === 1 ? 0 : 1)
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '操作失败' })
    }
  }

  const runBulk = async () => {
    if (!bulkText.trim()) {
      setMessage({ type: 'err', text: '请粘贴要导入的内容' })
      return
    }
    setSaving(true)
    try {
      const res = await bulkImportSchoolInfo({ category: bulkCategory, text: bulkText })
      setMessage({ type: 'ok', text: res.msg + '（可继续编辑或启用）' })
      setBulkOpen(false)
      setBulkText('')
      await load()
    } catch (e: any) {
      setMessage({ type: 'err', text: e?.message || '导入失败' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold text-neutral-900">
            <School className="h-6 w-6 text-primary-500" />
            校园信息设置
          </h1>
          <p className="mt-1 text-sm text-neutral-500">
            维护学校简介、校规制度、作息宿舍、奖助政策等。AI 助手回答校内问题时**优先使用这里的内容**。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={() => setBulkOpen((v) => !v)}>
            <Upload className="mr-1 h-4 w-4" />
            批量导入
          </Button>
          <Button onClick={startNew}>
            <Plus className="mr-1 h-4 w-4" />
            新增条目
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

      {bulkOpen && (
        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">批量导入</CardTitle>
            <span className="text-xs text-neutral-400">
              按「## 标题」分段，每段正文自动成为一条知识
            </span>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex flex-wrap items-center gap-3">
              <Label className="text-xs text-neutral-500">分类</Label>
              <select
                className="h-9 rounded-md border border-neutral-200 px-2 text-sm"
                value={bulkCategory}
                onChange={(e) => setBulkCategory(e.target.value)}
              >
                {categories.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <textarea
              className="h-48 w-full rounded-lg border border-neutral-200 p-3 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
              placeholder={'## 学生请假管理办法\n学生请假须提前向班主任提交书面申请……\n\n## 宿舍管理规定\n宿舍 22:30 熄灯……'}
              value={bulkText}
              onChange={(e) => setBulkText(e.target.value)}
            />
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setBulkOpen(false)}>
                取消
              </Button>
              <Button onClick={runBulk} disabled={saving}>
                <Upload className="mr-1 h-4 w-4" />
                {saving ? '导入中…' : '开始导入'}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[360px_1fr]">
        {/* 左：条目列表 */}
        <Card className="h-fit">
          <CardHeader className="space-y-3">
            <CardTitle className="text-base">知识条目（{filtered.length}）</CardTitle>
            <div className="flex gap-2">
              <select
                className="h-9 rounded-md border border-neutral-200 px-2 text-sm"
                value={category}
                onChange={(e) => setCategory(e.target.value)}
              >
                <option value="">全部分类</option>
                {categories.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <div className="relative flex-1">
                <Search className="absolute left-2 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-400" />
                <input
                  className="h-9 w-full rounded-md border border-neutral-200 pl-8 pr-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                  placeholder="搜索标题/内容"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                />
              </div>
            </div>
          </CardHeader>
          <CardContent className="max-h-[560px] space-y-2 overflow-y-auto">
            {loading && <div className="py-6 text-center text-xs text-neutral-400">加载中…</div>}
            {!loading && filtered.length === 0 && (
              <div className="py-8 text-center text-xs text-neutral-400">
                暂无内容，点击「新增条目」或「批量导入」
              </div>
            )}
            {filtered.map((item) => (
              <div
                key={item.id}
                className={`cursor-pointer rounded-lg border p-3 transition-colors ${
                  editingId === item.id
                    ? 'border-primary-300 bg-primary-50/60'
                    : 'border-neutral-200 hover:border-neutral-300 hover:bg-neutral-50'
                }`}
                onClick={() => startEdit(item)}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="truncate text-sm font-medium text-neutral-900">{item.title}</div>
                    <div className="mt-0.5 text-[11px] text-neutral-400">
                      {item.category} · {item.status === 1 ? '已启用' : '已停用'}
                      {item.updatedAt ? ` · ${item.updatedAt}` : ''}
                    </div>
                  </div>
                  <div className="flex shrink-0 items-center gap-1">
                    <button
                      type="button"
                      title={item.status === 1 ? '停用' : '启用'}
                      onClick={(e) => {
                        e.stopPropagation()
                        toggle(item)
                      }}
                      className={`rounded p-1 ${
                        item.status === 1 ? 'text-emerald-500 hover:bg-emerald-50' : 'text-neutral-400 hover:bg-neutral-100'
                      }`}
                    >
                      <Power className="h-3.5 w-3.5" />
                    </button>
                    <button
                      type="button"
                      title="删除"
                      onClick={(e) => {
                        e.stopPropagation()
                        remove(item)
                      }}
                      className="rounded p-1 text-neutral-300 hover:bg-red-50 hover:text-red-500"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
                <p className="mt-1 line-clamp-2 text-xs leading-5 text-neutral-500">{item.content}</p>
              </div>
            ))}
          </CardContent>
        </Card>

        {/* 右：编辑表单 */}
        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">{editingId ? '编辑条目' : '新增条目'}</CardTitle>
            <div className="flex items-center gap-3 text-xs text-neutral-400">
              {editingId && <span>ID {editingId}</span>}
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
              <div className="space-y-1.5">
                <Label className="text-xs text-neutral-500">分类</Label>
                <select
                  className="h-9 w-full rounded-md border border-neutral-200 px-2 text-sm"
                  value={form.category || '校园概况'}
                  onChange={(e) => setForm({ ...form, category: e.target.value })}
                >
                  {categories.map((c) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                </select>
              </div>
              <div className="space-y-1.5 md:col-span-2">
                <Label className="text-xs text-neutral-500">标题</Label>
                <input
                  className="h-9 w-full rounded-md border border-neutral-200 px-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                  placeholder="如：学生请假管理办法"
                  value={form.title || ''}
                  onChange={(e) => setForm({ ...form, title: e.target.value })}
                />
              </div>
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs text-neutral-500">
                关键词（逗号分隔，帮助 AI 命中，如：请假,病假,销假）
              </Label>
              <input
                className="h-9 w-full rounded-md border border-neutral-200 px-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
                value={form.keywords || ''}
                onChange={(e) => setForm({ ...form, keywords: e.target.value })}
              />
            </div>

            <div className="space-y-1.5">
              <Label className="text-xs text-neutral-500">正文</Label>
              <textarea
                className="h-72 w-full rounded-lg border border-neutral-200 p-3 text-sm leading-6 focus:outline-none focus:ring-2 focus:ring-primary-500"
                placeholder="把学校的正式规定粘贴到这里（纯文本，保留换行即可）"
                value={form.content || ''}
                onChange={(e) => setForm({ ...form, content: e.target.value })}
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label className="text-xs text-neutral-500">排序（越小越前）</Label>
                <input
                  type="number"
                  className="h-9 w-full rounded-md border border-neutral-200 px-2 text-sm"
                  value={form.sort ?? 0}
                  onChange={(e) => setForm({ ...form, sort: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-1.5">
                <Label className="text-xs text-neutral-500">状态</Label>
                <select
                  className="h-9 w-full rounded-md border border-neutral-200 px-2 text-sm"
                  value={form.status ?? 1}
                  onChange={(e) => setForm({ ...form, status: Number(e.target.value) })}
                >
                  <option value={1}>启用（参与 AI 回答）</option>
                  <option value={0}>停用</option>
                </select>
              </div>
            </div>

            <div className="flex items-start gap-2 rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-700">
              <Info className="mt-0.5 h-4 w-4 flex-shrink-0" />
              <span>
                校园信息是 AI 的**底层知识**：学生问「宿舍几点熄灯」「请假流程」等问题时，助手会优先且仅依据这里的内容回答；
                未收录时会明确告知「暂未收录，请咨询班主任」，不会编造。
              </span>
            </div>

            <div className="flex justify-end gap-2">
              {editingId && (
                <Button variant="outline" onClick={startNew}>
                  新建
                </Button>
              )}
              <Button onClick={submit} disabled={saving}>
                <Save className="mr-1 h-4 w-4" />
                {saving ? '保存中…' : editingId ? '保存修改' : '创建条目'}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}

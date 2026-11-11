import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Select } from './select'
import {
  flexRender,
  getCoreRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type PaginationState,
  type RowSelectionState,
  type SortingState,
  type Updater,
} from '@tanstack/react-table'
import { ChevronDown, ChevronUp, ChevronsUpDown, Search, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Checkbox } from './checkbox'
import { Pagination } from './pagination'
import { SegmentedControl } from './segmented-control'
import { Skeleton } from './skeleton'

/** 列元信息：控制对齐与单元格样式（BoardUI 数据表的列配置约定） */
export interface DataTableColumnMeta {
  align?: 'left' | 'right'
  className?: string
  headerClassName?: string
}

interface DataTableProps<TData> {
  columns: ColumnDef<TData, any>[]
  data: TData[]
  getRowId?: (row: TData) => string
  loading?: boolean
  error?: string
  emptyText?: string
  /** 工具栏搜索框（受控） */
  search?: { value: string; onChange: (value: string) => void; placeholder?: string }
  /** 工具栏右侧插槽（视图切换、导出等） */
  toolbarRight?: ReactNode
  /** 行选择（含表头全选 / 半选态） */
  enableSelection?: boolean
  /** 排序（客户端排序；服务端分页场景请按需关闭） */
  enableSorting?: boolean
  pagination: PaginationState
  onPaginationChange: (next: PaginationState) => void
  /** 传入即为服务端分页：不再对数据切片 */
  pageCount?: number
  totalRows: number
  pageSizeOptions?: number[]
  density?: 'normal' | 'compact'
  onDensityChange?: (density: 'normal' | 'compact') => void
  /** 切换标签/筛选时清空选择 */
  resetSelectionKey?: string | number
}

/**
 * 数据表格（移植 BoardUI Data Table）
 * TanStack Table 驱动：可排序列（自定义 V 形图标）、行选择（表头不确定态）、
 * 工具栏筛选、分页页脚（页码 + 每页条数）、正常/紧凑密度。
 */
export function DataTable<TData>({
  columns,
  data,
  getRowId,
  loading = false,
  error = '',
  emptyText = '暂无数据',
  search,
  toolbarRight,
  enableSelection = false,
  enableSorting = true,
  pagination,
  onPaginationChange,
  pageCount,
  totalRows,
  pageSizeOptions = [10, 20, 50],
  density = 'normal',
  onDensityChange,
  resetSelectionKey,
}: DataTableProps<TData>) {
  const [sorting, setSorting] = useState<SortingState>([])
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({})

  // 切换标签/搜索条件后清空选择，避免跨数据集误操作
  useEffect(() => {
    setRowSelection({})
  }, [resetSelectionKey])

  const manualPagination = typeof pageCount === 'number'

  const tableColumns = useMemo<ColumnDef<TData, any>[]>(() => {
    if (!enableSelection) return columns
    const selectColumn: ColumnDef<TData, any> = {
      id: '__select',
      size: 40,
      enableSorting: false,
      header: ({ table }) => (
        <Checkbox
          aria-label="全选当前页"
          checked={
            table.getIsAllPageRowsSelected()
              ? true
              : table.getIsSomePageRowsSelected()
                ? 'indeterminate'
                : false
          }
          onCheckedChange={(value) => table.toggleAllPageRowsSelected(value === true)}
        />
      ),
      cell: ({ row }) => (
        <Checkbox
          aria-label="选择此行"
          checked={row.getIsSelected()}
          onCheckedChange={(value) => row.toggleSelected(value === true)}
        />
      ),
    }
    return [selectColumn, ...columns]
  }, [columns, enableSelection])

  const table = useReactTable({
    data,
    columns: tableColumns,
    state: { sorting, rowSelection, pagination },
    getRowId,
    enableRowSelection: enableSelection,
    enableSorting,
    autoResetPageIndex: false,
    manualPagination,
    pageCount: manualPagination ? pageCount : undefined,
    onSortingChange: setSorting,
    onRowSelectionChange: setRowSelection,
    onPaginationChange: (updater: Updater<PaginationState>) => {
      const next = typeof updater === 'function' ? updater(pagination) : updater
      onPaginationChange(next)
    },
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getPaginationRowModel: manualPagination ? undefined : getPaginationRowModel(),
  })

  const selectedCount = table.getSelectedRowModel().rows.length
  const totalPages = manualPagination ? (pageCount as number) : table.getPageCount()
  const colCount = table.getAllLeafColumns().length
  const cellPadding = density === 'compact' ? 'py-2' : 'py-3'
  const showToolbar = Boolean(search || toolbarRight || onDensityChange)

  return (
    <div className="overflow-hidden rounded-3xl border border-line-separator bg-surface-primary shadow-card">
      {/* 工具栏：搜索 + 筛选 + 视图/密度切换 */}
      {showToolbar && (
        <div className="flex flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 items-center gap-2">
            {search && (
              <div className="relative w-full sm:w-80">
                <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-tertiary" />
                <input
                  value={search.value}
                  onChange={(e) => search.onChange(e.target.value)}
                  placeholder={search.placeholder}
                  className="h-9 w-full rounded-[10px] bg-surface-secondary pl-9 pr-9 text-body-2-regular text-ink-primary outline-hidden transition-shadow placeholder:text-ink-tertiary focus-visible:bg-surface-primary focus-visible:shadow-focus-double"
                />
                {search.value && (
                  <button
                    type="button"
                    aria-label="清空搜索"
                    onClick={() => search.onChange('')}
                    className="absolute right-2 top-1/2 flex h-5 w-5 -translate-y-1/2 items-center justify-center rounded-full text-ink-tertiary transition-colors hover:bg-neutral-200 hover:text-ink-primary"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>
            )}
          </div>
          <div className="flex items-center gap-2">
            {toolbarRight}
            {onDensityChange && (
              <SegmentedControl
                size="sm"
                ariaLabel="表格密度"
                value={density}
                onChange={onDensityChange}
                options={[
                  { value: 'normal', label: '正常' },
                  { value: 'compact', label: '紧凑' },
                ]}
              />
            )}
          </div>
        </div>
      )}

      {/* 表格主体 */}
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead className="bg-surface-secondary">
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id}>
                {headerGroup.headers.map((header) => {
                  const meta = header.column.columnDef.meta as DataTableColumnMeta | undefined
                  const canSort = header.column.getCanSort()
                  const sorted = header.column.getIsSorted()
                  return (
                    <th
                      key={header.id}
                      className={cn(
                        'whitespace-nowrap px-4 py-2.5 text-caption-1-medium text-ink-secondary',
                        meta?.align === 'right' ? 'text-right' : 'text-left',
                        meta?.headerClassName
                      )}
                    >
                      {header.isPlaceholder ? null : canSort ? (
                        <button
                          type="button"
                          onClick={header.column.getToggleSortingHandler()}
                          className={cn(
                            'inline-flex items-center gap-1 transition-colors hover:text-ink-primary',
                            meta?.align === 'right' && 'flex-row-reverse'
                          )}
                        >
                          {flexRender(header.column.columnDef.header, header.getContext())}
                          <span className="text-ink-tertiary">
                            {sorted === 'desc' ? (
                              <ChevronDown className="h-3.5 w-3.5" />
                            ) : sorted === 'asc' ? (
                              <ChevronUp className="h-3.5 w-3.5" />
                            ) : (
                              <ChevronsUpDown className="h-3.5 w-3.5 opacity-40" />
                            )}
                          </span>
                        </button>
                      ) : (
                        flexRender(header.column.columnDef.header, header.getContext())
                      )}
                    </th>
                  )
                })}
              </tr>
            ))}
          </thead>
          <tbody>
            {loading ? (
              Array.from({ length: 5 }).map((_, rowIndex) => (
                <tr key={`skeleton-${rowIndex}`} className="border-b border-line-separator last:border-0">
                  {Array.from({ length: colCount }).map((__, cellIndex) => (
                    <td key={cellIndex} className={cn('px-4 align-middle', cellPadding)}>
                      {cellIndex === 0 && enableSelection ? (
                        <Skeleton className="h-5 w-5 rounded-md" />
                      ) : (
                        <Skeleton
                          className={cn(
                            'h-4',
                            cellIndex === 0
                              ? 'w-3/4'
                              : cellIndex === colCount - 1
                                ? 'ml-auto w-1/2'
                                : 'w-2/3'
                          )}
                        />
                      )}
                    </td>
                  ))}
                </tr>
              ))
            ) : error ? (
              <tr>
                <td colSpan={colCount} className="px-6 py-10 text-center text-body-2-regular text-danger">
                  {error}
                </td>
              </tr>
            ) : table.getRowModel().rows.length === 0 ? (
              <tr>
                <td colSpan={colCount} className="px-6 py-10 text-center text-body-2-regular text-ink-tertiary">
                  {emptyText}
                </td>
              </tr>
            ) : (
              table.getRowModel().rows.map((row) => (
                <tr
                  key={row.id}
                  className={cn(
                    'border-b border-line-separator transition-colors last:border-0',
                    row.getIsSelected() ? 'bg-primary-50/60' : 'hover:bg-surface-hover'
                  )}
                >
                  {row.getVisibleCells().map((cell) => {
                    const meta = cell.column.columnDef.meta as DataTableColumnMeta | undefined
                    return (
                      <td
                        key={cell.id}
                        className={cn(
                          'px-4 align-middle',
                          cellPadding,
                          meta?.align === 'right' ? 'text-right' : 'text-left',
                          meta?.className
                        )}
                      >
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    )
                  })}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* 分页页脚 */}
      <div className="flex flex-col gap-3 border-t border-line-separator px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-2 text-body-2-regular text-ink-tertiary">
          {loading ? (
            <Skeleton className="h-4 w-16" />
          ) : enableSelection && selectedCount > 0 ? (
            <>
              <span className="inline-flex items-center rounded-md bg-neutral-150 px-2 py-0.5 text-caption-1-medium text-ink-secondary">
                已选 {selectedCount} 项
              </span>
              <button
                type="button"
                onClick={() => table.resetRowSelection()}
                className="text-caption-1-medium text-ink-tertiary transition-colors hover:text-ink-primary"
              >
                清除
              </button>
            </>
          ) : (
            <span>共 {totalRows} 条</span>
          )}
        </div>
        <div className="flex items-center gap-3">
          {loading ? (
            <Skeleton className="h-8 w-32 rounded-lg" />
          ) : (
            <>
              <label className="flex items-center gap-1.5 text-body-2-regular text-ink-tertiary">
                每页
                <Select
                  aria-label="每页条数"
                  value={pagination.pageSize}
                  onChange={(e) =>
                    onPaginationChange({ pageIndex: 0, pageSize: Number(e.target.value) })
                  }
                  className="h-8 rounded-lg bg-surface-secondary px-2 text-body-2-regular text-ink-secondary outline-hidden transition-shadow focus-visible:shadow-focus-double"
                >
                  {pageSizeOptions.map((size) => (
                    <option key={size} value={size}>
                      {size}
                    </option>
                  ))}
                </Select>
              </label>
              <Pagination
                page={pagination.pageIndex + 1}
                totalPages={Math.max(1, totalPages)}
                onChange={(page) => onPaginationChange({ ...pagination, pageIndex: page - 1 })}
              />
            </>
          )}
        </div>
      </div>
    </div>
  )
}

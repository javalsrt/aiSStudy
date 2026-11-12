import * as React from 'react'
import * as SelectPrimitive from '@radix-ui/react-select'
import { ChevronDown } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * Select（移植 BoardUI）
 * 触发器：10px 圆角、浅灰底、1px 发丝边、14px/500 文字、16px 下拉箭头。
 * 面板：16px 圆角、8px 内边距、最大 240px 高，150ms 淡入 + 95% 缩放。
 * 选项：36px 高、10px 圆角，悬停/选中使用浅灰背景。
 *
 * 兼容原生 <select> 写法：保留 <option> / <optgroup> 子元素与
 * onChange={e => e.target.value} 的取值方式，便于平滑替换。
 */

const EMPTY_VALUE = '__bui_empty__'

interface ParsedOption {
  value: string
  label: React.ReactNode
  text: string
  disabled?: boolean
}

type ParsedEntry =
  | { kind: 'option'; option: ParsedOption }
  | { kind: 'group'; label: string }

function childrenToText(node: React.ReactNode): string {
  if (node === null || node === undefined || typeof node === 'boolean') return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(childrenToText).join('')
  if (React.isValidElement(node)) {
    return childrenToText((node.props as { children?: React.ReactNode }).children)
  }
  return ''
}

function collectEntries(children: React.ReactNode, out: ParsedEntry[]) {
  React.Children.forEach(children, (child) => {
    if (!React.isValidElement(child)) return
    if (child.type === 'option') {
      const props = child.props as {
        value?: string | number
        disabled?: boolean
        children?: React.ReactNode
      }
      const value = props.value === undefined ? childrenToText(props.children) : String(props.value)
      const text = childrenToText(props.children) || value
      out.push({
        kind: 'option',
        option: {
          value,
          label: props.children ?? value,
          text,
          disabled: props.disabled,
        },
      })
      return
    }
    if (child.type === 'optgroup') {
      const props = child.props as { label?: string; children?: React.ReactNode }
      if (props.label) out.push({ kind: 'group', label: props.label })
      collectEntries(props.children, out)
      return
    }
    if (child.type === React.Fragment) {
      collectEntries((child.props as { children?: React.ReactNode }).children, out)
    }
  })
}

/** 只保留旧 className 中的布局类，视觉样式统一由 BoardUI 规范接管 */
function pickLayoutClasses(className?: string) {
  if (!className) return ''
  return className
    .split(/\s+/)
    .filter((token) =>
      /^(?:w|min-w|max-w|h|min-h|max-h|m[trblxy]?|mt|mb|ml|mr|mx|my|flex|basis|shrink|grow|self|order)-/.test(
        token
      )
    )
    .join(' ')
}

const encode = (value: string) => (value === '' ? EMPTY_VALUE : value)
const decode = (value: string) => (value === EMPTY_VALUE ? '' : value)

export interface SelectProps {
  value?: string | number
  onChange?: (event: { target: { value: string } }) => void
  children?: React.ReactNode
  className?: string
  disabled?: boolean
  id?: string
  name?: string
  placeholder?: string
  title?: string
  'aria-label'?: string
}

export function Select({
  value,
  onChange,
  children,
  className,
  disabled,
  id,
  name,
  placeholder = '请选择',
  title,
  'aria-label': ariaLabel,
}: SelectProps) {
  const entries: ParsedEntry[] = []
  collectEntries(children, entries)
  const options = entries
    .filter((entry): entry is { kind: 'option'; option: ParsedOption } => entry.kind === 'option')
    .map((entry) => entry.option)

  const normalized = value === undefined || value === null ? '' : String(value)
  const selected = options.find((option) => option.value === normalized)
  const ghost = Boolean(className && /(?:bg-transparent|appearance-none)/.test(className))
  const compact = Boolean(className && /(?:^|\s)h-7(?:\s|$)/.test(className))

  return (
    <SelectPrimitive.Root
      value={encode(normalized)}
      onValueChange={(next) => onChange?.({ target: { value: decode(next) } })}
      disabled={disabled}
      name={name}
    >
      <SelectPrimitive.Trigger
        id={id}
        title={title}
        aria-label={ariaLabel}
        className={cn(
          'flex w-auto cursor-pointer items-center justify-between gap-1.5 rounded-[var(--radius-control)] border border-line-separator bg-neutral-100 px-2.5 text-body-2-medium text-ink-primary shadow-xs outline-hidden transition-[background-color,border-color,box-shadow] duration-200 hover:bg-neutral-150 focus-visible:border-neutral-300 focus-visible:bg-surface-primary data-[state=open]:border-neutral-300 data-[state=open]:bg-surface-primary disabled:cursor-not-allowed disabled:opacity-50',
          ghost
            ? 'h-8 border-transparent bg-transparent px-1 shadow-none hover:bg-neutral-100 focus-visible:border-transparent focus-visible:bg-neutral-100 data-[state=open]:border-transparent data-[state=open]:bg-neutral-100'
            : compact
              ? 'h-7 px-2 text-caption-1-medium'
              : 'h-9',
          pickLayoutClasses(className)
        )}
      >
        <SelectPrimitive.Value
          placeholder={placeholder}
          className="min-w-0 flex-1 truncate text-left"
        >
          {selected && selected.text ? (
            selected.label
          ) : (
            <span className="text-ink-tertiary">{placeholder}</span>
          )}
        </SelectPrimitive.Value>
        <SelectPrimitive.Icon asChild>
          <ChevronDown className="h-4 w-4 shrink-0 text-ink-tertiary" />
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>

      <SelectPrimitive.Portal>
        <SelectPrimitive.Content
          position="popper"
          sideOffset={4}
          className="relative z-[1000] max-h-60 w-max min-w-[var(--radix-select-trigger-width)] max-w-[320px] overflow-hidden rounded-2xl border border-line-separator bg-surface-primary p-2 text-ink-primary shadow-dropdown duration-150 data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=closed]:zoom-out-95 data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-95 data-[side=bottom]:slide-in-from-top-1 data-[side=top]:slide-in-from-bottom-1"
        >
          <SelectPrimitive.Viewport className="scrollbar-thin max-h-56 overflow-y-auto">
            {entries.map((entry, index) =>
              entry.kind === 'group' ? (
                <div
                  key={`group-${index}-${entry.label}`}
                  role="presentation"
                  className="px-2.5 pb-1 pt-2 text-caption-2-medium text-ink-tertiary"
                >
                  {entry.label}
                </div>
              ) : (
                <SelectPrimitive.Item
                  key={`${entry.option.value}-${index}`}
                  value={encode(entry.option.value)}
                  textValue={entry.option.text}
                  disabled={entry.option.disabled}
                  className="relative flex h-9 w-full cursor-pointer select-none items-center whitespace-nowrap rounded-[10px] px-2.5 text-body-2-medium text-ink-primary outline-hidden data-[disabled]:pointer-events-none data-[disabled]:opacity-50 data-[highlighted]:bg-neutral-150 data-[state=checked]:bg-neutral-150"
                >
                  <SelectPrimitive.ItemText>{entry.option.label}</SelectPrimitive.ItemText>
                </SelectPrimitive.Item>
              )
            )}
          </SelectPrimitive.Viewport>
        </SelectPrimitive.Content>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  )
}

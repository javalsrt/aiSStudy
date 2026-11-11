import { useState, useCallback } from 'react'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

interface ConfirmOptions {
  title?: string
  description?: string
  confirmText?: string
  cancelText?: string
  variant?: 'default' | 'danger'
}

interface AlertOptions {
  title?: string
  description?: string
  confirmText?: string
}

interface PromptOptions {
  title?: string
  description?: string
  defaultValue?: string
  confirmText?: string
  cancelText?: string
  placeholder?: string
}

export interface SelectOption {
  value: string
  label: string
}

interface SelectOptions {
  title?: string
  description?: string
  options: SelectOption[]
  cancelText?: string
}

type DialogState =
  | { type: 'confirm'; options: ConfirmOptions; resolve: (value: boolean) => void }
  | { type: 'alert'; options: AlertOptions; resolve: () => void }
  | { type: 'prompt'; options: PromptOptions; resolve: (value: string | null) => void }
  | { type: 'select'; options: SelectOptions; resolve: (value: string | null) => void }
  | null

export function useDialog() {
  const [state, setState] = useState<DialogState>(null)
  const [promptValue, setPromptValue] = useState('')

  const confirm = useCallback(
    (options: ConfirmOptions = {}) =>
      new Promise<boolean>((resolve) => {
        setState({ type: 'confirm', options, resolve })
      }),
    []
  )

  const alert = useCallback(
    (options: AlertOptions = {}) =>
      new Promise<void>((resolve) => {
        setState({ type: 'alert', options, resolve })
      }),
    []
  )

  const prompt = useCallback(
    (options: PromptOptions = {}) =>
      new Promise<string | null>((resolve) => {
        setPromptValue(options.defaultValue || '')
        setState({ type: 'prompt', options, resolve })
      }),
    []
  )

  const select = useCallback(
    (options: SelectOptions) =>
      new Promise<string | null>((resolve) => {
        setState({ type: 'select', options, resolve })
      }),
    []
  )

  const close = useCallback(() => {
    setState(null)
  }, [])

  const DialogComponent = state ? (
    <Dialog open={!!state} onOpenChange={(open) => !open && close()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{state.options.title || '提示'}</DialogTitle>
          {state.options.description && (
            <DialogDescription>{state.options.description}</DialogDescription>
          )}
        </DialogHeader>

        {state.type === 'prompt' && (
          <div className="py-2">
            <Input
              value={promptValue}
              onChange={(e) => setPromptValue(e.target.value)}
              placeholder={state.options.placeholder || ''}
              autoFocus
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  state.resolve(promptValue)
                  close()
                }
              }}
            />
          </div>
        )}

        {state.type === 'select' && (
          <div className="py-2 space-y-2 max-h-[300px] overflow-y-auto">
            {state.options.options.map((option) => (
              <button
                key={option.value}
                type="button"
                onClick={() => {
                  state.resolve(option.value)
                  close()
                }}
                className="w-full text-left px-4 py-3 rounded-lg border border-neutral-200 bg-surface-primary hover:bg-neutral-50 hover:border-neutral-300 transition-colors text-sm text-neutral-800"
              >
                {option.label}
              </button>
            ))}
          </div>
        )}

        <DialogFooter>
          {state.type === 'confirm' && (
            <>
              <Button variant="outline" onClick={() => { state.resolve(false); close() }}>
                {state.options.cancelText || '取消'}
              </Button>
              <Button
                variant={state.options.variant === 'danger' ? 'destructive' : 'default'}
                onClick={() => { state.resolve(true); close() }}
              >
                {state.options.confirmText || '确定'}
              </Button>
            </>
          )}
          {state.type === 'prompt' && (
            <>
              <Button variant="outline" onClick={() => { state.resolve(null); close() }}>
                {state.options.cancelText || '取消'}
              </Button>
              <Button onClick={() => { state.resolve(promptValue); close() }}>
                {state.options.confirmText || '确定'}
              </Button>
            </>
          )}
          {state.type === 'alert' && (
            <Button onClick={() => { state.resolve(); close() }}>
              {state.options.confirmText || '确定'}
            </Button>
          )}
          {state.type === 'select' && (
            <Button variant="outline" onClick={() => { state.resolve(null); close() }}>
              {state.options.cancelText || '取消'}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  ) : null

  return { alert, confirm, prompt, select, DialogComponent }
}

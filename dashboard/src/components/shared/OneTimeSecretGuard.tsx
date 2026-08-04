import { useCallback, useState } from 'react'
import { useBeforeUnload, useBlocker } from 'react-router-dom'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'

interface OneTimeSecretGuardProps {
  active: boolean
  title: string
  description: string
  onConfirmLeave?: () => boolean | Promise<boolean>
}

/**
 * Protects one-time credentials from both browser unloads and in-app navigation.
 * The native unload dialog is browser-owned; same-document navigation uses an
 * explicit, safe-default confirmation that keeps the user on the current page.
 */
export function OneTimeSecretGuard({
  active,
  title,
  description,
  onConfirmLeave,
}: OneTimeSecretGuardProps) {
  const blocker = useBlocker(active)
  const [confirming, setConfirming] = useState(false)

  useBeforeUnload(useCallback((event) => {
    if (!active) return
    event.preventDefault()
    event.returnValue = true
  }, [active]))

  const stayOnPage = () => {
    if (blocker.state === 'blocked') blocker.reset()
  }

  const leavePage = async () => {
    if (blocker.state !== 'blocked' || confirming) return
    setConfirming(true)

    let shouldLeave: boolean
    try {
      shouldLeave = onConfirmLeave ? await onConfirmLeave() : true
    } catch {
      shouldLeave = false
    }

    setConfirming(false)
    if (shouldLeave && blocker.state === 'blocked') blocker.proceed()
  }

  return (
    <ConfirmDialog
      open={blocker.state === 'blocked'}
      title={title}
      description={description}
      confirmLabel={confirming ? '正在处理…' : '确认离开'}
      cancelLabel="留在此页"
      destructive
      pending={confirming}
      onCancel={stayOnPage}
      onConfirm={() => { void leavePage() }}
    />
  )
}

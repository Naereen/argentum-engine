import { useEffect } from 'react'
import { useGameStore } from '@/store/gameStore'

const NOTICE_MS = 6500

type InitiativeNotice = NonNullable<ReturnType<typeof useGameStore.getState>['initiativeResultNotice']>

export function InitiativeResultNotice({ topOffset = 0 }: { topOffset?: number }) {
  const notice = useGameStore((state) => state.initiativeResultNotice)
  const dismiss = useGameStore((state) => state.dismissInitiativeResultNotice)

  useEffect(() => {
    if (!notice) return
    const timer = setTimeout(() => {
      if (useGameStore.getState().initiativeResultNotice?.key === notice.key) dismiss()
    }, NOTICE_MS)
    return () => clearTimeout(timer)
  }, [notice, dismiss])

  return <InitiativeResultNoticeContent notice={notice} topOffset={topOffset} onDismiss={dismiss} />
}

export function InitiativeResultNoticeContent({
  notice,
  topOffset = 0,
  onDismiss,
}: {
  notice: InitiativeNotice | null
  topOffset?: number
  onDismiss?: () => void
}) {
  if (!notice) return null

  const explorerWon = notice.outcome === 'explorerWon'
  const accent = explorerWon ? '#74d99f' : '#f0a15a'
  const headline = explorerWon
    ? `${notice.explorerIsYou ? 'You' : notice.explorerName} won initiative`
    : 'Encounter won initiative'
  const detail = explorerWon
    ? `${notice.explorerIsYou ? 'You may' : `${notice.explorerName} may`} fight or flee freely.`
    : `${notice.explorerIsYou ? 'Your' : `${notice.explorerName}’s`} turn ends.`

  return (
    <div
      key={notice.key}
      role="status"
      aria-live="polite"
      onClick={onDismiss}
      data-testid="initiative-result-notice"
      style={{
        position: 'fixed',
        top: topOffset + 52,
        left: '50%',
        transform: 'translateX(-50%)',
        zIndex: 125,
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        maxWidth: 'min(620px, calc(100vw - 24px))',
        padding: '11px 18px',
        borderRadius: 12,
        border: `1px solid ${accent}99`,
        background: 'linear-gradient(135deg, rgba(19, 23, 30, 0.98), rgba(12, 12, 18, 0.96))',
        boxShadow: `0 8px 32px rgba(0, 0, 0, 0.55), 0 0 22px ${accent}33`,
        color: '#e8ecf5',
        cursor: 'pointer',
        animation: 'initiativeResultNoticeIn 240ms ease-out',
      }}
      title="Dismiss initiative result"
    >
      <span aria-hidden style={{ fontSize: 10, lineHeight: 1, color: accent, fontWeight: 900, letterSpacing: '0.04em' }}>INIT</span>
      <span style={{ display: 'grid', gap: 3, minWidth: 0 }}>
        <span style={{ color: accent, fontSize: 15, fontWeight: 800, letterSpacing: '0.01em' }}>{headline}</span>
        <span style={{ fontSize: 12, opacity: 0.9 }}>{detail}</span>
        {notice.rolls && <span style={{ fontSize: 11, opacity: 0.72 }}>Rolls: {notice.rolls}</span>}
      </span>
      <style>{`
        @keyframes initiativeResultNoticeIn {
          from { opacity: 0; transform: translateX(-50%) translateY(-8px) scale(0.98); }
          to { opacity: 1; transform: translateX(-50%) translateY(0) scale(1); }
        }
      `}</style>
    </div>
  )
}

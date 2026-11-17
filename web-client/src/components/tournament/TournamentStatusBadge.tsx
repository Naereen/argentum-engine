/**
 * A small colored pill for a tournament's lifecycle status (in progress / completed / abandoned).
 * Shared by the admin dashboard's Tournaments table, the profile Recent tournaments table, and the
 * tournament detail modal. Accepts a plain string so callers don't have to share a status type.
 */
import type React from 'react'
import { useTranslation } from 'react-i18next'

const STYLES: Record<string, { color: string; bg: string; border: string }> = {
  IN_PROGRESS: { color: '#7fd1ff', bg: 'rgba(88,166,255,0.14)', border: '#3d6fa855' },
  COMPLETED: { color: '#5bd16e', bg: 'rgba(91,209,110,0.12)', border: '#3a8a4a55' },
  ABANDONED: { color: '#e0a15b', bg: 'rgba(224,161,91,0.12)', border: '#8a6a3a55' },
}

export function TournamentStatusBadge({ status }: { status: string }) {
  const { t } = useTranslation()
  const styles = STYLES[status] ?? {
    color: '#aaa',
    bg: 'rgba(160,160,160,0.12)',
    border: '#55555555',
  }
  const label = status === 'IN_PROGRESS'
    ? t('tournamentProfile.statusInProgress')
    : status === 'COMPLETED'
      ? t('tournamentProfile.statusCompleted')
      : status === 'ABANDONED'
        ? t('tournamentProfile.statusAbandoned')
        : status
  return (
    <span
      style={{
        ...badge,
        color: styles.color,
        backgroundColor: styles.bg,
        border: `1px solid ${styles.border}`,
      }}
    >
      {label}
    </span>
  )
}

const badge: React.CSSProperties = {
  display: 'inline-block',
  fontSize: 11,
  fontWeight: 600,
  letterSpacing: 0.3,
  borderRadius: 999,
  padding: '2px 8px',
  whiteSpace: 'nowrap',
}

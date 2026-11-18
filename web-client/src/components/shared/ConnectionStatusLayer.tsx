/**
 * What the player sees while the server can't be reached, on every route (mounted beside the
 * router, like the matchmaking layer).
 *
 * - **Server restarting** (the server announced it, or closed with 1012 — a deploy): on a screen
 *   that can't work without the server — a game, a mulligan, a draft, a spectated game — a card
 *   over the dimmed table says the game is saved and will pick up where it left off. The table
 *   stays visible behind it, so it's clear nothing was lost. Anywhere else (home, deckbuilder,
 *   library…) a slim banner at the bottom says the same without blocking what still works offline.
 * - **Connection lost mid-game** (no restart announced): after a short grace, the same card in a
 *   "reconnecting" voice, with a "Try now" once the backoff has grown.
 * - **Back**: when the server answers after a restart, a brief "update complete" note confirms it.
 */
import { useEffect, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { isAppRoute } from '@/hooks/useHomeVisible'
import { useGameStore } from '@/store/gameStore.ts'
import styles from './ConnectionStatusLayer.module.css'

/** A blip shorter than this (a socket swap, a tab waking up) doesn't deserve a takeover. */
const LOST_GRACE_MS = 2_500
/** When "Try now" appears on a lost connection: by then the backoff waits are getting long. */
const LOST_RETRY_OFFER_MS = 10_000
/** How long the "update complete" note stays up. */
const BACK_NOTICE_MS = 4_000

export default function ConnectionStatusLayer() {
  const { t } = useTranslation()
  const { pathname } = useLocation()
  const serverRestart = useGameStore((s) => s.serverRestart)
  const serverBackAt = useGameStore((s) => s.serverBackAt)
  const connectionLostAt = useGameStore((s) => s.connectionLostAt)
  const sessionReplaced = useGameStore((s) => s.sessionReplaced)
  const retryConnection = useGameStore((s) => s.retryConnection)
  // Screens that are dead without the server: the table (or a draft) is frozen until it's back.
  const liveTable = useGameStore((s) =>
    s.gameOverState == null && (
      s.gameState != null ||
      s.mulliganState != null ||
      s.waitingForOpponentMulligan ||
      s.spectatingState != null ||
      s.lobbyState?.state === 'DRAFTING'
    ),
  )
  const blocking = liveTable && isAppRoute(pathname)

  const ticking = serverRestart != null || connectionLostAt != null ||
    (serverBackAt != null && Date.now() - serverBackAt < BACK_NOTICE_MS + 1_000)
  const now = useNow(ticking)

  // The takeover overlay owns the screen when another tab has the session.
  if (sessionReplaced) return null

  if (serverRestart) {
    const elapsedMs = Math.max(0, now - serverRestart.startedAt)
    return blocking
      ? <RestartCard elapsedMs={elapsedMs} expectedSeconds={serverRestart.expectedSeconds} />
      : <RestartBanner elapsedMs={elapsedMs} expectedSeconds={serverRestart.expectedSeconds} />
  }

  if (connectionLostAt != null && blocking) {
    const lostFor = now - connectionLostAt
    if (lostFor < LOST_GRACE_MS) return null
    return <LostCard offerRetry={lostFor >= LOST_RETRY_OFFER_MS} onRetry={retryConnection} />
  }

  if (serverBackAt != null && now - serverBackAt < BACK_NOTICE_MS) {
    return (
      <div className={`${styles.banner} ${styles.bannerBack}`} role="status">
        <span className={styles.check} aria-hidden>✓</span>
        <span><strong>{t('connection.updateComplete')}</strong> {t('connection.backOnline')}</span>
      </div>
    )
  }

  return null
}

function RestartCard({ elapsedMs, expectedSeconds }: { elapsedMs: number; expectedSeconds: number }) {
  const { t } = useTranslation()
  const overdue = elapsedMs > expectedSeconds * 1000 * 1.5
  const progress = Math.min(elapsedMs / (expectedSeconds * 1000), 1)
  return (
    <div className={styles.scrim} role="alertdialog" aria-modal="true" aria-labelledby="restart-title" aria-describedby="restart-body">
      <div className={styles.card}>
        <p className={styles.eyebrow}>{t('connection.serverUpdate')}</p>
        <h2 id="restart-title" className={styles.title}>{t('connection.installingUpdate')}</h2>
        <p id="restart-body" className={styles.body}>
          {t('connection.gameSaved', { duration: quote(expectedSeconds, t) })}
        </p>
        <div
          className={`${styles.track} ${overdue ? styles.trackWaiting : ''}`}
          role="progressbar"
          aria-label={t('connection.timeSinceUpdate')}
          aria-valuemin={0}
          aria-valuemax={expectedSeconds}
          aria-valuenow={Math.round(elapsedMs / 1000)}
          aria-valuetext={t('connection.elapsed', { time: formatElapsed(elapsedMs) })}
        >
          <div className={styles.fill} style={{ width: `${progress * 100}%` }} />
        </div>
        <div className={styles.statusRow} aria-live="polite">
          <span className={styles.statusText}>
            <span className={styles.pulse} aria-hidden />
            {overdue ? t('connection.takingLonger') : t('connection.reconnecting')}
          </span>
          <span className={styles.elapsed}>{formatElapsed(elapsedMs)}</span>
        </div>
        <p className={styles.hint}>
          {overdue ? t('connection.keepTrying') : t('connection.noRefresh')}
        </p>
      </div>
    </div>
  )
}

function RestartBanner({ elapsedMs, expectedSeconds }: { elapsedMs: number; expectedSeconds: number }) {
  const { t } = useTranslation()
  return (
    <div className={styles.banner} role="status">
      <span className={styles.pulse} aria-hidden />
      <span className={styles.bannerText}>
        <strong>{t('connection.updateInProgress')}</strong>{' '}
        {t('connection.backIn', { duration: quote(expectedSeconds, t) })}
      </span>
      <span className={styles.elapsed}>{formatElapsed(elapsedMs)}</span>
    </div>
  )
}

function LostCard({ offerRetry, onRetry }: { offerRetry: boolean; onRetry: () => void }) {
  const { t } = useTranslation()
  return (
    <div className={styles.scrim} role="alertdialog" aria-modal="true" aria-labelledby="lost-title" aria-describedby="lost-body">
      <div className={styles.card}>
        <p className={styles.eyebrow}>{t('connection.lost')}</p>
        <h2 id="lost-title" className={styles.title}>{t('connection.reconnectingEllipsis')}</h2>
        <p id="lost-body" className={styles.body}>{t('connection.gameStillSaved')}</p>
        <div className={`${styles.track} ${styles.trackWaiting}`} aria-hidden>
          <div className={styles.fill} />
        </div>
        {offerRetry && (
          <button type="button" className={styles.retry} onClick={onRetry}>
            {t('connection.tryNow')}
          </button>
        )}
      </div>
    </div>
  )
}

/** "1–2 minutes" for the deploy-sized default; plain minutes or seconds otherwise. */
function quote(seconds: number, t: TFunction): string {
  if (seconds <= 45) return t('connection.aboutMinute')
  if (seconds <= 120) return t('connection.oneToTwoMinutes')
  return t('connection.minutes', { count: Math.ceil(seconds / 60) })
}

function formatElapsed(ms: number): string {
  const total = Math.floor(ms / 1000)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

/** Re-render once a second while [active]; returns the current time. */
function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    setNow(Date.now())
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [active])
  return now
}

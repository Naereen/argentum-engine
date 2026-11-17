import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useGameStore } from '@/store/gameStore.ts'
import overlay from '@/components/game/overlay/GameOverlays.module.css'
import styles from './Matchmaking.module.css'
import type { MatchmakingMode } from '@/types'
import { queueLabel } from './queues'

/** What accepting leads to, so nobody is surprised to land straight in a game. */
const AFTER_ACCEPT: Record<MatchmakingMode, 'matchmaking.afterAccept.random' | 'matchmaking.afterAccept.jumpIn' | 'matchmaking.afterAccept.constructed'> = {
  RANDOM_DECK: 'matchmaking.afterAccept.random',
  MOMIR_BASIC: 'matchmaking.afterAccept.random',
  JUMP_IN: 'matchmaking.afterAccept.jumpIn',
  CONSTRUCTED: 'matchmaking.afterAccept.constructed',
}

/**
 * "Match found" — the accept prompt both paired players see. Mounted app-wide so it reaches the
 * player wherever they are. The server runs the real deadline; the countdown here only shows it.
 * Once both accept, the server seats them and this closes: Random deck and Momir Basic go straight
 * into the game, Jump In to the pack choice, Constructed to a lobby to pick decks in.
 */
export function MatchFoundDialog() {
  const { t } = useTranslation()
  const offer = useGameStore((s) => s.matchOffer)
  const respond = useGameStore((s) => s.respondToMatch)
  // Matchmaking keeps you searching through a warm-up against the AI; accepting ends that game.
  const inWarmUp = useGameStore((s) => s.gameState != null && s.gameOverState == null)
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!offer) return
    const id = window.setInterval(() => setNow(Date.now()), 250)
    // A backgrounded tab still gets the news through its title.
    const previousTitle = document.title
    document.title = `${t('matchmaking.matchFound')} — Argentum`
    return () => {
      window.clearInterval(id)
      document.title = previousTitle
    }
  }, [offer, t])

  if (!offer) return null

  const remainingMs = Math.max(0, offer.receivedAt + offer.acceptWindowMs - now)
  const seconds = Math.ceil(remainingMs / 1000)
  const accepted = offer.youAccepted === true

  return (
    <div className={`${overlay.scrim} ${overlay.scrimFixed}`} role="dialog" aria-modal="true" aria-labelledby="match-found-title">
      <div className={overlay.card} data-testid="match-found">
        <p className={overlay.eyebrow}>{queueLabel(t, offer.mode, offer.format, offer.ranked)}</p>
        <h2 id="match-found-title" className={overlay.waitTitle}>{t('matchmaking.matchFound')}</h2>
        <p className={overlay.reason}>
          {t('matchmaking.opponent', { name: offer.opponentName })}
          {offer.opponentRating != null && <> · {t('matchmaking.rating', { rating: offer.opponentRating })}</>}
        </p>
        <div className={styles.countdown} aria-hidden>
          <div
            className={styles.countdownBar}
            style={{ width: `${offer.acceptWindowMs > 0 ? (remainingMs / offer.acceptWindowMs) * 100 : 0}%` }}
          />
        </div>
        <p className={overlay.reason}>{t(AFTER_ACCEPT[offer.mode])}</p>
        <p className={overlay.reason}>
          {accepted
            ? t('matchmaking.waitingForOpponent', { opponent: offer.opponentName, seconds })
            : t('matchmaking.acceptWithin', { seconds })}
        </p>
        {inWarmUp && !accepted && (
          <p className={styles.warmUpNote}>{t('matchmaking.acceptEndsWarmup')}</p>
        )}
        <div className={overlay.actions}>
          <button type="button" className={overlay.secondary} onClick={() => respond(false)}>
            {t('matchmaking.decline')}
          </button>
          <button
            type="button"
            className={overlay.primary}
            onClick={() => respond(true)}
            disabled={accepted}
            autoFocus
            data-testid="match-found-accept"
          >
            {accepted ? t('matchmaking.accepted') : t('matchmaking.accept')}
          </button>
        </div>
      </div>
    </div>
  )
}

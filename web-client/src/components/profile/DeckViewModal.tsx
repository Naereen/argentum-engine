/**
 * Modal that shows every seat's decklist for one finished game from the user's history. Opened from
 * the recent-games table so a player can review what they (and everyone they played against) actually
 * played. The decks come straight from the recorded match — the server only returns games the user
 * took part in.
 *
 * Multiplayer games (Free-for-All pods, team games) can seat three to six players, so the modal grows
 * once there are more than two decks to show rather than squeezing a whole pod into two columns.
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { type GameDecks, fetchGameDecks } from '@/api/account'
import { GameDeckColumns } from '@/components/deck/GameDeckView'
import { SaveGameDeckButton } from '@/components/profile/SaveGameDeckButton'
import { AccountModal, accountStyles as a } from '@/components/profile/accountUi'

export function DeckViewModal({
  gameId,
  opponentLabel,
  onClose,
}: {
  gameId: string
  opponentLabel: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [decks, setDecks] = useState<GameDecks | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)

  useEffect(() => {
    let live = true
    setDecks(null)
    setLoadFailed(false)
    fetchGameDecks(gameId)
      .then((d) => live && setDecks(d))
      .catch(() => live && setLoadFailed(true))
    return () => {
      live = false
    }
  }, [gameId])

  const seats = decks?.participants.length ?? 0
  const multiplayer = seats > 2

  return (
    <AccountModal
      title={t('profileUi.gameDecks')}
      size={multiplayer ? 'xl' : 'large'}
      onClose={onClose}
    >
      <p className={a.muted} style={{ marginTop: 4 }}>
        {t('profileUi.versus', { opponent: opponentLabel })}
        {multiplayer && <span className={a.dim}> · {t('profileUi.playerCountGame', { count: seats })}</span>}
      </p>

      {loadFailed ? (
        <p className={a.error} style={{ marginTop: 12 }}>{t('profileUi.loadDecksError')}</p>
      ) : !decks ? (
        <p className={a.muted} style={{ marginTop: 12 }}>{t('common.loading')}</p>
      ) : decks.participants.length === 0 ? (
        <p className={a.muted} style={{ marginTop: 12 }}>{t('profileUi.noDecklist')}</p>
      ) : (
        <div style={{ marginTop: 16 }}>
          <GameDeckColumns
            participants={decks.participants}
            renderActions={(p) => <SaveGameDeckButton participant={p} endedAt={decks.endedAt} />}
          />
        </div>
      )}
    </AccountModal>
  )
}

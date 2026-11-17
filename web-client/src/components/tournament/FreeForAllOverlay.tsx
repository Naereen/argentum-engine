import { useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { useGameStore, type FfaState } from '@/store/gameStore.ts'
import { ReplayViewer, type GameSummary } from '../admin/ReplayViewer'
import type { ReplayData } from '@/replay/reconstructSnapshots.ts'
import styles from '../ui/GameUI.module.css'
import { randomBackground } from '@/utils/background'

/**
 * Free-for-All pod overlay — the FFA-mode counterpart of [TournamentOverlay]. Shown between
 * games: standings of the last game (placement order), a "Play Again" ready loop, and the
 * usual share/replays/deck/leave toolbar. During a game the board renders instead.
 */
export function FreeForAllOverlay({ ffaState }: { ffaState: FfaState }) {
  const { t } = useTranslation()
  const playerId = useGameStore((state) => state.playerId)
  const lobbyState = useGameStore((state) => state.lobbyState)
  const deckBuildingState = useGameStore((state) => state.deckBuildingState)
  const readyForNextRound = useGameStore((state) => state.readyForNextRound)
  const unsubmitDeck = useGameStore((state) => state.unsubmitDeck)
  const leaveTournament = useGameStore((state) => state.leaveTournament)
  const spectateGame = useGameStore((state) => state.spectateGame)
  const [linkCopied, setLinkCopied] = useState(false)
  const [showReplays, setShowReplays] = useState(false)
  const [confirmLeave, setConfirmLeave] = useState(false)

  const shareLink = `${window.location.origin}/tournament/${ffaState.lobbyId}`
  const copyShareLink = () => {
    navigator.clipboard.writeText(shareLink)
    setLinkCopied(true)
    setTimeout(() => setLinkCopied(false), 2000)
  }

  const fetchPodGames = useCallback(async (): Promise<GameSummary[]> => {
    const token = localStorage.getItem('argentum-token')
    if (!token) throw new Error('No player token')
    const res = await fetch(`/api/replays/tournament/${ffaState.lobbyId}`, {
      headers: { 'X-Player-Token': token },
    })
    if (!res.ok) throw new Error(`Server error: ${res.status}`)
    return await res.json() as GameSummary[]
  }, [ffaState.lobbyId])

  const fetchPodReplay = useCallback(async (gameId: string): Promise<ReplayData> => {
    const token = localStorage.getItem('argentum-token')
    if (!token) throw new Error('No player token')
    const res = await fetch(`/api/replays/${gameId}?lobbyId=${ffaState.lobbyId}`, {
      headers: { 'X-Player-Token': token },
    })
    if (!res.ok) throw new Error(`Failed to load replay: ${res.status}`)
    return await res.json() as ReplayData
  }, [ffaState.lobbyId])

  if (showReplays) {
    return (
      <ReplayViewer
        fetchGames={fetchPodGames}
        fetchReplay={fetchPodReplay}
        onBack={() => setShowReplays(false)}
      />
    )
  }

  const isSpectator = !playerId || !lobbyState?.players.some((p) => p.playerId === playerId)
  const gameInProgress = ffaState.currentGameSessionId != null
  const isPlayerReady = playerId ? ffaState.readyPlayerIds.includes(playerId) : false
  const readyCount = ffaState.readyPlayerIds.length
  const totalPlayers = lobbyState?.players.filter((p) => p.isConnected).length
    ?? ffaState.standings?.length ?? 0

  return (
    <div className={styles.tournamentOverlay} style={tournamentBackdrop}>
      {/* ── Header ── */}
      <div className={styles.trnHeader}>
        <div className={styles.trnHeaderTop}>
          <h1 className={styles.trnTitle}>{t('ffa.title')}</h1>
          <span className={styles.trnRound}>
            {gameInProgress
              ? t('ffa.gameInProgress', { number: ffaState.gameNumber })
              : ffaState.gamesPlayed > 0
                ? t('ffa.afterGame', { number: ffaState.gamesPlayed })
                : t('ffa.waitingToStart')}
          </span>
        </div>
        <div className={styles.trnToolbar}>
          <button onClick={copyShareLink} className={styles.trnToolbarBtn}>
            {linkCopied ? t('ffa.copied') : t('ffa.shareLink')}
          </button>
          {ffaState.gamesPlayed > 0 && (
            <button onClick={() => setShowReplays(true)} className={styles.trnToolbarBtn}>
              {t('ffa.replays')}
            </button>
          )}
          {gameInProgress && isSpectator && ffaState.currentGameSessionId && (
            <button
              onClick={() => spectateGame(ffaState.currentGameSessionId!)}
              className={styles.trnToolbarBtn}
            >
              {t('ffa.watchGame')}
            </button>
          )}
        </div>
      </div>

      {/* ── Action zone: play again / waiting ── */}
      {!isSpectator && !gameInProgress && (
        <div className={styles.trnActionZone}>
          <div className={styles.trnReadyRow}>
            <button
              onClick={readyForNextRound}
              disabled={isPlayerReady}
              className={styles.readyButton}
            >
              {isPlayerReady ? t('ffa.ready') : ffaState.gamesPlayed > 0 ? t('ffa.playAgain') : t('ffa.ready')}
            </button>
            {!lobbyState?.settings.jumpstartActive && !isPlayerReady && deckBuildingState && (
              <button onClick={unsubmitDeck} className={styles.editDeckButton}>
                {t('ffa.editDeck')}
              </button>
            )}
            <span className={styles.readyCount}>
              {t('ffa.readyCount', { ready: readyCount, total: totalPlayers })}
            </span>
          </div>
        </div>
      )}
      {gameInProgress && !isSpectator && (
        <div className={`${styles.statusBoxWaiting} ${styles.trnSection}`}>
          {t('ffa.gameInProgressStatus')}
        </div>
      )}

      {/* ── Standings (placement order of the last game) ── */}
      {ffaState.standings && (
        <div className={`${styles.standingsTable} ${styles.trnSection}`}>
          <table className={styles.standingsTableInner}>
            <thead className={styles.standingsHeader}>
              <tr>
                <th className={styles.standingsTh}>#</th>
                <th className={styles.standingsThLeft}>{t('ffa.player')}</th>
                <th className={styles.standingsTh}>{t('ffa.result')}</th>
              </tr>
            </thead>
            <tbody>
              {ffaState.standings.map((standing) => {
                const isMe = standing.playerId === playerId
                return (
                  <tr
                    key={standing.playerId}
                    className={`${styles.standingsRow} ${isMe ? styles.standingsRowMe : ''}`}
                  >
                    <td className={`${styles.standingsTd} ${styles.standingsRank} ${
                      standing.placement === 1 ? styles.standingsRankFirst :
                      standing.placement === 2 ? styles.standingsRankSecond :
                      standing.placement === 3 ? styles.standingsRankThird : ''
                    }`}>
                      {standing.placement}
                    </td>
                    <td className={styles.standingsTdLeft} style={{ fontWeight: isMe ? 600 : 400 }}>
                      <span className={styles.standingsPlayerName} title={standing.playerName}>
                        {standing.playerName}
                      </span>
                      {isMe && <span className={styles.meIndicator}>{t('ffa.you')}</span>}
                    </td>
                    <td className={styles.standingsTd}>
                      {standing.placement === 1 ? t('ffa.winner') : t('ffa.place', { number: standing.placement })}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      {!ffaState.standings && !gameInProgress && (
        <div className={`${styles.statusBoxWaiting} ${styles.trnSection}`}>
          {t('ffa.waitingForPlayers')}
        </div>
      )}

      {/* ── Leave ── */}
      <div className={styles.trnSection} style={{ display: 'flex', justifyContent: 'center', gap: 8 }}>
        {!confirmLeave ? (
          <button onClick={() => setConfirmLeave(true)} className={styles.trnLeave}>
            {t('common.leave')}
          </button>
        ) : (
          <>
            <button onClick={leaveTournament} className={styles.trnLeaveConfirm}>
              {t('ffa.confirmLeave')}
            </button>
            <button onClick={() => setConfirmLeave(false)} className={styles.trnToolbarBtn}>
              {t('common.cancel')}
            </button>
          </>
        )}
      </div>
    </div>
  )
}

/** The card art behind a tint, as on the landing screen; the tint is darker since this is a table. */
const tournamentBackdrop = {
  backgroundImage: `linear-gradient(180deg, rgba(6, 7, 12, 0.7) 0%, rgba(6, 7, 12, 0.6) 45%, rgba(6, 7, 12, 0.82) 100%), url(${randomBackground})`,
}

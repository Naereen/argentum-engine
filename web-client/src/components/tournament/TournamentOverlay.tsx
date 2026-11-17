import { useState, useEffect, useCallback, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useGameStore, type TournamentState } from '@/store/gameStore.ts'
import type { SealedCardInfo } from '@/types'
import { getCardImageUrl } from '@/utils/cardImages.ts'
import { ManaCost } from '../ui/ManaSymbols'
import { ReplayViewer, type GameSummary } from '../admin/ReplayViewer'
import type { ReplayData } from '@/replay/reconstructSnapshots.ts'
import { useDeckLibrary, buildDraftedDeckSave, type SavedDeckEntry } from '@/store/deckLibrary'
import { useSaveDeck } from '@/store/useSaveDeck'
import { deriveRoundPhase } from './roundPhase'
import { SideboardingScreen } from './SideboardingScreen'
import styles from '../ui/GameUI.module.css'
import { randomBackground } from '@/utils/background'

/**
 * Tournament overlay showing standings between rounds.
 */
interface HoveredStanding {
  playerId: string
  playerName: string
  wins: number
  losses: number
  draws: number
  points: number
  gamesWon: number
  gamesLost: number
  lifeDifferential: number | undefined
  omwPercent: number | undefined
  ogwPercent: number | undefined
  tiebreakerReason: string | null | undefined
  rect: DOMRect
}

export function TournamentOverlay({
  tournamentState,
}: {
  tournamentState: TournamentState
}) {
  const { t, i18n } = useTranslation()
  const playerId = useGameStore((state) => state.playerId)
  const spectateGame = useGameStore((state) => state.spectateGame)
  const readyForNextRound = useGameStore((state) => state.readyForNextRound)
  const addExtraRound = useGameStore((state) => state.addExtraRound)
  const leaveTournament = useGameStore((state) => state.leaveTournament)
  const unsubmitDeck = useGameStore((state) => state.unsubmitDeck)
  const lobbyState = useGameStore((state) => state.lobbyState)
  const deckBuildingState = useGameStore((state) => state.deckBuildingState)
  const disconnectedPlayers = useGameStore((state) => state.disconnectedPlayers)
  const addDisconnectTime = useGameStore((state) => state.addDisconnectTime)
  const kickPlayer = useGameStore((state) => state.kickPlayer)
  const [hoveredStanding, setHoveredStanding] = useState<HoveredStanding | null>(null)
  const [linkCopied, setLinkCopied] = useState(false)
  const [showDeckViewer, setShowDeckViewer] = useState(false)
  const [confirmLeave, setConfirmLeave] = useState(false)
  const [showReplays, setShowReplays] = useState(false)
  const [deckSavedAt, setDeckSavedAt] = useState<number | null>(null)
  const [saveDeckDialog, setSaveDeckDialog] = useState<{ name: string } | null>(null)
  const hydrateDeckLibrary = useDeckLibrary((s) => s.hydrate)
  const { save: saveDeckRouted, isLoggedIn } = useSaveDeck()
  useEffect(() => { hydrateDeckLibrary() }, [hydrateDeckLibrary])

  const buildDeckSave = (): { cards: Record<string, number>; entries: SavedDeckEntry[] | undefined } | null => {
    if (!deckBuildingState) return null
    const built = buildDraftedDeckSave(
      deckBuildingState.deck,
      deckBuildingState.landCounts,
      [...deckBuildingState.cardPool, ...deckBuildingState.basicLands],
    )
    return Object.keys(built.cards).length === 0 ? null : built
  }

  const openSaveDeckDialog = () => {
    if (!deckBuildingState) return
    const setNames = lobbyState?.settings.setNames?.join(' + ')
    const stamp = new Date().toLocaleDateString(i18n.language)
    const defaultName = setNames
      ? t('tournamentUi.draftedSetDeckName', { sets: setNames, date: stamp })
      : t('tournamentUi.draftedDeckName', { date: stamp })
    setSaveDeckDialog({ name: defaultName })
  }

  const confirmSaveDeck = () => {
    if (!saveDeckDialog) return
    const built = buildDeckSave()
    if (!built) return
    const name = saveDeckDialog.name.trim() || t('tournamentUi.draftedDeckName', {
      date: new Date().toLocaleDateString(i18n.language),
    })
    // Routes to the account when signed in, localStorage otherwise — same as the deckbuilder.
    void saveDeckRouted({ name, cards: built.cards, ...(built.entries ? { entries: built.entries } : {}) })
    setSaveDeckDialog(null)
    setDeckSavedAt(Date.now())
    setTimeout(() => setDeckSavedAt(null), 2000)
  }
  // Tick every second to update disconnect countdown timers
  const [, setTick] = useState(0)
  const hasDisconnected = Object.keys(disconnectedPlayers).length > 0
  useEffect(() => {
    if (!hasDisconnected) return
    const timer = setInterval(() => setTick((t) => t + 1), 1000)
    return () => clearInterval(timer)
  }, [hasDisconnected])

  const shareLink = `${window.location.origin}/tournament/${tournamentState.lobbyId}`
  const copyShareLink = () => {
    navigator.clipboard.writeText(shareLink)
    setLinkCopied(true)
    setTimeout(() => setLinkCopied(false), 2000)
  }

  // Spectators are not in the standings list
  const isSpectator = !playerId || !tournamentState.standings.some(s => s.playerId === playerId)

  // Waiting for ready (before the first game or between matches), and — the distinction that keeps an
  // early finisher honest — whether the round they just finished is still being played elsewhere.
  const { isWaitingForReady, roundStillRunning, roundNumber, readyLabel } = deriveRoundPhase(tournamentState)

  // Check if current player is ready
  const isPlayerReady = playerId ? tournamentState.readyPlayerIds.includes(playerId) : false
  const readyCount = tournamentState.readyPlayerIds.length
  const totalPlayers = tournamentState.standings.filter(s => s.isConnected).length

  // Auto-ready when player has a bye - no need for manual confirmation
  // Exception: Don't auto-ready before first round (round 0) so player can still edit deck
  useEffect(() => {
    if (!isSpectator && isWaitingForReady && tournamentState.nextRoundHasBye && !isPlayerReady && tournamentState.currentRound > 0) {
      readyForNextRound()
    }
  }, [isSpectator, isWaitingForReady, tournamentState.nextRoundHasBye, isPlayerReady, tournamentState.currentRound, readyForNextRound])

  const fetchTournamentGames = useCallback(async (): Promise<GameSummary[]> => {
    const token = localStorage.getItem('argentum-token')
    if (!token) throw new Error('No player token')
    const res = await fetch(`/api/replays/tournament/${tournamentState.lobbyId}`, {
      headers: { 'X-Player-Token': token },
    })
    if (!res.ok) throw new Error(`Server error: ${res.status}`)
    return await res.json() as GameSummary[]
  }, [tournamentState.lobbyId])

  const fetchTournamentReplay = useCallback(async (gameId: string): Promise<ReplayData> => {
    const token = localStorage.getItem('argentum-token')
    if (!token) throw new Error('No player token')
    const res = await fetch(`/api/replays/${gameId}?lobbyId=${tournamentState.lobbyId}`, {
      headers: { 'X-Player-Token': token },
    })
    if (!res.ok) throw new Error(`Failed to load replay: ${res.status}`)
    return await res.json() as ReplayData
  }, [tournamentState.lobbyId])

  // Between the games of a best-of-three the match isn't over: the sideboarding step owns the screen.
  // Keyed by game, so game 3 starts from the deck that played game 2, not from stale local edits.
  const betweenGames = tournamentState.betweenGames
  if (betweenGames && !isSpectator) {
    return <SideboardingScreen key={`${betweenGames.round}-${betweenGames.nextGameNumber}`} snapshot={betweenGames} />
  }

  if (showReplays) {
    return (
      <ReplayViewer
        fetchGames={fetchTournamentGames}
        fetchReplay={fetchTournamentReplay}
        onBack={() => setShowReplays(false)}
      />
    )
  }

  const roundLabel = !tournamentState.isComplete
    ? t('tournamentUi.roundOf', { round: roundNumber, total: tournamentState.totalRounds })
    : null

  return (
    <div className={styles.tournamentOverlay} style={tournamentBackdrop}>
      {/* ── Header: title + round + toolbar ── */}
      <div className={styles.trnHeader}>
        <div className={styles.trnHeaderTop}>
          <h1 className={styles.trnTitle}>
            {tournamentState.isComplete ? t('tournamentUi.complete') : t('tournamentUi.standings')}
          </h1>
          {roundLabel && <span className={styles.trnRound}>{roundLabel}</span>}
        </div>
        <div className={styles.trnToolbar}>
          <button onClick={copyShareLink} className={styles.trnToolbarBtn}>
            {linkCopied ? t('tournamentUi.copied') : t('tournamentUi.shareLink')}
          </button>
          {tournamentState.lastRoundResults && (
            <button onClick={() => setShowReplays(true)} className={styles.trnToolbarBtn}>
              {t('tournamentUi.replays')}
            </button>
          )}
          {!isSpectator && deckBuildingState && (
            <>
              <button
                onClick={openSaveDeckDialog}
                className={styles.trnToolbarBtn}
                style={{
                  background: deckSavedAt ? 'rgba(95, 208, 140, 0.16)' : 'rgba(242, 180, 92, 0.18)',
                  borderColor: deckSavedAt ? 'rgba(95, 208, 140, 0.5)' : 'rgba(242, 180, 92, 0.6)',
                  color: deckSavedAt ? '#c8f2d8' : '#f6d9a8',
                }}
                title={isLoggedIn ? t('tournamentUi.saveAccountTitle') : t('tournamentUi.saveBrowserTitle')}
              >
                {deckSavedAt ? t('tournamentUi.saved') : t('tournamentUi.saveDeck')}
              </button>
              <button onClick={() => setShowDeckViewer(true)} className={styles.trnToolbarBtn}>
                {t('tournamentUi.viewDeck')}
              </button>
            </>
          )}
        </div>
      </div>

      {/* ── Action zone: next match / ready / bye ── */}
      {!isSpectator && !tournamentState.isComplete && (
        <div className={styles.trnActionZone}>
          {/* Next opponent */}
          {isWaitingForReady && !tournamentState.nextRoundHasBye && (
            <div className={styles.statusBoxMatch}>
              <div className={styles.statusBoxMatchLabel}>{t('tournamentUi.nextMatch')}</div>
              <div className={styles.statusBoxMatchOpponent}>
                {tournamentState.nextOpponentName
                  ? t('tournamentUi.vsOpponent', { opponent: tournamentState.nextOpponentName })
                  : t('tournamentUi.waitingMatchup')}
              </div>
            </div>
          )}

          {/* BYE status */}
          {((isWaitingForReady && tournamentState.nextRoundHasBye) || (tournamentState.isBye && !isWaitingForReady)) && (
            <div className={styles.statusBoxBye}>
              <span className={styles.byeIcon}>&#x2713;</span>
              <span>{t('tournamentUi.sittingOut')}</span>
            </div>
          )}

          {/* Ready button row */}
          {isWaitingForReady && (
            <div className={styles.trnReadyRow}>
              <button
                onClick={readyForNextRound}
                disabled={isPlayerReady}
                className={styles.readyButton}
              >
                {isPlayerReady
                  ? t('tournamentUi.ready')
                  : t(readyLabel === 'Ready for Next Match' ? 'tournamentUi.readyNextMatch' : 'tournamentUi.readyNextRound')}
              </button>
              {/* Rebuilding from the pool is allowed before round 1 and between matches — "continuous
                  construction", the casual-event norm. The server refuses it mid-match. */}
              {!lobbyState?.settings.jumpstartActive && !isPlayerReady && (tournamentState.currentRound === 0 || deckBuildingState) && (
                <button
                  onClick={unsubmitDeck}
                  className={styles.editDeckButton}
                  title={tournamentState.currentRound === 0
                    ? t('tournamentUi.changeDeckBeforeFirst')
                    : t('tournamentUi.rebuildDeckBeforeNext')}
                >
                  {t('tournamentUi.editDeck')}
                </button>
              )}
              <span className={styles.readyCount}>
                {t('tournamentUi.readyCount', { ready: readyCount, total: totalPlayers })}
              </span>
            </div>
          )}

          {/* Waiting for others — including the early finisher who may already be ready: the round
              they just played is still running, so the notice belongs next to the Ready button. */}
          {(roundStillRunning || !isWaitingForReady) && !tournamentState.isBye && !tournamentState.currentMatchGameSessionId && (
            <div className={styles.statusBoxWaiting}>
              {roundStillRunning
                ? isPlayerReady
                  ? t('tournamentUi.roundStillNext', { round: roundNumber })
                  : t('tournamentUi.roundStillOthers', { round: roundNumber })
                : t('tournamentUi.waitingMatches')}
            </div>
          )}
        </div>
      )}

      {/* ── Disconnected players ── */}
      {Object.keys(disconnectedPlayers).length > 0 && (
        <div className={`${styles.disconnectedBanner} ${styles.trnSection}`}>
          {Object.entries(disconnectedPlayers).map(([pid, info]) => {
            const elapsed = Math.floor((Date.now() - info.disconnectedAt) / 1000)
            const remaining = Math.max(0, info.secondsRemaining - elapsed)
            const mins = Math.floor(remaining / 60)
            const secs = remaining % 60
            const canKick = elapsed >= 120
            return (
              <div key={pid} className={styles.disconnectedPlayer}>
                <span className={styles.disconnectedName}>{t('tournamentUi.disconnected', { player: info.playerName })}</span>
                <span className={styles.disconnectedTimer}>{mins}:{secs.toString().padStart(2, '0')}</span>
                {!isSpectator && (
                  <>
                    <button className={styles.addTimeButton} onClick={() => addDisconnectTime(pid)}>
                      {t('tournamentUi.addMinute')}
                    </button>
                    {canKick && (
                      <button className={styles.kickButton} onClick={() => kickPlayer(pid)}>
                        {t('tournamentUi.kick')}
                      </button>
                    )}
                  </>
                )}
              </div>
            )
          })}
        </div>
      )}

      {/* ── Standings table ── */}
      <div className={`${styles.standingsTable} ${styles.trnSection}`}>
        <table className={styles.standingsTableInner}>
          <thead className={styles.standingsHeader}>
            <tr>
              <th className={styles.standingsTh}>#</th>
              <th className={styles.standingsThLeft}>{t('tournamentUi.player')}</th>
              <th className={styles.standingsTh}>{t('tournamentUi.record')}</th>
              <th className={styles.standingsTh}>{t('tournamentUi.pointsShort')}</th>
              <th className={styles.standingsTh} title={t('tournamentUi.gameWinRate')}>GWR</th>
              {isWaitingForReady && <th className={styles.standingsTh}>{t('tournamentUi.ready')}</th>}
            </tr>
          </thead>
          <tbody>
            {tournamentState.standings.map((standing, index) => {
              const isMe = standing.playerId === playerId
              const isReady = tournamentState.readyPlayerIds.includes(standing.playerId)
              const displayRank = standing.rank ?? index + 1

              const gamesWon = standing.gamesWon ?? 0
              const gamesLost = standing.gamesLost ?? 0
              const totalGames = gamesWon + gamesLost
              const winRate = totalGames > 0 ? ((gamesWon / totalGames) * 100).toFixed(0) : '-'

              const handleMouseEnter = (e: React.MouseEvent<HTMLTableRowElement>) => {
                const rect = e.currentTarget.getBoundingClientRect()
                setHoveredStanding({
                  playerId: standing.playerId,
                  playerName: standing.playerName,
                  wins: standing.wins,
                  losses: standing.losses,
                  draws: standing.draws,
                  points: standing.points,
                  gamesWon: gamesWon,
                  gamesLost: gamesLost,
                  lifeDifferential: standing.lifeDifferential,
                  omwPercent: standing.omwPercent,
                  ogwPercent: standing.ogwPercent,
                  tiebreakerReason: standing.tiebreakerReason,
                  rect,
                })
              }

              return (
                <tr
                  key={standing.playerId}
                  className={`${styles.standingsRow} ${isMe ? styles.standingsRowMe : ''}`}
                  onMouseEnter={handleMouseEnter}
                  onMouseLeave={() => setHoveredStanding(null)}
                >
                  <td className={`${styles.standingsTd} ${styles.standingsRank} ${displayRank === 1 ? styles.standingsRankFirst :
                    displayRank === 2 ? styles.standingsRankSecond :
                      displayRank === 3 ? styles.standingsRankThird : ''
                    }`}>
                    {displayRank}
                    {standing.tiebreakerReason === 'TIED' && (
                      <span className={styles.tiedIndicator}>*</span>
                    )}
                  </td>
                  <td className={styles.standingsTdLeft} style={{ fontWeight: isMe ? 600 : 400 }}>
                    <span className={styles.standingsPlayerName} title={standing.playerName}>
                      {standing.playerName}
                    </span>
                    {isMe && <span className={styles.meIndicator}>{t('tournamentUi.you')}</span>}
                    {!standing.isConnected && (
                      <span className={styles.disconnectedIndicator}>DC</span>
                    )}
                  </td>
                  <td className={`${styles.standingsTd} ${styles.standingsRecord}`}>
                    <span className={styles.standingsWins}>{standing.wins}</span>
                    {'-'}
                    <span className={styles.standingsLosses}>{standing.losses}</span>
                    {'-'}
                    <span className={styles.standingsDraws}>{standing.draws}</span>
                  </td>
                  <td className={`${styles.standingsTd} ${styles.standingsPoints}`}>{standing.points}</td>
                  <td className={`${styles.standingsTd} ${styles.standingsGwr}`}>
                    {totalGames > 0 ? `${winRate}%` : '-'}
                  </td>
                  {isWaitingForReady && (
                    <td className={styles.standingsTd} style={{ color: isReady ? 'var(--color-success-light)' : 'var(--text-disabled)' }}>
                      {standing.isConnected ? (isReady ? '✓' : '···') : '-'}
                    </td>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>

        {/* Instant hover tooltip */}
        {hoveredStanding && (
          <div
            className={styles.standingsTooltip}
            style={{
              top: hoveredStanding.rect.top + hoveredStanding.rect.height / 2,
              left: hoveredStanding.rect.right + 12,
              transform: 'translateY(-50%)',
            }}
          >
            <div className={styles.tooltipName}>{hoveredStanding.playerName}</div>
            <div className={styles.tooltipStat}>
              <span className={styles.tooltipStatLabel}>{t('tournamentUi.matchRecord')}</span>
              <span className={styles.tooltipStatValue}>
                {hoveredStanding.wins}W-{hoveredStanding.losses}L-{hoveredStanding.draws}D
              </span>
            </div>
            <div className={styles.tooltipStat}>
              <span className={styles.tooltipStatLabel}>{t('tournamentUi.points')}</span>
              <span className={styles.tooltipStatValue}>{hoveredStanding.points}</span>
            </div>
            {(hoveredStanding.gamesWon + hoveredStanding.gamesLost) > 0 && (
              <div className={styles.tooltipStat}>
                <span className={styles.tooltipStatLabel}>{t('tournamentUi.gameWinPercent')}</span>
                <span className={styles.tooltipStatValue}>
                  {hoveredStanding.gamesWon}-{hoveredStanding.gamesLost} (
                  {((hoveredStanding.gamesWon / (hoveredStanding.gamesWon + hoveredStanding.gamesLost)) * 100).toFixed(0)}%)
                </span>
              </div>
            )}
            {hoveredStanding.omwPercent !== undefined && (
              <div className={styles.tooltipStat}>
                <span className={styles.tooltipStatLabel}>{t('tournamentUi.opponentMatchWinPercent')}</span>
                <span className={styles.tooltipStatValue}>{(hoveredStanding.omwPercent * 100).toFixed(1)}%</span>
              </div>
            )}
            {hoveredStanding.ogwPercent !== undefined && (
              <div className={styles.tooltipStat}>
                <span className={styles.tooltipStatLabel}>{t('tournamentUi.opponentGameWinPercent')}</span>
                <span className={styles.tooltipStatValue}>{(hoveredStanding.ogwPercent * 100).toFixed(1)}%</span>
              </div>
            )}
            {hoveredStanding.lifeDifferential !== undefined && (
              <div className={styles.tooltipStat}>
                <span className={styles.tooltipStatLabel}>{t('tournamentUi.lifeDiff')}</span>
                <span className={styles.tooltipStatValue}>
                  {hoveredStanding.lifeDifferential >= 0 ? '+' : ''}{hoveredStanding.lifeDifferential}
                </span>
              </div>
            )}
            {hoveredStanding.tiebreakerReason && hoveredStanding.tiebreakerReason !== 'TIED' && (
              <div className={styles.tooltipTiebreaker}>
                {hoveredStanding.tiebreakerReason === 'OMW'
                    ? t('tournamentUi.rankedByOpponentMatchWin')
                  : hoveredStanding.tiebreakerReason === 'GW'
                      ? t('tournamentUi.rankedByGameWin')
                    : hoveredStanding.tiebreakerReason === 'OGW'
                        ? t('tournamentUi.rankedByOpponentGameWin')
                      : null}
              </div>
            )}
            {hoveredStanding.tiebreakerReason === 'TIED' && (
              <div className={styles.tooltipTiebreaker}>{t('tournamentUi.tied')}</div>
            )}
          </div>
        )}
      </div>

      {/* ── Live matches ── */}
      {!tournamentState.currentMatchGameSessionId && tournamentState.activeMatches && tournamentState.activeMatches.length > 0 && (
        <div className={`${styles.matchesSection} ${styles.trnSection}`}>
          <h3 className={styles.matchesSectionTitle}>
            {t('tournamentUi.liveMatches')}
          </h3>
          <div className={styles.matchesList}>
            {tournamentState.activeMatches.map((match) => (
              <button
                key={match.gameSessionId}
                onClick={() => spectateGame(match.gameSessionId)}
                className={styles.matchButton}
              >
                <div className={styles.matchPlayers}>
                  <span className={styles.matchPlayerName}>{match.player1Name}</span>
                  <span className={styles.matchVs}>{t('tournamentUi.vs')}</span>
                  <span className={styles.matchPlayerName}>{match.player2Name}</span>
                </div>
                <div className={styles.matchScore}>
                  <span className={match.player1Life <= 5 ? styles.matchLifeLow : styles.matchLifeHigh}>
                    {match.player1Life}
                  </span>
                  <span className={styles.matchScoreDash}>-</span>
                  <span className={match.player2Life <= 5 ? styles.matchLifeLow : styles.matchLifeHigh}>
                    {match.player2Life}
                  </span>
                  <span className={styles.matchWatch}>▶ {t('tournamentUi.watch')}</span>
                </div>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* ── Last round results ── */}
      {tournamentState.lastRoundResults && tournamentState.lastRoundResults.length > 0 && (
        <div className={`${styles.resultsSection} ${styles.trnSection}`}>
          <h3 className={styles.resultsSectionTitle}>
            {t('tournamentUi.roundResults', { round: tournamentState.currentRound })}
          </h3>
          {tournamentState.lastRoundResults.map((result, i) => {
            // The winner's name is lit; the middle says only what a name can't (draw, bye, simulated).
            const decided = !result.isBye && !result.isDraw
            const p1Won = decided && result.winnerId === result.player1Id
            const p2Won = decided && result.winnerId === result.player2Id
            // A best-of-three reports its game score ("2–1") in place of the bare "vs".
            const gameWins = (result.player1GameWins ?? 0) + (result.player2GameWins ?? 0)
            const gameScore = (lobbyState?.settings.bestOf ?? 1) > 1 && !result.isBye && !result.isSimulated && gameWins > 0
              ? `${result.player1GameWins ?? 0}–${result.player2GameWins ?? 0}`
              : null
            return (
              <div key={i} className={styles.resultRow}>
                <span className={p1Won ? styles.resultWinner : decided ? styles.resultLoser : undefined}>
                  {result.player1Name}
                </span>
                <span className={styles.resultOutcome}>
                  {result.isBye ? t('tournamentUi.bye') : gameScore ? (result.isDraw ? t('tournamentUi.drawScore', { score: gameScore }) : gameScore) : result.isDraw ? t('tournamentUi.draw') : t('tournamentUi.vs')}
                  {result.isSimulated && (
                    <span
                      className={styles.resultSimulated}
                      title={t('tournamentUi.simulatedTitle')}
                    >
                      {t('tournamentUi.simulated')}
                    </span>
                  )}
                </span>
                <span className={p2Won ? styles.resultWinner : decided ? styles.resultLoser : undefined}>
                  {result.isBye ? '' : result.player2Name}
                </span>
              </div>
            )
          })}
        </div>
      )}

      {/* ── Footer actions ── */}
      <div className={styles.trnFooter}>
        {tournamentState.isComplete ? (
          <>
            {lobbyState?.isHost && (
              <button onClick={addExtraRound} className={styles.editDeckButton}>
                {t('tournamentUi.addRound')}
              </button>
            )}
            <button onClick={leaveTournament} className={styles.readyButton}>
              {t('tournamentUi.returnToMenu')}
            </button>
          </>
        ) : (
          <button
            onClick={() => {
              if (confirmLeave) {
                leaveTournament()
              } else {
                setConfirmLeave(true)
                setTimeout(() => setConfirmLeave(false), 3000)
              }
            }}
            className={confirmLeave ? styles.trnLeaveConfirm : styles.trnLeave}
          >
            {confirmLeave ? t('tournamentUi.confirmLeave') : t('tournamentUi.leaveTournament')}
          </button>
        )}
      </div>

      {/* Deck Viewer Modal */}
      {showDeckViewer && deckBuildingState && (
        <DeckViewerModal
          deckBuildingState={deckBuildingState}
          onClose={() => setShowDeckViewer(false)}
        />
      )}

      {/* Save Deck dialog */}
      {saveDeckDialog && (
        <SaveDeckDialog
          name={saveDeckDialog.name}
          online={isLoggedIn}
          onNameChange={(name) => setSaveDeckDialog({ name })}
          onCancel={() => setSaveDeckDialog(null)}
          onConfirm={confirmSaveDeck}
        />
      )}
    </div>
  )
}

function SaveDeckDialog({
  name,
  online,
  onNameChange,
  onConfirm,
  onCancel,
}: {
  name: string
  online: boolean
  onNameChange: (name: string) => void
  onConfirm: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  useEffect(() => {
    // Focus + select on mount so the user can immediately overtype the suggested name.
    inputRef.current?.focus()
    inputRef.current?.select()
  }, [])
  return (
    <div className={styles.deckViewerBackdrop} onClick={onCancel}>
      <div
        className={styles.deckViewerPanel}
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: 480 }}
      >
        <div className={styles.deckViewerHeader}>
          <h3 className={styles.deckViewerTitle}>{t('tournamentUi.saveDeck')}</h3>
          <button className={styles.deckViewerClose} onClick={onCancel}>
            &#x2715;
          </button>
        </div>
        <div style={{ padding: 'var(--space-4) var(--space-5)', display: 'flex', flexDirection: 'column', gap: 'var(--space-3)' }}>
          <label style={{ fontSize: 'var(--font-sm)', color: 'var(--text-faint)' }}>
            {t('tournamentUi.deckName')}
          </label>
          <p style={{ margin: 0, fontSize: 'var(--font-xs, 0.75rem)', color: 'var(--text-faint)' }}>
            {online
              ? t('tournamentUi.savingOnline')
              : t('tournamentUi.savingLocal')}
          </p>
          <input
            ref={inputRef}
            type="text"
            value={name}
            onChange={(e) => onNameChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') onConfirm()
              if (e.key === 'Escape') onCancel()
            }}
            placeholder={t('tournamentUi.deckName')}
            style={{
              background: 'rgba(0, 0, 0, 0.3)',
              border: '1px solid rgba(255, 255, 255, 0.12)',
              borderRadius: 'var(--radius-sm)',
              padding: 'var(--space-2) var(--space-3)',
              color: 'var(--text-primary)',
              fontSize: '0.95rem',
            }}
          />
          <div style={{ display: 'flex', gap: 'var(--space-2)', justifyContent: 'flex-end' }}>
            <button
              type="button"
              onClick={onCancel}
              className={styles.leaveButton}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              onClick={onConfirm}
              disabled={!name.trim()}
              className={styles.startButton}
            >
              {t('tournamentUi.saveToMyDecks')}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Read-only deck viewer modal for viewing submitted deck during tournament.
 */
function DeckViewerModal({
  deckBuildingState,
  onClose,
}: {
  deckBuildingState: { cardPool: readonly SealedCardInfo[]; basicLands: readonly SealedCardInfo[]; deck: readonly string[]; landCounts: Record<string, number> }
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const [hoveredCard, setHoveredCard] = useState<SealedCardInfo | null>(null)
  const [hoverPos, setHoverPos] = useState<{ x: number; y: number } | null>(null)
  const hydrateDeckLibrary = useDeckLibrary((s) => s.hydrate)
  const { save: saveDeckRouted, isLoggedIn } = useSaveDeck()
  useEffect(() => { hydrateDeckLibrary() }, [hydrateDeckLibrary])
  const now = new Date()
  const defaultDeckName = t('tournamentUi.draftedDeckNameWithTime', {
    date: now.toLocaleDateString(i18n.language),
    time: now.toLocaleTimeString(i18n.language, { hour: '2-digit', minute: '2-digit' }),
  })
  const [saveName, setSaveName] = useState(defaultDeckName)
  const [savedAt, setSavedAt] = useState<number | null>(null)

  const handleHover = (card: SealedCardInfo | null, e?: React.MouseEvent) => {
    setHoveredCard(card)
    if (card && e) {
      setHoverPos({ x: e.clientX, y: e.clientY })
    } else {
      setHoverPos(null)
    }
  }

  const cardInfoMap = new Map<string, SealedCardInfo>()
  for (const card of deckBuildingState.cardPool) {
    cardInfoMap.set(card.name, card)
  }
  for (const land of deckBuildingState.basicLands) {
    cardInfoMap.set(land.name, land)
  }

  // Count cards in deck
  const deckCounts = new Map<string, number>()
  for (const name of deckBuildingState.deck) {
    deckCounts.set(name, (deckCounts.get(name) ?? 0) + 1)
  }

  // Calculate CMC for a card
  const getCmc = (card: SealedCardInfo): number => {
    if (!card.manaCost) return 0
    let cmc = 0
    for (const match of card.manaCost.matchAll(/\{([^}]+)\}/g)) {
      const sym = match[1] ?? ''
      const num = parseInt(sym, 10)
      if (!isNaN(num)) cmc += num
      else if (sym !== 'X') cmc += 1
    }
    return cmc
  }

  // Group cards by CMC
  const grouped = new Map<number, { card: SealedCardInfo; count: number }[]>()
  for (const [name, count] of deckCounts) {
    const card = cardInfoMap.get(name)
    if (!card) continue
    const cmc = getCmc(card)
    if (!grouped.has(cmc)) grouped.set(cmc, [])
    grouped.get(cmc)!.push({ card, count })
  }
  // Sort groups by CMC, cards within by name
  const sortedGroups = [...grouped.entries()].sort((a, b) => a[0] - b[0])
  for (const [, cards] of sortedGroups) {
    cards.sort((a, b) => a.card.name.localeCompare(b.card.name))
  }

  // Calculate stats
  const totalLands = Object.values(deckBuildingState.landCounts).reduce((s, n) => s + n, 0)
  const totalSpellCards = deckBuildingState.deck.length
  let creatures = 0
  let nonCreatureSpells = 0
  for (const [name, count] of deckCounts) {
    const card = cardInfoMap.get(name)
    if (!card) continue
    if (card.typeLine.toLowerCase().includes('creature')) creatures += count
    else nonCreatureSpells += count
  }
  const totalCards = totalSpellCards + totalLands

  return (
    <div className={styles.deckViewerBackdrop} onClick={onClose}>
      <div className={styles.deckViewerPanel} onClick={(e) => e.stopPropagation()}>
        <div className={styles.deckViewerHeader}>
          <h3 className={styles.deckViewerTitle}>{t('tournamentUi.yourDeckCount', { count: totalCards })}</h3>
          <button className={styles.deckViewerClose} onClick={onClose}>
            &#x2715;
          </button>
        </div>
        <div className={styles.deckViewerBody}>
          {/* Stats */}
          <div className={styles.deckViewerStats}>
            <div className={styles.deckViewerStat}>
              <span className={styles.deckViewerStatValue}>{creatures}</span>
              <span className={styles.deckViewerStatLabel}>{t('tournamentUi.creatures')}</span>
            </div>
            <div className={styles.deckViewerStat}>
              <span className={styles.deckViewerStatValue}>{nonCreatureSpells}</span>
              <span className={styles.deckViewerStatLabel}>{t('tournamentUi.spells')}</span>
            </div>
            <div className={styles.deckViewerStat}>
              <span className={styles.deckViewerStatValue}>{totalLands}</span>
              <span className={styles.deckViewerStatLabel}>{t('tournamentUi.lands')}</span>
            </div>
          </div>

          {/* Card list grouped by CMC */}
          {sortedGroups.map(([cmc, cards]) => (
            <div key={cmc} className={styles.deckViewerGroup}>
              <div className={styles.deckViewerGroupHeader}>
                {t('tournamentUi.cmcCount', { cmc })} ({cards.reduce((s, c) => s + c.count, 0)})
              </div>
              {cards.map(({ card, count }) => (
                <div
                  key={card.name}
                  className={styles.deckViewerRow}
                  onMouseEnter={(e) => handleHover(card, e)}
                  onMouseMove={(e) => handleHover(card, e)}
                  onMouseLeave={() => handleHover(null)}
                >
                  <span className={styles.deckViewerCount}>{count}</span>
                  <span className={styles.deckViewerCardName}>{card.name}</span>
                  <span className={styles.deckViewerManaCost}>
                    {card.manaCost
                      ? <ManaCost cost={card.manaCost} size={12} />
                      : <span style={{ color: '#666', fontSize: 10 }}>({getCmc(card)})</span>}
                  </span>
                </div>
              ))}
            </div>
          ))}

          {/* Basic lands */}
          {totalLands > 0 && (
            <div className={styles.deckViewerLandsSection}>
              <div className={styles.deckViewerGroupHeader}>
                {t('tournamentUi.landsCount', { count: totalLands })}
              </div>
              {Object.entries(deckBuildingState.landCounts)
                .filter(([, count]) => count > 0)
                .map(([name, count]) => {
                  const landCard = cardInfoMap.get(name)
                  return (
                    <div
                      key={name}
                      className={styles.deckViewerLandRow}
                      onMouseEnter={(e) => landCard && handleHover(landCard, e)}
                      onMouseMove={(e) => landCard && handleHover(landCard, e)}
                      onMouseLeave={() => handleHover(null)}
                    >
                      <span className={styles.deckViewerCount}>{count}</span>
                      <span className={styles.deckViewerLandName}>{name}</span>
                    </div>
                  )
                })}
            </div>
          )}
        </div>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: 'var(--space-3) var(--space-5)',
            borderTop: '1px solid rgba(255, 255, 255, 0.08)',
          }}
        >
          <input
            type="text"
            value={saveName}
            onChange={(e) => setSaveName(e.target.value)}
            placeholder={t('tournamentUi.deckName')}
            style={{
              flex: 1,
              background: 'rgba(0, 0, 0, 0.3)',
              border: '1px solid rgba(255, 255, 255, 0.1)',
              borderRadius: 'var(--radius-sm)',
              padding: 'var(--space-1) var(--space-2)',
              color: 'var(--text-primary)',
              fontSize: '0.85rem',
            }}
          />
          <button
            type="button"
            onClick={() => {
              // Combine non-basic deck cards with basic-land counts, pinning each to the
              // printing it was drafted as so the saved deck keeps the exact art/printing.
              const { cards, entries } = buildDraftedDeckSave(
                deckBuildingState.deck,
                deckBuildingState.landCounts,
                [...deckBuildingState.cardPool, ...deckBuildingState.basicLands],
              )
              // Routes to the account when signed in, localStorage otherwise.
              void saveDeckRouted({ name: saveName.trim() || defaultDeckName, cards, ...(entries ? { entries } : {}) })
              setSavedAt(Date.now())
              setTimeout(() => setSavedAt(null), 2000)
            }}
            disabled={totalCards === 0}
            className={styles.startButton}
            style={{ flexShrink: 0 }}
            title={isLoggedIn ? t('tournamentUi.saveAccountTitle') : t('tournamentUi.saveBrowserTitle')}
          >
            {savedAt ? t('tournamentUi.saved') : isLoggedIn ? t('tournamentUi.saveToMyDecksOnline') : t('tournamentUi.saveToMyDecks')}
          </button>
        </div>
      </div>

      {/* Card image preview on hover */}
      {hoveredCard && <DeckViewerCardPreview card={hoveredCard} pos={hoverPos} />}
    </div>
  )
}

/**
 * Card image preview that follows the cursor, shown when hovering cards in the deck viewer.
 */
function DeckViewerCardPreview({ card, pos }: { card: SealedCardInfo; pos: { x: number; y: number } | null }) {
  const imageUrl = getCardImageUrl(card.name, card.imageUri, 'large')
  const previewWidth = 250
  const previewHeight = Math.round(previewWidth * 1.4)

  let top = 80
  let left = 20
  if (pos) {
    const margin = 20
    if (pos.x + previewWidth + margin + 20 < window.innerWidth) {
      left = pos.x + margin
    } else {
      left = pos.x - previewWidth - margin
    }
    top = Math.max(10, Math.min(pos.y - previewHeight / 2, window.innerHeight - previewHeight - 10))
  }

  return (
    <div
      style={{
        position: 'fixed',
        top,
        left,
        pointerEvents: 'none',
        zIndex: 1002,
        transition: 'top 0.05s, left 0.05s',
      }}
    >
      <div
        style={{
          width: previewWidth,
          height: previewHeight,
          borderRadius: 12,
          overflow: 'hidden',
          boxShadow: '0 8px 32px rgba(0, 0, 0, 0.8)',
        }}
      >
        <img
          src={imageUrl}
          alt={card.name}
          style={{
            width: '100%',
            height: '100%',
            objectFit: 'cover',
          }}
        />
      </div>
    </div>
  )
}

/** The card art behind a tint, as on the landing screen; the tint is darker since this is a table. */
const tournamentBackdrop = {
  backgroundImage: `linear-gradient(180deg, rgba(6, 7, 12, 0.7) 0%, rgba(6, 7, 12, 0.6) 45%, rgba(6, 7, 12, 0.82) 100%), url(${randomBackground})`,
}

/**
 * Admin Live overview: what the server is doing right now — every game session in memory (public or
 * private, human or AI) and every tournament lobby past its waiting room. Built to answer "is this a
 * good moment for maintenance?": the headline counts the games and lobbies a restart would interrupt,
 * and each game shows how long ago someone last acted in it. Any started game can be watched in a new
 * tab through the ordinary `/?spectate=` deep link (as an ephemeral spectator the seated players see in
 * their watcher list). Above the games, every online player with what they're doing — playing,
 * drafting, in a lobby, searching, in the deckbuilder — and a feed of recent moves
 * ({@link OnlinePlayersPanel}, {@link ActivityFeedPanel}).
 *
 * Each game shows when it started and, once no human is connected, when the server's abandoned-game
 * sweep will end it; an "End" button ends one right away (as a draw).
 *
 * Polls every {@link REFRESH_MS}; gated behind the dashboard's shared {@link AdminAuth}.
 */
import { useCallback, useEffect, useState } from 'react'
import type React from 'react'
import { useTranslation } from 'react-i18next'
import { type LiveGame, type LiveLobby, type LiveOverview, endLiveGame, fetchLiveOverview } from '@/api/adminLiveOverview'
import type { AdminAuth } from '@/api/adminAuth'
import { formatAgo, formatClock, gameModeLabel } from './statFormat'
import { ActivityFeedPanel, OnlinePlayersPanel } from './AdminOnlinePlayers'
import { AdminScreen, Panel, StatCard, Table, adminTheme, cellStyle } from './adminUi'

const REFRESH_MS = 10_000
/** A game with no action for this long is shown as idle — likely abandoned rather than being played. */
const IDLE_AFTER_MS = 5 * 60_000

export function AdminLiveOverview({ auth, onBack }: { auth: AdminAuth; onBack: () => void }) {
  const { t } = useTranslation()
  const [overview, setOverview] = useState<LiveOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [showAiOnly, setShowAiOnly] = useState(false)

  const load = useCallback(async () => {
    try {
      setOverview(await fetchLiveOverview(auth))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('adminLive.loadFailed'))
    }
  }, [auth, t])

  const endGame = useCallback(
    async (game: LiveGame) => {
      const players = game.seats.map((s) => s.name).join(` ${t('adminActivity.versus')} `)
      if (!window.confirm(t('adminLive.endConfirm', { players }))) return
      try {
        await endLiveGame(auth, game.gameSessionId)
      } catch (e) {
        setError(e instanceof Error ? e.message : t('adminLive.endFailed'))
      }
      void load()
    },
    [auth, load, t],
  )

  useEffect(() => {
    void load()
    const id = window.setInterval(() => void load(), REFRESH_MS)
    return () => window.clearInterval(id)
  }, [load])

  const now = overview ? Date.parse(overview.generatedAt) : Date.now()
  const running = overview?.games.filter((g) => !g.gameOver) ?? []
  const withHumans = running.filter((g) => g.hasConnectedHuman)
  const activeWithHumans = withHumans.filter((g) => !isIdle(g, now))
  const aiOnly = running.filter((g) => !g.hasConnectedHuman)
  const busyLobbies = overview?.lobbies.filter((l) => l.connectedHumans > 0) ?? []
  const visible = showAiOnly ? running : withHumans

  return (
    <AdminScreen
      title={t('adminLive.overviewTitle')}
      subtitle={t('adminLive.overviewSubtitle')}
      onBack={onBack}
      backLabel={t('adminStats.back')}
      right={
        <button type="button" style={styles.refresh} onClick={() => void load()}>
          {t('adminLive.refresh')}
        </button>
      }
    >
      {error && <p style={styles.error}>{error}</p>}
      {overview && (
        <>
          <MaintenanceVerdict activeGames={activeWithHumans.length} idleGames={withHumans.length - activeWithHumans.length} lobbies={busyLobbies.length} />

          <div style={styles.metrics}>
            <StatCard label={t('adminLive.onlinePlayers')} value={overview.onlinePlayers} />
            <StatCard label={t('adminLive.gamesWithPlayers')} value={withHumans.length} accent={withHumans.length > 0} />
            <StatCard label={t('adminLive.activeLastFive')} value={activeWithHumans.length} />
            <StatCard label={t('adminLive.noConnectedHuman')} value={aiOnly.length} />
            <StatCard label={t('adminLive.runningTournaments')} value={busyLobbies.length} />
          </div>

          <div style={styles.activityRow}>
            <div style={styles.playersCol}>
              <OnlinePlayersPanel players={overview.players} now={now} />
            </div>
            <div style={styles.feedCol}>
              <ActivityFeedPanel feed={overview.feed} now={now} />
            </div>
          </div>

          <Panel
            title={t('adminLive.games')}
            subtitle={t('adminLive.refreshInterval', { time: formatClock(overview.generatedAt), seconds: REFRESH_MS / 1000 })}
            action={
              <label style={styles.toggle}>
                <input type="checkbox" checked={showAiOnly} onChange={(e) => setShowAiOnly(e.target.checked)} />
                {t('adminLive.showAiOnly', { count: aiOnly.length })}
              </label>
            }
          >
            {visible.length === 0 ? (
              <p style={cellStyle.muted}>{t('adminLive.noConnectedGames')}</p>
            ) : (
              <Table head={[t('adminLive.players'), t('adminLive.mode'), t('adminLive.turn'), t('adminLive.started'), t('adminLive.lastAction'), t('adminLive.autoEnds'), t('adminLive.watchingCount'), '']} leftColumns={6}>
                {visible.map((g) => (
                  <GameRow key={g.gameSessionId} game={g} now={now} onEnd={() => void endGame(g)} />
                ))}
              </Table>
            )}
          </Panel>

          <Panel title={t('adminLive.tournamentLobbies')} subtitle={t('adminLive.lobbySubtitle')}>
            {overview.lobbies.length === 0 ? (
              <p style={cellStyle.muted}>{t('adminLive.noTournamentsRunning')}</p>
            ) : (
              <Table head={[t('adminLive.mode'), t('adminLive.sets'), t('adminLive.stage'), t('adminLive.playersOnline'), t('adminLive.round')]} leftColumns={3}>
                {overview.lobbies.map((l) => (
                  <LobbyRow key={l.lobbyId} lobby={l} />
                ))}
              </Table>
            )}
          </Panel>
        </>
      )}
    </AdminScreen>
  )
}

function MaintenanceVerdict({ activeGames, idleGames, lobbies }: { activeGames: number; idleGames: number; lobbies: number }) {
  const { t } = useTranslation()
  const quiet = activeGames === 0 && lobbies === 0
  const parts: string[] = []
  if (activeGames > 0) parts.push(t(activeGames === 1 ? 'adminLive.activeGameOne' : 'adminLive.activeGamesMany', { count: activeGames }))
  if (lobbies > 0) parts.push(t(lobbies === 1 ? 'adminLive.runningTournamentOne' : 'adminLive.runningTournamentsMany', { count: lobbies }))
  if (idleGames > 0) parts.push(t(idleGames === 1 ? 'adminLive.idleGameOne' : 'adminLive.idleGamesMany', { count: idleGames }))
  return (
    <div style={{ ...styles.verdict, borderColor: quiet ? adminTheme.good : adminTheme.bad }}>
      <span style={{ ...styles.verdictDot, backgroundColor: quiet ? adminTheme.good : adminTheme.bad }} aria-hidden />
      <span>
        <strong style={{ color: adminTheme.text }}>{quiet ? t('adminLive.quietRestart') : t('adminLive.busyRestart')}</strong>
        {parts.length > 0 && <span style={styles.verdictDetail}> {parts.join(' · ')}.</span>}
      </span>
    </div>
  )
}

function GameRow({ game, now, onEnd }: { game: LiveGame; now: number; onEnd: () => void }) {
  const { t, i18n } = useTranslation()
  const mode = gameModeLabel(game.gameMode, game.format)
  const idle = isIdle(game, now)
  const locale = i18n.resolvedLanguage ?? 'en'
  return (
    <tr>
      <td style={cellStyle.td}>
        {game.seats.map((s, i) => (
          <span key={`${s.name}-${i}`}>
            {i > 0 && <span style={styles.vs}> {t('adminActivity.versus')} </span>}
            <span style={s.name === game.activePlayerName ? styles.active : undefined}>{s.name}</span>
            {s.isAi ? (
              <span style={styles.tag}> {t('adminActivity.ai')}</span>
            ) : (
              !s.connected && <span style={styles.disconnected} title={t('adminLive.disconnected')}> ⦸</span>
            )}
            {s.life != null && <span style={styles.life}> {s.life}</span>}
          </span>
        ))}
      </td>
      <td style={cellStyle.td}>
        {mode.primary}
        {mode.variant ? <span style={styles.tag}> › {mode.variant}</span> : null}
        {game.setCode ? <span style={styles.tag}> · {game.setCode}</span> : null}
        {game.ranked ? <span style={styles.ranked}> · {t('adminLive.ranked')}</span> : null}
      </td>
      <td style={cellStyle.td}>
        {game.gameOver
          ? t('adminLive.over')
          : !game.started
            ? t('adminLive.pregame')
            : t('adminLive.turnStep', {
                turn: game.turnNumber ?? '?',
                step: game.step ? t(`phases.steps.${game.step}`, { defaultValue: prettyStep(game.step) }) : '—',
              })}
      </td>
      <td style={cellStyle.td}>
        {game.startedAt ? (
          <>
            {formatStarted(game.startedAt, now, locale)}
            <span style={styles.tag}> · {t('adminLive.ago', { time: formatAgo(now - Date.parse(game.startedAt)) })}</span>
          </>
        ) : (
          '—'
        )}
      </td>
      <td style={{ ...cellStyle.td, color: idle ? adminTheme.textMuted : adminTheme.good }}>
        {game.lastActionAt ? t('adminLive.ago', { time: formatAgo(now - Date.parse(game.lastActionAt)) }) : '—'}
      </td>
      <td style={cellStyle.td} title={t('adminLive.autoEndTitle')}>
        {game.autoEndAt ? <span style={styles.tag}>{t('adminLive.autoEndIn', { time: formatAgo(Date.parse(game.autoEndAt) - now) })}</span> : '—'}
      </td>
      <td style={cellStyle.tdNum}>{game.spectatorCount || '—'}</td>
      <td style={{ ...cellStyle.tdNum, whiteSpace: 'nowrap' }}>
        {game.started && !game.gameOver ? (
          <a style={styles.link} href={`/?spectate=${encodeURIComponent(game.gameSessionId)}`} target="_blank" rel="noreferrer">
            {t('adminLive.watch')} ↗
          </a>
        ) : (
          <span style={styles.tag}>—</span>
        )}
        {!game.gameOver && (
          <button type="button" style={styles.end} onClick={onEnd} title={t('adminLive.endGameTitle')}>
            {t('adminLive.endGame')}
          </button>
        )}
      </td>
    </tr>
  )
}

function LobbyRow({ lobby }: { lobby: LiveLobby }) {
  const mode = gameModeLabel(lobby.gameMode, lobby.format)
  return (
    <tr>
      <td style={cellStyle.td}>
        {mode.primary}
        {mode.variant ? <span style={styles.tag}> › {mode.variant}</span> : null}
      </td>
      <td style={cellStyle.td}>{lobby.setNames.join(', ') || '—'}</td>
      <td style={cellStyle.td}>{prettyToken(lobby.state)}</td>
      <td style={cellStyle.tdNum}>
        {lobby.connectedHumans} / {lobby.humanPlayers}
      </td>
      <td style={cellStyle.tdNum}>{lobby.currentRound != null ? `${lobby.currentRound} / ${lobby.totalRounds ?? '?'}` : '—'}</td>
    </tr>
  )
}

/** Clock time for a game started today, date and time for an older one — the long-forgotten ones. */
function formatStarted(iso: string, now: number, locale: string): string {
  const started = new Date(iso)
  const time = started.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
  if (started.toLocaleDateString(locale) === new Date(now).toLocaleDateString(locale)) return time
  return `${started.toLocaleDateString(locale, { month: 'short', day: 'numeric' })} ${time}`
}

function isIdle(game: LiveGame, now: number): boolean {
  const last = game.lastActionAt ?? game.startedAt
  return !last || now - Date.parse(last) > IDLE_AFTER_MS
}

function prettyToken(token: string): string {
  return token
    .toLowerCase()
    .split('_')
    .map((w) => (w ? w.charAt(0).toUpperCase() + w.slice(1) : w))
    .join(' ')
}

function prettyStep(step: string | null): string {
  return step ? prettyToken(step) : '—'
}

const styles: Record<string, React.CSSProperties> = {
  error: { color: adminTheme.bad, fontSize: 13, margin: '0 0 12px' },
  // Players take the width; the feed sits beside them on a wide screen and drops below on a narrow one.
  activityRow: { display: 'flex', flexWrap: 'wrap', gap: 18, marginBottom: 18, alignItems: 'flex-start' },
  playersCol: { flex: '2 1 480px', minWidth: 0 },
  feedCol: { flex: '1 1 280px', minWidth: 0 },
  metrics: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12, marginBottom: 18 },
  verdict: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    padding: '14px 16px',
    marginBottom: 18,
    borderRadius: 12,
    border: '1px solid',
    backgroundColor: adminTheme.panel,
    color: adminTheme.textSecondary,
    fontSize: 14,
  },
  verdictDot: { width: 10, height: 10, borderRadius: '50%', flexShrink: 0 },
  verdictDetail: { color: adminTheme.textSecondary },
  toggle: { display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, color: adminTheme.textMuted, cursor: 'pointer' },
  vs: { color: adminTheme.textMuted },
  active: { color: adminTheme.text, fontWeight: 600 },
  tag: { color: adminTheme.textMuted, fontSize: 12 },
  ranked: { color: adminTheme.accent, fontSize: 12 },
  life: { color: adminTheme.textMuted, fontSize: 12, fontVariantNumeric: 'tabular-nums' },
  disconnected: { color: adminTheme.bad, fontSize: 12 },
  link: { color: adminTheme.accent, fontSize: 13, textDecoration: 'none', whiteSpace: 'nowrap' },
  end: {
    marginLeft: 10,
    background: 'none',
    border: `1px solid ${adminTheme.border}`,
    borderRadius: 6,
    color: adminTheme.bad,
    padding: '2px 8px',
    font: 'inherit',
    fontSize: 12,
    cursor: 'pointer',
  },
  refresh: {
    background: 'none',
    border: `1px solid ${adminTheme.border}`,
    borderRadius: 8,
    color: adminTheme.text,
    padding: '6px 12px',
    font: 'inherit',
    fontSize: 13,
    cursor: 'pointer',
  },
}

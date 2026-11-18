/**
 * AI Sandbox — a dev-only "put two bots at a table and let me watch" screen.
 *
 * The LLM Tournament page next door exists to compare *models*; this one exists to watch the
 * built-in engine AI play so its mistakes are visible. Everything is driven by the existing
 * `/api/dev/ai-tournament` endpoint (the same one `just watch-ai-match` uses): create a private,
 * AI-only sealed lobby, let the bots build decks and start matches unattended, then hand you the
 * live game.
 *
 * The lobby id lives in the URL (`/ai-sandbox/:lobbyId`) so a refresh — and the browser Back
 * button out of a spectated game — comes back to the same sandbox rather than an empty form.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { DevPage } from '@/components/admin/DevPage'
import { useNavigate, useParams } from 'react-router-dom'
import type { AvailableSet } from '@/types/messages'
import { SetPickerModal } from '@/components/ui/SetPickerModal'
import { useTranslation } from 'react-i18next'

const API = '/api/dev/ai-tournament'

interface LiveGame {
  gameSessionId: string
  player1Name: string
  player2Name: string
  player1Life: number
  player2Life: number
  turnNumber: number
}

interface SandboxStatus {
  lobbyId: string
  state: string
  playerNames: string[]
  decksSubmitted: number
  round: number
  totalRounds: number
  complete: boolean
  liveGames: LiveGame[]
}

/** Auto-watch is a preference, not a per-run choice — it survives the jump into the game. */
const AUTO_WATCH_KEY = 'argentum-ai-sandbox-autowatch'

/**
 * Games we have already auto-jumped into, so coming Back from a spectated game doesn't bounce you
 * straight into the same one again. Session-scoped: a fresh tab starts clean.
 */
function markAutoWatched(gameSessionId: string) {
  sessionStorage.setItem(`argentum-ai-sandbox-watched:${gameSessionId}`, '1')
}
function wasAutoWatched(gameSessionId: string): boolean {
  return sessionStorage.getItem(`argentum-ai-sandbox-watched:${gameSessionId}`) === '1'
}

/**
 * Full page load rather than a react-router navigation: `/?spectate=` is read off
 * `window.location.search` when `App` mounts, and the spectator connects as its own ephemeral
 * session (see App.tsx).
 */
function watchGame(gameSessionId: string) {
  markAutoWatched(gameSessionId)
  window.location.assign(`/?spectate=${gameSessionId}`)
}

export function AiSandboxPage() {
  const { t } = useTranslation()
  const { lobbyId: routeLobbyId } = useParams<{ lobbyId?: string }>()
  const navigate = useNavigate()
  const [status, setStatus] = useState<SandboxStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [autoWatch, setAutoWatch] = useState(() => localStorage.getItem(AUTO_WATCH_KEY) !== 'false')

  const lobbyIdRef = useRef<string | null>(routeLobbyId ?? null)

  const refresh = useCallback(async () => {
    const id = lobbyIdRef.current
    if (!id) return
    try {
      const res = await fetch(`${API}/${id}`)
      if (res.ok) {
        setStatus(await res.json() as SandboxStatus)
      } else if (res.status === 404) {
        // Lobbies don't survive a server restart — drop back to the form rather than polling a
        // lobby id that will never answer again.
        lobbyIdRef.current = null
        setStatus(null)
        navigate('/ai-sandbox', { replace: true })
      }
    } catch {
      /* transient — keep the last view */
    }
  }, [navigate])

  useEffect(() => {
    lobbyIdRef.current = routeLobbyId ?? null
    if (routeLobbyId) void refresh()
    else setStatus(null)
  }, [routeLobbyId, refresh])

  useEffect(() => {
    const interval = setInterval(refresh, 1500)
    return () => clearInterval(interval)
  }, [refresh])

  useEffect(() => {
    localStorage.setItem(AUTO_WATCH_KEY, String(autoWatch))
  }, [autoWatch])

  // The whole point of the screen: as soon as the bots actually start playing, put the board on
  // screen without a click.
  useEffect(() => {
    if (!autoWatch || !status) return
    const next = status.liveGames.find((g) => !wasAutoWatched(g.gameSessionId))
    if (next) watchGame(next.gameSessionId)
  }, [autoWatch, status])

  return (
    <DevPage section={t('developerTools.section')} title={t('aiSandbox.title')} dev width="normal">

      {error && (
        <div style={styles.errorBar} onClick={() => setError(null)}>
          {error} <span style={{ float: 'right' }}>✕</span>
        </div>
      )}

      {!status && (
        <SetupForm
          onCreated={(lobbyId) => {
            lobbyIdRef.current = lobbyId
            navigate(`/ai-sandbox/${lobbyId}`)
          }}
          onError={setError}
        />
      )}

      {status && (
        <StatusPanel
          status={status}
          autoWatch={autoWatch}
          onToggleAutoWatch={setAutoWatch}
          onNew={() => {
            lobbyIdRef.current = null
            setStatus(null)
            navigate('/ai-sandbox')
          }}
        />
      )}
    </DevPage>
  )
}

// ============================================================================
// Setup form
// ============================================================================

function SetupForm({
  onCreated,
  onError,
}: {
  onCreated: (lobbyId: string) => void
  onError: (e: string) => void
}) {
  const { t } = useTranslation()
  const [sets, setSets] = useState<AvailableSet[]>([])
  const [setCodes, setSetCodes] = useState<string[]>([])
  const [pickerOpen, setPickerOpen] = useState(false)
  const [playerCount, setPlayerCount] = useState(2)
  const [gamesPerMatch, setGamesPerMatch] = useState(3)
  const [deckbuild, setDeckbuild] = useState<'draftsim' | 'llm'>('draftsim')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    fetch(`${API}/sets`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((s: AvailableSet[]) => {
        setSets(s)
        // Newest fully-implemented set, matching the lobby picker's bias — a partial set makes the
        // bots look worse than they are.
        const def = s
          .filter((x) => !x.partial && !x.extensionSet)
          .sort((a, b) => (b.releaseDate ?? '').localeCompare(a.releaseDate ?? ''))[0]
        if (def) setSetCodes([def.code])
      })
      .catch(() => onError(t('aiSandbox.loadSetsError')))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const selectedNames = setCodes
    .map((code) => sets.find((s) => s.code === code)?.name ?? code)
    .join(' + ')

  const create = async () => {
    if (setCodes.length === 0) {
      onError(t('aiSandbox.pickAtLeastOneSet'))
      return
    }
    setBusy(true)
    try {
      const res = await fetch(API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          setCodes,
          playerCount,
          gamesPerMatch,
          skipLlmDeckbuilding: deckbuild === 'draftsim',
        }),
      })
      const data = await res.json() as { lobbyId: string; message: string }
      if (res.ok && data.lobbyId) onCreated(data.lobbyId)
      else onError(data.message || t('aiSandbox.createFailed'))
    } catch (e) {
      onError(String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={styles.card}>
      <h2 style={styles.h2}>{t('aiSandbox.newLobby')}</h2>

      <div style={styles.formRow}>
        <div style={{ flex: 1 }}>
          <label style={styles.label}>{t('aiSandbox.setsSealedPool')}</label>
          <button type="button" style={styles.setButton} onClick={() => setPickerOpen(true)}>
            {selectedNames || t('aiSandbox.chooseSets')}
          </button>
        </div>
        <div>
          <label style={styles.label}>{t('aiSandbox.aiPlayers')}</label>
          <input
            type="number"
            min={2}
            max={8}
            value={playerCount}
            onChange={(e) => setPlayerCount(Math.min(8, Math.max(2, Number(e.target.value))))}
            style={{ ...styles.select, width: 70 }}
          />
        </div>
        <div>
          <label style={styles.label}>{t('aiSandbox.gamesPerPairing')}</label>
          <input
            type="number"
            min={1}
            max={9}
            value={gamesPerMatch}
            onChange={(e) => setGamesPerMatch(Math.min(9, Math.max(1, Number(e.target.value))))}
            style={{ ...styles.select, width: 70 }}
          />
        </div>
      </div>

      <label style={styles.label}>{t('aiSandbox.deckBuilding')}</label>
      <div style={styles.radioRow}>
        <label style={styles.radioLabel}>
          <input type="radio" checked={deckbuild === 'draftsim'} onChange={() => setDeckbuild('draftsim')} />
          {t('aiSandbox.draftsimFast')}
        </label>
        <label style={styles.radioLabel}>
          <input type="radio" checked={deckbuild === 'llm'} onChange={() => setDeckbuild('llm')} />
          {t('aiSandbox.serverDefault')}
        </label>
      </div>

      <div style={{ marginTop: 18 }}>
        <button style={styles.primaryBtn} disabled={busy} onClick={create}>
          {busy ? t('aiSandbox.creating') : t('aiSandbox.createAndWatch')}
        </button>
      </div>

      <p style={styles.smallNote}>
        {t('aiSandbox.descriptionBeforeConfig')}{' '}
        (<code>game.ai.mode</code> — <code>engine</code> {t('aiSandbox.default')}).{' '}
        {t('aiSandbox.descriptionAfterConfig')} <code>game.dev-endpoints.enabled=true</code>.
      </p>

      {pickerOpen && (
        <SetPickerModal
          sets={sets}
          selectedCodes={setCodes}
          mode="multi"
          title={t('aiSandbox.chooseSets')}
          onToggleSet={(code) =>
            setSetCodes((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]))
          }
          onClose={() => setPickerOpen(false)}
        />
      )}
    </div>
  )
}

// ============================================================================
// Status panel
// ============================================================================

function StatusPanel({
  status,
  autoWatch,
  onToggleAutoWatch,
  onNew,
}: {
  status: SandboxStatus
  autoWatch: boolean
  onToggleAutoWatch: (v: boolean) => void
  onNew: () => void
}) {
  const { t } = useTranslation()
  const stateLabel = (state: string) => {
    switch (state) {
      case 'WAITING_FOR_PLAYERS': return t('aiSandbox.statusSeatingBots')
      case 'DECK_BUILDING': return t('aiSandbox.statusBuildingDecks')
      case 'TOURNAMENT_ACTIVE': return t('aiSandbox.statusPlaying')
      case 'TOURNAMENT_COMPLETE': return t('aiSandbox.statusFinished')
      default: return state
    }
  }
  const label = stateLabel(status.state)
  const building = status.state === 'DECK_BUILDING'

  return (
    <>
      <div style={styles.pacingBar}>
        <div style={styles.statusGroup}>
          <span style={{ ...styles.statusPill, ...statePillColor(status.state) }}>{label}</span>
          <span style={styles.metaText}>
            {t('aiSandbox.botCount', { count: status.playerNames.length })}
            {status.totalRounds > 0 && <> · {t('aiSandbox.roundProgress', { round: status.round, total: status.totalRounds })}</>}
            {building && <> · {t('aiSandbox.decksBuilt', { built: status.decksSubmitted, total: status.playerNames.length })}</>}
          </span>
        </div>
        <div style={styles.controlsGroup}>
          <label style={styles.radioLabel} title={t('aiSandbox.autoWatchTitle')}>
            <input type="checkbox" checked={autoWatch} onChange={(e) => onToggleAutoWatch(e.target.checked)} />
            {t('aiSandbox.autoWatch')}
          </label>
          <a href={`/tournament/${status.lobbyId}`} style={styles.ctrlBtn}>{t('aiSandbox.standings')}</a>
          <button style={styles.secondaryBtn} onClick={onNew}>{t('aiSandbox.newLobby')}</button>
        </div>
      </div>

      <div style={styles.card}>
        <h2 style={styles.h2}>{t('aiSandbox.liveGames')}</h2>
        {status.liveGames.length === 0 ? (
          <p style={styles.hint}>
            {status.complete
              ? t('aiSandbox.allMatchesDone')
              : t('aiSandbox.noGamesYet')}
          </p>
        ) : (
          status.liveGames.map((game) => (
            <div key={game.gameSessionId} style={styles.gameRow}>
              <div>
                <div style={styles.gameNames}>{game.player1Name} vs {game.player2Name}</div>
                <div style={styles.hint}>
                  {t('aiSandbox.gameStatus', { turn: game.turnNumber, player1Life: game.player1Life, player2Life: game.player2Life })}
                </div>
              </div>
              <button style={styles.watchBtn} onClick={() => watchGame(game.gameSessionId)}>
                {t('aiSandbox.watch')}
              </button>
            </div>
          ))
        )}
      </div>

      <div style={styles.card}>
        <h2 style={styles.h2}>{t('aiSandbox.table')}</h2>
        <div style={styles.botList}>
          {status.playerNames.map((name) => (
            <span key={name} style={styles.botChip}>{name}</span>
          ))}
        </div>
      </div>
    </>
  )
}

function statePillColor(state: string): React.CSSProperties {
  switch (state) {
    case 'TOURNAMENT_ACTIVE':
      return { background: '#166534', color: '#dcfce7' }
    case 'TOURNAMENT_COMPLETE':
      return { background: '#3730a3', color: '#e0e7ff' }
    default:
      return { background: '#78350f', color: '#fef3c7' }
  }
}

// ============================================================================
// Styles — inline, matching the LLM Tournament page's dev-tool look.
// ============================================================================

const styles: Record<string, React.CSSProperties> = {
  h2: { fontFamily: 'var(--font-display)', fontSize: 19, fontWeight: 600, margin: '0 0 14px', color: '#f1f3f9' },
  errorBar: { background: 'rgba(127, 29, 29, 0.75)', border: '1px solid rgba(248, 113, 113, 0.5)', color: '#fecaca', padding: '10px 14px', borderRadius: 10, marginBottom: 0, cursor: 'pointer' },
  card: { background: 'rgba(10, 12, 20, 0.72)', backdropFilter: 'blur(18px) saturate(140%)', WebkitBackdropFilter: 'blur(18px) saturate(140%)', border: '1px solid rgba(255, 255, 255, 0.09)', borderRadius: 16, padding: 18, marginBottom: 0, maxWidth: 760 },
  label: { display: 'block', fontSize: 11.5, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', color: '#a3abbd', marginBottom: 7 },
  hint: { fontSize: 12, color: '#8f98ac', marginTop: 6 },
  formRow: { display: 'flex', flexWrap: 'wrap', gap: 18, alignItems: 'flex-start', margin: '0 0 16px' },
  select: {
    background: 'rgba(0, 0, 0, 0.32)',
    color: '#eef0f6',
    border: '1px solid rgba(255, 255, 255, 0.16)',
    borderRadius: 8,
    padding: '8px 10px',
    fontSize: 13,
  },
  setButton: {
    background: 'rgba(0, 0, 0, 0.32)',
    color: '#eef0f6',
    border: '1px solid rgba(255, 255, 255, 0.16)',
    borderRadius: 8,
    padding: '8px 12px',
    fontSize: 13,
    cursor: 'pointer',
    textAlign: 'left',
    minWidth: 220,
  },
  radioRow: { display: 'flex', gap: 14, paddingTop: 6 },
  radioLabel: { fontSize: 13, display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' },
  primaryBtn: {
    background: '#f2b45c',
    color: '#1d1405',
    border: 'none',
    borderRadius: 10,
    padding: '11px 20px',
    boxShadow: '0 4px 16px rgba(242, 180, 92, 0.22)',
    fontSize: 14,
    cursor: 'pointer',
    fontWeight: 600,
  },
  smallNote: { fontSize: 11, color: '#8f98ac', marginTop: 12, lineHeight: 1.5 },
  pacingBar: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    flexWrap: 'wrap',
    gap: 12,
    background: 'rgba(10, 12, 20, 0.72)', backdropFilter: 'blur(18px) saturate(140%)', WebkitBackdropFilter: 'blur(18px) saturate(140%)',
    border: '1px solid rgba(255, 255, 255, 0.09)',
    borderRadius: 12,
    padding: '12px 16px',
    marginBottom: 0,
    maxWidth: 760,
  },
  statusGroup: { display: 'flex', alignItems: 'center', gap: 12 },
  statusPill: { fontSize: 12, fontWeight: 700, padding: '4px 12px', borderRadius: 999, letterSpacing: 0.5 },
  metaText: { fontSize: 12, color: '#a3abbd' },
  controlsGroup: { display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' },
  ctrlBtn: {
    background: 'rgba(255, 255, 255, 0.08)',
    color: '#eef0f6',
    border: '1px solid rgba(255, 255, 255, 0.16)',
    borderRadius: 8,
    padding: '8px 14px',
    fontSize: 13,
    cursor: 'pointer',
    textDecoration: 'none',
  },
  secondaryBtn: {
    background: 'transparent',
    color: '#eef0f6',
    border: '1px solid rgba(255, 255, 255, 0.16)',
    borderRadius: 8,
    padding: '8px 14px',
    fontSize: 13,
    cursor: 'pointer',
  },
  watchBtn: {
    background: '#166534',
    color: '#dcfce7',
    border: '1px solid #16a34a',
    borderRadius: 8,
    padding: '8px 16px',
    fontSize: 13,
    fontWeight: 600,
    cursor: 'pointer',
  },
  gameRow: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 12,
    padding: '10px 0',
    borderTop: '1px solid rgba(255, 255, 255, 0.09)',
  },
  gameNames: { fontSize: 14, fontWeight: 600 },
  botList: { display: 'flex', flexWrap: 'wrap', gap: 8 },
  botChip: {
    background: 'rgba(255, 255, 255, 0.08)',
    border: '1px solid rgba(255, 255, 255, 0.16)',
    borderRadius: 999,
    padding: '4px 12px',
    fontSize: 12,
    color: '#d5dae6',
  },
}

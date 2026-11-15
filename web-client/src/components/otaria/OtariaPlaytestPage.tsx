import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { DevPage } from '@/components/admin/DevPage'
import { pageStyles } from '@/components/ui/PageShell'
import type { ScenarioCreateResponse } from '@/components/scenario/types'
import { Color, ColorSymbols } from '@/types'
import styles from './OtariaPlaytestPage.module.css'

type StartingColor = Color | 'OTHER'

interface StartingDeck {
  id: string
  setCode: string
  theme: string
  color: StartingColor
  cards: Record<string, number>
  startingLands: string[]
  startingLandSource: 'DECK' | 'SEPARATE_STOCK'
}

interface OtariaLobbyPlayer {
  seatIndex: number
  name: string
  host: boolean
  ready: boolean
  you: boolean
}

interface OtariaLobby {
  lobbyId: string
  tableSize: number
  players: OtariaLobbyPlayer[]
  openSeats: number[]
  aiSeatsOnStart: number
  started: boolean
  memberToken?: string | null
  gameSessionId?: string | null
  gameToken?: string | null
}

export interface OtariaPlaytestOptions {
  aiEnabled: boolean
  decks: StartingDeck[]
  limitations: string[]
  missingCards: string[]
}

const COLOR_NAMES: Record<StartingColor, string> = {
  [Color.WHITE]: 'White',
  [Color.BLUE]: 'Blue',
  [Color.BLACK]: 'Black',
  [Color.RED]: 'Red',
  [Color.GREEN]: 'Green',
  OTHER: 'Other (colorless / multicolor)',
}

const API = '/api/dev/otaria'
const DEFAULT_AI_ACTION_DELAY_MS = 100

export async function readOtariaResponse(response: Response): Promise<unknown> {
  if (!response.ok && response.status === 404) {
    throw new Error('Otaria playtesting is only available on a local server with game.dev-endpoints.enabled=true.')
  }
  const text = await response.text()
  let body: unknown
  try {
    body = JSON.parse(text)
  } catch (cause) {
    if (!(cause instanceof SyntaxError)) throw cause
    if (!response.ok) {
      throw new Error(`The server could not process Otaria (HTTP ${response.status}). Check the server log and try again.`)
    }
    throw new Error(`The server returned an empty or invalid JSON response for Otaria (HTTP ${response.status}). Check the server log and try again.`)
  }
  if (!response.ok) {
    const detail = body && typeof body === 'object' && 'errors' in body && Array.isArray(body.errors)
      ? body.errors.filter((value): value is string => typeof value === 'string').join(' ')
      : null
    throw new Error(detail || `The server could not process Otaria (HTTP ${response.status}). Check the server log and try again.`)
  }
  return body
}

export function OtariaPlaytestPage() {
  const [options, setOptions] = useState<OtariaPlaytestOptions | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [starting, setStarting] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const launching = useRef(false)
  const redirected = useRef(false)
  const [name, setName] = useState(() => localStorage.getItem('argentum-player-name') || 'Explorer')
  const [opponents, setOpponents] = useState(2)
  const [hostSeat, setHostSeat] = useState(0)
  const [color, setColor] = useState<StartingColor | null>(null)
  const [boosterId, setBoosterId] = useState('')
  const [fullAi, setFullAi] = useState(false)
  const [aiThinkingDelayMs, setAiThinkingDelayMs] = useState(DEFAULT_AI_ACTION_DELAY_MS)
  const [online, setOnline] = useState(() => new URLSearchParams(window.location.search).has('lobby'))
  const [lobby, setLobby] = useState<OtariaLobby | null>(null)
  const [inviteLobbyId, setInviteLobbyId] = useState(() =>
    new URLSearchParams(window.location.search).get('lobby') || '',
  )
  const [invitePreview, setInvitePreview] = useState<OtariaLobby | null>(null)
  const [inviteSeat, setInviteSeat] = useState<number | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError(null)
    void (async () => {
      try {
        const response = await fetch(API, { signal: controller.signal })
        const data = await readOtariaResponse(response) as OtariaPlaytestOptions
        if (!controller.signal.aborted) setOptions(data)
      } catch (cause) {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'Could not load Otaria.')
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    })()
    return () => controller.abort()
  }, [attempt])

  useEffect(() => {
    if (!inviteLobbyId || lobby) return
    const controller = new AbortController()
    const memberToken = sessionStorage.getItem(`otaria-lobby-${inviteLobbyId}`)
    const memberQuery = memberToken ? `?memberToken=${encodeURIComponent(memberToken)}` : ''
    void fetch(`${API}/lobbies/${encodeURIComponent(inviteLobbyId)}${memberQuery}`, { signal: controller.signal })
      .then(readOtariaResponse)
      .then((data) => {
        if (!controller.signal.aborted) {
          const room = data as OtariaLobby
          if (room.memberToken) {
            setLobby(room)
            setOnline(true)
          } else {
            setInvitePreview(room)
            setInviteSeat(room.openSeats[0] ?? null)
          }
        }
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'Could not load this Otaria lobby.')
      })
    return () => controller.abort()
  }, [inviteLobbyId, lobby])

  const redirectWhenStarted = (session: OtariaLobby) => {
    if (redirected.current || !session.started || !session.gameToken) return
    redirected.current = true
    localStorage.setItem('argentum-player-name', name.trim())
    window.location.assign(`/?token=${encodeURIComponent(session.gameToken)}`)
  }

  useEffect(() => {
    if (!lobby?.memberToken) return
    const controller = new AbortController()
    const refresh = async () => {
      try {
        const response = await fetch(
          `${API}/lobbies/${encodeURIComponent(lobby.lobbyId)}?memberToken=${encodeURIComponent(lobby.memberToken!)}`,
          { signal: controller.signal },
        )
        const updated = await readOtariaResponse(response) as OtariaLobby
        if (!controller.signal.aborted) {
          setLobby(updated)
          redirectWhenStarted(updated)
        }
      } catch (cause) {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'Could not refresh the Otaria lobby.')
      }
    }
    void refresh()
    const timer = window.setInterval(() => void refresh(), 2000)
    return () => {
      controller.abort()
      window.clearInterval(timer)
    }
  }, [lobby?.lobbyId, lobby?.memberToken])

  const start = async () => {
    if (launching.current || (!online && !options?.aiEnabled) || !name.trim()) return
    launching.current = true
    setStarting(true)
    setError(null)
    try {
      const response = await fetch(online ? `${API}/lobbies` : API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(online
          ? {
              playerName: name.trim(), tableSize: opponents + 1, seatIndex: hostSeat,
              color, boosterId: boosterId || null,
            }
          : {
              playerName: name.trim(), aiOpponents: opponents, fullAi,
              ...(fullAi ? { aiThinkingDelayMs } : {}),
              color: fullAi ? null : color, boosterId: fullAi ? null : boosterId || null,
            }),
      })
      const result = await readOtariaResponse(response)
      if (online) {
        const session = result as OtariaLobby
        if (!session.memberToken) throw new Error('The server did not return your lobby membership token.')
        sessionStorage.setItem(`otaria-lobby-${session.lobbyId}`, session.memberToken)
        setLobby(session)
        setInvitePreview(null)
        setInviteLobbyId(session.lobbyId)
        window.history.replaceState(null, '', `/otaria?lobby=${encodeURIComponent(session.lobbyId)}`)
        launching.current = false
        setStarting(false)
        return
      }
      const session = result as ScenarioCreateResponse
      if (fullAi) {
        if (!session.sessionId || session.player1.token !== '(AI)' || session.player2.token !== '(AI)') {
          throw new Error('The server did not return an all-AI table. Check the server log before trying again.')
        }
        window.location.assign(`/?spectate=${encodeURIComponent(session.sessionId)}`)
        return
      }
      if (!session.player1.token || session.player1.token === '(AI)') {
        throw new Error('The server did not return a human seat. Check the server log before trying again.')
      }
      localStorage.setItem('argentum-player-name', name.trim())
      window.location.assign(`/?token=${encodeURIComponent(session.player1.token)}`)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not start Otaria.')
      launching.current = false
      setStarting(false)
    }
  }

  const joinLobby = async () => {
    if (!invitePreview || inviteSeat === null || !name.trim() || launching.current) return
    launching.current = true
    setStarting(true)
    setError(null)
    try {
      const response = await fetch(`${API}/lobbies/${encodeURIComponent(invitePreview.lobbyId)}/join`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          playerName: name.trim(), seatIndex: inviteSeat, color, boosterId: boosterId || null,
        }),
      })
      const joined = await readOtariaResponse(response) as OtariaLobby
      if (!joined.memberToken) throw new Error('The server did not return your lobby membership token.')
      sessionStorage.setItem(`otaria-lobby-${joined.lobbyId}`, joined.memberToken)
      setLobby(joined)
      setInvitePreview(null)
      launching.current = false
      setStarting(false)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not join this Otaria lobby.')
      launching.current = false
      setStarting(false)
    }
  }

  const setReady = async (ready: boolean) => {
    if (!lobby?.memberToken) return
    try {
      const response = await fetch(`${API}/lobbies/${encodeURIComponent(lobby.lobbyId)}/ready`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ memberToken: lobby.memberToken, ready }),
      })
      setLobby(await readOtariaResponse(response) as OtariaLobby)
      setError(null)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not update your ready status.')
    }
  }

  const startLobby = async () => {
    if (!lobby?.memberToken) return
    setStarting(true)
    try {
      const response = await fetch(`${API}/lobbies/${encodeURIComponent(lobby.lobbyId)}/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ memberToken: lobby.memberToken }),
      })
      const started = await readOtariaResponse(response) as OtariaLobby
      setLobby(started)
      redirectWhenStarted(started)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not start the Otaria game.')
      setStarting(false)
    }
  }

  const inviteLobby = lobby?.lobbyId ?? invitePreview?.lobbyId
  const inviteUrl = inviteLobby
    ? `${window.location.origin}/otaria?lobby=${encodeURIComponent(inviteLobby)}`
    : null

  return (
    <DevPage
      section="Otaria playtest"
      title="Explorer of: Otaria"
      subtitle="Play against AI explorers, invite human explorers, or watch a fast, fully autonomous table. A local work-in-progress, not a finished campaign."
      dev
    >
      {loading && <p role="status">Loading starting decks and playtest status...</p>}
      {error && <div role="alert" className={styles.error}>
        <p>{error}</p>
        {!options && <button type="button" className={pageStyles.button} onClick={() => setAttempt((value) => value + 1)}>Retry</button>}
      </div>}
      {options && <OtariaPlaytestContents
        options={options}
        name={name}
        onName={setName}
        opponents={opponents}
        onOpponents={(value) => { setOpponents(value); setHostSeat((seat) => Math.min(seat, value)) }}
        hostSeat={hostSeat}
        onHostSeat={setHostSeat}
        color={color}
        onColor={(value) => { setColor(value); setBoosterId('') }}
        boosterId={boosterId}
        onBoosterId={setBoosterId}
        fullAi={fullAi}
        onFullAi={(value) => { setFullAi(value); if (value) setOnline(false) }}
        aiThinkingDelayMs={aiThinkingDelayMs}
        onAiThinkingDelayMs={setAiThinkingDelayMs}
        online={online}
        onOnline={(value) => { setOnline(value); if (value) setFullAi(false) }}
        starting={starting}
        onStart={() => void start()}
        lobby={lobby}
        invitePreview={invitePreview}
        inviteUrl={inviteUrl}
        inviteSeat={inviteSeat}
        onInviteSeat={setInviteSeat}
        onJoinLobby={() => void joinLobby()}
        onReady={(ready) => void setReady(ready)}
        onStartLobby={() => void startLobby()}
      />}
    </DevPage>
  )
}

export function OtariaPlaytestContents({
  options, name, onName, opponents, onOpponents, hostSeat, onHostSeat, color, onColor, boosterId, onBoosterId,
  fullAi, onFullAi, aiThinkingDelayMs, onAiThinkingDelayMs, online, onOnline, starting, onStart, lobby, invitePreview, inviteUrl, inviteSeat,
  onInviteSeat, onJoinLobby, onReady, onStartLobby,
}: {
  options: OtariaPlaytestOptions
  name: string
  onName: (value: string) => void
  opponents: number
  onOpponents: (value: number) => void
  hostSeat: number
  onHostSeat: (value: number) => void
  color: StartingColor | null
  onColor: (value: StartingColor | null) => void
  boosterId: string
  onBoosterId: (value: string) => void
  fullAi: boolean
  onFullAi: (value: boolean) => void
  aiThinkingDelayMs: number
  onAiThinkingDelayMs: (value: number) => void
  online: boolean
  onOnline: (value: boolean) => void
  starting: boolean
  onStart: () => void
  lobby: OtariaLobby | null
  invitePreview: OtariaLobby | null
  inviteUrl: string | null
  inviteSeat: number | null
  onInviteSeat: (value: number | null) => void
  onJoinLobby: () => void
  onReady: (ready: boolean) => void
  onStartLobby: () => void
}) {
  const availableDecks = options.decks.filter((candidate) => color === null || candidate.color === color)
  const deck = availableDecks.find((candidate) => candidate.id === boosterId)
  const validSelection = availableDecks.length > 0 && (!boosterId || !!deck)
  return (
    <div className={styles.layout}>
      <section className={`${pageStyles.panel} ${styles.setup}`} aria-labelledby="otaria-setup">
        <p className={styles.eyebrow}>Local playtest</p>
        <h2 id="otaria-setup">Set out for Otaria</h2>
        <p className={pageStyles.muted}>Start with one published 20-card Jumpstart booster from Jumpstart 2022 or Foundations Jumpstart. No deck import or API key needed.</p>
        {!lobby && <fieldset className={styles.fieldset} disabled={starting}>
          <legend>Play mode</legend>
          <div className={styles.colors}>
            <button type="button" className={styles.choice} aria-pressed={!fullAi && !online} onClick={() => { onFullAi(false); onOnline(false) }}>Play vs AI</button>
            <button type="button" className={styles.choice} aria-pressed={online} onClick={() => onOnline(true)}>Online lobby</button>
            <button type="button" className={styles.choice} aria-pressed={fullAi} onClick={() => onFullAi(true)}>Full AI (fast)</button>
          </div>
        </fieldset>}
        {!lobby && !invitePreview && online && <label className={styles.field}>
          Your seat
          <select className={pageStyles.input} value={hostSeat} onChange={(event) => onHostSeat(Number(event.target.value))} disabled={starting}>
            {Array.from({ length: opponents + 1 }, (_, seat) => <option key={seat} value={seat}>Seat {seat + 1}</option>)}
          </select>
        </label>}
        {fullAi && <>
          <p className={pageStyles.muted}>Every explorer is AI-controlled. Watch as a spectator; no input is needed. Experimental encounters and unsupported events can still stop a run.</p>
          <label className={styles.field} htmlFor="otaria-ai-action-delay">
            <span>Delay between AI actions: <output>{aiThinkingDelayMs} ms</output></span>
            <input
              id="otaria-ai-action-delay"
              className={pageStyles.input}
              type="range"
              aria-label="Delay between AI actions"
              aria-describedby="otaria-ai-action-delay-description"
              min={0}
              max={5000}
              step={100}
              value={aiThinkingDelayMs}
              onChange={(event) => onAiThinkingDelayMs(Number(event.target.value))}
              disabled={starting}
            />
            <span id="otaria-ai-action-delay-description" className={pageStyles.muted}>Choose 0–5000 ms. The default is 100 ms.</span>
          </label>
        </>}
        {!lobby && <label className={styles.field}>
          {fullAi ? 'First AI explorer name' : 'Your name'}
          <input className={pageStyles.input} value={name} onChange={(event) => onName(event.target.value)} maxLength={40} disabled={starting} />
        </label>}
        {!lobby && <fieldset className={styles.fieldset} disabled={starting}>
          <legend>{fullAi ? 'AI table size' : online ? 'Total table size' : 'AI opponents'}</legend>
          <div className={styles.choices}>
            {[2, 3, 4].map((count) => <button
              type="button" key={count} aria-pressed={opponents === count}
              className={styles.choice} onClick={() => onOpponents(count)}
            >{fullAi ? count + 1 : online ? count + 1 : count} {online ? 'seats' : 'AI'} <small>{count + 1} players total</small></button>)}
          </div>
        </fieldset>}
        {!lobby && (fullAi ? <p className={pageStyles.muted}>Every AI independently rolls a random color category, including Other, then a random Jumpstart booster in that category. Colors and themes can repeat.</p> : <fieldset className={styles.fieldset} disabled={starting}>
          <legend>Your starting booster</legend>
          <div className={styles.colors}>
            <button type="button" className={styles.choice} aria-pressed={color === null} onClick={() => onColor(null)}>Any color (random)</button>
            {Object.values(Color).map((candidate) => <button
              type="button" key={candidate} className={styles.choice}
              aria-pressed={color === candidate} onClick={() => onColor(candidate)}
            ><i aria-hidden className={`ms ms-${ColorSymbols[candidate].toLowerCase()} ms-cost`} /> {COLOR_NAMES[candidate]}</button>)}
            <button type="button" className={styles.choice} aria-pressed={color === 'OTHER'} onClick={() => onColor('OTHER')}>Other</button>
          </div>
          <label className={styles.field}>
            Theme and booster variant
            <select className={pageStyles.input} value={boosterId} onChange={(event) => onBoosterId(event.target.value)}>
              <option value="">Random booster{color ? ` - ${COLOR_NAMES[color]}` : ' - any color'}</option>
              {[...new Set(availableDecks.map((candidate) => candidate.setCode))].map((setCode) =>
                <optgroup key={setCode} label={setCode === 'J22' ? 'Jumpstart 2022' : 'Foundations Jumpstart'}>
                  {availableDecks.filter((candidate) => candidate.setCode === setCode).map((candidate) =>
                    <option key={candidate.id} value={candidate.id}>{candidate.id.split(':')[1]} - {COLOR_NAMES[candidate.color]}</option>)}
                </optgroup>)}
            </select>
          </label>
          <p className={pageStyles.muted}>{availableDecks.length} published boosters available. Other includes Urza&apos;s (colorless) and Chaos (multicolor).</p>
        </fieldset>)}
        {!lobby && <div className={styles.startingFacts}>
          <span><strong>15</strong> life</span><span><strong>5</strong> cards in hand</span>
          <span><strong>2</strong> lands in play</span><span>No mulligan</span>
        </div>}
        {!options.aiEnabled && <p role="alert" className={styles.error}>
          {online
            ? 'AI opponents are disabled. An online lobby can still start when every seat is filled by a ready human.'
            : 'AI opponents are disabled. Start the server with game.ai.enabled=true.'}
        </p>}
        {!lobby && !invitePreview && <button type="button" className={`${pageStyles.buttonPrimary} ${styles.launch}`}
          disabled={starting || (!online && !options.aiEnabled) || !name.trim() || (!fullAi && !validSelection)}
          onClick={onStart}
        >{starting ? 'Preparing the table...' : fullAi ? `Watch ${opponents + 1} AI explorers` : online ? 'Create invite lobby' : `Start playtest vs ${opponents} AI`}</button>}
        {lobby && <div className={styles.lobby}>
          <h3>Lobby {lobby.lobbyId.slice(0, 8)}</h3>
          <p>Share this invite link. The game starts with {lobby.tableSize} seats; {lobby.aiSeatsOnStart} open seat(s) {options.aiEnabled ? 'will be filled by AI' : 'must be filled by humans because AI is disabled'}.</p>
          <label className={styles.field}>
            Invite link
            <input className={pageStyles.input} readOnly value={inviteUrl || ''} onFocus={(event) => event.currentTarget.select()} />
          </label>
          <ul className={styles.players}>
            {Array.from({ length: lobby.tableSize }, (_, seatIndex) => {
              const player = lobby.players.find((candidate) => candidate.seatIndex === seatIndex)
              return <li key={seatIndex}>
                <strong>Seat {seatIndex + 1}</strong>
                <span>{player ? `${player.name}${player.you ? ' (you)' : ''}${player.host ? ' · host' : ''}` : 'Open — AI fills at start'}</span>
                {player && <span>{player.ready ? 'Ready' : 'Not ready'}</span>}
              </li>
            })}
          </ul>
          {!lobby.started && <div className={styles.colors}>
            <button type="button" className={styles.choice} disabled={starting}
              onClick={() => onReady(!lobby.players.find((player) => player.you)?.ready)}>
              {lobby.players.find((player) => player.you)?.ready ? 'Cancel ready' : 'Ready to start'}
            </button>
            {lobby.players.find((player) => player.you)?.host &&
              <button type="button" className={styles.choice} disabled={starting || lobby.players.some((player) => !player.ready)}
                onClick={onStartLobby}>Start game</button>}
          </div>}
          <p className={pageStyles.muted}>This private local playtest lobby expires after six hours. Starting locations and the first player are random.</p>
        </div>}
        {invitePreview && <div className={styles.lobby}>
          <h3>Join Otaria lobby</h3>
          <p>{invitePreview.players.length} of {invitePreview.tableSize} seats occupied. {invitePreview.aiSeatsOnStart} open seat(s) will be filled by AI.</p>
          <label className={styles.field}>
            Choose a seat
            <select className={pageStyles.input} value={inviteSeat ?? ''} onChange={(event) => onInviteSeat(event.target.value ? Number(event.target.value) : null)}>
              {invitePreview.openSeats.map((seat) => <option key={seat} value={seat}>Seat {seat + 1}</option>)}
            </select>
          </label>
          <ul className={styles.players}>
            {invitePreview.players.map((player) => <li key={player.seatIndex}><strong>Seat {player.seatIndex + 1}</strong><span>{player.name}{player.host ? ' · host' : ''}</span><span>{player.ready ? 'Ready' : 'Not ready'}</span></li>)}
          </ul>
          <button type="button" className={`${pageStyles.buttonPrimary} ${styles.launch}`}
            disabled={starting || inviteSeat === null || !name.trim() || !validSelection}
            onClick={onJoinLobby}>{starting ? 'Joining lobby...' : 'Join lobby'}</button>
        </div>}
        {!lobby && !invitePreview && <p className={pageStyles.muted}>Private, unranked test. Starting locations and the first player are random.</p>}
      </section>
      <div className={styles.details}>
        <section className={`${pageStyles.panel} ${styles.notice}`} aria-labelledby="otaria-limits">
          <h2 id="otaria-limits">Before you play</h2>
          <ul>{options.limitations.map((limitation) => <li key={limitation}>{limitation}</li>)}</ul>
          <p>At the table, open <strong>Otaria map</strong> beside the phase bar. At the start of your turn, choose Move, Explore, Prepare or Combat.</p>
          <Link to="/help/playing#otaria-map">Read the Otaria interface guide</Link>
          {options.missingCards.length > 0 && <details>
            <summary>{options.missingCards.length} supply cards still missing</summary>
            <p>{options.missingCards.join(' ; ')}</p>
          </details>}
        </section>
        {!fullAi && <section className={pageStyles.panel} aria-labelledby="otaria-deck">
          <h2 id="otaria-deck">{deck ? `${deck.theme} - ${deck.setCode}` : 'A surprise Jumpstart booster'}</h2>
          {deck ? <>
          <p className={pageStyles.muted}>Exactly 20 cards from {deck.id.split(':')[1]}. Starting lands: {deck.startingLands.join(' + ')}.
            {deck.startingLandSource === 'SEPARATE_STOCK'
              ? ' These two extra Wastes start in play; all 20 booster cards remain available to draw.'
              : ' These two basics move from the booster into play before you draw five cards.'}</p>
          <ul className={styles.deck}>{Object.entries(deck.cards).map(([card, count]) => <li key={card}><span>{count}</span>{card}</li>)}</ul>
          </> : <p className={pageStyles.muted}>The server rolls {color ? `a ${COLOR_NAMES[color]} booster` : 'a color category, then a booster'} when you start. Select a theme above to preview its exact decklist instead. AI opponents always choose randomly and independently, including Other.</p>}
        </section>}
      </div>
    </div>
  )
}

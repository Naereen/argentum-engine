import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { DevPage } from '@/components/admin/DevPage'
import { pageStyles } from '@/components/ui/PageShell'
import type { ScenarioCreateResponse } from '@/components/scenario/types'
import { Color, ColorSymbols } from '@/types'
import styles from './OtariaPlaytestPage.module.css'
import { useTranslation } from 'react-i18next'

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
  [Color.WHITE]: 'white',
  [Color.BLUE]: 'blue',
  [Color.BLACK]: 'black',
  [Color.RED]: 'red',
  [Color.GREEN]: 'green',
  OTHER: 'otherColor',
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
  const { t } = useTranslation()
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
      section={t('otaria.playtest')}
      title={t('otaria.pageTitle')}
      subtitle={t('otaria.pageSubtitle')}
      dev
    >
      {loading && <p role="status">{t('otaria.loading')}</p>}
      {error && <div role="alert" className={styles.error}>
        <p>{error}</p>
        {!options && <button type="button" className={pageStyles.button} onClick={() => setAttempt((value) => value + 1)}>{t('otaria.retry')}</button>}
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
  const { t } = useTranslation()
  const colorLabel = (value: StartingColor) => value === 'OTHER' ? t('otaria.otherColor') : t(`otaria.colorList.${COLOR_NAMES[value] as 'white' | 'blue' | 'black' | 'red' | 'green'}`)
  const availableDecks = options.decks.filter((candidate) => color === null || candidate.color === color)
  const deck = availableDecks.find((candidate) => candidate.id === boosterId)
  const validSelection = availableDecks.length > 0 && (!boosterId || !!deck)
  return (
    <div className={styles.layout}>
      <section className={`${pageStyles.panel} ${styles.setup}`} aria-labelledby="otaria-setup">
        <p className={styles.eyebrow}>{t('otaria.localPlaytest')}</p>
        <h2 id="otaria-setup">{t('otaria.setOut')}</h2>
        <p className={pageStyles.muted}>{t('otaria.boosterIntro')}</p>
        {!lobby && <fieldset className={styles.fieldset} disabled={starting}>
          <legend>{t('otaria.playMode')}</legend>
          <div className={styles.colors}>
            <button type="button" className={styles.choice} aria-pressed={!fullAi && !online} onClick={() => { onFullAi(false); onOnline(false) }}>{t('otaria.playVsAi')}</button>
            <button type="button" className={styles.choice} aria-pressed={online} onClick={() => onOnline(true)}>{t('otaria.onlineLobby')}</button>
            <button type="button" className={styles.choice} aria-pressed={fullAi} onClick={() => onFullAi(true)}>{t('otaria.fullAiFast')}</button>
          </div>
        </fieldset>}
        {!lobby && !invitePreview && online && <label className={styles.field}>
          {t('otaria.yourSeat')}
          <select className={pageStyles.input} value={hostSeat} onChange={(event) => onHostSeat(Number(event.target.value))} disabled={starting}>
            {Array.from({ length: opponents + 1 }, (_, seat) => <option key={seat} value={seat}>{t('otaria.seat', { number: seat + 1 })}</option>)}
          </select>
        </label>}
        {fullAi && <>
          <p className={pageStyles.muted}>{t('otaria.allAiDescription')}</p>
          <label className={styles.field} htmlFor="otaria-ai-action-delay">
            <span>{t('otaria.aiDelay', { milliseconds: aiThinkingDelayMs })}</span>
            <input
              id="otaria-ai-action-delay"
              className={pageStyles.input}
              type="range"
              aria-label={t('otaria.aiDelayLabel')}
              aria-describedby="otaria-ai-action-delay-description"
              min={0}
              max={5000}
              step={100}
              value={aiThinkingDelayMs}
              onChange={(event) => onAiThinkingDelayMs(Number(event.target.value))}
              disabled={starting}
            />
            <span id="otaria-ai-action-delay-description" className={pageStyles.muted}>{t('otaria.aiDelayDescription')}</span>
          </label>
        </>}
        {!lobby && <label className={styles.field}>
          {fullAi ? t('otaria.firstAiExplorer') : t('otaria.yourName')}
          <input className={pageStyles.input} value={name} onChange={(event) => onName(event.target.value)} maxLength={40} disabled={starting} />
        </label>}
        {!lobby && <fieldset className={styles.fieldset} disabled={starting}>
          <legend>{fullAi ? t('otaria.aiTableSize') : online ? t('otaria.tableSize') : t('otaria.aiOpponents')}</legend>
          <div className={styles.choices}>
            {[2, 3, 4].map((count) => <button
              type="button" key={count} aria-pressed={opponents === count}
              className={styles.choice} onClick={() => onOpponents(count)}
            >{fullAi ? count + 1 : online ? count + 1 : count} {online ? t('otaria.seats') : 'AI'} <small>{t('otaria.playersTotal', { count: count + 1 })}</small></button>)}
          </div>
        </fieldset>}
        {!lobby && (fullAi ? <p className={pageStyles.muted}>{t('otaria.randomColors')}</p> : <fieldset className={styles.fieldset} disabled={starting}>
          <legend>{t('otaria.startingBooster')}</legend>
          <div className={styles.colors}>
            <button type="button" className={styles.choice} aria-pressed={color === null} onClick={() => onColor(null)}>{t('otaria.anyColorRandom')}</button>
            {Object.values(Color).map((candidate) => <button
              type="button" key={candidate} className={styles.choice}
              aria-pressed={color === candidate} onClick={() => onColor(candidate)}
            ><i aria-hidden className={`ms ms-${ColorSymbols[candidate].toLowerCase()} ms-cost`} /> {colorLabel(candidate)}</button>)}
            <button type="button" className={styles.choice} aria-pressed={color === 'OTHER'} onClick={() => onColor('OTHER')}>{t('otaria.other')}</button>
          </div>
          <label className={styles.field}>
            {t('otaria.themeBooster')}
            <select className={pageStyles.input} value={boosterId} onChange={(event) => onBoosterId(event.target.value)}>
              <option value="">{t('otaria.randomBooster')}{color ? ` - ${colorLabel(color)}` : ` - ${t('otaria.anyColor')}`}</option>
              {[...new Set(availableDecks.map((candidate) => candidate.setCode))].map((setCode) =>
                <optgroup key={setCode} label={setCode === 'J22' ? t('otaria.jumpstart2022') : t('otaria.foundationsJumpstart')}>
                  {availableDecks.filter((candidate) => candidate.setCode === setCode).map((candidate) =>
                    <option key={candidate.id} value={candidate.id}>{candidate.id.split(':')[1]} - {colorLabel(candidate.color)}</option>)}
                </optgroup>)}
            </select>
          </label>
          <p className={pageStyles.muted}>{t('otaria.boostersAvailable', { count: availableDecks.length })}</p>
        </fieldset>)}
        {!lobby && <div className={styles.startingFacts}>
          <span><strong>15</strong> {t('otaria.life')}</span><span><strong>5</strong> {t('otaria.cardsInHand')}</span>
          <span><strong>2</strong> {t('otaria.landsInPlay')}</span><span>{t('otaria.noMulligan')}</span>
        </div>}
        {!options.aiEnabled && <p role="alert" className={styles.error}>
          {online
            ? t('otaria.aiDisabledOnline')
            : t('otaria.aiDisabledLocal')}
        </p>}
        {!lobby && !invitePreview && <button type="button" className={`${pageStyles.buttonPrimary} ${styles.launch}`}
          disabled={starting || (!online && !options.aiEnabled) || !name.trim() || (!fullAi && !validSelection)}
          onClick={onStart}
        >{starting ? t('otaria.preparing') : fullAi ? t('otaria.watchExplorers', { count: opponents + 1 }) : online ? t('otaria.createLobby') : t('otaria.startPlaytest', { count: opponents })}</button>}
        {lobby && <div className={styles.lobby}>
          <h3>{t('otaria.lobby', { id: lobby.lobbyId.slice(0, 8) })}</h3>
          <p>{t('otaria.shareInvite', { seats: lobby.tableSize, openSeats: lobby.aiSeatsOnStart, fillExplanation: options.aiEnabled ? t('otaria.filledByAi') : t('otaria.filledByHumans') })}</p>
          <label className={styles.field}>
            {t('otaria.inviteLink')}
            <input className={pageStyles.input} readOnly value={inviteUrl || ''} onFocus={(event) => event.currentTarget.select()} />
          </label>
          <ul className={styles.players}>
            {Array.from({ length: lobby.tableSize }, (_, seatIndex) => {
              const player = lobby.players.find((candidate) => candidate.seatIndex === seatIndex)
              return <li key={seatIndex}>
                <strong>{t('otaria.seat', { number: seatIndex + 1 })}</strong>
                <span>{player ? `${player.name}${player.you ? ` (${t('otaria.you')})` : ''}${player.host ? ` · ${t('otaria.host')}` : ''}` : t('otaria.openAiSeat')}</span>
                {player && <span>{player.ready ? t('otaria.ready') : t('otaria.notReady')}</span>}
              </li>
            })}
          </ul>
          {!lobby.started && <div className={styles.colors}>
            <button type="button" className={styles.choice} disabled={starting}
              onClick={() => onReady(!lobby.players.find((player) => player.you)?.ready)}>
              {lobby.players.find((player) => player.you)?.ready ? t('otaria.cancelReady') : t('otaria.readyToStart')}
            </button>
            {lobby.players.find((player) => player.you)?.host &&
              <button type="button" className={styles.choice} disabled={starting || lobby.players.some((player) => !player.ready)}
                onClick={onStartLobby}>{t('otaria.startGame')}</button>}
          </div>}
          <p className={pageStyles.muted}>{t('otaria.lobbyExpires')}</p>
        </div>}
        {invitePreview && <div className={styles.lobby}>
          <h3>{t('otaria.joinLobbyTitle')}</h3>
          <p>{t('otaria.seatsOccupied', { occupied: invitePreview.players.length, total: invitePreview.tableSize, openSeats: invitePreview.aiSeatsOnStart })}</p>
          <label className={styles.field}>
            {t('otaria.chooseSeat')}
            <select className={pageStyles.input} value={inviteSeat ?? ''} onChange={(event) => onInviteSeat(event.target.value ? Number(event.target.value) : null)}>
              {invitePreview.openSeats.map((seat) => <option key={seat} value={seat}>{t('otaria.seat', { number: seat + 1 })}</option>)}
            </select>
          </label>
          <ul className={styles.players}>
            {invitePreview.players.map((player) => <li key={player.seatIndex}><strong>{t('otaria.seat', { number: player.seatIndex + 1 })}</strong><span>{player.name}{player.host ? ` · ${t('otaria.host')}` : ''}</span><span>{player.ready ? t('otaria.ready') : t('otaria.notReady')}</span></li>)}
          </ul>
          <button type="button" className={`${pageStyles.buttonPrimary} ${styles.launch}`}
            disabled={starting || inviteSeat === null || !name.trim() || !validSelection}
            onClick={onJoinLobby}>{starting ? t('otaria.joiningLobby') : t('otaria.joinLobby')}</button>
        </div>}
        {!lobby && !invitePreview && <p className={pageStyles.muted}>{t('otaria.privateTest')}</p>}
      </section>
      <div className={styles.details}>
        <section className={`${pageStyles.panel} ${styles.notice}`} aria-labelledby="otaria-limits">
          <h2 id="otaria-limits">{t('otaria.beforeYouPlay')}</h2>
          <ul>{options.limitations.map((limitation) => <li key={limitation}>{t(`otaria.limitations.${limitation}`, { defaultValue: limitation })}</li>)}</ul>
          <p>{t('otaria.inGameInstructions')}</p>
          <Link to="/help/playing#otaria-map">{t('otaria.interfaceGuide')}</Link>
          {options.missingCards.length > 0 && <details>
            <summary>{t('otaria.missingSupply', { count: options.missingCards.length })}</summary>
            <p>{options.missingCards.join(' ; ')}</p>
          </details>}
        </section>
        {!fullAi && <section className={pageStyles.panel} aria-labelledby="otaria-deck">
          <h2 id="otaria-deck">{deck ? `${deck.theme} - ${deck.setCode}` : t('otaria.surpriseBooster')}</h2>
          {deck ? <>
          <p className={pageStyles.muted}>{t('otaria.exactCards', { theme: deck.id.split(':')[1], lands: deck.startingLands.join(' + ') })}{' '}
            {deck.startingLandSource === 'SEPARATE_STOCK'
              ? t('otaria.wastesExtra')
              : t('otaria.basicsMoved')}</p>
          <br/>
          <ul className={styles.deck}>{Object.entries(deck.cards).map(([card, count]) => <li key={card}><span>{count}</span>{card}</li>)}</ul>
          </> : <p className={pageStyles.muted}>{t('otaria.serverRandomBooster', { selection: color ? t('otaria.colorBooster', { color: t(`otaria.colorList.${color}`, { defaultValue: COLOR_NAMES[color] }) }) : t('otaria.colorCategory') })}</p>}
        </section>}
      </div>
    </div>
  )
}

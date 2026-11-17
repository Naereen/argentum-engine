/**
 * The games behind the "AI vs humans" numbers: a list of games (who sat down, with which colours, how it
 * went), the decks of any one of them, and one human's games against the AI. The list is the
 * "Recent games" panel and the body of a human's drawer; both open a game's decks in the shared
 * deck viewer ({@link GameDeckColumns}), so a surprising result can be read off the two decklists.
 *
 * The open game and human live in the query string (`?game=`, `?human=`) — see {@link useAdminParam} —
 * so the browser's Back closes them and a refresh or a trip to a replay comes back to them.
 */
import { useEffect, useState } from 'react'
import {
  type AiHumanGames,
  type AiRecentGame,
  type AiVsHumanFilter,
  fetchAiHumanGames,
  fetchMatchDecks,
} from '@/api/adminStats'
import type { AdminAuth } from '@/api/adminAuth'
import type { GameDecks } from '@/api/account'
import { GameDeckColumns } from '@/components/deck/GameDeckView'
import { ManaSymbol } from '@/components/ui/ManaSymbols'
import { ModalShell } from './AdminPlayerDecks'
import { cellStyle } from './adminUi'
import { useAdminNav } from './adminNav'
import { gameModeLabel } from './statFormat'
import css from './aiVsHuman.module.css'
import { useTranslation } from 'react-i18next'

/** Open a finished game's replay in a new tab, so the admin keeps their place here. */
const watchReplay = (gameId: string) => window.open(`/replay/${gameId}`, '_blank', 'noopener')

function modeText(gameMode: string | null, format: string | null): string {
  const { primary, variant } = gameModeLabel(gameMode, format)
  return variant ? `${primary} › ${variant}` : primary
}

function when(iso: string, language: string): string {
  return new Date(iso).toLocaleString(language, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

/** A deck's colours as mana symbols — `C` for colourless, a dash when no deck was recorded. */
export function ColorPips({ colors }: { colors: string | null }) {
  const { t } = useTranslation()
  if (colors == null) {
    return (
      <span className={css.pipsNone} title={t('adminAiGames.noDeck')}>
        —
      </span>
    )
  }
  const colorNames: Record<string, string> = {
    W: t('adminAiGames.white'),
    U: t('adminAiGames.blue'),
    B: t('adminAiGames.black'),
    R: t('adminAiGames.red'),
    G: t('adminAiGames.green'),
  }
  const label = colors ? [...colors].map((color) => colorNames[color] ?? color).join('/') : t('adminAiGames.colorless')
  return (
    <span className={css.pips} title={label} aria-label={label} role="img">
      {(colors ? [...colors] : ['C']).map((c, i) => (
        <ManaSymbol key={i} symbol={c} size={15} />
      ))}
    </span>
  )
}

/**
 * Games as rows: when, mode, the AI's colours, the human(s) and their colours, the outcome, and the
 * deck/replay actions. `hideHumans` drops the human column inside one human's own drawer, where it
 * would repeat their name on every row — their colours stay, beside the AI's.
 */
export function GameList({
  games,
  onOpenGame,
  onOpenHuman,
  focusKey,
}: {
  games: readonly AiRecentGame[]
  onOpenGame: (matchId: number) => void
  onOpenHuman?: (key: string) => void
  /** Inside a human's drawer: their key, so the row shows their own colours instead of a human column. */
  focusKey?: string
}) {
  const { t, i18n } = useTranslation()
  return (
    <div className={css.games} role="table" aria-label={t('adminAiGames.games')}>
      <div className={`${css.gameRow} ${css.gameHead}`} role="row">
        <span role="columnheader">{t('adminAiGames.when')}</span>
        <span role="columnheader">{t('adminAiGames.mode')}</span>
        <span role="columnheader">{t('adminAiGames.aiDeck')}</span>
        <span role="columnheader">{focusKey ? t('adminAiGames.theirDeck') : t('adminAiGames.humanDeck')}</span>
        <span role="columnheader">{t('adminAiGames.result')}</span>
        <span role="columnheader" className={css.srOnly}>
          {t('adminAiGames.actions')}
        </span>
      </div>
      {games.map((g) => {
        const own = focusKey ? g.humans.filter((h) => h.key === focusKey) : g.humans
        const others = focusKey ? g.humans.filter((h) => h.key !== focusKey) : []
        return (
          <div key={g.matchId} className={css.gameRow} role="row">
            <span className={css.gameWhen} role="cell">
              {when(g.endedAt, i18n.resolvedLanguage ?? 'en')}
            </span>
            <span className={css.gameMode} role="cell" title={modeText(g.gameMode, g.format)}>
              {modeText(g.gameMode, g.format)}
            </span>
            <span className={css.gameSide} role="cell">
              <span className={css.gameSideLabel}>{t('adminAiGames.ai')}</span>
              {g.aiColors.length === 0 ? <ColorPips colors={null} /> : g.aiColors.map((c, i) => <ColorPips key={i} colors={c} />)}
            </span>
            <span className={css.gameHumans} role="cell">
              {own.map((h) => (
                <span key={h.key} className={css.gameHuman}>
                  {!focusKey &&
                    (onOpenHuman ? (
                      <button
                        type="button"
                        className={css.humanLink}
                        data-won={h.won}
                        onClick={() => onOpenHuman(h.key)}
                        title={t('adminAiGames.humanGamesTitle', { name: h.name })}
                      >
                        {h.name}
                      </button>
                    ) : (
                      <span className={css.humanName} data-won={h.won}>
                        {h.name}
                      </span>
                    ))}
                  {!focusKey && h.isGuest && <span className={css.guest}>{t('adminAiGames.guest')}</span>}
                  <ColorPips colors={h.colors} />
                </span>
              ))}
              {others.length > 0 && (
                <span className={css.gameWith} title={others.map((h) => h.name).join(', ')}>
                  {t('adminAiGames.withHumans', { names: others.map((h) => h.name).join(', ') })}
                </span>
              )}
            </span>
            <span role="cell">
              <span className={css.outcome} data-outcome={g.outcome}>
                {t(g.outcome === 'AI' ? 'adminAiGames.aiWon' : g.outcome === 'HUMAN' ? 'adminAiGames.humanWon' : 'adminAiGames.draw')}
              </span>
            </span>
            <span className={css.gameActions} role="cell">
              <button type="button" className={css.deckButton} onClick={() => onOpenGame(g.matchId)}>
                {t('adminAiGames.decks')}
              </button>
              {g.replayGameId ? (
                <button
                  type="button"
                  className={css.excludeButton}
                  onClick={() => watchReplay(g.replayGameId as string)}
                  title={t('adminAiGames.openReplay')}
                >
                  {t('adminAiGames.replay')}
                </button>
              ) : (
                <span className={css.noReplay} title={t('adminAiGames.noReplayTitle')}>
                  {t('adminAiGames.noReplay')}
                </span>
              )}
            </span>
          </div>
        )
      })}
    </div>
  )
}

/** Every seat's deck for one game, AI first, with a link to its replay. Shared by the AI-vs-humans and Activity screens. */
export function MatchDecksModal({ auth, matchId, onClose }: { auth: AdminAuth; matchId: number; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const [decks, setDecks] = useState<GameDecks | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let live = true
    setDecks(null)
    setFailed(false)
    fetchMatchDecks(auth, matchId)
      .then((d) => live && setDecks(d))
      .catch(() => live && setFailed(true))
    return () => {
      live = false
    }
  }, [auth, matchId])

  const ai = decks?.participants.filter((p) => p.isAi) ?? []
  const humans = decks?.participants.filter((p) => !p.isAi) ?? []
  // An AI game reads "Engine AI vs <humans>"; a game between people names every seat.
  const title = !decks
    ? t('adminAiGames.gameDecks')
    : ai.length === 0
      ? humans.map((p) => p.playerName).join(` ${t('adminAiGames.versus')} `) || t('adminAiGames.gameDecks')
      : `${ai.length > 1 ? t('adminAiGames.aiSeatMany', { count: ai.length }) : t('adminAiGames.engineAi')} ${t('adminAiGames.versus')} ${humans.map((p) => p.playerName).join(', ') || t('adminAiGames.humans')}`
  const subtitle = decks
    ? `${modeText(decks.gameMode, decks.format ?? null)} · ${new Date(decks.endedAt).toLocaleString(i18n.resolvedLanguage ?? 'en', {
        dateStyle: 'medium',
        timeStyle: 'short',
      })}`
    : undefined
  const recorded = decks?.participants.some((p) => p.cards.length > 0) ?? false

  return (
    <ModalShell
      title={title}
      subtitle={subtitle}
      width={1040}
      onClose={onClose}
      actions={
        decks?.hasReplay ? (
          <button type="button" className={css.deckButton} onClick={() => watchReplay(decks.gameId)}>
            {t('adminAiGames.watchReplay')}
          </button>
        ) : undefined
      }
    >
      {failed ? (
        <p className={css.error}>{t('adminAiGames.couldNotLoadDecks')}</p>
      ) : !decks ? (
        <p style={cellStyle.muted}>{t('adminAiGames.loading')}</p>
      ) : !recorded ? (
        <p style={cellStyle.muted}>{t('adminAiGames.noDecklist')}</p>
      ) : (
        <GameDeckColumns participants={decks.participants} />
      )}
    </ModalShell>
  )
}

/**
 * One human's games against the AI — the same slice their row in the humans table counts — each
 * openable to both decks, with a way through to their full admin profile when they have an account.
 */
export function HumanGamesModal({
  auth,
  humanKey,
  days,
  rangeLabel,
  filter,
  onOpenGame,
  onClose,
}: {
  auth: AdminAuth
  humanKey: string
  days: number
  rangeLabel: string
  filter: AiVsHumanFilter
  onOpenGame: (matchId: number) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const { open } = useAdminNav()
  const [data, setData] = useState<AiHumanGames | null>(null)
  const [failed, setFailed] = useState(false)

  // The slice, not the filter object: leaving someone else out mustn't refetch this player's games.
  const { gameMode, format, setCode } = filter
  useEffect(() => {
    let live = true
    setData(null)
    setFailed(false)
    fetchAiHumanGames(auth, humanKey, days, { gameMode, format, setCode, excludeHumans: [], excludeTopHumans: 0 })
      .then((d) => live && setData(d))
      .catch(() => live && setFailed(true))
    return () => {
      live = false
    }
  }, [auth, humanKey, days, gameMode, format, setCode])

  const human = data?.human
  const fallbackName = humanKey.replace(/^(user|guest):/, '')
  const name = human?.name ?? data?.games[0]?.humans.find((h) => h.key === humanKey)?.name ?? (humanKey.startsWith('guest:') ? fallbackName : t('adminAiGames.player'))
  const userId = humanKey.startsWith('user:') ? humanKey.slice('user:'.length) : null
  const decisive = human ? human.wins + human.losses : 0
  const slice = [
    days ? t('adminAiGames.lastRange', { range: rangeLabel }) : t('adminAiGames.allTime'),
    filter.gameMode ? modeText(filter.gameMode, filter.format) : null,
    filter.setCode,
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <ModalShell
      title={
        <>
          {name} <span className={css.vsAi}>{t('adminAiGames.versusAi')}</span>
          {human?.isGuest && <span className={css.guest}>{t('adminAiGames.guest')}</span>}
        </>
      }
      subtitle={
        human ? (
          <>
            {t(human.games === 1 ? 'adminAiGames.singleGameStats' : 'adminAiGames.gameStats', {
              games: human.games,
              wins: human.wins,
              losses: human.losses,
              draws: human.draws,
            })}
            {decisive > 0 && t('adminAiGames.beatsAi', { percent: Math.round((human.wins / decisive) * 100) })}
            <span className={css.sliceNote}> · {slice}</span>
          </>
        ) : (
          slice
        )
      }
      width={980}
      onClose={onClose}
      actions={
        userId ? (
          <button type="button" className={css.excludeButton} onClick={() => open(`players/${encodeURIComponent(userId)}`)}>
            {t('adminAiGames.fullProfile')}
          </button>
        ) : undefined
      }
    >
      {failed ? (
        <p className={css.error}>{t('adminAiGames.couldNotLoadHumanGames')}</p>
      ) : !data ? (
        <p style={cellStyle.muted}>{t('adminAiGames.loading')}</p>
      ) : data.games.length === 0 ? (
        <p style={cellStyle.muted}>{t('adminAiGames.noGamesInSlice')}</p>
      ) : (
        <>
          <GameList games={data.games} onOpenGame={onOpenGame} focusKey={humanKey} />
          {human && human.games > data.games.length && (
            <p className={css.listNote}>
              {t('adminAiGames.showingLatest', { shown: data.games.length, total: human.games })}
            </p>
          )}
        </>
      )}
    </ModalShell>
  )
}

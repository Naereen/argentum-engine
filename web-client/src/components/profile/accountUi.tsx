/**
 * Building blocks shared by the account pages (profile, stats, public profiles, friends): the page
 * frame, the recent-games and tournament lists, and the glass modal the deck and tournament viewers
 * open in. Styles live in `account.module.css`; panels, buttons and inputs come from the page shell.
 */
import { useEffect } from 'react'
import type React from 'react'
import { useTranslation } from 'react-i18next'
import type { GameHistoryEntry, NotCountedReason, UserTournamentEntry } from '@/api/account'
import { colorForIdentity, colorLabel, gameModeLabel } from '@/components/admin/statFormat'
import { TournamentStatusBadge } from '@/components/tournament/TournamentStatusBadge'
import { PageShell, pageStyles } from '@/components/ui/PageShell'
import { formatDateTime } from '@/utils/datetime'
import { AvatarArt, hasAvatarArt } from './AvatarArt'
import { initialOf } from './avatars'
import { EloCell, OpponentCell } from './gameHistoryCells'
import a from './account.module.css'

export { a as accountStyles }

/** The page shell at the width an account page wants. */
export function AccountPage({
  title,
  width = 'normal',
  plain = false,
  children,
}: {
  title?: string
  width?: 'narrow' | 'normal' | 'wide'
  /** Dark backdrop instead of card art — for the chart-heavy stats views. */
  plain?: boolean
  children: React.ReactNode
}) {
  return (
    <PageShell title={title} width={width} plain={plain}>
      {children}
    </PageShell>
  )
}

/** A centred glass card for signed-out, loading and error states. */
export function MessageCard({ children, center = false }: { children: React.ReactNode; center?: boolean }) {
  return (
    <div className={a.centerWrap}>
      <div className={`${pageStyles.panel} ${a.messageCard}`} data-center={center}>
        {children}
      </div>
    </div>
  )
}

/**
 * An account's avatar: its chosen preset portrait, or the display name's initial in a circle when it
 * has none (or picked one this client has no art for).
 */
export function Avatar({
  name,
  avatar,
  small = false,
  online,
}: {
  name: string
  avatar?: string | null | undefined
  small?: boolean
  online?: boolean | undefined
}) {
  const art = hasAvatarArt(avatar)
  return (
    <span className={small ? a.avatarSmall : a.avatar} data-portrait={art} aria-hidden>
      {art ? <AvatarArt avatar={avatar} /> : initialOf(name)}
      {online !== undefined && <span className={a.presence} data-online={online} />}
    </span>
  )
}

/**
 * Recent games as rows: result, mode + date, opponent + colours, rating, and (on the owner's own
 * profile) the deck / replay actions. Collapses to a two-line card on a phone.
 */
export function GameHistoryList({
  games,
  renderActions,
}: {
  games: GameHistoryEntry[]
  renderActions?: (game: GameHistoryEntry) => React.ReactNode
}) {
  const { t } = useTranslation()
  return (
    <ul className={a.list}>
      {games.map((g, i) => {
        const mode = gameModeLabel(g.gameMode, g.format)
        return (
          <li key={`${g.gameId}-${i}`} className={a.gameRow} data-compact={!renderActions}>
            <span
              className={a.result}
              data-won={g.won}
              data-counted={!g.notCounted}
              title={g.notCounted ? t(NOT_COUNTED_KEY[g.notCounted]) : undefined}
            >
              {g.won ? t('accountHistory.win') : t('accountHistory.loss')}
            </span>
            <span className={a.cellStack}>
              <span className={a.cellMain}>
                {mode.primary}
                {mode.variant ? <span className={a.dim}> › {mode.variant}</span> : null}
              </span>
              <span className={a.cellSub}>
                {formatDateTime(g.endedAt)}
                {g.notCounted && (
                  <span className={a.notCounted} title={t(NOT_COUNTED_KEY[g.notCounted])}>
                    {t('accountHistory.notCounted')}
                  </span>
                )}
              </span>
            </span>
            <span className={a.cellStack}>
              <span className={a.cellMain}>
                <span className={a.dim}>{t('accountHistory.versus')} </span>
                <OpponentCell entry={g} />
              </span>
              <span className={a.cellSub}>
                {g.colors ? (
                  <>
                    <span className={a.colorDot} style={{ backgroundColor: colorForIdentity(g.colors) }} />
                    {colorLabel(g.colors)}
                  </>
                ) : (
                  t('accountHistory.colorsUnknown')
                )}
              </span>
            </span>
            <span className={a.cellSub}>
              <EloCell entry={g} />
            </span>
            {renderActions && <span className={a.rowActions}>{renderActions(g)}</span>}
          </li>
        )
      })}
    </ul>
  )
}

/** Why a listed game is left out of the record, as the row's tooltip. */
const NOT_COUNTED_KEY: Record<NotCountedReason, 'accountHistory.notCountedNoAction' | 'accountHistory.notCountedOpponentNoAction'> = {
  NO_ACTION: 'accountHistory.notCountedNoAction',
  OPPONENT_NO_ACTION: 'accountHistory.notCountedOpponentNoAction',
}

/**
 * A match record without the matches nobody played: forfeits (a player left the tournament) are
 * counted in the standings but split out here, as a dim "+N forfeited" beside the played record.
 */
export function PlayedRecord({
  wins,
  losses,
  draws,
  forfeitWins = 0,
  forfeitLosses = 0,
}: {
  wins: number
  losses: number
  draws: number
  forfeitWins?: number | undefined
  forfeitLosses?: number | undefined
}) {
  const { t } = useTranslation()
  const forfeits = forfeitWins + forfeitLosses
  const played = `${wins - forfeitWins}–${losses - forfeitLosses}${draws ? `–${draws}` : ''}`
  const forfeitTitle = [
    forfeitWins > 0 ? t('accountHistory.forfeitOpponentLeft', { count: forfeitWins }) : null,
    forfeitLosses > 0 ? t('accountHistory.forfeitLeftTournament', { count: forfeitLosses }) : null,
  ]
    .filter(Boolean)
    .join(', ')
  return (
    <span className={a.dim} title={t('accountHistory.recordTitle')}>
      {played}
      {forfeits > 0 && (
        <span className={a.forfeits} title={t('accountHistory.forfeitedTitle', { details: forfeitTitle })}>
          {' '}
          {t('accountHistory.forfeitedCount', { count: forfeits })}
        </span>
      )}
    </span>
  )
}

/**
 * Tournaments as clickable rows: name + mode and date, match record, placement. A live tournament
 * shows its status instead of a record; an abandoned one is shown by the games it got through, with
 * its last standing, rather than flagged as abandoned.
 */
export function TournamentList({
  tournaments,
  onOpen,
}: {
  tournaments: UserTournamentEntry[]
  onOpen: (id: number) => void
}) {
  const { t } = useTranslation()
  return (
    <ul className={a.list}>
      {tournaments.map((tournament, i) => {
        const mode = gameModeLabel(tournament.gameMode, tournament.format)
        const live = tournament.status === 'IN_PROGRESS'
        const won = tournament.status === 'COMPLETED' && tournament.placement === 1
        return (
          <li key={`${tournament.id}-${i}`}>
            <button type="button" className={a.tournamentRow} onClick={() => onOpen(tournament.id)}>
              <span className={a.cellStack}>
                <span className={a.cellMain}>{tournament.name?.trim() || t('accountHistory.defaultTournament')}</span>
                <span className={a.cellSub}>
                  {mode.variant ?? mode.primary} · {formatDateTime(tournament.endedAt)}
                </span>
              </span>
              <span>
                {live ? (
                  <TournamentStatusBadge status={tournament.status} />
                ) : (
                  <PlayedRecord
                    wins={tournament.wins}
                    losses={tournament.losses}
                    draws={tournament.draws}
                    forfeitWins={tournament.forfeitWins}
                    forfeitLosses={tournament.forfeitLosses}
                  />
                )}
              </span>
              <span className={a.place} data-first={won}>
                {!live && tournament.placement > 0 ? (
                  <>
                    {won ? '🏆 ' : ''}
                    {tournament.placement}
                    <span className={a.dim} style={{ fontSize: 13 }}>/{tournament.playerCount}</span>
                  </>
                ) : (
                  <span className={a.dim}>—</span>
                )}
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

/** A glass dialog over a blurred backdrop; Escape or a backdrop click closes it. */
export function AccountModal({
  title,
  titleExtra,
  size = 'normal',
  onClose,
  children,
}: {
  title: React.ReactNode
  titleExtra?: React.ReactNode
  size?: 'small' | 'normal' | 'large' | 'xl'
  onClose: () => void
  children: React.ReactNode
}) {
  const { t } = useTranslation()
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className={a.backdrop} onClick={onClose} role="presentation">
      <div className={a.dialog} data-size={size} onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className={a.dialogHead}>
          <span className={a.dialogTitleRow}>
            <h2 className={a.dialogTitle}>{title}</h2>
            {titleExtra}
          </span>
          <button type="button" className={a.close} onClick={onClose} aria-label={t('accountHistory.close')}>
            ×
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}

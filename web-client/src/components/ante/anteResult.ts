import type { AnteResult, AnteScope, AnteTransfer } from '@/types'
import i18n from '@/i18n'
import type { TFunction } from 'i18next'

/**
 * How a finished game's ante settlement (CR 407) reads from one seat — pure, so the game-over
 * overlay and the spectator banner tell the same story and it can be tested without a DOM.
 *
 * The server decides every transfer; this only sorts them by whether the viewer gained or lost
 * the card. A viewer outside the transfers (a spectator, the third seat of a pod) gets them all as
 * neutral "X won Y from Z" lines.
 */
export interface AnteResultView {
  /** Cards the viewer gained. Empty for a spectator. */
  readonly won: readonly AnteTransfer[]
  /** Cards the viewer gave up. Empty for a spectator. */
  readonly lost: readonly AnteTransfer[]
  /** Transfers the viewer took no part in — everything, for a spectator. */
  readonly others: readonly AnteTransfer[]
  /** What the transfers mean beyond this game; null when nothing changed hands. */
  readonly scope: AnteScope
  readonly scopeSide: 'won' | 'lost' | 'other'
  /** Shown instead of any card when nothing changed hands. */
  readonly empty: boolean
  /** One per refilled loss, viewer-relative. */
  readonly refillTransfers: readonly { readonly transfer: AnteTransfer; readonly isViewer: boolean; readonly cardName: string }[]
}

/** What the settlement means beyond this game, said to the viewer when they won or lost a card. */
export function anteScopeLine(
  scope: AnteScope,
  side: 'won' | 'lost' | 'other' = 'other',
  t: TFunction = i18n.t,
): string {
  if (scope === 'EVENT') {
    switch (side) {
      case 'won': return t('ante.eventWon')
      case 'lost': return t('ante.eventLost')
      case 'other': return t('ante.eventOther')
    }
  }
  return side === 'won' ? t('ante.spoilsWon') : t('ante.spoilsOther')
}

export function viewAnteResult(ante: AnteResult, viewerId: string | null): AnteResultView {
  const won: AnteTransfer[] = []
  const lost: AnteTransfer[] = []
  const others: AnteTransfer[] = []
  for (const t of ante.transfers) {
    // A card moving between a player and themselves isn't a transfer worth showing.
    if (t.fromPlayerId === t.toPlayerId) continue
    if (viewerId !== null && t.toPlayerId === viewerId) won.push(t)
    else if (viewerId !== null && t.fromPlayerId === viewerId) lost.push(t)
    else others.push(t)
  }
  const any = won.length + lost.length + others.length > 0
  const scopeSide = won.length > 0 && lost.length === 0 ? 'won' : lost.length > 0 && won.length === 0 ? 'lost' : 'other'
  const refillTransfers = [...lost, ...others]
    .filter((transfer): transfer is AnteTransfer & { refilledWith: string } => !!transfer.refilledWith)
    .map((transfer) => ({ transfer, isViewer: transfer.fromPlayerId === viewerId, cardName: transfer.refilledWith }))
  return {
    won,
    lost,
    others,
    scope: ante.scope,
    scopeSide,
    empty: !any,
    refillTransfers,
  }
}

/**
 * The one-line caption under a card: who it came from or went to. A card won from the ante is
 * "won"; one an ante card's effect moved (Darkpact, Bronze Tablet) was "taken".
 */
export function anteTransferCaption(
  transfer: AnteTransfer,
  side: 'won' | 'lost' | 'other',
  t: TFunction = i18n.t,
): string {
  const taken = transfer.cause === 'OWNERSHIP'
  switch (side) {
    case 'won': return taken
      ? t('ante.takenFrom', { playerName: transfer.fromPlayerName })
      : t('ante.wonFrom', { playerName: transfer.fromPlayerName })
    case 'lost': return taken
      ? t('ante.takenBy', { playerName: transfer.toPlayerName })
      : t('ante.lostTo', { playerName: transfer.toPlayerName })
    case 'other': return taken
      ? t('ante.otherTook', { toPlayerName: transfer.toPlayerName, fromPlayerName: transfer.fromPlayerName })
      : t('ante.otherWon', { toPlayerName: transfer.toPlayerName, fromPlayerName: transfer.fromPlayerName })
  }
}

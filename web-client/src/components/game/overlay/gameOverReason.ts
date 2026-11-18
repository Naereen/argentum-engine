import { GameOverReason } from '@/types'
import i18n from '@/i18n'
import type { TFunction } from 'i18next'

export const OTARIA_MIRARI_GAME_OVER_TEXT = 'Mirari was revealed at its matching location.'

type GameOverReasonKey =
  | 'game.reason.opponentLifeZero'
  | 'game.reason.opponentDeckOut'
  | 'game.reason.opponentConceded'
  | 'game.reason.opponentPoisoned'
  | 'game.reason.opponentDisconnected'
  | 'game.reason.yourLifeZero'
  | 'game.reason.yourDeckOut'
  | 'game.reason.youConceded'
  | 'game.reason.youPoisoned'
  | 'game.reason.youDisconnected'

export function formatGameOverReason(
  reason: GameOverReason,
  result: 'win' | 'lose' | 'draw',
  table: 'duel' | 'pod' | 'teams' = 'duel',
  t: TFunction = i18n.t,
): string {
  if (result === 'draw') {
    if (reason === GameOverReason.CARD_EFFECT) return t('game.reason.effectMadeDraw')
    return table === 'duel' ? t('game.reason.bothPlayersLost') : t('game.reason.remainingPlayersLost')
  }
  if (reason === GameOverReason.OTARIA_MIRARI) return t('game.reason.mirari')
  if (result === 'win' && table === 'pod') return t('game.reason.lastPlayerStanding')
  if (result === 'win' && table === 'teams') return t('game.reason.lastTeamStanding')
  const winMessages: Partial<Record<GameOverReason, GameOverReasonKey>> = {
    [GameOverReason.LIFE_ZERO]: 'game.reason.opponentLifeZero',
    [GameOverReason.DECK_OUT]: 'game.reason.opponentDeckOut',
    [GameOverReason.CONCESSION]: 'game.reason.opponentConceded',
    [GameOverReason.POISON_COUNTERS]: 'game.reason.opponentPoisoned',
    [GameOverReason.DISCONNECTION]: 'game.reason.opponentDisconnected',
  }
  const loseMessages: Partial<Record<GameOverReason, GameOverReasonKey>> = {
    [GameOverReason.LIFE_ZERO]: 'game.reason.yourLifeZero',
    [GameOverReason.DECK_OUT]: 'game.reason.yourDeckOut',
    [GameOverReason.CONCESSION]: 'game.reason.youConceded',
    [GameOverReason.POISON_COUNTERS]: 'game.reason.youPoisoned',
    [GameOverReason.DISCONNECTION]: 'game.reason.youDisconnected',
  }
  const messages = result === 'win' ? winMessages : loseMessages
  return t(messages[reason] ?? 'game.reason.ended')
}

import { GameOverReason } from '@/types'

export const OTARIA_MIRARI_GAME_OVER_TEXT = 'Mirari was revealed at its matching location.'

export function formatGameOverReason(
  reason: GameOverReason,
  result: 'win' | 'lose' | 'draw',
  table: 'duel' | 'pod' | 'teams' = 'duel',
): string {
  if (result === 'draw') {
    if (reason === GameOverReason.CARD_EFFECT) return 'An effect made the game a draw. Nobody won.'
    return table === 'duel' ? 'Both players lost simultaneously.' : 'The remaining players lost simultaneously.'
  }
  if (reason === GameOverReason.OTARIA_MIRARI) return OTARIA_MIRARI_GAME_OVER_TEXT
  if (result === 'win' && table === 'pod') return 'You are the last player standing.'
  if (result === 'win' && table === 'teams') return 'Your team is the last one standing.'
  const winMessages: Partial<Record<GameOverReason, string>> = {
    [GameOverReason.LIFE_ZERO]: "Your opponent's life total reached zero.",
    [GameOverReason.DECK_OUT]: 'Your opponent had no cards left to draw.',
    [GameOverReason.CONCESSION]: 'Your opponent conceded the game.',
    [GameOverReason.POISON_COUNTERS]: 'Your opponent received ten poison counters.',
    [GameOverReason.DISCONNECTION]: 'Your opponent disconnected.',
  }
  const loseMessages: Partial<Record<GameOverReason, string>> = {
    [GameOverReason.LIFE_ZERO]: 'Your life total reached zero.',
    [GameOverReason.DECK_OUT]: 'You had no cards left to draw.',
    [GameOverReason.CONCESSION]: 'You conceded the game.',
    [GameOverReason.POISON_COUNTERS]: 'You received ten poison counters.',
    [GameOverReason.DISCONNECTION]: 'You disconnected from the game.',
  }
  const messages = result === 'win' ? winMessages : loseMessages
  return messages[reason] ?? 'The game has ended.'
}

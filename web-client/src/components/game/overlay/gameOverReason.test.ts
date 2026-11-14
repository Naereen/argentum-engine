import { describe, expect, it } from 'vitest'
import { GameOverReason } from '@/types'
import { formatGameOverReason } from './gameOverReason'

describe('game-over reasons', () => {
  it('explains a Mirari victory to both winners and opponents in a pod', () => {
    const reason = GameOverReason.OTARIA_MIRARI
    expect(formatGameOverReason(reason, 'win', 'pod')).toBe('Mirari was revealed at its matching location.')
    expect(formatGameOverReason(reason, 'lose', 'pod')).toBe('Mirari was revealed at its matching location.')
  })

  it('preserves ordinary last-player-standing and draw explanations', () => {
    expect(formatGameOverReason(GameOverReason.LIFE_ZERO, 'win', 'pod')).toBe('You are the last player standing.')
    expect(formatGameOverReason(GameOverReason.CARD_EFFECT, 'draw', 'duel')).toBe('An effect made the game a draw. Nobody won.')
  })
})

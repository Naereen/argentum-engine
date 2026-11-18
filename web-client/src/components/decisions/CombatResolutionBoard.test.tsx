import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import i18n from '@/i18n'
import { entityId, type CombatResolutionDecision } from '@/types'

vi.mock('@/store/gameStore.ts', () => ({
  useGameStore: (select: (state: object) => unknown) => select({
    playerId: entityId('manager'),
    gameState: null,
    hoverCard: () => {},
    submitCombatResolutionDecision: () => {},
  }),
}))

const { CombatResolutionBoard } = await import('./CombatResolutionBoard')

describe('combat resolution for a controlled encounter seat', () => {
  it('renders the acting seat’s editable damage and an attacked creature as a card', () => {
    const actor = entityId('encounter')
    const attacker = entityId('attacker')
    const blocker = entityId('blocker')
    const defender = entityId('defender')
    const decision: CombatResolutionDecision = {
      type: 'CombatResolutionDecision',
      id: 'decision',
      playerId: actor,
      prompt: 'Assign combat damage',
      context: { phase: 'COMBAT' },
      firstStrike: false,
      attackers: [{
        id: attacker, name: 'Attacker', power: 4, toughness: 4,
        hasTrample: true, hasDeathtouch: false, hasFirstStrike: false, hasDoubleStrike: false,
        dealsDamageThisStep: true, bandId: null, attackedDefenderId: defender,
        blockedByIds: [blocker], markedDamage: 0,
      }],
      blockers: [{
        id: blocker, name: 'Blocker', power: 1, toughness: 1,
        hasDeathtouch: false, hasFirstStrike: false, hasDoubleStrike: false,
        dealsDamageThisStep: true, blockedAttackerIds: [attacker], orderedAttackers: [attacker], markedDamage: 0,
      }],
      defenders: [{ id: defender, name: 'Face-down creature', kind: 'CREATURE', lifeOrLoyaltyOrDefense: 2 }],
      edges: [{
        id: 'attacker->defender', sourceId: attacker, targetId: defender, direction: 'ATTACKER_TO_CREATURE',
        amount: 3, maximum: 4, lethal: 0, orderConstrained: true, isTrampleDrain: true, editableBy: actor,
      }],
    }

    const html = renderToStaticMarkup(
      <I18nextProvider i18n={i18n}><CombatResolutionBoard decision={decision} /></I18nextProvider>,
    )

    expect(html).toContain('Attacker')
    expect(html).toContain('Face-down creature')
    expect(html).toContain('trample')
    expect(html).toContain('Reset')
    expect(html).not.toContain('Life:')
  })
})

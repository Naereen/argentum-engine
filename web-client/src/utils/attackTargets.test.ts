import { describe, expect, it } from 'vitest'
import { entityId } from '@/types'
import { unassignedAttackTargets } from './attackTargets'

const attacker = entityId('attacker')
const otherAttacker = entityId('other-attacker')
const player = entityId('opponent')
const actor = entityId('encounter')
const creature = entityId('creature')
const walker = entityId('walker')
const manager = entityId('manager')

const gameState = {
  players: [{ playerId: player, name: 'Opponent' }, { playerId: manager, name: 'Manager' }],
  cards: {
    [creature]: { name: 'Face-down creature', controllerId: actor },
    [walker]: { name: 'Planeswalker', controllerId: player },
  },
  otaria: {
    encounters: [{
      actorId: actor, managerId: manager, explorerId: player,
      position: { column: 0, row: 0 }, participants: [player],
    }],
  },
}

describe('unassigned attack targets', () => {
  it('offers server-authorized players and permanents, including an encounter creature', () => {
    expect(unassignedAttackTargets({
      validAttackTargets: [player, creature, walker],
      attackTargetsByAttacker: {},
      selectedAttackers: [attacker],
      attackerTargets: {},
    }, gameState)).toEqual([
      { id: player, name: 'Opponent', seatId: player },
      { id: creature, name: 'Face-down creature (Manager)', seatId: manager },
      { id: walker, name: 'Planeswalker (Opponent)', seatId: player },
    ])
  })

  it('only offers targets legal for at least one unassigned attacker', () => {
    const combat = {
      validAttackTargets: [player, creature, walker],
      attackTargetsByAttacker: { [attacker]: [creature], [otherAttacker]: [player] },
      selectedAttackers: [attacker, otherAttacker],
      attackerTargets: { [otherAttacker]: player },
    }
    expect(unassignedAttackTargets(combat, gameState)).toEqual([
      { id: creature, name: 'Face-down creature (Manager)', seatId: manager },
    ])
  })

  it('keeps an unresolved server target selectable instead of silently hiding it', () => {
    const missing = entityId('missing')
    expect(unassignedAttackTargets({
      validAttackTargets: [missing], attackTargetsByAttacker: {},
      selectedAttackers: [attacker], attackerTargets: {},
    }, gameState)).toEqual([{ id: missing, name: 'Permanent missing', seatId: missing }])
  })
})

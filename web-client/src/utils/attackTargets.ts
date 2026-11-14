import type { ClientCard, ClientOtariaState, ClientPlayer, EntityId } from '@/types'
import { defendingPlayerOf } from '@/utils/combatTargets'

/**
 * What one attacker may legally attack, as the server sent it: its entry in the sparse
 * `attackTargetsByAttacker` narrowing when present (a per-defender restriction such as "can't
 * attack unless defending player controls a Mountain" or "creatures can't attack you" rules the
 * rest out), otherwise every one of `validAttackTargets`. Pure lookup — no legality is computed
 * here.
 */
export function attackTargetsFor(
  combat: {
    readonly validAttackTargets: readonly EntityId[]
    readonly attackTargetsByAttacker: Readonly<Record<EntityId, readonly EntityId[]>>
  },
  attackerId: EntityId,
): readonly EntityId[] {
  return combat.attackTargetsByAttacker[attackerId] ?? combat.validAttackTargets
}

/** Whether the server lists [targetId] as a legal attack target for [attackerId]. */
export function canAttackTarget(
  combat: Parameters<typeof attackTargetsFor>[0],
  attackerId: EntityId,
  targetId: EntityId,
): boolean {
  return attackTargetsFor(combat, attackerId).includes(targetId)
}

/** Present the server's targets for attackers still needing a defender, including permanents. */
export function unassignedAttackTargets(
  combat: Parameters<typeof attackTargetsFor>[0] & {
    readonly selectedAttackers: readonly EntityId[]
    readonly attackerTargets: Readonly<Record<EntityId, EntityId>>
  },
  gameState: {
    readonly players: readonly Pick<ClientPlayer, 'playerId' | 'name'>[]
    readonly cards: Readonly<Record<EntityId, Pick<ClientCard, 'name' | 'controllerId' | 'protectorId'> | undefined>>
    readonly otaria?: Pick<ClientOtariaState, 'encounters'> | null
  },
): { id: EntityId; name: string; seatId: EntityId }[] {
  const unassigned = combat.selectedAttackers.filter((id) => !combat.attackerTargets[id])
  return combat.validAttackTargets
    .filter((id) => unassigned.some((attacker) => canAttackTarget(combat, attacker, id)))
    .map((id) => {
      const player = gameState.players.find((p) => p.playerId === id)
      if (player) return { id, name: player.name, seatId: id }
      const card = gameState.cards[id]
      const seatId = defendingPlayerOf(id, gameState.cards, gameState.otaria?.encounters)
      const seat = gameState.players.find((p) => p.playerId === seatId)
      const name = card?.name || `Permanent ${id}`
      return { id, name: seat ? `${name} (${seat.name})` : name, seatId }
    })
}

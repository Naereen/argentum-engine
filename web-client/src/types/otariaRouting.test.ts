import { describe, expect, it, vi } from 'vitest'
import { entityId, hasPriority, isMyTurn, actingPlayerFor } from './index'
import { otariaBoard, otariaGameState, otariaManager, otariaViewer } from '@/test/otariaFixtures'

vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} })
const { selectHasPriority, selectIsMyTurn, selectPriorityMode } = await import('@/store/selectors')
const { useGameStore } = await import('@/store/gameStore')
const actor = entityId('encounter')
const board = {
  ...otariaBoard(),
  encounters: [{
    actorId: actor, managerId: otariaManager, explorerId: otariaViewer,
    position: { column: 7, row: 8 }, participants: [otariaViewer],
  }],
}

describe('Otaria encounter input routing', () => {
  it('gives the manager turn and priority controls without giving them to the explorer', () => {
    const state = {
      ...otariaGameState(board), activePlayerId: actor, priorityPlayerId: actor,
      viewingPlayerId: otariaManager,
    }
    expect(actingPlayerFor(state, actor)).toBe(otariaManager)
    expect(hasPriority(state)).toBe(true)
    expect(isMyTurn(state)).toBe(true)
    expect(hasPriority({ ...state, viewingPlayerId: otariaViewer })).toBe(false)
    expect(isMyTurn({ ...state, viewingPlayerId: otariaViewer })).toBe(false)
    const store = { ...useGameStore.getState(), gameState: state, playerId: otariaManager }
    expect(selectHasPriority(store)).toBe(true)
    expect(selectIsMyTurn(store)).toBe(true)
    expect(selectPriorityMode(store)).toBe('ownTurn')
  })

  it('preserves ordinary seat and null routing', () => {
    const state = otariaGameState(null)
    expect(actingPlayerFor(state, otariaViewer)).toBe(otariaViewer)
    expect(actingPlayerFor(state, null)).toBeNull()
    expect(hasPriority({ ...state, priorityPlayerId: otariaManager })).toBe(false)
  })
})

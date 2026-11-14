import { describe, expect, it } from 'vitest'
import { reconstructSnapshots, type SpectatorReplayDelta, type SpectatorStateUpdate } from './reconstructSnapshots'
import { otariaBoard } from '@/test/otariaFixtures'

const snapshot: SpectatorStateUpdate = {
  gameSessionId: 'g',
  gameState: {
    cards: {}, zones: [], players: [], currentPhase: 'MAIN_1', currentStep: 'MAIN', activePlayerId: 'p1',
    priorityPlayerId: 'p1', turnNumber: 1, isGameOver: false, winnerId: null, combat: null, gameLog: [],
    voidActive: false, dayNight: null,
  },
  player1Id: 'p1', player2Id: 'p2', player1Name: 'A', player2Name: 'B', player1: null, player2: null,
  currentPhase: 'MAIN_1', activePlayerId: 'p1', priorityPlayerId: 'p1', combat: null, decisionStatus: null,
}

const frame = (gameStateDelta: Record<string, unknown>): SpectatorReplayDelta =>
  ({ gameStateDelta: { players: [], ...gameStateDelta } })

describe('reconstructSnapshots', () => {
  it('follows the Void condition and day/night through replay deltas, keeping them when omitted', () => {
    const frames = reconstructSnapshots(snapshot, [
      frame({ voidActive: true, dayNight: 'DAY' }),
      frame({}),
      frame({ voidActive: false }),
    ]).map((s) => s.gameState as { voidActive: boolean; dayNight: string | null })

    expect(frames.map((g) => g.voidActive)).toEqual([false, true, true, false])
    expect(frames.map((g) => g.dayNight)).toEqual([null, 'DAY', 'DAY', 'DAY'])
  })

  it('follows Otaria snapshots through additions, updates, omissions, and clears', () => {
    const board = otariaBoard()
    const changed = { ...board, treasureCount: 10 }
    const frames = reconstructSnapshots(snapshot, [
      frame({ otaria: board }),
      frame({}),
      frame({ otaria: changed }),
      frame({ otariaCleared: true }),
    ]).map((s) => (s.gameState as { otaria?: unknown }).otaria ?? null)

    expect(frames).toEqual([null, board, board, changed, null])
  })
})

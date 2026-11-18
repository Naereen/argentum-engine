import { Color, Phase, Step, entityId } from '@/types'
import type { ClientGameState, ClientOtariaState, ClientPlayer, EntityId } from '@/types'

export const otariaViewer = entityId('otaria-player-1')
export const otariaManager = entityId('otaria-player-2')
const thirdPlayer = entityId('otaria-player-3')

export function otariaBoard(): ClientOtariaState {
  const pile = { remaining: 55, discarded: 0 }
  return {
    tiles: [
      { position: { column: 6, row: 5 }, color: Color.GREEN, location: 'KROSIAN_FOREST' },
      { position: { column: 7, row: 3 }, color: Color.WHITE, location: 'ORDER_ENCAMPMENT' },
      { position: { column: 7, row: 8 }, color: Color.BLACK, location: 'SOUTHERN_PIT_ARENA' },
    ],
    players: [
      {
        playerId: otariaViewer, startingLocation: 'KROSIAN_FOREST', position: { column: 6, row: 5 },
        reserveCount: 1,
        privateData: {
          reserve: [{
            id: entityId('secret-mirari'),
            name: 'Mirari',
            manaCost: '{5}',
            typeLine: 'Legendary Artifact',
            oracleText: 'An artifact.',
            cmc: 5,
            cardTypes: ['ARTIFACT'],
            colors: [],
          }],
          locationCards: ['CABAL_CITY'],
          treasureTokens: { SCRAP: 1 },
        },
      },
      {
        playerId: otariaManager, startingLocation: 'ORDER_ENCAMPMENT', position: { column: 7, row: 3 },
        reserveCount: 2,
      },
      {
        playerId: thirdPlayer, startingLocation: 'SOUTHERN_PIT_ARENA', position: { column: 7, row: 8 },
        reserveCount: 0,
      },
    ],
    locations: [{ location: 'KROSIAN_FOREST', used: 1, limit: 4 }],
    starPile: { remaining: 22, discarded: 1 },
    colorPiles: {
      [Color.WHITE]: pile,
      [Color.BLUE]: pile,
      [Color.BLACK]: pile,
      [Color.RED]: pile,
      [Color.GREEN]: pile,
    },
    treasureCount: 11,
    locationCount: 24,
    pendingExploration: {
      explorerId: otariaViewer,
      managerId: otariaManager,
      source: { type: 'Terrain', color: Color.GREEN },
      stage: 'DRAWN',
      faceDown: true,
    },
  }
}

function player(playerId: EntityId, name: string): ClientPlayer {
  return {
    playerId, name, life: 15, poisonCounters: 0, handSize: 5, librarySize: 30,
    graveyardSize: 0, exileSize: 0, landsPlayedThisTurn: 0, hasLost: false,
  }
}

export function otariaGameState(board: ClientOtariaState | null = otariaBoard()): ClientGameState {
  return {
    viewingPlayerId: otariaViewer,
    cards: {},
    zones: [],
    players: [player(otariaViewer, 'Alice'), player(otariaManager, 'Bob'), player(thirdPlayer, 'Cara')],
    currentPhase: Phase.PRECOMBAT_MAIN,
    currentStep: Step.PRECOMBAT_MAIN,
    activePlayerId: otariaViewer,
    priorityPlayerId: otariaViewer,
    turnNumber: 1,
    isGameOver: false,
    winnerId: null,
    combat: null,
    otaria: board,
  }
}

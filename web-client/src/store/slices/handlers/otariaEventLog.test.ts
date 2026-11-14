import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ClientEvent } from '@/types'
import { entityId } from '@/types'
import { otariaGameState, otariaViewer } from '@/test/otariaFixtures'

vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} })
vi.stubGlobal('window', { addEventListener: () => {}, removeEventListener: () => {} })

const { useGameStore } = await import('../../gameStore')
const { createGameplayHandlers } = await import('./gameplayHandlers')
const handlers = createGameplayHandlers(useGameStore.setState, useGameStore.getState)
const imageUri = '/images/otaria/events/naturalisation.png'
const drawn: ClientEvent = {
  type: 'otariaNotice', explorerId: otariaViewer, cardName: 'Ev. Naturalisation', imageUri,
  description: 'Ev. Naturalisation was drawn.',
}
const finished: ClientEvent = { ...drawn, description: 'Ev. Naturalisation finished resolving and was discarded.' }

function receive(gameLog: readonly ClientEvent[], events: readonly ClientEvent[] = []) {
  handlers.onStateUpdate({
    type: 'stateUpdate', state: { ...otariaGameState(), gameLog },
    events, legalActions: [],
  })
}

beforeEach(() => {
  useGameStore.setState({
    sessionId: 'otaria-log-test', playerId: otariaViewer, eventLog: [],
    gameState: null, gameOverState: null,
  })
})

describe('Otaria event log state', () => {
  it('retains the card and explorer through subsequent updates and reconnect snapshots', () => {
    receive([drawn])
    expect(useGameStore.getState().eventLog[0]).toMatchObject({
      cardName: drawn.cardName, imageUri, playerId: otariaViewer,
    })
    const first = useGameStore.getState().eventLog[0]
    receive([drawn, finished])
    expect(useGameStore.getState().eventLog[0]).toBe(first)
    expect(useGameStore.getState().eventLog.map((entry) => entry.description))
      .toEqual([drawn.description, finished.description])
    useGameStore.setState({ eventLog: [] })
    receive([drawn, finished])
    expect(useGameStore.getState().eventLog.map((entry) => entry.imageUri)).toEqual([imageUri, imageUri])
  })

  it('keeps artwork from an event reveal after its temporary source entity has been removed', () => {
    receive([drawn, finished], [{
      type: 'cardsRevealed', revealingPlayerId: otariaViewer,
      cardIds: [entityId('temporary-source')], cardNames: ['Ev. Naturalisation'], imageUris: [imageUri],
      source: 'Explorer of Otaria', description: 'Event revealed',
    }])
    expect(useGameStore.getState().revealedCardsInfo?.imageUris).toEqual([imageUri])
  })
})

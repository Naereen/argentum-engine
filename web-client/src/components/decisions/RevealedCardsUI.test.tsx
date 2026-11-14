import { renderToStaticMarkup } from 'react-dom/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { entityId } from '@/types'
import { otariaGameState, otariaViewer } from '@/test/otariaFixtures'
import type { GameStore } from '@/store/slices/types'

vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => {}, removeItem: () => {} })
vi.stubGlobal('window', { addEventListener: () => {}, removeEventListener: () => {} })
vi.mock('@/hooks/useResponsive.ts', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/hooks/useResponsive.ts')>(),
  useResponsive: () => viewport,
}))
vi.mock('@/store/gameStore.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/store/gameStore.ts')>()
  return {
    ...actual,
    useGameStore: Object.assign(
      <T,>(selector: (state: GameStore) => T) => selector(actual.useGameStore.getState()),
      actual.useGameStore,
    ),
  }
})

let viewport = { viewportWidth: 1280, viewportHeight: 900, containerPadding: 16, isMobile: false }
const { useGameStore } = await import('@/store/gameStore')
const { RevealedCardsUI } = await import('./RevealedCardsUI')

beforeEach(() => {
  viewport = { viewportWidth: 1280, viewportHeight: 900, containerPadding: 16, isMobile: false }
  useGameStore.setState({
    gameState: otariaGameState(), playerId: otariaViewer, pendingDecision: null, revealedHandCardIds: null,
    revealedCardsInfo: {
      cardIds: [entityId('removed-event-source')], cardNames: ['Ev. Naturalisation'],
      imageUris: ['/images/otaria/events/naturalisation.png'], source: 'Explorer of Otaria',
      isYourReveal: true,
    },
  })
})

describe('Otaria event reveals', () => {
  it('uses a readable proxy without a bogus source-card lookup', () => {
    const html = renderToStaticMarkup(<RevealedCardsUI />)
    expect(html).toContain('src="/images/otaria/events/naturalisation.png"')
    expect(html).toContain('width:300px')
    expect(html).not.toContain('scryfall')
  })

  it('reduces the event image to keep controls visible on a short screen', () => {
    viewport = { viewportWidth: 1280, viewportHeight: 600, containerPadding: 16, isMobile: false }
    const html = renderToStaticMarkup(<RevealedCardsUI />)
    expect(html).toContain('width:228px')
    expect(html).toContain('View Battlefield')
    expect(html).toContain('OK')
  })

  it('keeps normal revealed-card sizes unchanged', () => {
    useGameStore.setState({
      revealedCardsInfo: {
        cardIds: [entityId('ordinary-card')], cardNames: ['Forest'],
        imageUris: ['/images/forest.png'], source: null, isYourReveal: true,
      },
    })
    expect(renderToStaticMarkup(<RevealedCardsUI />)).toContain('width:180px')
  })
})

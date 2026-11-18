import { describe, expect, it } from 'vitest'
import type { AnteTransfer } from '@/types'
import { anteScopeLine, anteTransferCaption, viewAnteResult } from './anteResult'
import i18n from '@/i18n'

const transfer = (overrides: Partial<AnteTransfer> = {}): AnteTransfer => ({
  cardName: 'Shivan Dragon',
  imageUri: null,
  fromPlayerId: 'bob',
  fromPlayerName: 'Bob',
  toPlayerId: 'alice',
  toPlayerName: 'Alice',
  cause: 'ANTE',
  ...overrides,
})

describe('viewAnteResult', () => {
  const ante = {
    scope: 'EVENT' as const,
    transfers: [transfer(), transfer({ cardName: 'Bronze Tablet', fromPlayerId: 'alice', fromPlayerName: 'Alice', toPlayerId: 'bob', toPlayerName: 'Bob', cause: 'OWNERSHIP' })],
  }

  it('splits transfers into won and lost for a seated player', () => {
    const view = viewAnteResult(ante, 'alice')
    expect(view.won.map((t) => t.cardName)).toEqual(['Shivan Dragon'])
    expect(view.lost.map((t) => t.cardName)).toEqual(['Bronze Tablet'])
    expect(view.others).toEqual([])
    expect(view.scope).toBe('EVENT')
    expect(view.scopeSide).toBe('other')
    expect(anteScopeLine(view.scope, view.scopeSide)).toMatch(/rest of the tournament/)
  })

  it('shows a spectator every transfer as neutral', () => {
    const view = viewAnteResult({ ...ante, scope: 'SPOILS' }, null)
    expect(view.others).toHaveLength(2)
    expect(anteScopeLine(view.scope, view.scopeSide)).toMatch(/spoils/)
  })

  it('says the ante goes home when nothing changed hands', () => {
    const view = viewAnteResult({ scope: 'SPOILS', transfers: [transfer({ toPlayerId: 'bob' })] }, 'bob')
    expect(view.won).toEqual([])
    expect(view.empty).toBe(true)
    expect(view.scopeSide).toBe('other')
  })
})

describe('refill lines', () => {
  it('tells the loser which basic land topped their deck back up', () => {
    const ante = { scope: 'EVENT' as const, transfers: [transfer({ refilledWith: 'Island' })] }
    expect(viewAnteResult(ante, 'bob').refillTransfers.map(({ isViewer, cardName }) => [isViewer, cardName])).toEqual([[true, 'Island']])
    expect(viewAnteResult(ante, null).refillTransfers[0]?.isViewer).toBe(false)
    expect(viewAnteResult(ante, 'alice').refillTransfers).toEqual([])
  })
})

describe('anteTransferCaption', () => {
  it('names the other side and marks a card-effect transfer', () => {
    void i18n.changeLanguage('en')
    expect(anteTransferCaption(transfer(), 'won')).toBe('won from Bob')
    expect(anteTransferCaption(transfer({ cause: 'OWNERSHIP' }), 'won')).toBe('taken from Bob')
    expect(anteTransferCaption(transfer(), 'lost')).toBe('lost to Alice')
    expect(anteTransferCaption(transfer({ cause: 'OWNERSHIP' }), 'lost')).toBe('taken by Alice')
    expect(anteTransferCaption(transfer(), 'other')).toBe('Alice won it from Bob')
    expect(anteTransferCaption(transfer({ cause: 'OWNERSHIP' }), 'other')).toBe('Alice took it from Bob')
  })

  it('localizes ante captions without translating player or card names', async () => {
    await i18n.changeLanguage('fr')
    expect(anteTransferCaption(transfer(), 'won')).toBe('remportée à Bob')
    await i18n.changeLanguage('en')
  })
})

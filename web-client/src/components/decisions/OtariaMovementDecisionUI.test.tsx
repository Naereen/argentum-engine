import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { otariaBoard, otariaGameState, otariaViewer } from '@/test/otariaFixtures'
import type { ChooseOptionDecision } from '@/types'
import { OtariaMovementDecisionUI, otariaMovementChoices } from './OtariaMovementDecisionUI'
import { I18nextProvider } from 'react-i18next'
import i18n from '@/i18n'

const decision: ChooseOptionDecision = {
  type: 'ChooseOptionDecision',
  id: 'move',
  playerId: otariaViewer,
  prompt: 'Choose a destination',
  context: { phase: 'RESOLUTION' },
  options: ['Order Encampment', 'Southern Pit-Arena'],
  optionMetadata: [{ id: 'otaria-move:7:3' }, { id: 'otaria-move:7:8' }],
}

describe('Otaria movement decision', () => {
  it('only makes server-provided destinations selectable on the map', () => {
    const board = otariaBoard()
    const gameState = otariaGameState(board)
    const choices = otariaMovementChoices(decision, gameState)
    expect(choices).toEqual([
      { position: { column: 7, row: 3 }, index: 0, label: 'Order Encampment' },
      { position: { column: 7, row: 8 }, index: 1, label: 'Southern Pit-Arena' },
    ])
    const html = renderToStaticMarkup(
      <I18nextProvider i18n={i18n}><OtariaMovementDecisionUI decision={decision} gameState={gameState} board={board} choices={choices ?? []} /></I18nextProvider>,
    )
    expect(html.match(/role="button"/g)).toHaveLength(2)
    expect(html).toContain('aria-label="Order Encampment"')
    expect(html).toContain('Confirm move')
    expect(html).toContain('aria-pressed="false">Zoom in</button>')
    expect(html).toContain('aria-pressed="true">Full map</button>')
    expect(html).toContain('viewBox="-8 -8 390 512"')
    expect(html).toContain('disabled=""')
  })

  it('falls back to the normal option list for missing or invalid map metadata', () => {
    const state = otariaGameState()
    expect(otariaMovementChoices({ ...decision, optionMetadata: [{ id: 'otaria-move:99:99' }] }, state)).toBeNull()
    expect(otariaMovementChoices({ ...decision, optionMetadata: [{ id: 'other' }, { id: 'otaria-move:7:8' }] }, state)).toBeNull()
    expect(otariaMovementChoices({ ...decision, optionMetadata: [{ id: 'otaria-move:7:3' }, { id: 'otaria-move:7:3' }] }, state)).toBeNull()
    expect(otariaMovementChoices(decision, otariaGameState(null))).toBeNull()
  })
})

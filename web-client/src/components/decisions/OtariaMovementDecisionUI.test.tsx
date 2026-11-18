import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { otariaBoard, otariaGameState, otariaViewer } from '@/test/otariaFixtures'
import type { ChooseOptionDecision } from '@/types'
import { OtariaMovementDecisionUI, otariaMovementChoices } from './OtariaMovementDecisionUI'
import { I18nextProvider } from 'react-i18next'
import i18n from '@/i18n'
import { ChooseOptionDecisionUI } from './ChooseOptionDecisionUI'

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
  it('localizes the explore-choice modal in French', async () => {
    await i18n.changeLanguage('fr')
    const exploreChoice: ChooseOptionDecision = {
      ...decision,
      prompt: 'Choose what to explore',
      options: [
        'Black terrain - take a basic land',
        'Black terrain - no basic land',
        'Starred location - take a basic land',
      ],
    }
    const html = renderToStaticMarkup(
      <I18nextProvider i18n={i18n}><ChooseOptionDecisionUI decision={exploreChoice} /></I18nextProvider>,
    )
    expect(html).toContain('Choisissez quoi explorer')
    expect(html).toContain('Terrain noir - prendre un terrain de base')
    expect(html).toContain('Terrain noir - aucun terrain de base')
    expect(html).toContain('Lieu marqué d&#x27;une étoile - prendre un terrain de base')
    await i18n.changeLanguage('en')
  })

  it('localizes the turn-action choice modal in French', async () => {
    await i18n.changeLanguage('fr')
    const turnChoice: ChooseOptionDecision = {
      ...decision,
      prompt: 'Choose your action for this turn',
      options: ['Move', 'Explore', 'Prepare', 'Combat'],
      optionMetadata: [
        { description: 'Move one or two spaces after your main phase.' },
        { description: 'Explore your terrain or a starred location.' },
        { description: 'Add reserve cards to your library before drawing.' },
        { description: 'Draw, then take a combat phase.' },
      ],
    }
    const html = renderToStaticMarkup(
      <I18nextProvider i18n={i18n}><ChooseOptionDecisionUI decision={turnChoice} /></I18nextProvider>,
    )
    expect(html).toContain('Choisissez votre action pour ce tour')
    expect(html).toContain('Déplacement')
    expect(html).toContain('Explorer')
    expect(html).toContain('Préparer')
    expect(html).toContain('Combat')
    expect(html).toContain('Déplacez-vous d&#x27;une ou deux cases après votre phase principale.')
    expect(html).toContain('Voir le champ de bataille')
    expect(html).toContain('Confirmer')
    await i18n.changeLanguage('en')
  })

  it('localizes the prompt and destination labels in French', async () => {
    await i18n.changeLanguage('fr')
    const board = otariaBoard()
    const gameState = otariaGameState(board)
    const choices = otariaMovementChoices(decision, gameState)
    const html = renderToStaticMarkup(
      <I18nextProvider i18n={i18n}><OtariaMovementDecisionUI decision={decision} gameState={gameState} board={board} choices={choices ?? []} /></I18nextProvider>,
    )
    expect(html).toContain('Choisissez une destination')
    expect(html).toContain('aria-label="Colonne 8, rangée 4 : Campement de l&#x27;Ordre"')
    expect(html).toContain('Confirmer le déplacement')
    await i18n.changeLanguage('en')
  })

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
    expect(html).toContain('aria-label="Column 8, row 4: Order Encampment"')
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

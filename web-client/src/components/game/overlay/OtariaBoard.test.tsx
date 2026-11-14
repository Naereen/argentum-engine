import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { otariaBoard, otariaGameState, otariaViewer, otariaManager } from '@/test/otariaFixtures'
import { Color, entityId } from '@/types'
import type { ClientOtariaState } from '@/types'
import { OtariaBoardButton, OtariaBoardContents, OtariaMap, OtariaReserveButton } from './OtariaBoard'
import styles from './OtariaBoard.module.css'

describe('Otaria board', () => {
  it('shows the supplied event artwork while the exploration is pending', () => {
    const original = otariaBoard()
    const board: ClientOtariaState = {
      ...original,
      pendingExploration: {
        explorerId: otariaViewer, managerId: otariaManager,
        source: { type: 'Terrain', color: Color.GREEN }, stage: 'DRAWN', faceDown: false,
        card: { name: 'Ev. Naturalisation', kind: 'EVENT', imageUri: '/images/otaria/events/naturalisation.png' },
      },
    }
    const html = renderToStaticMarkup(<OtariaBoardContents gameState={otariaGameState(board)} board={board} />)
    expect(html).toContain('src="/images/otaria/events/naturalisation.png"')
    expect(html).toContain('alt="Ev. Naturalisation"')
  })
  it('can zoom around the available moves and active player without changing the selection', () => {
    const board = otariaBoard()
    const props = {
      gameState: otariaGameState(board),
      board,
      choices: [{ position: { column: 7, row: 3 }, index: 0, label: 'Order Encampment' }],
      selectedIndex: 0,
    }
    const local = renderToStaticMarkup(<OtariaMap {...props} zoomed />)
    const full = renderToStaticMarkup(<OtariaMap {...props} zoomed={false} />)
    const viewBox = local.match(/viewBox="([^"]+)"/)![1]!.split(' ').map(Number)
    const [x, y, width, height] = viewBox as [number, number, number, number]
    for (const position of [board.players[0]!.position, props.choices[0]!.position]) {
      const centerX = 25 + position.column * 27
      const centerY = 25 + position.row * 31.18 + (position.column % 2) * 15.59
      expect(centerX - x).toBeGreaterThanOrEqual(32)
      expect(x + width - centerX).toBeGreaterThanOrEqual(32)
      expect(centerY - y).toBeGreaterThanOrEqual(32)
      expect(y + height - centerY).toBeGreaterThanOrEqual(32)
    }
    expect(width).toBeLessThan(390)
    expect(height).toBeLessThan(512)
    expect(full).toContain('viewBox="-8 -8 390 512"')
    for (const html of [local, full]) {
      expect(html).toContain('aria-label="Order Encampment" aria-pressed="true"')
      expect(html.match(/role="button"/g)).toHaveLength(1)
    }
    expect(renderToStaticMarkup(<OtariaMap gameState={props.gameState} board={board} zoomed />))
      .toContain('viewBox="-8 -8 390 512"')
  })

  it('keeps the whole map and distant player positions in view when choosing a move', () => {
    const original = otariaBoard()
    const board = {
      ...original,
      tiles: [
        ...original.tiles,
        { position: { column: 0, row: 0 }, color: Color.BLUE },
        { position: { column: 12, row: 14 }, color: Color.BLUE },
      ],
      players: original.players.map((player, index) =>
        index === 1 ? { ...player, position: { column: 12, row: 14 } } : player),
    }
    const html = renderToStaticMarkup(
      <OtariaMap
        gameState={otariaGameState(board)}
        board={board}
        choices={[{ position: { column: 7, row: 3 }, index: 0, label: 'Order Encampment' }]}
      />,
    )
    expect(html).toContain('viewBox="-8 -8 390 512"')
    expect(html).toContain('aria-label="Order Encampment"')
    expect(html).toContain('cx="349" cy="469.52"')
  })

  it('draws the selected destination above terrain without intercepting input', () => {
    const board = otariaBoard()
    const choices = [
      { position: { column: 7, row: 3 }, index: 0, label: 'Order Encampment' },
      { position: { column: 7, row: 8 }, index: 1, label: 'Southern Pit-Arena' },
    ]
    const renderMap = (selectedIndex: number | null) => renderToStaticMarkup(
      <OtariaMap gameState={otariaGameState(board)} board={board} choices={choices} selectedIndex={selectedIndex} />,
    )
    const unselected = renderMap(null)
    const selected = renderMap(0)
    const otherSelected = renderMap(1)
    const selectedDestination = styles.selectedDestination
    if (!selectedDestination) throw new Error('Missing selectedDestination CSS class')

    expect(unselected).not.toContain(selectedDestination)
    expect(selected).toContain('aria-label="Order Encampment" aria-pressed="true"')
    expect(selected).toContain(`class="${selectedDestination}" aria-hidden="true" pointer-events="none"`)
    expect(selected.indexOf(selectedDestination)).toBeGreaterThan(selected.lastIndexOf('</title>'))
    expect(selected.indexOf(selectedDestination)).toBeLessThan(selected.indexOf('<circle'))
    expect(otherSelected).toContain('aria-label="Southern Pit-Arena" aria-pressed="true"')
    expect(otherSelected).not.toContain('aria-label="Order Encampment" aria-pressed="true"')
  })

  it('keeps five enlarged player markers separate on a shared hex and above the terrain', () => {
    const original = otariaBoard()
    const board = {
      ...original,
      players: Array.from({ length: 5 }, (_, index) => ({
        ...original.players[0]!, playerId: entityId(`player-${index + 1}`),
      })),
    }
    const originalState = otariaGameState(board)
    const state = {
      ...originalState,
      players: board.players.map((player, index) => ({
        ...originalState.players[0]!, playerId: player.playerId, name: `Player ${index + 1}`,
      })),
    }
    const html = renderToStaticMarkup(<OtariaMap gameState={state} board={board} />)
    const circles = [...html.matchAll(/<circle cx="([^"]+)" cy="([^"]+)" r="9"/g)]
    expect(circles).toHaveLength(5)
    const centers = circles.map((match) => [Number(match[1]), Number(match[2])] as const)
    for (let i = 0; i < centers.length; i++) {
      for (let j = i + 1; j < centers.length; j++) {
        expect(Math.hypot(centers[i]![0] - centers[j]![0], centers[i]![1] - centers[j]![1])).toBeGreaterThanOrEqual(19)
      }
    }
    for (let number = 1; number <= 5; number++) {
      expect(html).toContain(`>${number}</text>`)
    }
    expect(html.indexOf('<circle')).toBeGreaterThan(html.lastIndexOf('<polygon'))
    expect(html).toContain('pointer-events="none"')
  })

  it('only offers the map for Otaria positions', () => {
    expect(renderToStaticMarkup(<OtariaBoardButton gameState={otariaGameState()} />)).toContain('Otaria map')
    expect(renderToStaticMarkup(<OtariaBoardButton gameState={otariaGameState(null)} />)).toBe('')
  })

  it('renders the live map as an accessible full-panel button in the overview', () => {
    const state = otariaGameState()
    const html = renderToStaticMarkup(<OtariaBoardButton gameState={state} miniature />)
    expect(html).toContain('aria-label="Open Otaria map"')
    expect(html).toContain('viewBox="-8 -8 390 512"')
    expect(html).toContain('<circle')
    expect(renderToStaticMarkup(<OtariaBoardButton gameState={otariaGameState(null)} miniature />)).toBe('')
  })

  it('offers the viewing player direct access to their private reserve from the game HUD', () => {
    const state = otariaGameState()
    const html = renderToStaticMarkup(<OtariaReserveButton gameState={state} />)

    expect(html).toContain('aria-label="Open your reserve, 1 card"')
    expect(html).toContain('Reserve · 1')
    expect(renderToStaticMarkup(<OtariaReserveButton gameState={otariaGameState(null)} />)).toBe('')

    const hiddenFromViewer = {
      ...state,
      otaria: {
        ...state.otaria!,
        players: state.otaria!.players.map((player) => ({ ...player, privateData: null })),
      },
    }
    expect(renderToStaticMarkup(<OtariaReserveButton gameState={hiddenFromViewer} />)).toBe('')
  })

  it('shows public positions and counts and only the private reserve provided by the server', () => {
    const board = otariaBoard()
    const html = renderToStaticMarkup(<OtariaBoardContents gameState={otariaGameState(board)} board={board} />)

    expect(html).toContain('Krosian Forest')
    expect(html).toContain('Alice')
    expect(html).toContain('Bob')
    expect(html).toContain('2 in reserve')
    expect(html).toContain('1 / 4')
    expect(html).toContain('Your private reserve')
    expect(html).toContain('Mirari')
    expect(html).toContain('Cabal City')
    expect(html).toContain('scrap: ')
    expect(html).toContain('Face-down creature')
    expect(html).toContain('href="/help/playing#otaria-map"')
    expect(html).toContain('href="/otaria"')
    expect(html).not.toContain('Face-down creature:')
    expect(html).not.toContain('Bob&#x27;s private reserve')
  })

  it('identifies a face-down creature only when its identity is in the projection', () => {
    const board = otariaBoard()
    const pending = board.pendingExploration!
    const managerView = {
      ...board,
      pendingExploration: { ...pending, card: { name: 'Hidden Morph', kind: 'CREATURE' as const } },
    }
    const html = renderToStaticMarkup(
      <OtariaBoardContents gameState={otariaGameState(managerView)} board={managerView} />,
    )
    expect(html).toContain('Face-down creature: Hidden Morph (private)')
  })

  it('distinguishes encounter actors from the players exploring, managing and participating', () => {
    const board: ClientOtariaState = {
      ...otariaBoard(),
      pendingExploration: null,
      turn: { action: 'EXPLORE', movementAllowed: false, explorationFinished: true },
      encounters: [{
        actorId: entityId('encounter-actor'),
        explorerId: otariaViewer,
        managerId: otariaManager,
        position: { column: 7, row: 8 },
        participants: [otariaViewer, otariaManager],
      }],
    }
    const html = renderToStaticMarkup(
      <OtariaBoardContents gameState={otariaGameState(board)} board={board} />,
    )
    expect(html).toContain('Current turn')
    expect(html).toContain('Explore')
    expect(html).toContain('Exploration completed.')
    expect(html).toContain('Active encounters')
    expect(html).toContain('Southern Pit-Arena')
    expect(html).toContain('<strong>Alice</strong> explores; <strong>Bob</strong> manages the encounter.')
    expect(html).toContain('Participants: Alice, Bob')
    expect(html).not.toContain('encounter-actor')
    expect(html).not.toContain('Current exploration')
    expect(html).not.toContain('Movement is no longer available')
  })

  it('reports server-supplied movement availability without inventing actions', () => {
    const board: ClientOtariaState = {
      ...otariaBoard(),
      turn: { action: 'MOVE', movementAllowed: false, explorationFinished: false },
    }
    const html = renderToStaticMarkup(
      <OtariaBoardContents gameState={otariaGameState(board)} board={board} />,
    )
    expect(html).toContain('Movement is no longer available this turn.')
    expect(html).not.toContain('Active encounters')
    expect(html).not.toContain('Exploration completed.')
  })
})

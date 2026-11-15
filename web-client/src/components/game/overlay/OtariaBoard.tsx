import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Color } from '@/types'
import type { ClientGameState, ClientOtariaReserveCard, ClientOtariaState, EntityId, OtariaLocation, OtariaPosition } from '@/types'
import styles from './OtariaBoard.module.css'

const locationNames: Record<OtariaLocation, string> = {
  KROSIAN_FOREST: 'Krosian Forest',
  ORDER_ENCAMPMENT: 'Order Encampment',
  SOUTHERN_PIT_ARENA: 'Southern Pit-Arena',
  BALSHAN_BAY: 'Balshan Bay',
  PARDIC_MOUNTAINS: 'Pardic Mountains',
  ORDER_SOUTHERN_ENCAMPMENT: 'Order Southern Encampment',
  CABAL_CITY: 'Cabal City',
  CEPHALID_SOUTHERN_PALACE: 'Cephalid Southern Palace',
}

const terrainColors: Record<Color, string> = {
  [Color.WHITE]: '#d8c99e',
  [Color.BLUE]: '#4388a9',
  [Color.BLACK]: '#776984',
  [Color.RED]: '#b06450',
  [Color.GREEN]: '#668b65',
}
const seatColors = ['#ffe19a', '#b6dafe', '#f9b4a8', '#bde7b2', '#dcc0ff']
const terrainOrder = [Color.GREEN, Color.RED, Color.BLACK, Color.BLUE, Color.WHITE]
const turnActions = { MOVE: 'Move', EXPLORE: 'Explore', PREPARE: 'Prepare', COMBAT: 'Combat' }

function ReserveCards({ cards }: { cards: readonly ClientOtariaReserveCard[] }) {
  return (
    <>
      {cards.length === 0 && <p className={styles.muted}>No cards in reserve.</p>}
      {cards.map((card) => (
        <details key={card.id} className={styles.reserveCard}>
          <summary><span>{card.name}</span><span>{card.manaCost}</span></summary>
          <p className={styles.muted}>{card.typeLine}</p>
          {card.oracleText && <p>{card.oracleText}</p>}
        </details>
      ))}
    </>
  )
}

function samePosition(a: OtariaPosition, b: OtariaPosition): boolean {
  return a.column === b.column && a.row === b.row
}

function hexPoints(position: OtariaPosition): string {
  const x = 25 + position.column * 27
  const y = 25 + position.row * 31.18 + (position.column % 2) * 15.59
  return Array.from({ length: 6 }, (_, index) => {
    const angle = Math.PI * index / 3
    return `${x + 17.4 * Math.cos(angle)},${y + 17.4 * Math.sin(angle)}`
  }).join(' ')
}

function seatName(gameState: ClientGameState, playerId: EntityId): string {
  return gameState.players.find((player) => player.playerId === playerId)?.name ?? playerId
}

export interface OtariaMapChoice {
  position: OtariaPosition
  index: number
  label: string
}

export function OtariaMap({
  gameState,
  board,
  choices = [],
  zoomed = false,
  miniature = false,
  selectedIndex,
  onChoose,
}: {
  gameState: ClientGameState
  board: ClientOtariaState
  choices?: readonly OtariaMapChoice[]
  zoomed?: boolean
  miniature?: boolean
  selectedIndex?: number | null
  onChoose?: (index: number) => void
}) {
  const orderedPlayers = [...board.players].sort((a, b) =>
    gameState.players.findIndex((player) => player.playerId === a.playerId) -
    gameState.players.findIndex((player) => player.playerId === b.playerId))
  const destinations = new Map(choices.map((choice) =>
    [`${choice.position.column}:${choice.position.row}`, choice] as const))
  const selectedChoice = choices.find((choice) => choice.index === selectedIndex)
  const selectedTile = selectedChoice && board.tiles.find((tile) => samePosition(tile.position, selectedChoice.position))
  const activePosition = board.players.find((player) => player.playerId === gameState.activePlayerId)?.position
  const positionsToShow = choices.map(({ position }) => position)
  if (activePosition) positionsToShow.push(activePosition)
  let viewBox = '-8 -8 390 512'
  if (zoomed && choices.length) {
    const points = positionsToShow.map((position) => ({
      x: 25 + position.column * 27,
      y: 25 + position.row * 31.18 + (position.column % 2) * 15.59,
    }))
    const minX = Math.min(...points.map((point) => point.x)) - 32
    const maxX = Math.max(...points.map((point) => point.x)) + 32
    const minY = Math.min(...points.map((point) => point.y)) - 32
    const maxY = Math.max(...points.map((point) => point.y)) + 32
    viewBox = `${minX} ${minY} ${maxX - minX} ${maxY - minY}`
  }
  return (
    <span className={styles.mapFrame}>
      <svg viewBox={viewBox} role={choices.length ? 'group' : 'img'} aria-label="Otaria's terrain, starred locations, and player positions">
        {board.tiles.map((tile) => {
          const x = 25 + tile.position.column * 27
          const y = 25 + tile.position.row * 31.18 + (tile.position.column % 2) * 15.59
          const points = hexPoints(tile.position)
          const choice = destinations.get(`${tile.position.column}:${tile.position.row}`)
          const selected = choice != null && choice.index === selectedIndex
          const choose = () => { if (choice && onChoose) onChoose(choice.index) }
          return (
            <g
              key={`${tile.position.column}-${tile.position.row}`}
              role={choice ? 'button' : undefined}
              tabIndex={choice ? 0 : undefined}
              aria-label={choice?.label}
              aria-pressed={choice ? selected : undefined}
              className={choice ? styles.destination : undefined}
              onClick={choice ? choose : undefined}
              onKeyDown={choice ? (event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault()
                  choose()
                }
              } : undefined}
            >
              <title>{choice?.label ?? (tile.location ? locationNames[tile.location] : `${tile.color} terrain`)}</title>
              <polygon
                points={points}
                fill={terrainColors[tile.color]}
                stroke={selected ? '#fff7b2' : choice ? '#f5cf72' : '#17242c'}
                strokeWidth={selected ? '3' : choice ? '2.2' : '1.2'}
              />
              {tile.location && <text x={x} y={y + 3} textAnchor="middle" className={styles.star}>★</text>}
            </g>
          )
        })}
        {selectedTile && (
          <polygon
            points={hexPoints(selectedTile.position)}
            className={styles.selectedDestination}
            aria-hidden="true"
            pointerEvents="none"
          />
        )}
        <g pointerEvents="none">
          {board.tiles.flatMap((tile) => {
            const occupants = orderedPlayers.filter((player) => samePosition(player.position, tile.position))
            const columns = Math.min(occupants.length, 3)
            const rows = Math.ceil(occupants.length / 3)
            const x = 25 + tile.position.column * 27
            const y = 25 + tile.position.row * 31.18 + (tile.position.column % 2) * 15.59
            return occupants.map((player, index) => {
              const number = orderedPlayers.findIndex((candidate) => candidate.playerId === player.playerId)
              const row = Math.floor(index / 3)
              const rowSize = Math.min(columns, occupants.length - row * 3)
              const markerX = x + (index % 3 - (rowSize - 1) / 2) * 19
              const markerY = y + 8 + (row - (rows - 1) / 2) * 19
              return (
                <g key={player.playerId}>
                  {miniature && <circle cx={markerX} cy={markerY} r="11" className={styles.miniPlayerPulse} />}
                  <circle
                    cx={markerX}
                    cy={markerY}
                    r={miniature ? '10' : '9'}
                    fill={seatColors[number % seatColors.length]}
                    stroke={miniature ? '#fff' : '#17242c'}
                    strokeWidth={miniature ? '2' : '1.5'}
                    className={miniature ? styles.miniPlayerMarker : undefined}
                  />
                  <text x={markerX} y={markerY} textAnchor="middle" dominantBaseline="central" className={miniature ? styles.miniMarker : styles.marker}>{number + 1}</text>
                </g>
              )
            })
          })}
        </g>
      </svg>
    </span>
  )
}

/** The board is an independent view of server-supplied state, not a source of legal actions. */
export function OtariaBoardButton({ gameState, miniature = false }: { gameState: ClientGameState; miniature?: boolean }) {
  const [open, setOpen] = useState(false)
  const button = useRef<HTMLButtonElement>(null)
  const dialog = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    dialog.current?.querySelector<HTMLButtonElement>('button')?.focus()
    return () => button.current?.focus()
  }, [open])

  if (!gameState.otaria) return null

  return (
    <>
      <button
        ref={button}
        type="button"
        className={miniature ? styles.miniMap : styles.badge}
        aria-label={miniature ? 'Open Otaria map' : undefined}
        onClick={() => setOpen(true)}
      >
        {miniature
          ? <span aria-hidden="true"><OtariaMap gameState={gameState} board={gameState.otaria} miniature /></span>
          : <><span aria-hidden>✦</span> Otaria map</>}
      </button>
      {open && createPortal(
        <div className={styles.backdrop} onPointerDown={(event) => {
          if (event.target === event.currentTarget) setOpen(false)
        }}>
          <div
            ref={dialog}
            className={styles.dialog}
            role="dialog"
            aria-modal="true"
            aria-labelledby="otaria-board-title"
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                event.preventDefault()
                setOpen(false)
              }
              if (event.key === 'Tab') {
                const controls = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled), summary, a[href]')
                if (!controls?.length) return
                const first = controls[0]!
                const last = controls[controls.length - 1]!
                if (event.shiftKey && document.activeElement === first) {
                  event.preventDefault()
                  last.focus()
                } else if (!event.shiftKey && document.activeElement === last) {
                  event.preventDefault()
                  first.focus()
                }
              }
            }}
          >
            <header className={styles.header}>
              <div>
                <p className={styles.eyebrow}>Explorer of Otaria</p>
                <h2 id="otaria-board-title">The map of Otaria</h2>
                <p>Player positions, exploration supplies, and your private reserve.</p>
              </div>
              <button type="button" className={styles.close} aria-label="Close Otaria map" onClick={() => setOpen(false)}>×</button>
            </header>
            <OtariaBoardContents gameState={gameState} board={gameState.otaria} />
          </div>
        </div>,
        document.body,
      )}
    </>
  )
}

export function OtariaReserveButton({ gameState }: { gameState: ClientGameState }) {
  const [open, setOpen] = useState(false)
  const button = useRef<HTMLButtonElement>(null)
  const dialog = useRef<HTMLDivElement>(null)
  const player = gameState.otaria?.players.find((candidate) => candidate.playerId === gameState.viewingPlayerId)
  const reserve = player?.privateData?.reserve

  useEffect(() => {
    if (!open) return
    dialog.current?.querySelector<HTMLButtonElement>('button')?.focus()
    return () => button.current?.focus()
  }, [open])

  if (!reserve) return null

  return (
    <>
      <button
        ref={button}
        type="button"
        className={styles.badge}
        aria-label={`Open your reserve, ${reserve.length} ${reserve.length === 1 ? 'card' : 'cards'}`}
        onClick={() => setOpen(true)}
      >
        Reserve · {reserve.length}
      </button>
      {open && createPortal(
        <div className={styles.backdrop} onPointerDown={(event) => {
          if (event.target === event.currentTarget) setOpen(false)
        }}>
          <div
            ref={dialog}
            className={styles.dialog}
            role="dialog"
            aria-modal="true"
            aria-labelledby="otaria-reserve-title"
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                event.preventDefault()
                setOpen(false)
              }
              if (event.key === 'Tab') {
                const controls = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled), summary')
                if (!controls?.length) return
                const first = controls[0]!
                const last = controls[controls.length - 1]!
                if (event.shiftKey && document.activeElement === first) {
                  event.preventDefault()
                  last.focus()
                } else if (!event.shiftKey && document.activeElement === last) {
                  event.preventDefault()
                  first.focus()
                }
              }
            }}
          >
            <header className={styles.header}>
              <div>
                <p className={styles.eyebrow}>Explorer of Otaria</p>
                <h2 id="otaria-reserve-title">Your reserve</h2>
                <p>{reserve.length} {reserve.length === 1 ? 'card' : 'cards'} available to prepare.</p>
              </div>
              <button type="button" className={styles.close} aria-label="Close reserve" onClick={() => setOpen(false)}>×</button>
            </header>
            <div className={styles.reserveList}>
              <ReserveCards cards={reserve} />
            </div>
          </div>
        </div>,
        document.body,
      )}
    </>
  )
}

export function OtariaBoardContents({ gameState, board }: { gameState: ClientGameState; board: ClientOtariaState }) {
  const orderedPlayers = [...board.players].sort((a, b) =>
    gameState.players.findIndex((player) => player.playerId === a.playerId) -
    gameState.players.findIndex((player) => player.playerId === b.playerId))
  const positionName = (position: OtariaPosition) => {
    const tile = board.tiles.find((candidate) => samePosition(candidate.position, position))
    return tile?.location ? locationNames[tile.location] : `column ${position.column + 1}, row ${position.row + 1}`
  }
  const pending = board.pendingExploration
  const pendingName = pending?.card?.name

  return (
    <div className={styles.layout}>
      <section className={styles.mapSection} aria-label="Otaria map">
        <OtariaMap gameState={gameState} board={board} />
        <ul className={styles.seats}>
          {orderedPlayers.map((player, index) => (
            <li key={player.playerId}>
              <span className={styles.seatMarker} style={{ backgroundColor: seatColors[index % seatColors.length] }}>{index + 1}</span>
              <span
                className={
                  gameState.players.find((candidate) => candidate.playerId === player.playerId)?.hasLost
                    ? styles.lostPlayer
                    : undefined
                }
              >
                <strong>{seatName(gameState, player.playerId)}</strong> · {positionName(player.position)}
              </span>
              <span className={styles.muted}>{player.reserveCount} in reserve</span>
            </li>
          ))}
        </ul>
      </section>

      <div className={styles.sidebar}>
        {board.turn && (
          <section className={styles.section} aria-label="Current Otaria turn">
            <h3>Current turn</h3>
            <p><strong>{seatName(gameState, gameState.activePlayerId)}</strong> · {turnActions[board.turn.action]}</p>
            {board.turn.action === 'MOVE' && !board.turn.movementAllowed && (
              <p className={styles.muted}>Movement is no longer available this turn.</p>
            )}
            {board.turn.action === 'EXPLORE' && board.turn.explorationFinished && (
              <p className={styles.muted}>Exploration completed.</p>
            )}
          </section>
        )}
        {pending && (
          <section className={styles.section}>
            <h3>Current exploration</h3>
            <p>
              <strong>{seatName(gameState, pending.explorerId)}</strong> explores;{' '}
              <strong>{seatName(gameState, pending.managerId)}</strong> manages the draw.
            </p>
            <p className={styles.encounter}>
              {pending.faceDown
                ? (pendingName ? `Face-down creature: ${pendingName} (private)` : 'Face-down creature')
                : (pendingName ?? 'Exploration')}
            </p>
            {pending.card?.kind === 'EVENT' && pending.card.imageUri && (
              <img className={styles.eventImage} src={pending.card.imageUri} alt={pending.card.name} />
            )}
            {pending.stage === 'CHOOSING_LOCATION' && <p className={styles.muted}>Choosing whether to take a location.</p>}
          </section>
        )}
        {board.encounters && board.encounters.length > 0 && (
          <section className={styles.section} aria-label="Active encounters">
            <h3>Active encounters</h3>
            <ul className={styles.encounterList}>
              {board.encounters.map((encounter) => (
                <li key={encounter.actorId}>
                  <p className={styles.encounter}>{positionName(encounter.position)}</p>
                  <p>
                    <strong>{seatName(gameState, encounter.explorerId)}</strong> explores;{' '}
                    <strong>{seatName(gameState, encounter.managerId)}</strong> manages the encounter.
                  </p>
                  <p className={styles.muted}>Participants: {encounter.participants.map((id) => seatName(gameState, id)).join(', ')}</p>
                  {encounter.initiative && (
                    <p className={styles.muted}>Initiative: {encounter.initiative === 'EXPLORER' ? 'explorer' : 'encounter'}.</p>
                  )}
                </li>
              ))}
            </ul>
          </section>
        )}
        <section className={styles.section}>
          <h3>Starred locations</h3>
          <p className={styles.muted}>Explorations used / limit</p>
          <ul className={styles.locationList}>
            {board.locations.map(({ location, used, limit }) => (
              <li key={location}><span>{locationNames[location]}</span><strong>{used} / {limit}</strong></li>
            ))}
          </ul>
        </section>
        <section className={styles.section}>
          <h3>Exploration supplies</h3>
          <div className={styles.piles}>
            <span>★ Star pile <strong>{board.starPile.remaining}</strong></span>
            {terrainOrder.map((color) => (
              <span key={color}><i style={{ backgroundColor: terrainColors[color] }} />{color.toLowerCase()} <strong>{board.colorPiles[color].remaining}</strong></span>
            ))}
            <span>Treasures <strong>{board.treasureCount}</strong></span>
            <span>Locations <strong>{board.locationCount}</strong></span>
          </div>
        </section>
        {orderedPlayers.filter((player) => player.privateData != null).map((player) => {
          const privateData = player.privateData!
          return (
            <section key={player.playerId} className={styles.section}>
              <h3>{player.playerId === gameState.viewingPlayerId ? 'Your private reserve' : `${seatName(gameState, player.playerId)}'s private reserve`}</h3>
              <ReserveCards cards={privateData.reserve} />
              {privateData.locationCards.length > 0 && (
                <p><strong>Location cards:</strong> {privateData.locationCards.map((location) => locationNames[location]).join(', ')}</p>
              )}
              {Object.entries(privateData.treasureTokens).map(([kind, count]) => (
                <p key={kind}>{kind.toLowerCase()}: {count}</p>
              ))}
            </section>
          )
        })}
      </div>
      <nav className={styles.links} aria-label="Otaria resources">
        <a href="/help/playing#otaria-map" target="_blank" rel="noopener noreferrer">Otaria interface guide (new tab)</a>
        {import.meta.env.DEV && <a href="/otaria">New local playtest</a>}
      </nav>
    </div>
  )
}

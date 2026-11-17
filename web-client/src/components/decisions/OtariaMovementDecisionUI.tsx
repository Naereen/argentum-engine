import { useEffect, useRef, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import type { ChooseOptionDecision, ClientGameState, ClientOtariaState } from '@/types'
import { OtariaMap } from '../game/overlay/OtariaBoard'
import type { OtariaMapChoice } from '../game/overlay/OtariaBoard'
import styles from './DecisionUI.module.css'
import movementStyles from './OtariaMovementDecisionUI.module.css'
import { useTranslation } from 'react-i18next'

/** Only the server-provided options become selectable hexes. */
export function otariaMovementChoices(
  decision: ChooseOptionDecision,
  gameState: ClientGameState | null,
): OtariaMapChoice[] | null {
  const board = gameState?.otaria
  const metadata = decision.optionMetadata
  if (!board || !metadata?.length || metadata.length !== decision.options.length) return null
  const choices: OtariaMapChoice[] = []
  const seen = new Set<string>()
  for (let index = 0; index < metadata.length; index++) {
    const match = /^otaria-move:(\d+):(\d+)$/.exec(metadata[index]?.id ?? '')
    const label = decision.options[index]
    if (!match || label == null) return null
    const position = { column: Number(match[1]), row: Number(match[2]) }
    if (!board.tiles.some((tile) =>
      tile.position.column === position.column && tile.position.row === position.row)) return null
    const key = `${position.column}:${position.row}`
    if (seen.has(key)) return null
    seen.add(key)
    choices.push({ position, index, label })
  }
  return choices
}

export function OtariaMovementDecisionUI({
  decision,
  gameState,
  board,
  choices,
}: {
  decision: ChooseOptionDecision
  gameState: ClientGameState
  board: ClientOtariaState
  choices: OtariaMapChoice[]
}) {
  const { t } = useTranslation()
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null)
  const [zoomed, setZoomed] = useState(false)
  const submitOptionDecision = useGameStore((state) => state.submitOptionDecision)
  const panel = useRef<HTMLDivElement>(null)
  const selected = choices.find((choice) => choice.index === selectedIndex)

  useEffect(() => {
    const previouslyFocused = document.activeElement
    panel.current?.querySelector<HTMLElement>('[tabindex="0"]')?.focus()
    return () => {
      if (previouslyFocused instanceof HTMLElement && previouslyFocused.isConnected) previouslyFocused.focus()
    }
  }, [])

  return (
    <div className={styles.overlay}>
      <div
        ref={panel}
        className={movementStyles.panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby="otaria-movement-title"
        onKeyDown={(event) => {
          if (event.key !== 'Tab') return
          const controls = panel.current?.querySelectorAll<HTMLElement>('[tabindex="0"], button:not(:disabled)')
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
        }}
      >
        <h2 id="otaria-movement-title">{decision.prompt}</h2>
        <p>{t('otaria.movementPrompt')}</p>
        <div className={movementStyles.viewControls} role="group" aria-label={t('otaria.mapView')}>
          <button type="button" aria-pressed={zoomed} onClick={() => setZoomed(true)}>{t('otaria.zoom')}</button>
          <button type="button" aria-pressed={!zoomed} onClick={() => setZoomed(false)}>{t('otaria.fullMap')}</button>
        </div>
        <div className={movementStyles.map}>
          <OtariaMap
            gameState={gameState}
            board={board}
            choices={choices}
            zoomed={zoomed}
            selectedIndex={selectedIndex}
            onChoose={setSelectedIndex}
          />
        </div>
        <p aria-live="polite">{selected?.label ?? t('otaria.noDestination')}</p>
        <button
          type="button"
          className={styles.confirmButton}
          disabled={selectedIndex == null}
          onClick={() => {
            if (selectedIndex != null) submitOptionDecision(decision.id, selectedIndex)
          }}
        >
          {t('otaria.confirmMove')}
        </button>
      </div>
    </div>
  )
}

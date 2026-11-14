import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { Phase, Step } from '@/types'
import type { ClientOtariaTurn } from '@/types'
import { StepStrip } from './StepStrip'

function strip(step: Step, otariaTurn?: ClientOtariaTurn) {
  return renderToStaticMarkup(
    <StepStrip
      phase={Phase.BEGINNING}
      step={step}
      roundNumber={1}
      isActivePlayer
      hasPriority
      priorityMode="ownTurn"
      activeSide="bottom"
      stopOverrides={{ myTurnStops: [], opponentTurnStops: [] }}
      onToggleStop={() => {}}
      otariaTurn={otariaTurn}
    />,
  )
}

describe('Otaria turn strip', () => {
  const turn: ClientOtariaTurn = { action: 'MOVE', movementAllowed: true, explorationFinished: false }

  it('shows the active Otaria phase rather than the ordinary combat schedule', () => {
    expect(strip(Step.OTARIA_MOVEMENT, turn)).toContain('Move')
    expect(strip(Step.OTARIA_EXPLORATION, { ...turn, action: 'EXPLORE' })).toContain('Explore')
    expect(strip(Step.OTARIA_PREPARATION, { ...turn, action: 'PREPARE' })).toContain('Prep')
  })

  it('keeps the ordinary combat strip when no Otaria turn is supplied', () => {
    expect(strip(Step.BEGIN_COMBAT)).toContain('BC')
  })

  it('shows the combat or movement earned by encounter initiative', () => {
    const exploring = { ...turn, action: 'EXPLORE' as const, encounterInitiative: 'EXPLORER' as const }
    expect(strip(Step.BEGIN_COMBAT, { ...exploring, encounterAction: 'COMBAT' })).toContain('BC')
    expect(strip(Step.OTARIA_MOVEMENT, { ...exploring, encounterAction: 'MOVE' })).toContain('Move')
    expect(strip(Step.END, { ...exploring, encounterInitiative: 'ENCOUNTER' })).not.toContain('BC')
  })
})

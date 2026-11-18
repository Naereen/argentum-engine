import i18n from './index'

const STEP_TRANSLATION_KEYS = {
  UNTAP: 'phases.steps.UNTAP',
  UPKEEP: 'phases.steps.UPKEEP',
  DRAW: 'phases.steps.DRAW',
  PRECOMBAT_MAIN: 'phases.steps.PRECOMBAT_MAIN',
  BEGIN_COMBAT: 'phases.steps.BEGIN_COMBAT',
  DECLARE_ATTACKERS: 'phases.steps.DECLARE_ATTACKERS',
  DECLARE_BLOCKERS: 'phases.steps.DECLARE_BLOCKERS',
  FIRST_STRIKE_COMBAT_DAMAGE: 'phases.steps.FIRST_STRIKE_COMBAT_DAMAGE',
  COMBAT_DAMAGE: 'phases.steps.COMBAT_DAMAGE',
  END_COMBAT: 'phases.steps.END_COMBAT',
  POSTCOMBAT_MAIN: 'phases.steps.POSTCOMBAT_MAIN',
  END: 'phases.steps.END',
  CLEANUP: 'phases.steps.CLEANUP',
  OTARIA_PREPARATION: 'phases.steps.OTARIA_PREPARATION',
  OTARIA_MOVEMENT: 'phases.steps.OTARIA_MOVEMENT',
  OTARIA_EXPLORATION: 'phases.steps.OTARIA_EXPLORATION',
} as const

const NEXT_STOP_STEPS: Record<string, keyof typeof STEP_TRANSLATION_KEYS> = {
  'To Untap': 'UNTAP',
  'To Upkeep': 'UPKEEP',
  'To Draw': 'DRAW',
  'To Main 1': 'PRECOMBAT_MAIN',
  'To Combat': 'BEGIN_COMBAT',
  'To Attackers': 'DECLARE_ATTACKERS',
  'To Blockers': 'DECLARE_BLOCKERS',
  'To First Strike': 'FIRST_STRIKE_COMBAT_DAMAGE',
  'To Damage': 'COMBAT_DAMAGE',
  'To End Combat': 'END_COMBAT',
  'To Main 2': 'POSTCOMBAT_MAIN',
  'To End Step': 'END',
  'To Cleanup': 'CLEANUP',
  'To Preparation': 'OTARIA_PREPARATION',
  'To Movement': 'OTARIA_MOVEMENT',
  'To Exploration': 'OTARIA_EXPLORATION',
}

const SPECIAL_NEXT_STOP_LABELS: Record<string, () => string> = {
  Pass: () => i18n.t('gameCore.pass'),
  Resolve: () => i18n.t('gameCore.resolve'),
  'End Turn': () => i18n.t('gameCore.endTurn'),
  'To My Turn': () => i18n.t('gameCore.toMyTurn'),
  "To Opponent's Turn": () => i18n.t('gameCore.toOpponentsTurn'),
}

export function localizeNextStopPoint(label: string | null | undefined): string {
  const value = label ?? 'Pass'
  const specialLabel = SPECIAL_NEXT_STOP_LABELS[value]
  if (specialLabel) return specialLabel()

  const step = NEXT_STOP_STEPS[value]
  return step
    ? i18n.t('gameCore.toStep', { step: i18n.t(STEP_TRANSLATION_KEYS[step]) })
    : value
}

import { Phase, Step, StepDisplayNames } from '@/types'
import { useResponsive } from '@/hooks/useResponsive.ts'
import type { PriorityMode } from '@/store/selectors.ts'
import { useTranslation } from 'react-i18next'

interface PhaseIndicatorProps {
  phase: Phase
  step: Step
  turnNumber: number
  isActivePlayer: boolean
  hasPriority: boolean
  priorityMode: PriorityMode
  /** Active player's name (for spectator mode display) */
  activePlayerName?: string | undefined
}

/**
 * Color configuration for each priority mode.
 */
const modeColors: Record<PriorityMode, { border: string; glow: string; badge: string }> = {
  ownTurn: {
    border: '#4fc3f7',
    glow: '0 0 12px rgba(79, 195, 247, 0.5)',
    badge: '#4fc3f7',
  },
  responding: {
    border: '#ffc107',
    glow: '0 0 12px rgba(255, 193, 7, 0.5)',
    badge: '#ffc107',
  },
  waiting: {
    border: 'transparent',
    glow: 'none',
    badge: '#666',
  },
}

/**
 * Phase and step indicator.
 */
export function PhaseIndicator({
  phase,
  step,
  turnNumber,
  isActivePlayer,
  hasPriority,
  priorityMode,
  activePlayerName,
}: PhaseIndicatorProps) {
  const responsive = useResponsive()
  const { t } = useTranslation()
  const colors = modeColors[priorityMode]

  // Determine the status text based on mode
  // If activePlayerName is provided (spectator mode), use that
  const statusText = activePlayerName
    ? t('phases.playerTurn', { playerName: activePlayerName })
    : priorityMode === 'ownTurn'
    ? t('phases.yourTurn')
    : priorityMode === 'responding'
    ? t('phases.responding')
    : isActivePlayer
    ? t('phases.yourTurn')
    : t('phases.opponentTurn')

  return (
    <div
      style={{
        backgroundColor: 'rgba(0, 0, 0, 0.85)',
        borderRadius: responsive.isMobile ? 6 : 8,
        padding: responsive.isMobile ? '6px 10px' : '8px 16px',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: responsive.isMobile ? 2 : 4,
        pointerEvents: 'auto',
        border: `2px solid ${colors.border}`,
        boxShadow: colors.glow,
        transition: 'border-color 0.2s, box-shadow 0.2s',
      }}
    >
      {/* Turn status - replaces separate Active/Priority badges */}
      <span
        style={{
          color: hasPriority ? colors.badge : '#888',
          fontSize: responsive.isMobile ? 9 : 11,
          fontWeight: hasPriority ? 600 : 400,
          textTransform: 'uppercase',
          letterSpacing: '0.5px',
        }}
      >
        {statusText}
      </span>

      {/* Phase/Step display */}
      <div style={{ display: 'flex', alignItems: 'center', gap: responsive.isMobile ? 4 : 8 }}>
        <PhaseIcon phase={phase} isMobile={responsive.isMobile} />
        <span style={{ color: '#fff', fontSize: responsive.isMobile ? 11 : 14, fontWeight: 500 }}>
          {t(`phases.steps.${step}`, { defaultValue: StepDisplayNames[step] })}
        </span>
      </div>

      {/* Turn number */}
      <span style={{ color: '#666', fontSize: responsive.isMobile ? 8 : 10 }}>
        {t('phases.turn', { turnNumber })}
      </span>
    </div>
  )
}

/**
 * Phase icon based on current phase.
 */
function PhaseIcon({ phase, isMobile = false }: { phase: Phase; isMobile?: boolean }) {
  const icons: Record<Phase, string> = {
    [Phase.BEGINNING]: '🌅',
    [Phase.PRECOMBAT_MAIN]: '📜',
    [Phase.COMBAT]: '⚔️',
    [Phase.POSTCOMBAT_MAIN]: '📜',
    [Phase.ENDING]: '🌙',
    [Phase.OTARIA_PREPARATION]: '📚',
    [Phase.OTARIA_MOVEMENT]: '🧭',
    [Phase.OTARIA_EXPLORATION]: '🔎',
  }

  return <span style={{ fontSize: isMobile ? 14 : 18 }}>{icons[phase]}</span>
}

/**
 * Full phase breakdown showing all phases.
 */
export function PhaseBreakdown({
  currentPhase,
}: {
  currentPhase: Phase
  currentStep: Step
}) {
  const normalPhases = [
    Phase.BEGINNING,
    Phase.PRECOMBAT_MAIN,
    Phase.COMBAT,
    Phase.POSTCOMBAT_MAIN,
    Phase.ENDING,
  ]
  const phases = normalPhases.includes(currentPhase)
    ? normalPhases
    : [Phase.BEGINNING, currentPhase, Phase.ENDING]

  return (
    <div
      style={{
        display: 'flex',
        gap: 4,
      }}
    >
      {phases.map((phase) => (
        <div
          key={phase}
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            backgroundColor:
              phase === currentPhase
                ? '#00ff00'
                : phases.indexOf(phase) < phases.indexOf(currentPhase)
                ? '#888'
                : '#333',
          }}
        />
      ))}
    </div>
  )
}

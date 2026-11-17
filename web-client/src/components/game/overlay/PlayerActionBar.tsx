import { useGameStore } from '@/store/gameStore.ts'
import type { GameAction, LegalActionInfo } from '@/types'
import './PlayerActionBar.css'
import { useTranslation } from 'react-i18next'

type PlayerAction = Extract<GameAction, { type: 'TakePlayerAction' | 'ClaimOtariaMirariVictory' }>
type PlayerActionOffer = LegalActionInfo & { readonly action: PlayerAction }

function isPlayerActionOffer(offer: LegalActionInfo): offer is PlayerActionOffer {
  return offer.action.type === 'TakePlayerAction' || offer.action.type === 'ClaimOtariaMirariVictory'
}

function actionKey(action: PlayerAction): string {
  const subject = action.type === 'TakePlayerAction' ? action.permissionId : action.mirariId
  return `${action.type}:${action.playerId}:${subject}`
}

/** Player-level actions do not require a card to be visible on the battlefield. */
export function PlayerActionBar() {
  const { t } = useTranslation()
  const legalActions = useGameStore((state) => state.legalActions)
  const pipelineState = useGameStore((state) => state.pipelineState)
  const actions = legalActions.filter(isPlayerActionOffer)
  if (actions.length === 0 || pipelineState != null) return null
  return (
    <div className="player-action-bar" aria-label={t('gameCore.playerActions')}>
      {actions.map((offer) => (
        <button key={actionKey(offer.action)}
          disabled={offer.isAffordable === false} onClick={() => {
            const live = useGameStore.getState()
            const current = live.legalActions.find((candidate) =>
              isPlayerActionOffer(candidate) && actionKey(candidate.action) === actionKey(offer.action) &&
              candidate.isAffordable !== false && candidate.interactionEpoch === offer.interactionEpoch)
            if (current && live.pipelineState == null && offer.interactionEpoch === live.interactionEpoch) {
              live.submitAction(offer.action, offer.interactionEpoch)
            }
          }}>
          {offer.description}
        </button>
      ))}
    </div>
  )
}

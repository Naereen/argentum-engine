import { useGameStore } from '@/store/gameStore'
import { useInteraction } from '@/hooks/useInteraction'
import styles from './DecisionUI.module.css'
import { getCardImageUrl } from '@/utils/cardImages'
import { useTranslation } from 'react-i18next'

export function PlayCardDecisionUI() {
  const { t } = useTranslation()
  const decision = useGameStore(s => s.pendingDecision)
  const actions = useGameStore(s => s.legalActions)
  const gameState = useGameStore(s => s.gameState)
  const casting = useGameStore(s => !!(s.pipelineState || s.targetingState || s.xSelectionState ||
    s.modalModeSelectionState || s.convokeSelectionState || s.delveSelectionState ||
    s.tapForGenericSelectionState || s.manaSelectionState || s.damageDistributionState))
  const { executeAction } = useInteraction()
  if (decision?.type !== 'PlayCardDecision' || casting) return null
  const card = gameState?.cards[decision.cardId]
  return <div className={styles.overlay}>
    <div className={styles.sideBanner}>
      <h2>{card ? t('decisions.playCard', { cardName: card.name }) : decision.prompt}</h2>
      {card && <img src={getCardImageUrl(card.name, card.imageUri)} alt={card.name}
        style={{ width: 100, borderRadius: 8 }} />}
      <p>{t('decisions.playCardHint')}</p>
      {actions.map((action, i) => <button key={i} className={styles.confirmButton} disabled={!action.isAffordable}
        onClick={() => executeAction(action)}>{action.description}</button>)}
    </div>
  </div>
}

import { useMemo } from 'react'
import { useGameStore } from '@/store/gameStore.ts'
import type { EntityId, SearchLibraryDecision } from '@/types'
import { type ResponsiveSizes } from '@/hooks/useResponsive.ts'
import { ZoneSelectionUI, type ZoneCardInfo } from './ZoneSelectionUI'
import { useTranslation } from 'react-i18next'

interface LibrarySearchUIProps {
  decision: SearchLibraryDecision
  responsive: ResponsiveSizes
}

/**
 * Library search UI - uses the shared ZoneSelectionUI component.
 */
export function LibrarySearchUI({ decision, responsive }: LibrarySearchUIProps) {
  const { t } = useTranslation()
  const submitDecision = useGameStore((s) => s.submitDecision)

  // Convert library cards to ZoneCardInfo format
  const cards: ZoneCardInfo[] = useMemo(() => {
    return decision.options.map((cardId) => {
      const cardInfo = decision.cards[cardId]
      return {
        id: cardId,
        name: cardInfo?.name || t('common.unknownCard'),
        typeLine: cardInfo?.typeLine,
        manaCost: cardInfo?.manaCost,
        imageUri: cardInfo?.imageUri,
      }
    })
  }, [decision.options, decision.cards, t])

  const handleConfirm = (selectedCards: EntityId[]) => {
    submitDecision(decision.id, selectedCards)
  }

  return (
    <ZoneSelectionUI
      title={t('decisions.searchYourLibrary')}
      prompt={decision.prompt}
      cards={cards}
      minSelections={decision.minSelections}
      maxSelections={decision.maxSelections}
      responsive={responsive}
      onConfirm={handleConfirm}
      filterDescription={t('decisions.searchingFor', { filter: decision.filterDescription })}
      showFailToFind={decision.minSelections === 0}
      confirmText={t('decisions.confirmSelection')}
      failToFindText={t('decisions.failToFind')}
      sortByType={true}
    />
  )
}

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useGameStore, type MulliganState } from '@/store/gameStore.ts'
import type { EntityId } from '@/types'
import { useResponsive, calculateFittingCardWidth, type ResponsiveSizes } from '@/hooks/useResponsive.ts'
import { getCardImageUrl } from '@/utils/cardImages.ts'
import { StandaloneConcedeButton } from '../game/overlay'
import styles from './MulliganUI.module.css'

/**
 * Mulligan UI overlay.
 */
export function MulliganUI() {
  const { t } = useTranslation()
  const mulliganState = useGameStore((state) => state.mulliganState)
  const responsive = useResponsive()
  const [minimized, setMinimized] = useState(false)

  if (!mulliganState) return null

  const isChoosingBottom = mulliganState.phase === 'choosingBottomCards'

  // When minimized (only during choosingBottom phase), show floating button to restore
  if (minimized && isChoosingBottom) {
    return (
      <button
        onClick={() => setMinimized(false)}
        className={styles.minimizedButton}
      >
        ↑ {t('mulligan.returnToCardSelection')}
      </button>
    )
  }

  return (
    <div className={styles.overlay}>
      <StandaloneConcedeButton />
      {mulliganState.phase === 'deciding' ? (
        <MulliganDecision state={mulliganState} responsive={responsive} />
      ) : (
        <ChooseBottomCards state={mulliganState} responsive={responsive} onMinimize={() => setMinimized(true)} />
      )}
    </div>
  )
}

/**
 * Figure out how big each card should be so all cards fit horizontally
 * without overflowing vertically. Scales up aggressively on big screens
 * so cards stay readable, while preventing overflow on short viewports.
 */
function computeMulliganCardWidth(
  cardCount: number,
  responsive: ResponsiveSizes,
  gap: number,
): number {
  const { viewportWidth, viewportHeight, isMobile, isTablet } = responsive

  // Horizontal chrome must match the CSS: overlay padding + cardContainer padding,
  // both sides. The overlay's mobile media query is <640px, so tablet uses the
  // desktop values even though responsive.isTablet is true.
  const overlayPadding = isMobile ? 4 : 16       // --space-1 : --space-4
  const cardContainerPadding = isMobile ? 8 : 16 // --space-2 : --space-4
  const horizontalChrome = (overlayPadding + cardContainerPadding) * 2

  // Small safety buffer for sub-pixel rounding / scrollbars so a flex row never
  // spills to a second line by a fraction of a pixel.
  const availableWidth = Math.max(0, viewportWidth - horizontalChrome - 4)

  // Vertical budget: reserve space for title, subtitle, buttons, padding.
  // Cards can take roughly 55% of the viewport height on mobile, 60% otherwise.
  const cardVerticalBudget = viewportHeight * (isMobile ? 0.55 : 0.6)
  const cardRatio = 1.4

  // A portrait phone has the height to spare and not the width: seven cards in one row came out
  // ~45px wide. Lay them out in two rows (4 + 3) instead, sized so a fifth never fits on the first.
  const twoRows = isMobile && cardCount > 4 && viewportHeight > viewportWidth * 1.3
  const perRow = twoRows ? Math.ceil(cardCount / 2) : cardCount
  const widthFromHeight = Math.floor((twoRows ? (cardVerticalBudget - gap) / 2 : cardVerticalBudget) / cardRatio)

  // Target max card width scales by breakpoint
  const breakpointMax = isMobile ? 110 : isTablet ? 170 : 240

  // Absolute minimum so cards stay tappable, but never larger than what fits.
  // The fit width is the ceiling — clamping up to a larger "min" would force wrap.
  const fitCeiling = calculateFittingCardWidth(perRow, availableWidth, gap, breakpointMax, 1)
  const preferredMin = isMobile ? 52 : isTablet ? 72 : 96
  const minCardWidth = Math.min(preferredMin, fitCeiling)

  const maxCardWidth = Math.min(breakpointMax, widthFromHeight)
  return calculateFittingCardWidth(perRow, availableWidth, gap, maxCardWidth, minCardWidth)
}

/**
 * Mulligan decision phase - keep or mulligan.
 */
function MulliganDecision({ state, responsive }: { state: MulliganState; responsive: ResponsiveSizes }) {
  const { t } = useTranslation()
  const keepHand = useGameStore((s) => s.keepHand)
  const mulligan = useGameStore((s) => s.mulligan)
  const tournamentState = useGameStore((s) => s.tournamentState)
  const playerId = useGameStore((s) => s.playerId)
  // A Magic subgame (Shahrazad, CR 729) opens with a mulligan of its own — say which game this is.
  const subgame = useGameStore((s) => s.gameState?.subgame ?? null)
  const subgameSource = subgame?.waitingGames[subgame.waitingGames.length - 1]?.sourceName ?? null

  // Calculate card size that fits all 7 cards
  const gap = responsive.isMobile ? 4 : responsive.isTablet ? 6 : 10
  const cardWidth = computeMulliganCardWidth(state.hand.length, responsive, gap)

  // Tournament info
  const tournamentInfo = (() => {
    if (!tournamentState || !playerId) return null
    const playerStanding = tournamentState.standings.find((s) => s.playerId === playerId)
    const opponentName = tournamentState.currentMatchOpponentName
    const opponentStanding = opponentName
      ? tournamentState.standings.find((s) => s.playerName === opponentName)
      : null
    if (!playerStanding || !opponentName) return null
    const playerRecord = `${playerStanding.wins}-${playerStanding.losses}${playerStanding.draws > 0 ? `-${playerStanding.draws}` : ''}`
    const opponentRecord = opponentStanding
      ? `${opponentStanding.wins}-${opponentStanding.losses}${opponentStanding.draws > 0 ? `-${opponentStanding.draws}` : ''}`
      : null
    return { round: tournamentState.currentRound, playerRecord, opponentName, opponentRecord }
  })()

  return (
    <>
      {subgame && (
        <div className={styles.subgameBanner} data-testid="mulligan-subgame-banner">
          <p className={styles.subgameEyebrow}>
            {subgame.depth > 1
              ? subgameSource
                ? t('mulligan.subgameDepthWithSource', { depth: subgame.depth, sourceName: subgameSource })
                : t('mulligan.subgameDepth', { depth: subgame.depth })
              : subgameSource
                ? t('mulligan.subgameWithSource', { sourceName: subgameSource })
                : t('mulligan.subgame')}
          </p>
          <p className={styles.subgameText}>
            {t('mulligan.subgameDescription')}
          </p>
        </div>
      )}

      {tournamentInfo && !subgame && (
        <div className={styles.tournamentBanner}>
          <p className={styles.tournamentRound}>{t('mulligan.round', { round: tournamentInfo.round })}</p>
          <p className={styles.tournamentMatchup}>
            {tournamentInfo.opponentRecord
              ? t('mulligan.matchupWithRecords', {
                  playerRecord: tournamentInfo.playerRecord,
                  opponent: tournamentInfo.opponentName,
                  opponentRecord: tournamentInfo.opponentRecord,
                })
              : t('mulligan.matchup', {
                  playerRecord: tournamentInfo.playerRecord,
                  opponent: tournamentInfo.opponentName,
                })}
          </p>
        </div>
      )}

      <h2 className={styles.title}>
        {state.mulliganCount === 0
          ? t('mulligan.openingHand')
          : t('mulligan.mulliganCount', { count: state.mulliganCount })}
      </h2>

      <div
        className={`${styles.playDrawBadge} ${state.isOnThePlay ? styles.playDrawBadgePlay : styles.playDrawBadgeDraw}`}
      >
        <span className={styles.playDrawIcon} aria-hidden>
          {state.isOnThePlay ? '⚔' : '✦'}
        </span>
        <span className={styles.playDrawLabel}>
          {state.isOnThePlay ? t('mulligan.onThePlay') : t('mulligan.onTheDraw')}
        </span>
      </div>

      <p className={styles.subtitle}>
        {state.mulliganCount > 0 &&
          t('mulligan.bottomCardsWarning', { count: state.cardsToPutOnBottom })}
      </p>

      {/* Hand preview */}
      <div className={styles.cardContainer} style={{ gap }}>
        {state.hand.map((cardId) => {
          const cardInfo = state.cards[cardId]
          return (
            <MulliganCard
              key={cardId}
              cardId={cardId}
              cardName={cardInfo?.name || t('common.unknownCard')}
              imageUri={cardInfo?.imageUri}
              selectable={false}
              cardWidth={cardWidth}
            />
          )
        })}
      </div>

      {/* Action buttons */}
      <div className={styles.buttonContainer}>
        <button onClick={keepHand} className={styles.keepButton}>
          {t('mulligan.keepHand')}
        </button>

        <button onClick={mulligan} className={styles.mulliganButton}>
          {t('mulligan.mulligan')}
        </button>
      </div>
    </>
  )
}

/**
 * Choose cards to put on bottom after keeping.
 */
function ChooseBottomCards({ state, responsive, onMinimize }: { state: MulliganState; responsive: ResponsiveSizes; onMinimize: () => void }) {
  const { t } = useTranslation()
  const chooseBottomCards = useGameStore((s) => s.chooseBottomCards)
  const toggleMulliganCard = useGameStore((s) => s.toggleMulliganCard)

  const canConfirm = state.selectedCards.length === state.cardsToPutOnBottom

  // Calculate card size that fits all cards
  const gap = responsive.isMobile ? 4 : responsive.isTablet ? 6 : 10
  const cardWidth = computeMulliganCardWidth(state.hand.length, responsive, gap)

  return (
    <>
      <h2 className={styles.title}>
        {t('mulligan.chooseBottomCards', { count: state.cardsToPutOnBottom })}
      </h2>

      <p className={styles.subtitle}>
        {t('mulligan.selectedCount', { selected: state.selectedCards.length, total: state.cardsToPutOnBottom })}
      </p>

      {/* Hand with selectable cards */}
      <div className={styles.cardContainer} style={{ gap }}>
        {state.hand.map((cardId) => {
          const cardInfo = state.cards[cardId]
          return (
            <MulliganCard
              key={cardId}
              cardId={cardId}
              cardName={cardInfo?.name || t('common.unknownCard')}
              imageUri={cardInfo?.imageUri}
              selectable
              isSelected={state.selectedCards.includes(cardId)}
              onClick={() => toggleMulliganCard(cardId)}
              cardWidth={cardWidth}
            />
          )
        })}
      </div>

      {/* Action buttons */}
      <div className={styles.buttonContainer}>
        <button onClick={onMinimize} className={styles.viewBattlefieldButton}>
          {t('decisions.viewBattlefield')}
        </button>
        <button
          onClick={() => chooseBottomCards(state.selectedCards)}
          disabled={!canConfirm}
          className={styles.confirmButton}
        >
          {t('common.confirm')}
        </button>
      </div>
    </>
  )
}

/**
 * Card display for mulligan UI.
 * Uses imageUri from server if available, otherwise falls back to Scryfall API.
 */
function MulliganCard({
  cardId: _cardId,
  cardName,
  imageUri,
  selectable,
  isSelected = false,
  onClick,
  cardWidth = 130,
}: {
  cardId: EntityId
  cardName: string
  imageUri?: string | null | undefined
  selectable: boolean
  isSelected?: boolean
  onClick?: () => void
  cardWidth?: number
}) {
  // Use provided imageUri or fall back to Scryfall API
  const cardImageUrl = getCardImageUrl(cardName, imageUri)

  const cardRatio = 1.4
  const cardHeight = Math.round(cardWidth * cardRatio)

  const cardClasses = [
    styles.card,
    isSelected ? styles.cardSelected : styles.cardDefault,
    selectable && styles.cardSelectable,
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div
      onClick={selectable ? onClick : undefined}
      className={cardClasses}
      style={{
        width: cardWidth,
        height: cardHeight,
      }}
    >
      {/* Card image */}
      <img
        src={cardImageUrl}
        alt={cardName}
        className={styles.cardImage}
        onError={(e) => {
          // Hide image on error and show placeholder
          e.currentTarget.style.display = 'none'
        }}
      />

      {/* Fallback text (shown if image fails) */}
      <div className={styles.cardFallback}>
        <span className={styles.cardFallbackName}>
          {cardName}
        </span>
      </div>
    </div>
  )
}

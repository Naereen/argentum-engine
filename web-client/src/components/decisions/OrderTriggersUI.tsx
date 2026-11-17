import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useGameStore } from '@/store/gameStore.ts'
import { usePreferences } from '@/store/preferencesStore.ts'
import type { OrderTriggersDecision, TriggerToOrder } from '@/types'
import { getCardImageUrl } from '@/utils/cardImages.ts'
import { AbilityText } from '../ui/ManaSymbols'
import { DecisionCardPreview } from './DecisionComponents'
import decisionStyles from './DecisionUI.module.css'
import styles from './OrderTriggersUI.module.css'
import { useTranslation } from 'react-i18next'

/**
 * Put your own simultaneous triggered abilities on the stack in the order you choose (CR 603.3b).
 *
 * The list reads like the stack it builds: the top row resolves first. Rows reorder by drag, by
 * their arrow buttons, or with Alt+↑/↓ on a focused row, and slide to their new place so the eye
 * can follow the move. Confirming answers with the server's indices in resolution order; the
 * server already offers the order automatic ordering would use, so confirming untouched changes
 * nothing. "Don't ask again" turns the preference back on for the rest of this game and the next.
 * A player in Full Control is always asked, so there the footer says why instead.
 */
export function OrderTriggersUI({ decision }: { decision: OrderTriggersDecision }) {
  const { t } = useTranslation()
  const submitTriggerOrder = useGameStore((s) => s.submitTriggerOrder)
  const cards = useGameStore((s) => s.gameState?.cards)
  const hoverCard = useGameStore((s) => s.hoverCard)
  const updatePreferences = usePreferences((s) => s.update)
  const autoOrderTriggers = usePreferences((s) => s.prefs.gameplay.autoOrderTriggers)
  // Full Control always asks; "don't ask again" can only promise that outside it.
  const fullControl = useGameStore((s) => s.priorityMode === 'fullControl')

  const [order, setOrder] = useState<number[]>(() => decision.triggers.map((_, i) => i))
  const [dragging, setDragging] = useState<number | null>(null)
  const [dropTarget, setDropTarget] = useState<number | null>(null)
  const [justMoved, setJustMoved] = useState<number | null>(null)
  const [minimized, setMinimized] = useState(false)
  const [stopAsking, setStopAsking] = useState(false)
  const [preview, setPreview] = useState<TriggerToOrder | null>(null)

  const changed = order.some((trigger, position) => trigger !== position)

  // The panel opens over whatever card the pointer was resting on, which then never sees its
  // mouseleave: drop that board hover so its preview doesn't hang over the list.
  useEffect(() => {
    hoverCard(null)
  }, [hoverCard])

  // Slide rows to their new place (FLIP): remember where each row was, and after the reorder
  // start it there and let it travel to where it now sits.
  const rowRefs = useRef(new Map<number, HTMLLIElement>())
  const lastTops = useRef(new Map<number, number>())
  useLayoutEffect(() => {
    const previous = lastTops.current
    const next = new Map<number, number>()
    rowRefs.current.forEach((row, trigger) => {
      const top = row.getBoundingClientRect().top
      next.set(trigger, top)
      const before = previous.get(trigger)
      if (before === undefined || before === top || dragging !== null) return
      row.animate(
        [{ transform: `translateY(${before - top}px)` }, { transform: 'translateY(0)' }],
        { duration: 220, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' },
      )
    })
    lastTops.current = next
  }, [order, dragging])

  const move = useCallback((from: number, to: number) => {
    if (from === to || to < 0 || to >= order.length) return
    const next = [...order]
    const [trigger] = next.splice(from, 1) as [number]
    next.splice(to, 0, trigger)
    setOrder(next)
    setJustMoved(trigger)
  }, [order])

  const confirm = () => {
    if (stopAsking) updatePreferences('gameplay', { autoOrderTriggers: true })
    hoverCard(null)
    submitTriggerOrder(decision.id, order)
  }

  const showPreview = (trigger: TriggerToOrder, e: React.MouseEvent) => {
    if (cards?.[trigger.sourceId]) hoverCard(trigger.sourceId, { x: e.clientX, y: e.clientY })
    else setPreview(trigger)
  }
  const hidePreview = () => {
    hoverCard(null)
    setPreview(null)
  }

  if (minimized) {
    return (
      <button className={decisionStyles.floatingReturnButton} onClick={() => setMinimized(false)}>
        {t('decisions.returnToOrderingTriggers')}
      </button>
    )
  }

  return (
    <div className={decisionStyles.overlay} data-testid="order-triggers">
      <div className={styles.panel} role="dialog" aria-labelledby="order-triggers-title">
        <header className={styles.header}>
          <h2 id="order-triggers-title" className={styles.title}>{t('decisions.orderTriggersTitle')}</h2>
          <p className={styles.lede}>
            {t('decisions.orderTriggersDescription', { count: order.length })}
          </p>
        </header>

        <div className={styles.listHead}>
          <span className={styles.endLabel} aria-hidden="true">
            <span className={styles.endArrow}>▲</span> {t('decisions.resolvesFirst')}
          </span>
          {/* Always laid out, so showing it doesn't shift the list. */}
          <button
            type="button"
            className={styles.resetButton}
            style={{ visibility: changed ? 'visible' : 'hidden' }}
            onClick={() => setOrder(decision.triggers.map((_, i) => i))}
          >
            {t('decisions.resetOrder')}
          </button>
        </div>

        <ol className={styles.list} aria-label={t('decisions.triggerOrderList')}>
          {order.map((triggerIndex, position) => {
            const trigger = decision.triggers[triggerIndex]!
            const triggeredBy = trigger.triggeringEntityId ? cards?.[trigger.triggeringEntityId]?.name : undefined
            const imageUrl = getCardImageUrl(trigger.sourceName, trigger.imageUri ?? cards?.[trigger.sourceId]?.imageUri, 'small')
            const rowClass = [
              styles.row,
              dragging === position ? styles.rowDragging : '',
              dropTarget === position && dragging !== null && dragging !== position
                ? (dragging < position ? styles.rowDropBelow : styles.rowDropAbove)
                : '',
              justMoved === triggerIndex ? styles.rowMoved : '',
            ].filter(Boolean).join(' ')
            return (
              <li
                key={triggerIndex}
                ref={(el) => {
                  if (el) rowRefs.current.set(triggerIndex, el)
                  else rowRefs.current.delete(triggerIndex)
                }}
                className={rowClass}
                draggable
                tabIndex={0}
                aria-label={t('decisions.triggerOrderItem', { position: position + 1, cardName: trigger.sourceName, text: trigger.text })}
                onDragStart={(e) => {
                  e.dataTransfer.effectAllowed = 'move'
                  hidePreview()
                  setDragging(position)
                }}
                onDragOver={(e) => {
                  e.preventDefault()
                  if (dropTarget !== position) setDropTarget(position)
                }}
                onDrop={(e) => {
                  e.preventDefault()
                  if (dragging !== null) move(dragging, position)
                  setDragging(null)
                  setDropTarget(null)
                }}
                onDragEnd={() => {
                  setDragging(null)
                  setDropTarget(null)
                }}
                onKeyDown={(e) => {
                  if (!e.altKey) return
                  if (e.key === 'ArrowUp') { e.preventDefault(); move(position, position - 1) }
                  if (e.key === 'ArrowDown') { e.preventDefault(); move(position, position + 1) }
                }}
                onAnimationEnd={() => setJustMoved(null)}
              >
                <span className={styles.rank} aria-hidden="true">{position + 1}</span>
                <span className={styles.grip} aria-hidden="true">⋮⋮</span>
                <img
                  className={styles.thumb}
                  src={imageUrl}
                  alt=""
                  draggable={false}
                  onMouseEnter={(e) => showPreview(trigger, e)}
                  onMouseLeave={hidePreview}
                />
                <div className={styles.body}>
                  <span className={styles.source}>{trigger.sourceName}</span>
                  <span className={styles.text}><AbilityText text={trigger.text} size={14} /></span>
                  {(triggeredBy || trigger.choosesTargets || trigger.checksOnResolution) && (
                    <span className={styles.meta}>
                      {triggeredBy && <span>{t('decisions.triggeredByCard', { cardName: triggeredBy })}</span>}
                      {trigger.choosesTargets && <span className={styles.targetsChip}>{t('decisions.targetsChosenNext')}</span>}
                      {trigger.checksOnResolution && (
                        <span
                          className={styles.checksChip}
                          title={t('decisions.triggerConditionHint')}
                        >
                          {t('decisions.checksAsItResolves')}
                        </span>
                      )}
                    </span>
                  )}
                </div>
                <div className={styles.arrows}>
                  <button
                    type="button"
                    className={styles.arrow}
                    disabled={position === 0}
                    onClick={() => move(position, position - 1)}
                    aria-label={t('decisions.resolveCardEarlier', { cardName: trigger.sourceName })}
                    title={t('decisions.resolveEarlier')}
                  >
                    ▲
                  </button>
                  <button
                    type="button"
                    className={styles.arrow}
                    disabled={position === order.length - 1}
                    onClick={() => move(position, position + 1)}
                    aria-label={t('decisions.resolveCardLater', { cardName: trigger.sourceName })}
                    title={t('decisions.resolveLater')}
                  >
                    ▼
                  </button>
                </div>
              </li>
            )
          })}
        </ol>

        <div className={`${styles.endLabel} ${styles.endLabelLast}`} aria-hidden="true">
          <span className={styles.endArrow}>▼</span> {t('decisions.resolvesLast')}
        </div>

        <footer className={styles.footer}>
          <div className={styles.buttons}>
            <button onClick={() => setMinimized(true)} className={decisionStyles.viewBattlefieldButton}>
              {t('decisions.viewBattlefield')}
            </button>
            <button onClick={confirm} className={decisionStyles.confirmButton} autoFocus>
              {t('decisions.putOnStack')}
            </button>
          </div>
          {fullControl && autoOrderTriggers ? (
            <p className={styles.stopAskingHint}>
              {t('decisions.fullControlTriggerHint')}
            </p>
          ) : (
            <label className={styles.stopAsking}>
              <input type="checkbox" checked={stopAsking} onChange={(e) => setStopAsking(e.target.checked)} />
              <span>
                {t('decisions.stopAskingTriggerOrder')}
                <span className={styles.stopAskingHint}>
                  {fullControl
                    ? t('decisions.fullControlPreferenceHint')
                    : t('decisions.preferenceHint')}
                </span>
              </span>
            </label>
          )}
        </footer>
      </div>

      {preview && <DecisionCardPreview cardName={preview.sourceName} imageUri={preview.imageUri} />}
    </div>
  )
}

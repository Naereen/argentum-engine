/**
 * Search cards | Deck list — the two views inside one builder mode.
 *
 * Deliberately styled as underlined tabs with icons, unlike the pill-shaped {@link BuilderModeSwitch}
 * above it: the switch picks *what* you build (a deck or a cube), these pick *how you look at it*
 * (the card search, or the whole list), and two identical pill toggles stacked on each other read
 * as the same control. The list tab carries the card count so it's plainly "the thing you're building".
 */
import styles from './BuilderViewToggle.module.css'
import { useTranslation } from 'react-i18next'

export type BuilderView = 'cards' | 'list'

export function BuilderViewToggle({
  noun,
  count,
  view,
  onChange,
  className,
}: {
  /** What is being built: "Deck" or "Cube". */
  noun: 'Deck' | 'Cube'
  count: number
  view: BuilderView
  onChange: (view: BuilderView) => void
  className?: string | undefined
}) {
  const { t } = useTranslation()
  return (
    <div className={`${styles.tabs} ${className ?? ''}`} role="group" aria-label={t('deckbuilder.view')}>
      <button
        type="button"
        className={styles.tab}
        aria-pressed={view === 'cards'}
        onClick={() => onChange('cards')}
        title={t('deckbuilder.searchFilterCatalog')}
      >
        <svg className={styles.icon} viewBox="0 0 16 16" aria-hidden>
          <circle cx="7" cy="7" r="4.5" />
          <path d="M10.5 10.5 14 14" />
        </svg>
        {t('deckbuilder.searchCards')}
      </button>
      <button
        type="button"
        className={styles.tab}
        aria-pressed={view === 'list'}
        onClick={() => onChange('list')}
        title={t('deckbuilder.seeWholeList', { noun: t(noun === 'Deck' ? 'deckbuilder.deck' : 'deckbuilder.cube').toLowerCase() })}
      >
        <svg className={styles.icon} viewBox="0 0 16 16" aria-hidden>
          <path d="M2 4h12M2 8h12M2 12h12" />
        </svg>
        {t('deckbuilder.nounList', { noun: t(noun === 'Deck' ? 'deckbuilder.deck' : 'deckbuilder.cube') })}
        <span className={styles.count}>{count}</span>
      </button>
    </div>
  )
}

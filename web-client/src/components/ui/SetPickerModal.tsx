/**
 * SetPickerModal — the searchable, grouped set browser shared by the tournament lobby and the
 * Quick Game lobby's "Random" deck tab.
 *
 * Every set (complete + partial) lives behind this one modal. It supports two modes:
 *   - `multi`  (default): clicking a row toggles it and the modal stays open. The host builds a
 *               multi-set pool (tournament lobby). With `onSelectRandom` a top "Random Set" row
 *               asks the parent to add a random set to the selection.
 *   - `single`: clicking a row selects it and closes the modal. With `onSelectRandom` a top
 *               "Random Set" row clears the selection so the server rolls a random set
 *               (callers that need exactly one set).
 *
 * Extension sets (bonus sheets like The Big Score) carry an "extension" badge and are only
 * playable alongside a regular set, so single mode hides them entirely.
 *
 * Beside the list, a preview pane shows the Set guide's profile of the set under the pointer (or
 * the last one selected), so a player can pick by what a set plays like, not just its name. Narrow
 * screens drop the pane and show each set's one-line pitch under its name instead.
 *
 * Visuals come from `GameUI.module.css` (the shared lobby stylesheet — `LobbyScreen`
 * already reuses it) so both lobbies look identical.
 */
import { useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import type { AvailableSet } from '@/types/messages'
import { SetIcon } from './SetIcon'
import { SetPickerPreview } from '@/components/setGuide/SetPickerPreview'
import { setProfile } from '@/components/setGuide/setProfiles'
import guide from '@/components/setGuide/SetGuide.module.css'
import { visiblePickerSets } from './setPickerSets'
import styles from './GameUI.module.css'

export interface SetPickerModalProps {
  /** All sets the picker can browse (complete + partial). */
  sets: readonly AvailableSet[]
  /** Codes currently selected — drives row highlight and (multi mode) the "N selected" count. */
  selectedCodes: readonly string[]
  /** Toggle (multi) or select (single) a set by code. */
  onToggleSet: (code: string) => void
  /** Dismiss the modal. */
  onClose: () => void
  /**
   * 'multi' (default) keeps the modal open after each toggle; 'single' selects one set and closes.
   */
  mode?: 'single' | 'multi'
  /**
   * When provided, a "Random Set" row is shown at the top. In single mode it clears the selection
   * (the server then rolls a random set) and closes the modal; in multi mode the parent picks a
   * random set to add to the selection and the modal stays open.
   */
  onSelectRandom?: () => void
  /** Modal heading. Defaults to "Choose sets". */
  title?: string
  /** Optional non-booster product ids selected per set code. */
  selectedProducts?: Readonly<Record<string, readonly string[]>>
  onToggleProduct?: (setCode: string, productId: string) => void
}

function formatReleaseDate(releaseDate: string | undefined, language: string): string | null {
  if (!releaseDate) return null
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(releaseDate)
  if (!match) return null
  const [, year, month, day] = match
  if (!year || !month || !day) return null
  return new Intl.DateTimeFormat(language, { month: 'short', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(Date.UTC(Number(year), Number(month) - 1, Number(day))))
}

// Newest-first comparator on ISO `YYYY-MM-DD` keys; sets/blocks with no known release date sort last.
const UNKNOWN_RELEASE = ''
const releaseSortKey = (releaseDate?: string): string => releaseDate ?? UNKNOWN_RELEASE
function byNewestFirst(a: string, b: string): number {
  if (a === UNKNOWN_RELEASE || b === UNKNOWN_RELEASE) {
    if (a === b) return 0
    return a === UNKNOWN_RELEASE ? 1 : -1 // unknowns always last
  }
  return b.localeCompare(a) // later date first
}

export const setProductLabel = (id: string): string => ({
  beginnerbox: 'Beginner Box',
  startercollection: 'Starter Collection',
  setextension: 'Set Extension',
}[id] ?? id.replace(/[-_]/g, ' '))

export function SetPickerModal({
  sets,
  selectedCodes,
  onToggleSet,
  onClose,
  mode = 'multi',
  onSelectRandom,
  title,
  selectedProducts = {},
  onToggleProduct,
}: SetPickerModalProps) {
  const { t, i18n } = useTranslation()
  const [search, setSearch] = useState('')
  // Partially-implemented sets are hidden by default; the user opts into them with this toggle.
  const [showPartialSets, setShowPartialSets] = useState(false)
  // Hide thin sets (lots of partial sets have only a handful of cards). Defaults to 30.
  const [minCards, setMinCards] = useState(30)
  // The set the preview pane describes: the row under the pointer or keyboard focus.
  const [previewCode, setPreviewCode] = useState<string | null>(null)

  const isSingle = mode === 'single'
  const isSelected = (code: string) => selectedCodes.includes(code)

  const partialSetCount = sets.filter((s) => s.partial).length
  const pickerSets = visiblePickerSets(sets, selectedCodes, {
    search: '', single: isSingle, showPartial: showPartialSets, minCards,
  })
  const filteredPickerSets = visiblePickerSets(sets, selectedCodes, {
    search, single: isSingle, showPartial: showPartialSets, minCards,
  })

  const handlePick = (code: string) => {
    onToggleSet(code)
    if (isSingle) onClose()
  }

  const handleRandom = () => {
    onSelectRandom?.()
    if (isSingle) onClose()
  }

  // One toggle row inside the picker modal. Incomplete sets get a "partial" tag so the user knows
  // those boosters draw from a reduced pool of implemented cards.
  const renderSetPickerRow = (set: AvailableSet) => {
    const selected = isSelected(set.code)
    const released = formatReleaseDate(set.releaseDate, i18n.resolvedLanguage ?? 'en')
    const products = set.products ?? []
    const tagline = setProfile(set.code)?.tagline
    const selectedProductIds = selectedProducts[set.code] ?? []
    return (
      <div key={set.code}>
      <button
        type="button"
        onClick={() => handlePick(set.code)}
        onMouseEnter={() => setPreviewCode(set.code)}
        onFocus={() => setPreviewCode(set.code)}
        className={`${styles.setPickerRow} ${selected ? styles.setPickerRowActive : ''}`}
      >
        <span className={styles.setPickerCheck} aria-hidden>{selected ? '✓' : ''}</span>
        <SetIcon code={set.code} className={styles.setPickerIcon} />
        <span className={styles.setPickerName}>
          {set.name}
          {tagline && <span className={guide.pickerTagline}>{tagline}</span>}
        </span>
        {set.partial && <span className={styles.setPartialBadge}>{t('setPicker.partial')}</span>}
        {set.extensionSet && <span className={styles.setExtensionBadge}>{t('setPicker.extension')}</span>}
        {released && <span className={styles.setReleaseDate}>{released}</span>}
        {set.implementedCount != null && (
          <span className={styles.setButtonCardCount}>{t('setPicker.cardCount', { count: set.implementedCount })}</span>
        )}
      </button>
      {selected && !isSingle && onToggleProduct && products.length > 0 && (
        <div aria-label={t('setPicker.additionsFor', { setName: set.name })}>
          {products.map((product) => {
            const checked = selectedProductIds.includes(product.id)
            return (
              <button
                key={product.id}
                type="button"
                role="checkbox"
                aria-checked={checked}
                onClick={() => onToggleProduct(set.code, product.id)}
                className={`${styles.setPickerRow} ${checked ? styles.setPickerRowActive : ''}`}
                style={{ paddingLeft: 54 }}
              >
                <span className={styles.setPickerCheck} aria-hidden>{checked ? '✓' : ''}</span>
                <span className={styles.setPickerName}>
                  {({
                    beginnerbox: t('setPicker.beginnerBox'),
                    startercollection: t('setPicker.starterCollection'),
                    setextension: t('setPicker.setExtension'),
                  } as Record<string, string>)[product.id] ?? setProductLabel(product.id)}
                </span>
                <span className={styles.setButtonCardCount}>{t('setPicker.cardCount', { count: product.cardCount })}</span>
              </button>
            )
          })}
        </div>
      )}
      </div>
    )
  }

  // Order sets newest-first while keeping blocks grouped: each block is a single unit positioned by
  // its newest set's release date, and each blockless set is its own unit. Units are sorted by date
  // (newest on top), and sets within a block are sorted newest-first too.
  const renderGroupedSetRows = (rows: readonly AvailableSet[]) => {
    const blockOrder: string[] = []
    const blockSets = new Map<string, AvailableSet[]>()
    const ungrouped: AvailableSet[] = []
    for (const set of rows) {
      if (set.block) {
        if (!blockSets.has(set.block)) {
          blockOrder.push(set.block)
          blockSets.set(set.block, [])
        }
        blockSets.get(set.block)!.push(set)
      } else {
        ungrouped.push(set)
      }
    }

    type Unit =
      | { kind: 'block'; key: string; label: string; sets: AvailableSet[]; sortKey: string }
      | { kind: 'set'; key: string; set: AvailableSet; sortKey: string }

    const units: Unit[] = []
    for (const name of blockOrder) {
      const blockMembers = blockSets
        .get(name)!
        .slice()
        .sort((a, b) => byNewestFirst(releaseSortKey(a.releaseDate), releaseSortKey(b.releaseDate)))
      units.push({
        kind: 'block',
        key: name,
        label: t('setPicker.blockLabel', { name }),
        sets: blockMembers,
        sortKey: releaseSortKey(blockMembers[0]?.releaseDate), // newest in block, after sort above
      })
    }
    for (const set of ungrouped) {
      units.push({ kind: 'set', key: set.code, set, sortKey: releaseSortKey(set.releaseDate) })
    }
    units.sort((a, b) => byNewestFirst(a.sortKey, b.sortKey))

    return units.map((unit) =>
      unit.kind === 'block' ? (
        <div key={`block:${unit.key}`} className={styles.setPickerGroup}>
          <div className={styles.setPickerGroupLabel}>{unit.label}</div>
          {unit.sets.map(renderSetPickerRow)}
        </div>
      ) : (
        renderSetPickerRow(unit.set)
      ),
    )
  }

  const previewSet =
    sets.find((s) => s.code === previewCode) ?? sets.find((s) => s.code === selectedCodes[selectedCodes.length - 1])

  const showRandomRow = onSelectRandom != null
  const randomActive = isSingle && selectedCodes.length === 0

  // Portalled to the body: hosts like the Play hub's side sheet would otherwise confine the modal
  // (and its preview pane) to their own width.
  return createPortal(
    <div className={styles.deckViewerBackdrop} onClick={onClose}>
      <div className={`${styles.deckViewerPanel} ${guide.pickerPanel}`} style={{ maxWidth: 940 }} onClick={(e) => e.stopPropagation()}>
        <div className={styles.deckViewerHeader}>
          <h3 className={styles.deckViewerTitle}>{title ?? t('setPicker.chooseSets')}</h3>
          {!isSingle && (
            <span className={styles.setPickerSelectedCount}>
              {t('setPicker.selectedCount', { count: selectedCodes.length })}
            </span>
          )}
          <button className={styles.deckViewerClose} onClick={onClose} aria-label={t('common.close')}>×</button>
        </div>
        <div className={guide.pickerLayout}>
        <div className={`${styles.deckViewerBody} ${guide.pickerMain}`}>
          <input
            type="text"
            className={styles.setPickerSearch}
            placeholder={t('setPicker.searchPlaceholder')}
            value={search}
            spellCheck={false}
            autoComplete="off"
            autoFocus
            onChange={(e) => setSearch(e.target.value)}
          />
          <div className={styles.setPickerFilters}>
            <button
              type="button"
              role="switch"
              aria-checked={showPartialSets}
              className={`${styles.setPickerSwitch} ${showPartialSets ? styles.setPickerSwitchOn : ''}`}
              onClick={() => setShowPartialSets((v) => !v)}
            >
              <span className={styles.setPickerSwitchTrack} aria-hidden>
                <span className={styles.setPickerSwitchThumb} />
              </span>
              <span className={styles.setPickerSwitchLabel}>
                {t('setPicker.showPartial')}
                {partialSetCount > 0 && (
                  <span className={styles.setPickerSwitchCount}>{partialSetCount}</span>
                )}
              </span>
            </button>
            <label className={styles.setPickerMinCards}>
              <span>{t('setPicker.minimumCards')}</span>
              <input
                type="number"
                min={0}
                step={10}
                inputMode="numeric"
                className={styles.setPickerMinCardsInput}
                value={minCards}
                onChange={(e) => setMinCards(Math.max(0, Math.floor(Number(e.target.value) || 0)))}
              />
            </label>
          </div>
          <p className={styles.setPickerNote}>
            {t('setPicker.partialExplanation', { partial: t('setPicker.partial') })}
            {' '}{t('setPicker.searchIncludesHidden')}
            {!isSingle && pickerSets.some((s) => s.extensionSet) && (
              <>
                {' '}{t('setPicker.extensionExplanation', { extension: t('setPicker.extension') })}
              </>
            )}
          </p>
          <div className={styles.setPickerList}>
            {showRandomRow && (
              <button
                type="button"
                onClick={handleRandom}
                className={`${styles.setPickerRow} ${randomActive ? styles.setPickerRowActive : ''}`}
              >
                <span className={styles.setPickerCheck} aria-hidden>{randomActive ? '✓' : ''}</span>
                <span className={styles.setPickerIcon} aria-hidden>🎲</span>
                <span className={styles.setPickerName}>{t('setPicker.randomSet')}</span>
              </button>
            )}
            {filteredPickerSets.length > 0 ? (
              renderGroupedSetRows(filteredPickerSets)
            ) : (
              !showRandomRow && (
                <div className={styles.setPickerEmpty}>
                  {t('setPicker.noMatches')}{' '}
                  {!showPartialSets && partialSetCount > 0
                    ? t('setPicker.tryPartial')
                    : minCards > 0
                      ? t('setPicker.tryLowerMinimum')
                      : t('setPicker.tryDifferentSearch')}
                </div>
              )
            )}
          </div>
        </div>
        <aside className={guide.pickerPreview} aria-label={t('setPicker.aboutSet')}>
          <SetPickerPreview set={previewSet} />
        </aside>
        </div>
      </div>
    </div>,
    document.body,
  )
}

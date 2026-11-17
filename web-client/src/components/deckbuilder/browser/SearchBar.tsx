/**
 * Catalog search bar — free-text Scryfall-style query, sort selector, result count, and the
 * syntax help popover. Shared between the deckbuilder page and the scenario builder.
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ParseError } from '../cardFilter'
import { applyFix, SEARCH_KEYS } from '../query'
import { SEARCH_EXAMPLE_GROUPS } from './searchExamples'
import styles from '../deckbuilder.module.css'
import type { SortMode } from './cardSort'

export function SearchBar({
  query,
  onQueryChange,
  sortMode,
  onSortChange,
  resultLabel,
  errors,
  explanation,
  actionHint,
  filterChip,
}: {
  query: string
  onQueryChange: (next: string) => void
  sortMode: SortMode
  onSortChange: (m: SortMode) => void
  resultLabel: string
  errors: ParseError[]
  /** The query in plain words (`ParseResult.explanation`), shown under the input so the reader can check how it was read. */
  explanation?: string | null
  /** Overrides the trailing "click adds / right-click removes" hint. */
  actionHint?: React.ReactNode
  /** A filter toggle shown beside the result count — the deckbuilder's commander-identity chip. */
  filterChip?: React.ReactNode
}) {
  const { t } = useTranslation()
  const [helpOpen, setHelpOpen] = useState(false)
  const hasErrors = errors.length > 0
  // Local mirror of the URL-backed `query` so the input never has its DOM value reassigned by
  // the round-trip through `useSearchParams`. Without this, typing certain characters (e.g.
  // parentheses) caused the cursor to jump because React would briefly rerender the input
  // with the previous-tick query while the URL update was in flight, and the browser
  // re-anchored the caret.
  const [localQuery, setLocalQuery] = useState(query)
  useEffect(() => {
    setLocalQuery((prev) => (prev === query ? prev : query))
  }, [query])
  const setBoth = (next: string) => {
    setLocalQuery(next)
    onQueryChange(next)
  }
  const typing = localQuery.trim() !== ''
  return (
    <div className={styles.searchBar}>
      <div className={styles.searchInputWrap}>
        <input
          className={hasErrors ? styles.searchInputError : styles.searchInput}
          // Short enough not to be cut mid-query in a narrow pane; the ? button holds the full syntax.
          placeholder={t('library.browser.searchPlaceholder')}
          value={localQuery}
          onChange={(e) => setBoth(e.target.value)}
          aria-invalid={hasErrors}
          aria-describedby={hasErrors ? 'search-errors' : undefined}
        />
        {hasErrors && (
          <ul id="search-errors" className={styles.searchErrors} role="alert">
            {errors.map((e, i) => (
              <li key={i}>
                <code>{query.slice(e.span.start, e.span.end) || '·'}</code>
                <span>{e.message}</span>
                {e.fix ? (
                  <button
                    type="button"
                    className={styles.searchFix}
                    onClick={() => setBoth(applyFix(query, e.fix!))}
                    title={t('library.browser.applyFix')}
                  >
                    {t('library.browser.useFix')} <code>{e.fix.text}</code>
                  </button>
                ) : e.suggestion ? (
                  <span>{e.suggestion}</span>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </div>
      <button
        className={helpOpen ? styles.helpIconActive : styles.helpIcon}
        onClick={() => setHelpOpen((v) => !v)}
        title={t('library.browser.searchTips')}
        aria-label={t('library.browser.showSearchTips')}
        type="button"
      >
        ?
      </button>
      <select
        className={styles.sortSelect}
        value={sortMode}
        onChange={(e) => onSortChange(e.target.value as SortMode)}
      >
        <option value="name">{t('library.browser.sortName')}</option>
        <option value="cmc">{t('library.browser.sortManaValue')}</option>
        <option value="color">{t('library.browser.sortColor')}</option>
        <option value="rarity">{t('library.browser.sortRarity')}</option>
      </select>
      {filterChip}
      <span className={styles.resultCount}>{resultLabel}</span>
      <span className={styles.cardActionHint}>
        {actionHint ?? (
          <>
            <kbd>{t('library.browser.click')}</kbd> {t('library.browser.add')} · <kbd>{t('library.browser.rightClick')}</kbd> {t('library.browser.remove')}
          </>
        )}
      </span>
      {typing && (
        // Always rendered while there is a query, so the row doesn't jump in and out as the
        // query passes through invalid states mid-keystroke; the error list covers it then.
        <p className={styles.searchExplanation} aria-live="polite">
          {explanation ? (
            <>
              <span className={styles.searchExplanationLead}>{t('library.browser.cardsWhere')}</span> {explanation}
            </>
          ) : null}
        </p>
      )}
      {helpOpen && <SearchHelp onClose={() => setHelpOpen(false)} onInsert={(t) => setBoth(localQuery.trim() ? `${localQuery.trim()} ${t}` : t)} />}
    </div>
  )
}

export function SearchHelp({
  onClose,
  onInsert,
  intro,
}: {
  onClose: () => void
  /** Called with a clicked example; the caller decides whether it replaces or extends the query. */
  onInsert: (t: string) => void
  /** Rendered above the cheat sheet — e.g. how this search box's modes differ. */
  intro?: React.ReactNode
}) {
  const { t } = useTranslation()
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <>
      <div className={styles.helpBackdrop} onClick={onClose} />
      <div className={styles.helpPopover} role="dialog" aria-label={t('library.browser.searchTips')}>
        <div className={styles.helpHeader}>
          <strong>{t('library.browser.searchTips')}</strong>
          <button className={styles.helpClose} onClick={onClose} type="button" aria-label={t('library.browser.closeSearchTips')}>
            ×
          </button>
        </div>
        {intro}
        <p className={styles.helpLead}>
          {t('library.browser.searchHelpIntro')} <code>t:goblin</code>. {t('library.browser.searchHelpClick')}
        </p>
        <div className={styles.helpGroups}>
          {SEARCH_EXAMPLE_GROUPS.map((group) => (
            <section key={group.titleKey} className={styles.helpGroup}>
              <h4>{t(`library.browser.examples.titles.${group.titleKey}`)}</h4>
              <ul>
                {group.examples.map((ex) => (
                  <li key={ex.query}>
                    <button
                      className={styles.helpExample}
                      onClick={() => {
                        onInsert(ex.query)
                        onClose()
                      }}
                      type="button"
                      title={t('library.browser.addExample', { query: ex.query })}
                    >
                      <code>{ex.query}</code>
                    </button>
                    <span className={styles.helpDesc}>{t(`library.browser.examples.descriptions.${ex.descriptionKey}`)}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
        <details className={styles.helpAllKeys}>
          <summary>{t('library.browser.allFilters')}</summary>
          <ul>
            {SEARCH_KEYS.map((k) => (
              <li key={k.aliases[0]}>
                <code>{k.aliases[0]}:</code>
                <span>
                  {k.description}
                  {k.aliases.length > 1 && (
                    <span className={styles.helpAliases}> · {t('library.browser.also')} {k.aliases.slice(1).join(', ')}</span>
                  )}
                </span>
              </li>
            ))}
          </ul>
          <p className={styles.helpHint}>
            {t('library.browser.comparisonHelp')} <code>=</code> <code>&lt;</code> <code>&gt;</code>{' '}
            <code>&lt;=</code> <code>&gt;=</code>.
          </p>
        </details>
      </div>
    </>
  )
}

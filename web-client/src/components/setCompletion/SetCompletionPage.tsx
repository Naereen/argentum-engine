import { createPortal } from 'react-dom'
import { memo, useCallback, useEffect, useMemo, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router-dom'
import { SetIcon } from '../ui/SetIcon'
import { HelpTip } from '../help/HelpTip'
import { PageShell, pageStyles } from '../ui/PageShell'
import { HoverCardPreview } from '../ui/HoverCardPreview'
import { ProgressChartOverlay } from './ProgressChartOverlay'
import i18n from '@/i18n'
import { getScryfallFallbackUrl } from '@/utils/cardImages'
import {
  fetchSetCoverage,
  fetchCoverageSummary,
  fetchSetDetail,
  fetchAiReadingSummary,
  fetchSetAiReading,
  type AiUnreadCard,
  type SetAiReading,
  type SetAiReadingDetail,
  type SetDraftRatings,
  type SetCoverage,
  type CoverageSummary,
  type SetDetail,
  type CardCoverage,
} from '@/api/setCoverage'
import { SET_KIND_ORDER, setKind, setTypeLabelKey, type SetKind } from './setKinds'
import styles from './SetCompletionPage.module.css'

type SortKey = 'newest' | 'percent' | 'implemented' | 'assayReady' | 'aiRead' | 'name'
type Filter = 'all' | 'standard' | 'inProgress' | 'complete'
type KindFilter = 'all' | SetKind

// The page opens on main sets: they're what people draft and build from, and the Commander and
// precon long tail would otherwise bury them. Every other type is one chip away.
const DEFAULT_KIND: KindFilter = 'main'
type Tab = 'sets' | 'explorer'

const SORT_LABEL_KEYS = {
  newest: 'setCompletion.filters.newest',
  percent: 'setCompletion.filters.percent',
  implemented: 'setCompletion.filters.implemented',
  assayReady: 'setCompletion.filters.assayReady',
  aiRead: 'setCompletion.filters.aiRead',
  name: 'setCompletion.filters.name',
} as const satisfies Record<SortKey, string>

/**
 * Where game-server mounts the live Argentum Assay explorer.
 *
 * Mounted on *every* server, not just dev ones: the tool is a read over public card text and holds
 * no state a request can mutate. See `AssayExploreController` for what that costs and why it is
 * still worth it — and {@link ExplorerPane} for why there is consequently no flag to fetch.
 */
const EXPLORER_URL = '/api/assay/explorer'

/** A card nobody has authored that Assay already reads end to end: the cheapest work on the board. */
function isAssayReady(card: CardCoverage): boolean {
  return !card.implemented && card.notPlanned === null && card.assay?.readsWhole === true
}

const FILTER_LABEL_KEYS = {
  all: 'setCompletion.filters.all',
  standard: 'setCompletion.filters.standard',
  inProgress: 'setCompletion.filters.inProgress',
  complete: 'setCompletion.filters.complete',
} as const satisfies Record<Filter, string>

const KIND_LABEL_KEYS = {
  main: 'setCompletion.kinds.main.label',
  commander: 'setCompletion.kinds.commander.label',
  supplemental: 'setCompletion.kinds.supplemental.label',
  precon: 'setCompletion.kinds.precon.label',
  funny: 'setCompletion.kinds.funny.label',
} as const satisfies Record<SetKind, string>

const KIND_DESCRIPTION_KEYS = {
  main: 'setCompletion.kinds.main.description',
  commander: 'setCompletion.kinds.commander.description',
  supplemental: 'setCompletion.kinds.supplemental.description',
  precon: 'setCompletion.kinds.precon.description',
  funny: 'setCompletion.kinds.funny.description',
} as const satisfies Record<SetKind, string>

const NOT_PLANNED_REASON_KEYS = {
  ante: 'setCompletion.set.notPlannedReasonAnte',
  dexterity: 'setCompletion.set.notPlannedReasonDexterity',
} as const

/** Coverage tier drives the bar/accent color. Mirrors the at-a-glance red→green of the TUI. */
function tier(pct: number): 'empty' | 'low' | 'mid' | 'high' | 'done' {
  if (pct >= 100) return 'done'
  if (pct >= 67) return 'high'
  if (pct >= 34) return 'mid'
  if (pct > 0) return 'low'
  return 'empty'
}

function year(releaseDate: string | null): string {
  return releaseDate?.slice(0, 4) ?? '—'
}

function fmtPct(pct: number): string {
  return new Intl.NumberFormat(i18n.resolvedLanguage ?? i18n.language, { maximumFractionDigits: 1 }).format(pct)
}

function fmtCount(count: number): string {
  return count.toLocaleString(i18n.resolvedLanguage ?? i18n.language)
}

/** A URL parameter narrowed to the values the view knows, so a stale or hand-edited link falls back. */
function oneOf<T extends string>(value: string | null, allowed: readonly T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback
}

/** Downscale a baked normal-size Scryfall CDN URL to the small variant for grid thumbnails. */
function smallArt(name: string, imageUri: string | null): string {
  if (imageUri) return imageUri.replace('/normal/', '/small/')
  return getScryfallFallbackUrl(name, 'small')
}

interface HoverState {
  name: string
  imageUri: string | null
  isLandscape: boolean
  pos: { x: number; y: number }
}

export function SetCompletionPage() {
  const { t } = useTranslation()
  const [sets, setSets] = useState<readonly SetCoverage[] | null>(null)
  const [summary, setSummary] = useState<CoverageSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const aiRead = useAiReading()
  // The view's choices live in the URL, so "Commander sets, most Assay-ready first" is a link someone
  // can share, and reloading or going back lands on the same list rather than the default one.
  const [params, setParams] = useSearchParams()
  const setParam = useCallback(
    (key: string, value: string | null) =>
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          if (value) next.set(key, value)
          else next.delete(key)
          return next
        },
        { replace: true },
      ),
    [setParams],
  )
  const query = params.get('q') ?? ''
  const sort = oneOf(params.get('sort'), Object.keys(SORT_LABEL_KEYS) as SortKey[], 'newest')
  const filter = oneOf(params.get('status'), Object.keys(FILTER_LABEL_KEYS) as Filter[], 'all')
  const kind = oneOf(params.get('type'), ['all', ...SET_KIND_ORDER] as KindFilter[], DEFAULT_KIND)
  const openCode = params.get('set')
  const setQuery = useCallback((q: string) => setParam('q', q || null), [setParam])
  const setSort = useCallback((k: SortKey) => setParam('sort', k === 'newest' ? null : k), [setParam])
  const setFilter = useCallback((f: Filter) => setParam('status', f === 'all' ? null : f), [setParam])
  const setKindFilter = useCallback((k: KindFilter) => setParam('type', k === DEFAULT_KIND ? null : k), [setParam])
  const setOpenCode = useCallback((code: string | null) => setParam('set', code), [setParam])
  const clearFilters = useCallback(
    () =>
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          for (const k of ['q', 'status', 'type']) next.delete(k)
          return next
        },
        { replace: true },
      ),
    [setParams],
  )
  const [showProgress, setShowProgress] = useState(false)
  const [tab, setTab] = useState<Tab>('sets')
  // Where the framed explorer opens: its own home, or a card a set's AI-gaps list pointed at.
  const [explorerHash, setExplorerHash] = useState('')
  const exploreCard = useCallback(
    (name: string) => {
      // `/ai` opens the card scrolled to its AI reading panel.
      setExplorerHash('#c/' + encodeURIComponent(name) + '/ai')
      setOpenCode(null)
      setTab('explorer')
    },
    [setOpenCode],
  )
  // Opening a set straight on what the AI misses in it — the AI score on a tile links here.
  const openSetAiGaps = useCallback(
    (code: string) =>
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          next.set('set', code)
          next.set('cards', 'ai')
          return next
        },
        { replace: true },
      ),
    [setParams],
  )
  const closeSet = useCallback(
    () =>
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          next.delete('set')
          next.delete('cards')
          return next
        },
        { replace: true },
      ),
    [setParams],
  )

  useEffect(() => {
    let cancelled = false
    fetchSetCoverage()
      .then((data) => !cancelled && setSets(data))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    fetchCoverageSummary()
      .then((data) => !cancelled && setSummary(data))
      .catch(() => {}) // banner falls back to summing the per-set rows
    return () => {
      cancelled = true
    }
  }, [])

  const totals = useMemo(() => {
    if (!sets) return null
    const implemented = sets.reduce((n, s) => n + s.implemented, 0)
    const total = sets.reduce((n, s) => n + s.total, 0)
    const complete = sets.filter((s) => s.percent >= 100).length
    return {
      implemented,
      total,
      complete,
      setCount: sets.length,
      percent: total === 0 ? 0 : Math.round((implemented * 1000) / total) / 10,
    }
  }, [sets])

  // Every filter but the type one. The type chips count against this, so each chip says how many
  // sets it would show *given the rest of what is selected* — a chip reading "Commander 3" while
  // searching "dragon" is the number you get, not the 22 in the whole catalog.
  const preKind = useMemo(() => {
    if (!sets) return []
    const q = query.trim().toLowerCase()
    return sets.filter((s) => {
      if (filter === 'standard' && !s.inStandard) return false
      if (filter === 'complete' && s.percent < 100) return false
      if (filter === 'inProgress' && (s.percent >= 100 || s.implemented === 0)) return false
      if (!q) return true
      return s.name.toLowerCase().includes(q) || s.code.toLowerCase().includes(q)
    })
  }, [sets, query, filter])

  const kindCounts = useMemo(() => {
    const counts = new Map<SetKind, number>()
    for (const s of preKind) {
      const k = setKind(s.setType)
      counts.set(k, (counts.get(k) ?? 0) + 1)
    }
    return counts
  }, [preKind])

  const visible = useMemo(() => {
    const filtered = kind === 'all' ? preKind : preKind.filter((s) => setKind(s.setType) === kind)
    const byName = (a: SetCoverage, b: SetCoverage) => a.name.localeCompare(b.name)
    const sorted = [...filtered]
    switch (sort) {
      case 'percent':
        sorted.sort((a, b) => b.percent - a.percent || b.implemented - a.implemented || byName(a, b))
        break
      case 'implemented':
        sorted.sort((a, b) => b.implemented - a.implemented || byName(a, b))
        break
      case 'assayReady':
        // "Which set has the most free work in it" — the question the Assay join exists to answer.
        // A null (no ledger baked) sorts as 0 rather than dropping the set out of the list.
        sorted.sort((a, b) => (b.assayReady ?? 0) - (a.assayReady ?? 0) || byName(a, b))
        break
      case 'aiRead': {
        // "Which sets does the AI understand least" — the ones that need attention first. A set
        // with nothing authored (or before the index lands) has no score and sorts last.
        const score = (x: SetCoverage) => {
          const r = aiRead?.[x.code]
          return r && r.total > 0 ? r.readFully / r.total : Number.POSITIVE_INFINITY
        }
        sorted.sort((a, b) => score(a) - score(b) || byName(a, b))
        break
      }
      case 'name':
        sorted.sort(byName)
        break
      case 'newest':
      default:
        sorted.sort((a, b) => (b.releaseDate ?? '').localeCompare(a.releaseDate ?? '') || a.code.localeCompare(b.code))
        break
    }
    return sorted
  }, [preKind, kind, sort, aiRead])

  // The explorer pane is a full-height frame rather than a block in the scrolling document, so the
  // page stops being its own scroll container while that tab is up and hands the height to the frame.
  const onExplorer = tab === 'explorer'
  return (
    <PageShell title={t('setCompletion.tabs.sets')} width={onExplorer ? 'full' : 'wide'} fit={onExplorer} plain>
      <header className={styles.pageHead}>
        <div className={styles.pageHeading}>
        <h1 className={pageStyles.h1}>{t('setCompletion.title')}</h1>
          <p className={pageStyles.lede}>
          {t('setCompletion.descriptionBefore')}{' '}
          <Link to="/sets">{t('setCompletion.guideLink')}</Link>
          {t('setCompletion.descriptionAfter')}
        </p>
      </div>
      <div className={styles.viewSwitch}>
        <div className={pageStyles.tabs} role="tablist" aria-label={t('setCompletion.tabs.label')}>
            <button
              role="tab"
              aria-selected={tab === 'sets'}
              className={pageStyles.tab}
              onClick={() => setTab('sets')}
            >
              {t('setCompletion.tabs.sets')}
            </button>
            <button
              role="tab"
              aria-selected={tab === 'explorer'}
              className={pageStyles.tab}
              onClick={() => setTab('explorer')}
            >
              {t('setCompletion.tabs.explorer')}
            </button>
          </div>
          {/* Sibling of the tablist, never inside it: `HelpTip` is a button, and one nested in a
              `role="tab"` would be both invalid markup and a click that switches tabs. The explanation
              lives in `topics.ts` rather than in a `title=` so the popover and `/help` can't disagree. */}
          <HelpTip topicId="assay-explorer" label={t('setCompletion.tabs.explorerHelp')} />
        </div>
      </header>

      {onExplorer && <ExplorerPane hash={explorerHash} />}

      {!onExplorer && (
        <SetsTab
          sets={sets}
          summary={summary}
          totals={totals}
          visible={visible}
          error={error}
          query={query}
          setQuery={setQuery}
          sort={sort}
          setSort={setSort}
          filter={filter}
          setFilter={setFilter}
          kind={kind}
          setKind={setKindFilter}
          kindCounts={kindCounts}
          preKindCount={preKind.length}
          onClearFilters={clearFilters}
          onOpen={setOpenCode}
          onOpenAiGaps={openSetAiGaps}
          onShowProgress={() => setShowProgress(true)}
          aiRead={aiRead}
        />
      )}

      {/* Portalled: inside the shell's content column they would sit under its top bar. */}
      {openCode &&
        createPortal(
          <SetDetailOverlay
            code={openCode}
            setType={sets?.find((s) => s.code === openCode)?.setType ?? null}
            aiRead={aiRead?.[openCode] ?? null}
            initialFilter={params.get('cards') === 'ai' ? 'aiGaps' : 'all'}
            onExplore={exploreCard}
            onClose={closeSet}
          />,
          document.body,
        )}
      {showProgress && createPortal(<ProgressChartOverlay onClose={() => setShowProgress(false)} />, document.body)}
    </PageShell>
  )
}

/**
 * The live Argentum Assay explorer, framed.
 *
 * Framed rather than reimplemented on purpose. `:oracle-assay` already serves this page against the
 * **live grammar** — every number in it comes out of the same `Touchstone` / `Differential` objects
 * the CLI gates print, so a rule you just edited is one server restart away from being re-measured.
 * A React port would be a second implementation of those views, free to drift from the gates it
 * claims to display, which is exactly what that module's "a view, never a second source of truth"
 * rule exists to stop. game-server mounts the same handlers under `/api/assay/explorer`, so this is
 * one `<iframe>` and no duplicated logic.
 *
 * Always available, so there is no flag to fetch and no tab that appears late. The server starts its
 * corpus sweep on the first request to the tool, reports progress through its own `status` route
 * while the page is already up, and degrades to "the sweep failed" with the live parser and rule
 * tree still working if it can't reach one — so there is no state this component has to anticipate.
 */
function ExplorerPane({ hash }: { hash: string }) {
  const { t } = useTranslation()
  return (
    <div className={styles.explorerPane}>
      <iframe
        className={styles.explorerFrame}
        src={EXPLORER_URL + hash}
        title={t('setCompletion.tabs.explorerTitle')}
        // Same origin, so the frame can talk to its own endpoints; it needs nothing else.
        sandbox="allow-scripts allow-same-origin allow-forms"
      />
    </div>
  )
}

/** The set grid and its banner — the page as it was before the explorer tab joined it. */
function SetsTab({
  sets,
  summary,
  totals,
  visible,
  error,
  query,
  setQuery,
  sort,
  setSort,
  filter,
  setFilter,
  kind,
  setKind: setKindFilter,
  kindCounts,
  preKindCount,
  onClearFilters,
  onOpen,
  onOpenAiGaps,
  onShowProgress,
  aiRead,
}: {
  onOpenAiGaps: (code: string) => void
  aiRead: Readonly<Record<string, SetAiReading>> | null
  sets: readonly SetCoverage[] | null
  summary: CoverageSummary | null
  totals: { implemented: number; total: number; complete: number; setCount: number; percent: number } | null
  visible: readonly SetCoverage[]
  error: string | null
  query: string
  setQuery: (q: string) => void
  sort: SortKey
  setSort: (s: SortKey) => void
  filter: Filter
  setFilter: (f: Filter) => void
  kind: KindFilter
  setKind: (k: KindFilter) => void
  kindCounts: ReadonlyMap<SetKind, number>
  /** Sets passing every filter but the type one — the "All types" chip's count. */
  preKindCount: number
  onClearFilters: () => void
  onOpen: (code: string) => void
  onShowProgress: () => void
}) {
  const { t } = useTranslation()
  const narrowed = query.trim() !== '' || filter !== 'all' || kind !== DEFAULT_KIND
  return (
    <>
      {totals && (
        <button
          className={`${styles.summary} ${styles.summaryButton}`}
          onClick={onShowProgress}
          title={t('setCompletion.summary.progressTitle')}
        >
          {(() => {
            // Headline is the DISTINCT figure (reprints deduped by name) so it answers "how much of
            // Magic is covered" rather than "how many booster printings across all sets" — the latter
            // double-counts a staple once per set. Printings stay as a secondary line. Until the
            // summary endpoint loads we fall back to the printing-based sum of the per-set rows.
            const pct = summary ? summary.distinctPercent : totals.percent
            return (
              <>
                <div className={styles.summaryStat}>
                  <span className={styles.summaryValue}>{fmtPct(pct)}%</span>
                  <span className={styles.summaryLabel}>{t('setCompletion.summary.overall')}</span>
                </div>
                <div className={styles.summaryBarWrap}>
                  <div className={styles.summaryBarTrack}>
                    <div className={styles.summaryBarFill} style={{ width: `${Math.min(100, pct)}%` }} />
                  </div>
                  <div className={styles.summaryMeta}>
                    {summary ? (
                      <>
                        <span>
                          <strong>{fmtCount(summary.distinctImplemented)}</strong> /{' '}
                          {fmtCount(summary.distinctTotal)} {t('setCompletion.summary.distinctBooster')}
                        </span>
                        <span>
                          + <strong>{fmtCount(summary.extraDistinctImplemented)}</strong> /{' '}
                          {fmtCount(summary.extraDistinctTotal)} {t('setCompletion.summary.distinctExtras')}
                        </span>
                        <span>
                          <strong>{fmtCount(summary.printingsImplemented)}</strong> /{' '}
                          {fmtCount(summary.printingsTotal)} {t('setCompletion.summary.boosterPrintings')}
                        </span>
                        <span>
                          {t('setCompletion.summary.completeSets', { complete: summary.setsComplete, total: summary.setCount })}
                          {summary.distinctNotPlanned > 0 && (
                            <span
                              className={styles.summaryNote}
                              title={t('setCompletion.summary.notPlannedTitle')}
                            >
                              {' '}({t('setCompletion.summary.notPlannedCount', { count: summary.distinctNotPlanned })})
                            </span>
                          )}
                        </span>
                      </>
                    ) : (
                      <>
                        <span>
                          <strong>{fmtCount(totals.implemented)}</strong> / {fmtCount(totals.total)}{' '}
                          {t('setCompletion.summary.boosterPrintingsImplemented')}
                        </span>
                        <span>
                          {t('setCompletion.summary.completeSets', { complete: totals.complete, total: totals.setCount })}
                        </span>
                      </>
                    )}
                  </div>
                </div>
                <span className={styles.summaryHint}>{t('setCompletion.summary.viewProgress')}</span>
              </>
            )
          })()}
        </button>
      )}

      <div className={styles.controls}>
        <input
          className={styles.search}
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('setCompletion.filters.searchPlaceholder')}
          aria-label={t('setCompletion.filters.searchLabel')}
        />
        <div className={styles.segmented} role="group" aria-label={t('setCompletion.filters.completionLabel')}>
          {(Object.keys(FILTER_LABEL_KEYS) as Filter[]).map((f) => (
            <button
              key={f}
              className={filter === f ? styles.segmentActive : styles.segment}
              aria-pressed={filter === f}
              onClick={() => setFilter(f)}
            >
              {t(FILTER_LABEL_KEYS[f])}
            </button>
          ))}
        </div>
        {/* A dropdown, not a fifth row of segments: sort is set once and left, and five always-visible
            buttons cost the width the filters need. */}
        <label className={styles.sortSelect}>
          <span className={styles.sortLabel}>{t('setCompletion.filters.sort')}</span>
          <select value={sort} onChange={(e) => setSort(e.target.value as SortKey)} aria-label={t('setCompletion.filters.sortLabel')}>
            {(Object.keys(SORT_LABEL_KEYS) as SortKey[]).map((k) => (
              <option key={k} value={k}>
                {t(SORT_LABEL_KEYS[k])}
              </option>
            ))}
          </select>
        </label>
      </div>

      {sets && (
        <div className={styles.kindChips} role="group" aria-label={t('setCompletion.filters.kindLabel')}>
          <button
            className={styles.kindChip}
            aria-pressed={kind === 'all'}
            onClick={() => setKindFilter('all')}
          >
            {t('setCompletion.filters.allTypes')} <span className={styles.kindCount}>{preKindCount}</span>
          </button>
          {SET_KIND_ORDER.map((k) => {
            const n = kindCounts.get(k) ?? 0
            // A kind the catalog has no set of is noise; one the *other* filters emptied stays, at 0,
            // so the chip row doesn't jump around while you type.
            if (n === 0 && !sets.some((s) => setKind(s.setType) === k)) return null
            return (
              <button
                key={k}
                className={styles.kindChip}
                data-kind={k}
                aria-pressed={kind === k}
                disabled={n === 0 && kind !== k}
                onClick={() => setKindFilter(kind === k ? 'all' : k)}
                title={t(KIND_DESCRIPTION_KEYS[k])}
              >
                <span className={styles.kindDot} aria-hidden />
                {t(KIND_LABEL_KEYS[k])} <span className={styles.kindCount}>{n}</span>
              </button>
            )
          })}
        </div>
      )}

      {error && <div className={styles.error}>{t('setCompletion.errors.coverage', { error })}</div>}
      {!sets && !error && <div className={styles.loading}>{t('setCompletion.errors.loadingCoverage')}</div>}

      {sets && (
        <>
          {narrowed && visible.length > 0 && <ResultsLine visible={visible} onClear={onClearFilters} />}
          {visible.length === 0 ? (
            <div className={styles.empty}>
              {query
                ? t('setCompletion.filters.noMatches', { query })
                : t('setCompletion.filters.noMatchesWithoutQuery')}{' '}
              <button className={styles.linkButton} onClick={onClearFilters}>
                {t('setCompletion.filters.clear')}
              </button>
            </div>
          ) : (
            <div className={styles.grid}>
              {visible.map((s) => (
                <SetCard
                  key={s.code}
                  set={s}
                  aiRead={aiRead?.[s.code] ?? null}
                  onOpen={() => onOpen(s.code)}
                  onOpenAiGaps={() => onOpenAiGaps(s.code)}
                />
              ))}
            </div>
          )}
        </>
      )}
    </>
  )
}

/**
 * What the current filters add up to. The banner is the whole catalog; once you narrow to, say,
 * Commander sets, the question becomes "how far along are *these*", and summing the cards in view
 * answers it without sending you to add up tiles.
 */
function ResultsLine({ visible, onClear }: { visible: readonly SetCoverage[]; onClear: () => void }) {
  const { t } = useTranslation()
  const implemented = visible.reduce((n, s) => n + s.implemented, 0)
  const total = visible.reduce((n, s) => n + s.total, 0)
  const complete = visible.filter((s) => s.percent >= 100).length
  const pct = total === 0 ? 0 : Math.round((implemented * 1000) / total) / 10
  return (
    <div className={styles.resultsLine} aria-live="polite">
      <span>
        <strong>{t('setCompletion.results.set', { count: visible.length })}</strong>
      </span>
      <span>
        <strong>{fmtCount(implemented)}</strong> / {fmtCount(total)} {t('setCompletion.results.boosterPrintings')} ·{' '}
        {fmtPct(pct)}%
      </span>
      <span>
        <strong>{complete}</strong> {t('setCompletion.results.complete')}
      </span>
      <button className={styles.linkButton} onClick={onClear}>
        {t('setCompletion.filters.clear')}
      </button>
    </div>
  )
}

/** The per-set type badge — absent on main sets, which are the default and most of the list. */
function KindBadge({ setType }: { setType: string | null }) {
  const { t } = useTranslation()
  const key = setTypeLabelKey(setType)
  const label = key ? t(key) : setType && setKind(setType) !== 'main' ? setType.replace(/_/g, ' ') : null
  if (!label) return null
  const k = setKind(setType)
  return (
    <span className={styles.kindBadge} data-kind={k} title={t(KIND_DESCRIPTION_KEYS[k])}>
      {label}
    </span>
  )
}

function SetCard({
  set,
  aiRead,
  onOpen,
  onOpenAiGaps,
}: {
  set: SetCoverage
  aiRead: SetAiReading | null
  onOpen: () => void
  onOpenAiGaps: () => void
}) {
  const { t: translate } = useTranslation()
  const t = tier(set.percent)
  const remaining = set.total - set.implemented
  const notPlanned = set.notPlanned + set.extraNotPlanned
  return (
    <button
      className={styles.card}
      data-tier={t}
      data-kind={setKind(set.setType)}
      onClick={onOpen}
      title={translate('setCompletion.set.viewCards', { name: set.name })}
    >
      <SetIcon code={set.code} className={styles.watermark} />
      <div className={styles.cardHead}>
        <SetIcon code={set.code} className={styles.cardIcon} title={set.name} />
        <div className={styles.cardTitle}>
          <span className={styles.cardName} title={set.name}>
            {set.name}
          </span>
          <span className={styles.cardMeta}>
            <span className={styles.codeBadge}>{set.code}</span>
            <span>{year(set.releaseDate)}</span>
            <KindBadge setType={set.setType} />
            {set.block && <span className={styles.block}>{set.block}</span>}
            {set.inStandard && (
              <span className={styles.standardBadge} title={translate('setCompletion.set.standardTitle')}>
                {translate('setCompletion.filters.standard')}
              </span>
            )}
          </span>
        </div>
        <span className={styles.percent}>{fmtPct(set.percent)}%</span>
      </div>

      <div className={styles.barTrack}>
        <div className={styles.barFill} style={{ width: `${Math.min(100, set.percent)}%` }} />
      </div>

      <div className={styles.cardFoot}>
        <span>
          <strong>{fmtCount(set.implemented)}</strong> / {fmtCount(set.total)} {translate(set.extraTotal > 0 ? 'setCompletion.set.booster' : 'setCompletion.set.cards')}
        </span>
        {set.extraTotal > 0 && (
          <span className={styles.extra}>
            {fmtCount(set.extraImplemented)}/{fmtCount(set.extraTotal)} {translate('setCompletion.set.extra')}
          </span>
        )}
        {notPlanned > 0 && (
          <span
            className={styles.notPlannedTag}
            title={translate('setCompletion.set.notPlanned', { count: notPlanned })}
          >
            {notPlanned} {translate('setCompletion.set.notPlannedLabel')}
          </span>
        )}
        {/* Only worth the pixels when there is free work: a "0 Assay-ready" tag on 900 sets would
            bury the sets where the number is the reason to open them. Null (no ledger) hides too. */}
        {set.assayReady != null && set.assayReady > 0 && (
          <span
            className={styles.assayTag}
            title={translate('setCompletion.set.assayReadyTitle', { count: set.assayReady })}
          >
            ⚡ {set.assayReady} {translate('setCompletion.set.assayReady')}
          </span>
        )}
        <AiReadTag reading={aiRead} onOpen={onOpenAiGaps} />
        {t === 'done' ? (
          <span className={styles.doneTag}>{translate('setCompletion.set.complete')}</span>
        ) : (
          <span className={styles.remaining}>{translate('setCompletion.set.remaining', { count: remaining })}</span>
        )}
      </div>
    </button>
  )
}

/**
 * The per-set AI card-reading share, polled until the server's background index is ready.
 *
 * Its own request, never part of the coverage payload: the server builds the index lazily the first
 * time anyone asks (several seconds over the whole corpus), and the set grid must not wait on it. So
 * the grid renders at once and the AI tags fill in when the index lands. Null until then, or if the
 * endpoint is unavailable — the tags simply don't appear.
 */
function useAiReading(): Readonly<Record<string, SetAiReading>> | null {
  const [sets, setSets] = useState<Readonly<Record<string, SetAiReading>> | null>(null)
  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let tries = 0
    const poll = () => {
      fetchAiReadingSummary()
        .then((d) => {
          if (cancelled) return
          if (d.ready) setSets(d.sets)
          else if (++tries < 40) timer = setTimeout(poll, 1500)
        })
        .catch(() => {}) // advisory: no tags rather than an error banner
    }
    poll()
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [])
  return sets
}

function aiReadPercent(r: SetAiReading): number {
  return r.total === 0 ? 0 : Math.floor((r.readFully * 100) / r.total)
}

type AiBand = 'good' | 'fair' | 'low'

/**
 * The AI-reading score's band. Fixed thresholds rather than relative ones, so a set's band means
 * the same thing next month and a set climbs out of "needs attention" by being fixed, not by its
 * neighbours getting worse. Placed against today's spread (most sets read 55–75% of their cards
 * fully): under 55% the AI is guessing on nearly every other card, and those are the sets to work
 * first. Raise them as the readers improve.
 */
function aiBand(pct: number): AiBand {
  return pct >= 75 ? 'good' : pct >= 55 ? 'fair' : 'low'
}

/** Colour is never the only cue: each band carries its own glyph and a word in the tooltip. */
const AI_BAND = {
  good: { icon: '✓', wordKey: 'setCompletion.ai.bandWellRead' },
  fair: { icon: '◐', wordKey: 'setCompletion.ai.bandPartlyRead' },
  low: { icon: '!', wordKey: 'setCompletion.ai.bandNeedsAttention' },
} as const satisfies Record<AiBand, { icon: string; wordKey: string }>

/**
 * "AI 82%" on a set tile: the set's AI-reading score — of its authored cards, the share the engine
 * AI's static readers read fully. Banded by colour and glyph so the sets needing attention stand
 * out in the grid; clicking it opens the set straight on its AI gaps. Absent until the index is
 * ready and on sets with nothing authored, so it never claims a number nobody measured.
 *
 * A span with a click handler rather than a button: the whole tile is already a button, and a
 * button inside a button is invalid. Keyboard users reach the same view through the tile and the
 * set's "AI gaps" tab.
 */
function AiReadTag({ reading, onOpen }: { reading: SetAiReading | null; onOpen: () => void }) {
  const { t } = useTranslation()
  if (!reading || reading.total === 0) return null
  const pct = aiReadPercent(reading)
  const band = aiBand(pct)
  const unread = reading.total - reading.readFully
  return (
    <span
      className={styles.aiReadTag}
      data-band={band}
      onClick={(e) => {
        e.stopPropagation()
        onOpen()
      }}
      title={t('setCompletion.ai.scoreTitle', {
        read: reading.readFully,
        total: reading.total,
        band: t(AI_BAND[band].wordKey),
        unread,
      })}
    >
      <span aria-hidden="true">{AI_BAND[band].icon}</span> AI {pct}%
    </span>
  )
}

/** Plain words for an audit check — the group's headline; the effect type stays beside it. */
const AI_CHECK_WORD_KEYS = {
  polarity: 'setCompletion.ai.checkWords.polarity',
  intent: 'setCompletion.ai.checkWords.intent',
  combat: 'setCompletion.ai.checkWords.combat',
  timing: 'setCompletion.ai.checkWords.timing',
  modal: 'setCompletion.ai.checkWords.modal',
} as const

const AI_CHECK_SHORT_KEYS = {
  polarity: 'setCompletion.ai.checkShort.polarity',
  intent: 'setCompletion.ai.checkShort.intent',
  combat: 'setCompletion.ai.checkShort.combat',
  timing: 'setCompletion.ai.checkShort.timing',
  modal: 'setCompletion.ai.checkShort.modal',
} as const

function aiCheckWordKey(check: string) {
  return check in AI_CHECK_WORD_KEYS ? AI_CHECK_WORD_KEYS[check as keyof typeof AI_CHECK_WORD_KEYS] : null
}

function aiCheckShortKey(check: string) {
  return check in AI_CHECK_SHORT_KEYS ? AI_CHECK_SHORT_KEYS[check as keyof typeof AI_CHECK_SHORT_KEYS] : null
}

function notPlannedReasonKey(kind: string) {
  return kind in NOT_PLANNED_REASON_KEYS
    ? NOT_PLANNED_REASON_KEYS[kind as keyof typeof NOT_PLANNED_REASON_KEYS]
    : null
}

/** An audit flag key (`intent: AddCountersEffect`) split into its check and effect type. */
function splitFlag(key: string): { check: string; effectType: string } {
  const at = key.indexOf(': ')
  return at < 0 ? { check: key, effectType: '' } : { check: key.slice(0, at), effectType: key.slice(at + 2) }
}

/** How the coverage view keys a double-faced card: by its front face. */
function frontFace(name: string): string {
  return name.split(' // ')[0] ?? name
}

/** "AI reads 1/3 abilities" on a tile: partly read is ◐, nothing read is !. */
function cardAiBand(card: AiUnreadCard): AiBand {
  return card.abilitiesRead > 0 ? 'fair' : 'low'
}

/**
 * The AI's reading of one hovered card, under its preview: how many abilities the readers
 * understand and each gap in plain words, with the effect type behind it — so a reader scanning the
 * grid sees why a card is here without leaving it. The card's page in the Assay Explorer has the
 * ability-by-ability detail; clicking the tile goes there.
 */
function AiCardPanel({ card, selected }: { card: AiUnreadCard; selected: string | null }) {
  const { t } = useTranslation()
  const band = cardAiBand(card)
  return (
    <div className={styles.aiHoverPanel}>
      <div className={styles.aiHoverHead} data-band={band}>
        <span aria-hidden="true">{AI_BAND[band].icon}</span>{' '}
        {card.abilities > 0 ? (
          <>
            {t('setCompletion.ai.readAbilities', { read: card.abilitiesRead, count: card.abilities })}
          </>
        ) : (
          <>{t('setCompletion.ai.readingHasGaps')}</>
        )}
      </div>
      <ul className={styles.aiHoverList}>
        {card.flags.map((f) => {
          const { check, effectType } = splitFlag(f)
          const wordKey = aiCheckWordKey(check)
          return (
            <li key={f} data-on={selected === f || undefined}>
              <span>{wordKey ? t(wordKey) : check}</span>
              {effectType && <code>{effectType}</code>}
            </li>
          )
        })}
      </ul>
      <div className={styles.aiHoverHint}>{t('setCompletion.ai.clickExplorerHint')}</div>
    </div>
  )
}

/** Rough height of {@link AiCardPanel}, so the preview above it is placed on-screen. */
function aiCardPanelHeight(card: AiUnreadCard): number {
  return 64 + card.flags.length * 38
}

/**
 * What the AI misses in one set: its unread cards grouped by the effect type behind each gap,
 * biggest group first. The groups are the actionable part — one reader entry fixes every card in
 * one, which is the unit `just ai-reading-loop` works on — so they lead, and picking one narrows
 * the card grid below to its cards.
 */
function AiGapsPanel({
  data,
  error,
  selected,
  onSelect,
  onExplore,
}: {
  data: SetAiReadingDetail | null
  error: string | null
  selected: string | null
  onSelect: (key: string | null) => void
  onExplore: (name: string) => void
}) {
  const { t } = useTranslation()
  if (error) return <div className={styles.error}>{t('setCompletion.ai.loadError', { error })}</div>
  if (!data || !data.ready) return <div className={styles.loading}>{t('setCompletion.ai.loading')}</div>
  const score = data.score
  if (!score) return <div className={styles.aiGapsEmpty}>{t('setCompletion.ai.nothingToRead')}</div>
  const pct = aiReadPercent(score)
  const band = aiBand(pct)
  const abilityPct = score.abilities === 0 ? 100 : Math.floor((score.abilitiesRead * 100) / score.abilities)
  const top = data.groups[0]?.cards.length ?? 1
  return (
    <section className={styles.aiGaps} aria-label={t('setCompletion.ai.sectionLabel')}>
      <div className={styles.aiGapsHead}>
        <span className={styles.aiScore} data-band={band}>
          <span aria-hidden="true">{AI_BAND[band].icon}</span> {pct}%
        </span>
        <span className={styles.aiGapsSummary}>
          {t('setCompletion.ai.scoreSummary', {
            read: score.readFully,
            total: score.total,
            band: t(AI_BAND[band].wordKey),
            abilitiesRead: score.abilitiesRead,
            abilities: score.abilities,
            percent: abilityPct,
          })}
          <span className={styles.assayNoteTip}>
            <HelpTip topicId="ai-reading" size="sm" />
          </span>
        </span>
      </div>
      {data.draft && data.draft.nonland > 0 && <DraftRatingsLine draft={data.draft} />}
      {data.groups.length === 0 ? (
        <div className={styles.aiGapsEmpty}>{t('setCompletion.ai.allRead')}</div>
      ) : (
        <>
          <p className={styles.aiGapsLead}>
            {t('setCompletion.ai.groupsIntro')}
          </p>
          <ol className={styles.aiGroups}>
            {data.groups.slice(0, 12).map((g) => {
              const wordKey = aiCheckWordKey(g.check)
              return (
                <li key={g.key}>
                  <button
                    className={styles.aiGroup}
                    data-on={selected === g.key || undefined}
                    aria-pressed={selected === g.key}
                    onClick={() => onSelect(selected === g.key ? null : g.key)}
                  >
                    <span className={styles.aiGroupCount}>{g.cards.length}</span>
                    <span className={styles.aiGroupBar} aria-hidden="true">
                      <span style={{ width: `${Math.max(4, (g.cards.length * 100) / top)}%` }} />
                    </span>
                    <span className={styles.aiGroupText}>
                      <span>{wordKey ? t(wordKey) : g.check}</span>
                      <code>{g.effectType}</code>
                    </span>
                  </button>
                  <span className={styles.aiGroupCards}>
                    {g.cards.slice(0, 4).map((name, i) => (
                      <span key={name}>
                        {i > 0 && ', '}
                        <button
                          className={styles.linkButton}
                          onClick={() => onExplore(name)}
                          title={t('setCompletion.ai.openCardTitle', { name })}
                        >
                          {name}
                        </button>
                      </span>
                    ))}
                    {g.cards.length > 4 && ` +${g.cards.length - 4}`}
                  </span>
                </li>
              )
            })}
          </ol>
          {data.groups.length > 12 && (
            <p className={styles.aiGapsLead}>{t('setCompletion.ai.moreGroups', { count: data.groups.length - 12 })}</p>
          )}
        </>
      )}
    </section>
  )
}

/**
 * Where the draft AI's pick numbers for this set come from, as one bar: the share its own pick
 * table rates (solid), the share borrowed from another set's table or 17Lands (hatched), and the
 * share nothing rates at all, which the effect heuristic guesses (amber) — the part worth seeing
 * before trusting how the AI drafts the set.
 */
function DraftRatingsLine({ draft }: { draft: SetDraftRatings }) {
  const { t } = useTranslation()
  const filled = draft.nonland - draft.rated
  const borrowed = filled - draft.heuristic
  const pct = (n: number) => (n * 100) / draft.nonland
  return (
    <div className={styles.draftRatings}>
      <span className={styles.draftRatingsLabel}>{t('setCompletion.ai.draftPicks')}</span>
      <span
        className={styles.draftRatingsBar}
        role="img"
        aria-label={t('setCompletion.ai.draftRatingsAria', {
          rated: draft.rated,
          borrowed,
          heuristic: draft.heuristic,
        })}
      >
        <span className={styles.draftRatingsTable} style={{ width: `${pct(draft.rated)}%` }} />
        <span className={styles.draftRatingsFallback} style={{ width: `${pct(borrowed)}%` }} />
        <span className={styles.draftRatingsGuess} style={{ width: `${pct(draft.heuristic)}%` }} />
      </span>
      <span className={styles.draftRatingsText}>
        {draft.hasTable ? (
          <>
            {t('setCompletion.ai.pickTableRates', { rated: draft.rated, total: draft.nonland })}
          </>
        ) : (
          <>{t('setCompletion.ai.noPickTable')}</>
        )}
        {borrowed > 0 && <> · {t('setCompletion.ai.borrowed', { count: borrowed })}</>}
        {draft.heuristic > 0 && (
          <span className={styles.draftRatingsWarn}>
            {' · '}
            {t('setCompletion.ai.heuristic', { count: draft.heuristic })}
          </span>
        )}
        {draft.archetypes && ` · ${t('setCompletion.ai.archetypeAware')}`}
        <span className={styles.assayNoteTip}>
          <HelpTip topicId="ai-rating" size="sm" />
        </span>
      </span>
    </div>
  )
}

type CardFilter = 'all' | 'implemented' | 'missing' | 'assayReady' | 'aiGaps' | 'notPlanned'

const CARD_FILTER_LABEL_KEYS = {
  all: 'setCompletion.details.filterAll',
  implemented: 'setCompletion.details.filterImplemented',
  missing: 'setCompletion.details.filterMissing',
  assayReady: 'setCompletion.details.filterAssayReady',
  aiGaps: 'setCompletion.details.filterAiGaps',
  notPlanned: 'setCompletion.details.filterNotPlanned',
} as const satisfies Record<CardFilter, string>

function SetDetailOverlay({
  code,
  setType,
  aiRead,
  initialFilter,
  onExplore,
  onClose,
}: {
  code: string
  /** From the set list — the detail endpoint doesn't repeat it. */
  setType: string | null
  /** The AI card-reading share, once the server has indexed it; null until then or with no cards. */
  aiRead: SetAiReading | null
  /** The filter to open on — `aiGaps` when the set was opened from its AI score. */
  initialFilter: CardFilter
  /** Open a card in the framed Assay Explorer. */
  onExplore: (name: string) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [detail, setDetail] = useState<SetDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [cardFilter, setCardFilter] = useState<CardFilter>(initialFilter)
  // The set's AI gaps load only when that tab is opened, and once per set.
  const [aiDetail, setAiDetail] = useState<SetAiReadingDetail | null>(null)
  const [aiError, setAiError] = useState<string | null>(null)
  const [aiGroup, setAiGroup] = useState<string | null>(null)
  const [hover, setHover] = useState<HoverState | null>(null)
  const aiReady = aiDetail?.ready === true
  useEffect(() => {
    if (cardFilter !== 'aiGaps' || aiReady) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const load = () =>
      fetchSetAiReading(code)
        .then((d) => {
          if (cancelled) return
          setAiDetail(d)
          if (!d.ready) timer = setTimeout(load, 1500) // the server's index is still building
        })
        .catch((e) => !cancelled && setAiError(e instanceof Error ? e.message : String(e)))
    load()
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [cardFilter, code, aiReady])
  const aiUnread = useMemo(() => {
    if (!aiDetail?.ready) return null
    const names = aiGroup
      ? (aiDetail.groups.find((g) => g.key === aiGroup)?.cards ?? [])
      : aiDetail.unread.map((u) => u.name)
    return new Set(names.map(frontFace))
  }, [aiDetail, aiGroup])
  // Each unread card's own reading, for its tile and hover panel — only while the AI gaps tab is up,
  // so the other tabs' tiles keep their plain implemented/missing look.
  const aiCards = useMemo(() => {
    if (cardFilter !== 'aiGaps' || !aiDetail?.ready) return null
    return new Map(aiDetail.unread.map((u) => [frontFace(u.name), u] as const))
  }, [cardFilter, aiDetail])
  const hoverAi = hover && aiCards ? (aiCards.get(frontFace(hover.name)) ?? null) : null

  useEffect(() => {
    let cancelled = false
    setDetail(null)
    setError(null)
    setAiDetail(null)
    setAiGroup(null)
    fetchSetDetail(code)
      .then((d) => !cancelled && setDetail(d))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [code])

  // Close on Escape.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // Stable handler + memoized filtered lists so that a hover (high-frequency mousemove) only
  // re-renders the floating preview — not the whole ~280-tile grid. The position update is
  // coalesced to one per animation frame so a fast mousemove can't outrun the paint loop.
  const rafRef = useRef<number | null>(null)
  const pendingHover = useRef<HoverState | null>(null)
  const onHover = useCallback((h: HoverState | null) => {
    if (h === null) {
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current)
      rafRef.current = null
      pendingHover.current = null
      setHover(null)
      return
    }
    pendingHover.current = h
    if (rafRef.current == null) {
      rafRef.current = requestAnimationFrame(() => {
        rafRef.current = null
        if (pendingHover.current) setHover(pendingHover.current)
      })
    }
  }, [])
  useEffect(() => () => {
    if (rafRef.current != null) cancelAnimationFrame(rafRef.current)
  }, [])
  // "Missing" means still to do, so the not-planned cards live in their own bucket rather than
  // padding a list of work that will never happen.
  const matches = useCallback(
    (c: CardCoverage) => {
      switch (cardFilter) {
        case 'implemented':
          return c.implemented
        case 'missing':
          return !c.implemented && c.notPlanned === null
        case 'assayReady':
          return isAssayReady(c)
        case 'notPlanned':
          return c.notPlanned !== null
        case 'aiGaps':
          return c.implemented && aiUnread !== null && aiUnread.has(frontFace(c.name))
        default:
          return true
      }
    },
    [cardFilter, aiUnread],
  )
  const draftCards = useMemo(() => detail?.draft.filter(matches) ?? [], [detail, matches])
  // Each extras section keeps its own tally, so filtering to "Missing" still shows a section as
  // e.g. 0/13 done rather than making the reader count tiles.
  const extraSections = useMemo(
    () => detail?.extraGroups.map((g) => ({ group: g, cards: g.cards.filter(matches) })) ?? [],
    [detail, matches],
  )
  const notPlannedCount = detail ? detail.notPlanned + detail.extraNotPlanned : 0

  // One list drives both the index and the body, so a section can never appear in the rail without
  // a heading to jump to (or vice versa).
  const sections = useMemo(
    () =>
      detail
        ? [
            {
              label: t(extraSections.length > 0 ? 'setCompletion.details.draftCards' : 'setCompletion.details.cards'),
              cards: draftCards,
              total: detail.draft.length,
              done: detail.implemented,
              of: detail.total,
            },
            ...extraSections.map(({ group, cards }) => ({
              label: group.label,
              cards,
              total: group.cards.length,
              done: group.implemented,
              of: group.total,
            })),
          ]
        : [],
    [detail, draftCards, extraSections, t],
  )

  // The index stays put while the card grid scrolls under it, so it needs the scroller and each
  // heading's node to translate a click into a scroll offset.
  const bodyRef = useRef<HTMLDivElement>(null)
  const sectionNodes = useRef(new Map<string, HTMLElement>())
  const [activeSection, setActiveSection] = useState<string | null>(null)
  const registerSection = useCallback((label: string, el: HTMLElement | null) => {
    if (el) sectionNodes.current.set(label, el)
    else sectionNodes.current.delete(label)
  }, [])

  const jumpTo = useCallback((label: string) => {
    const body = bodyRef.current
    const el = sectionNodes.current.get(label)
    if (!body || !el) return
    const top = body.scrollTop + el.getBoundingClientRect().top - body.getBoundingClientRect().top
    body.scrollTo({ top, behavior: 'smooth' })
  }, [])

  // Highlight whichever section the reader is actually in: the last one whose heading has reached
  // the top of the scroller. Coalesced to one measurement per frame — scroll fires far faster.
  useEffect(() => {
    const body = bodyRef.current
    if (!body || sections.length < 2) return
    let raf: number | null = null
    const measure = () => {
      raf = null
      const bodyTop = body.getBoundingClientRect().top
      let current: string | null = null
      for (const s of sections) {
        const el = sectionNodes.current.get(s.label)
        if (el && el.getBoundingClientRect().top - bodyTop <= 8) current = s.label
      }
      setActiveSection(current ?? sections[0]?.label ?? null)
    }
    const onScroll = () => {
      if (raf == null) raf = requestAnimationFrame(measure)
    }
    measure()
    body.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      body.removeEventListener('scroll', onScroll)
      if (raf != null) cancelAnimationFrame(raf)
    }
  }, [sections])

  const coverageTier = detail ? tier(detail.percent) : 'empty'

  return (
    <div className={styles.overlayBackdrop} onClick={onClose}>
      <div className={styles.overlay} data-tier={coverageTier} onClick={(e) => e.stopPropagation()}>
        <header className={styles.overlayHeader}>
          <SetIcon code={code} className={styles.overlayIcon} />
          <div className={styles.overlayTitleBlock}>
            <span className={styles.overlayTitle}>{detail?.name ?? code}</span>
            <span className={styles.cardMeta}>
              <span className={styles.codeBadge}>{code}</span>
              {detail && <span>{year(detail.releaseDate)}</span>}
              <KindBadge setType={setType} />
              {detail?.block && <span className={styles.block}>{detail.block}</span>}
            </span>
          </div>
          {detail && (
            <div className={styles.overlayStats}>
              <span className={styles.percent}>{fmtPct(detail.percent)}%</span>
              <span className={styles.overlayCounts}>
                <strong>{fmtCount(detail.implemented)}</strong>/{fmtCount(detail.total)} {t(detail.extraTotal > 0 ? 'setCompletion.set.booster' : 'setCompletion.set.cards')}
                {detail.extraTotal > 0 && (
                  <>
                    {' · '}
                    {fmtCount(detail.extraImplemented)}/{fmtCount(detail.extraTotal)} {t('setCompletion.details.countExtra')}
                  </>
                )}
                {notPlannedCount > 0 && <> · {notPlannedCount} {t('setCompletion.details.notPlanned')}</>}
                {detail.assayReady != null && detail.assayReady > 0 && (
                  <span className={styles.assayNote}>
                    {' · '}⚡ {detail.assayReady} {t('setCompletion.details.assayReady')}
                    <span className={styles.assayNoteTip}>
                      <HelpTip topicId="assay-ready" size="sm" />
                    </span>
                  </span>
                )}
                {aiRead && aiRead.total > 0 && (
                  <span className={styles.aiReadNote}>
                    {' · '}
                    <button
                      className={styles.aiReadNoteButton}
                      data-band={aiBand(aiReadPercent(aiRead))}
                      onClick={() => setCardFilter('aiGaps')}
                      title={t('setCompletion.ai.showGapsTitle')}
                    >
                      <span aria-hidden="true">{AI_BAND[aiBand(aiReadPercent(aiRead))].icon}</span>{' '}
                      {t('setCompletion.details.aiReadCards', {
                        read: aiRead.readFully,
                        total: aiRead.total,
                        percent: aiReadPercent(aiRead),
                      })}
                    </button>
                  </span>
                )}
              </span>
            </div>
          )}
          {detail && detail.implemented > 0 && (
            <Link
              className={styles.overlayBrowse}
              to={`/cards?q=${encodeURIComponent(`s:${code.toLowerCase()}`)}`}
              title={t('setCompletion.details.browseCardsTitle', { name: detail.name })}
            >
              {t('setCompletion.details.browseCards')}
            </Link>
          )}
          <button className={styles.overlayClose} onClick={onClose} aria-label={t('common.close')}>
            ✕
          </button>
        </header>

        {detail && (
          <div className={styles.barTrack} style={{ flexShrink: 0 }}>
            <div className={styles.barFill} style={{ width: `${Math.min(100, detail.percent)}%` }} />
          </div>
        )}

        <div className={styles.overlayControls}>
          {(Object.keys(CARD_FILTER_LABEL_KEYS) as CardFilter[])
            // The not-planned tab only exists for the handful of sets that have such a card.
            .filter((f) => f !== 'notPlanned' || notPlannedCount > 0)
            // The Assay tab needs a baked ledger to mean anything. Hidden when `assayReady` is null
            // (nobody ran `just assay-bake`); shown at zero, because "no free work left in this set"
            // is a real answer and worth being able to read.
            .filter((f) => f !== 'assayReady' || detail?.assayReady != null)
            // AI gaps is only worth a tab once something in the set is implemented.
            .filter((f) => f !== 'aiGaps' || (detail?.implemented ?? 0) + (detail?.extraImplemented ?? 0) > 0)
            .map((f) => (
              <button
                key={f}
                className={cardFilter === f ? styles.segmentActive : styles.segment}
                onClick={() => setCardFilter(f)}
                title={
                  f === 'assayReady'
                    ? t('setCompletion.details.assayReadyTitle')
                    : f === 'aiGaps'
                      ? t('setCompletion.details.aiGapsTitle')
                      : undefined
                }
              >
                {t(CARD_FILTER_LABEL_KEYS[f])}
                {f === 'notPlanned' && ` (${notPlannedCount})`}
                {f === 'assayReady' && ` (${detail?.assayReady ?? 0})`}
                {f === 'aiGaps' && aiRead && ` (${aiRead.total - aiRead.readFully})`}
              </button>
            ))}
        </div>

        <div className={styles.overlayLayout}>
          {/* Pinned beside the grid rather than hidden in a menu (Scryfall's own set pages use a
              dropdown): what a set contains stays readable without scrolling a 280-tile grid. */}
          {sections.length > 1 && (
            <nav className={styles.sectionIndex} aria-label={t('setCompletion.details.sections', { code })}>
              {sections.map((s) => (
                <button
                  key={s.label}
                  className={activeSection === s.label ? styles.indexItemActive : styles.indexItem}
                  onClick={() => jumpTo(s.label)}
                >
                  <span className={styles.indexLabel}>{s.label}</span>
                  <span className={styles.indexCount}>
                    {s.done}/{s.of}
                  </span>
                </button>
              ))}
            </nav>
          )}

          <div className={styles.overlayBody} ref={bodyRef}>
            {error && <div className={styles.error}>{t('setCompletion.details.loadCardsError', { error })}</div>}
            {!detail && !error && <div className={styles.loading}>{t('setCompletion.details.loadingCards', { code })}</div>}
            {cardFilter === 'aiGaps' && (
              <AiGapsPanel
                data={aiDetail}
                error={aiError}
                selected={aiGroup}
                onSelect={setAiGroup}
                onExplore={onExplore}
              />
            )}
            {/* Scryfall's own set-page sectioning: the draftable pool first, then each
                completionist product that added cards of its own. */}
            {sections.map((s) => (
              <CardSection
                key={s.label}
                title={s.label}
                cards={s.cards}
                total={s.total}
                done={s.done}
                of={s.of}
                onHover={onHover}
                register={registerSection}
                ai={aiCards}
                aiGroup={aiGroup}
                onExplore={onExplore}
              />
            ))}
          </div>
        </div>
      </div>
      {hover && (
        <HoverCardPreview
          name={hover.name}
          imageUri={hover.imageUri}
          pos={hover.pos}
          imageRotateDeg={hover.isLandscape ? 90 : 0}
          extraHeight={hoverAi ? aiCardPanelHeight(hoverAi) : 0}
        >
          {hoverAi && <AiCardPanel card={hoverAi} selected={aiGroup} />}
        </HoverCardPreview>
      )}
    </div>
  )
}

const CardSection = memo(function CardSection({
  title,
  cards,
  total,
  done,
  of,
  onHover,
  register,
  ai,
  aiGroup,
  onExplore,
}: {
  title: string
  /** The cards passing the active filter — what actually renders. */
  cards: readonly CardCoverage[]
  /** Cards in the section before filtering; a section with none never renders at all. */
  total: number
  /** Implemented count for the whole section, so the tally survives filtering. */
  done: number
  /** Section cards we intend to build (not-planned ones excluded). */
  of: number
  onHover: (h: HoverState | null) => void
  /** Publishes this section's node so the index can scroll to it and track it. */
  register: (label: string, el: HTMLElement | null) => void
  /** On the AI gaps tab: each unread card's reading, by front-face name. Null on every other tab. */
  ai: ReadonlyMap<string, AiUnreadCard> | null
  /** The gap group picked in the AI gaps panel, whose chip each tile highlights. */
  aiGroup: string | null
  onExplore: (name: string) => void
}) {
  const { t } = useTranslation()
  if (total === 0) return null
  return (
    <section className={styles.cardSection} ref={(el) => register(title, el)}>
      <h3 className={styles.sectionTitle}>
        {title}{' '}
        <span className={styles.sectionCount}>
          {done}/{of}
        </span>
      </h3>
      {cards.length === 0 ? (
        <div className={styles.sectionEmpty}>{t('setCompletion.details.nothingForFilter')}</div>
      ) : (
        <div className={styles.cardImageGrid}>
          {cards.map((c) => (
            <CardTile
              key={c.name}
              card={c}
              onHover={onHover}
              ai={ai?.get(frontFace(c.name)) ?? null}
              aiGroup={aiGroup}
              onExplore={onExplore}
            />
          ))}
        </div>
      )}
    </section>
  )
})

const CardTile = memo(function CardTile({
  card,
  onHover,
  ai,
  aiGroup,
  onExplore,
}: {
  card: CardCoverage
  onHover: (h: HoverState | null) => void
  /** The AI's reading of this card, on the AI gaps tab; the tile then opens it in the explorer. */
  ai: AiUnreadCard | null
  aiGroup: string | null
  onExplore: (name: string) => void
}) {
  const { t } = useTranslation()
  // A not-planned card is neither done nor outstanding, so it gets its own look and always
  // explains itself — the reason travels with the flag from the manifest to this tooltip.
  const className = card.implemented
    ? styles.tile
    : card.notPlanned
      ? styles.tileNotPlanned
      : styles.tileMissing
  const ready = isAssayReady(card)
  const reasonKey = card.notPlanned ? notPlannedReasonKey(card.notPlanned.kind) : null
  const assay = card.assay
  const assayText = !assay
    ? null
    : assay.readsWhole
      ? t(card.implemented ? 'setCompletion.assay.readsWhole' : 'setCompletion.assay.implementable')
      : t('setCompletion.assay.declines', { kind: assay.kind, line: assay.line ? ` — “${assay.line}”` : '' })
  // Assay's reading is on every tooltip but only badges the actionable case. A chip on all ~280
  // tiles would be wallpaper; the one state worth spotting across a grid is "nobody built this and
  // the grammar already reads it", which is a card you could pick up right now.
  const tooltip = [
    card.notPlanned
      ? t('setCompletion.set.notPlannedCardTitle', {
          name: card.name,
          reason: reasonKey ? t(reasonKey) : card.notPlanned.kind,
        })
      : card.name,
    assayText,
  ]
    .filter(Boolean)
    .join('\n')
  const hoverAt = (e: ReactMouseEvent) =>
    onHover({ name: card.name, imageUri: card.imageUri, isLandscape: card.isLandscape === true, pos: { x: e.clientX, y: e.clientY } })
  if (ai) return <AiCardTile card={card} ai={ai} aiGroup={aiGroup} onHover={onHover} hoverAt={hoverAt} onExplore={onExplore} />
  return (
    <div
      className={className}
      data-assay-ready={ready || undefined}
      title={tooltip}
      onMouseEnter={(e) => onHover({ name: card.name, imageUri: card.imageUri, isLandscape: card.isLandscape === true, pos: { x: e.clientX, y: e.clientY } })}
      onMouseMove={(e) => onHover({ name: card.name, imageUri: card.imageUri, isLandscape: card.isLandscape === true, pos: { x: e.clientX, y: e.clientY } })}
      onMouseLeave={() => onHover(null)}
    >
      <img
        className={styles.tileImage}
        src={smallArt(card.name, card.imageUri)}
        alt={card.name}
        loading="lazy"
        decoding="async"
      />
      <span className={styles.tileBadge}>
        {card.implemented
          ? '✓'
          : card.notPlanned
            ? reasonKey
              ? t(reasonKey)
              : card.notPlanned.kind
            : t('setCompletion.details.filterMissing')}
      </span>
      {ready && (
        <span className={styles.assayBadge} aria-label={t('setCompletion.set.badgeAssayReads')}>
          ⚡
        </span>
      )}
      <span className={styles.tileName}>{card.name}</span>
    </div>
  )
})

/**
 * A card tile on the AI gaps tab. It answers "why is this card here" at a glance — the ability
 * count the AI reads in the badge, one chip per kind of gap along the bottom — and the hover panel
 * spells each gap out. The whole tile is a button: what to do with a gap is look at where it is,
 * and that is the card's page in the Assay Explorer.
 */
function AiCardTile({
  card,
  ai,
  aiGroup,
  onHover,
  hoverAt,
  onExplore,
}: {
  card: CardCoverage
  ai: AiUnreadCard
  aiGroup: string | null
  onHover: (h: HoverState | null) => void
  hoverAt: (e: ReactMouseEvent) => void
  onExplore: (name: string) => void
}) {
  const { t } = useTranslation()
  const band = cardAiBand(ai)
  // One chip per kind of gap, not per flag: a card missing two intent readings says "Purpose ×2"
  // rather than spending the tile's width on the same word twice.
  const checks = new Map<string, { count: number; on: boolean }>()
  for (const f of ai.flags) {
    const { check } = splitFlag(f)
    const c = checks.get(check) ?? { count: 0, on: false }
    checks.set(check, { count: c.count + 1, on: c.on || f === aiGroup })
  }
  const readSummary = ai.abilities > 0
    ? t('setCompletion.ai.readAbilities', { read: ai.abilitiesRead, count: ai.abilities })
    : t('setCompletion.ai.readingHasGaps')
  const missesSummary = ai.flags.map((f) => {
    const { check, effectType } = splitFlag(f)
    const wordKey = aiCheckWordKey(check)
    return `${wordKey ? t(wordKey) : check}${effectType ? ` (${effectType})` : ''}`
  }).join('; ')
  return (
    <button
      type="button"
      className={`${styles.tile} ${styles.tileAi}`}
      aria-label={t('setCompletion.ai.cardTileAria', {
        name: card.name,
        summary: t('setCompletion.ai.misses', { read: readSummary, misses: missesSummary }),
      })}
      onClick={() => {
        onHover(null)
        onExplore(card.name)
      }}
      onMouseEnter={hoverAt}
      onMouseMove={hoverAt}
      onMouseLeave={() => onHover(null)}
    >
      <img
        className={styles.tileImage}
        src={smallArt(card.name, card.imageUri)}
        alt=""
        loading="lazy"
        decoding="async"
      />
      <span className={styles.tileAiBadge} data-band={band} aria-hidden="true">
        {AI_BAND[band].icon}
        {ai.abilities > 0 && ` ${ai.abilitiesRead}/${ai.abilities}`}
      </span>
      <span className={styles.tileAiChips} aria-hidden="true">
        {[...checks].map(([check, { count, on }]) => {
          const shortKey = aiCheckShortKey(check)
          return (
            <span key={check} className={styles.tileAiChip} data-on={on || undefined}>
              {shortKey ? t(shortKey) : check}
              {count > 1 && ` ×${count}`}
            </span>
          )
        })}
      </span>
    </button>
  )
}

/**
 * The Set guide, at `/sets` — every fully playable set with how its Limited games play, what wins
 * them, what its mechanics do to a game, and power / speed / complexity ratings, so a player can pick a set for sealed or draft
 * without knowing Magic's back catalogue.
 *
 * Two views over the same filtered list: a grid of profile tiles, and a map that places each set by
 * speed and complexity and tints it by power. Any set can be opened up close, and up to four can be
 * compared side by side. The sort, view, open set and comparison live in the URL so a comparison is
 * a link worth sending; the filters are throwaway.
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { listPlayableSets } from '@/api/playableSets'
import { PageShell, pageStyles } from '@/components/ui/PageShell'
import { SetIcon } from '@/components/ui/SetIcon'
import { RatingMeters, ThemeTags, TryIf } from './SetProfileParts'
import { SetCompareDialog, SetProfileDialog } from './SetGuideDialogs'
import {
  filterGuideSets,
  GUIDE_SORTS,
  MAX_COMPARE,
  QUICK_FILTERS,
  sortGuideSets,
  toGuideSets,
  type GuideSet,
  type GuideSort,
  type QuickFilter,
} from './setGuideModel'
import { SET_THEMES, type SetTheme } from './setProfiles'
import styles from './SetGuide.module.css'
import { useTranslation } from 'react-i18next'

type View = 'grid' | 'map'

export function SetGuidePage() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  const [sets, setSets] = useState<GuideSet[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    listPlayableSets()
      .then((s) => setSets(toGuideSets(s)))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
  }, [])

  const [search, setSearch] = useState('')
  const [quick, setQuick] = useState<ReadonlySet<QuickFilter>>(new Set())
  const [themes, setThemes] = useState<ReadonlySet<SetTheme>>(new Set())

  const sortParam = params.get('sort')
  const sort: GuideSort = GUIDE_SORTS.some((s) => s.id === sortParam) ? (sortParam as GuideSort) : 'newest'
  const view: View = params.get('view') === 'map' ? 'map' : 'grid'
  const openCode = params.get('set')
  const compareCodes = useMemo(() => (params.get('compare') ?? '').split(',').filter(Boolean).slice(0, MAX_COMPARE), [params])
  const comparing = params.get('comparing') === '1'

  const update = useCallback(
    (edit: (p: URLSearchParams) => void) =>
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          edit(next)
          return next
        },
        { replace: true },
      ),
    [setParams],
  )
  const setParam = (key: string, value: string | null) => update((p) => (value ? p.set(key, value) : p.delete(key)))

  const toggleCompare = useCallback(
    (code: string) =>
      update((p) => {
        const current = (p.get('compare') ?? '').split(',').filter(Boolean)
        const next = current.includes(code) ? current.filter((c) => c !== code) : [...current, code].slice(0, MAX_COMPARE)
        if (next.length > 0) p.set('compare', next.join(','))
        else {
          p.delete('compare')
          p.delete('comparing')
        }
      }),
    [update],
  )

  const toggle = <T,>(setter: (fn: (prev: ReadonlySet<T>) => ReadonlySet<T>) => void, id: T) =>
    setter((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const shown = useMemo(
    () => (sets ? sortGuideSets(filterGuideSets(sets, { search, quick, themes }), sort) : []),
    [sets, search, quick, themes, sort],
  )
  const bySetCode = useMemo(() => new Map((sets ?? []).map((s) => [s.code, s])), [sets])
  const openSet = openCode ? bySetCode.get(openCode) : undefined
  const compareSets = compareCodes.map((c) => bySetCode.get(c)).filter((s): s is GuideSet => s != null)
  const filtersActive = search.trim() !== '' || quick.size > 0 || themes.size > 0
  const clearFilters = () => {
    setSearch('')
    setQuick(new Set())
    setThemes(new Set())
  }

  return (
    <PageShell title={t('setGuide.title')} width="wide">
      <div className={styles.intro}>
        <div>
          <h1 className={pageStyles.h1}>{t('setGuide.heading')}</h1>
          <p className={pageStyles.lede}>
            {sets ? t('setGuide.intro', { count: sets.length }) : t('setGuide.introAll')}
          </p>
        </div>
        <Link to="/set-completion" className={styles.introLink}>
          {t('setGuide.implementationProgress')}
        </Link>
      </div>

      <div className={`${pageStyles.panel} ${styles.controls}`}>
        <div className={styles.controlRow}>
          <input
            type="search"
            className={`${pageStyles.input} ${styles.search}`}
            placeholder={t('setGuide.searchPlaceholder')}
            value={search}
            spellCheck={false}
            onChange={(e) => setSearch(e.target.value)}
            aria-label={t('setGuide.searchAria')}
          />
          <div className={pageStyles.tabs} role="tablist" aria-label={t('setGuide.viewAria')}>
            {(['grid', 'map'] as const).map((v) => (
              <button
                key={v}
                type="button"
                role="tab"
                className={pageStyles.tab}
                aria-selected={view === v}
                onClick={() => setParam('view', v === 'grid' ? null : v)}
              >
                {v === 'grid' ? t('setGuide.sets') : t('setGuide.map')}
              </button>
            ))}
          </div>
          {view === 'grid' && (
            <label className={styles.sortLabel}>
              <span>{t('setGuide.sort')}</span>
              <select
                className={`${pageStyles.input} ${styles.sortSelect}`}
                value={sort}
                onChange={(e) => setParam('sort', e.target.value === 'newest' ? null : e.target.value)}
              >
                {GUIDE_SORTS.map((s) => (
                  <option key={s.id} value={s.id}>{t(`setGuide.sorts.${s.id}`)}</option>
                ))}
              </select>
            </label>
          )}
        </div>
        <div className={styles.filterGroup} aria-label={t('setGuide.quickFilters')}>
          {QUICK_FILTERS.map((q) => (
            <button key={q.id} type="button" className={styles.filterChip} aria-pressed={quick.has(q.id)} onClick={() => toggle(setQuick, q.id)}>
              {t(`setGuide.filters.${q.id}`)}
            </button>
          ))}
          <span className={styles.filterDivider} aria-hidden />
          {SET_THEMES.map((theme) => (
            <button key={theme.id} type="button" className={styles.filterChip} data-kind="theme" title={t(`setGuide.themes.${theme.id}.hint`)} aria-pressed={themes.has(theme.id)} onClick={() => toggle(setThemes, theme.id)}>
              {t(`setGuide.themes.${theme.id}.label`)}
            </button>
          ))}
          {filtersActive && (
            <button type="button" className={styles.clearFilters} onClick={clearFilters}>
              {t('setGuide.clear')}
            </button>
          )}
        </div>
      </div>

      {error ? (
        <div className={pageStyles.panel} role="alert">{t('setGuide.loadError', { error })}</div>
      ) : !sets ? (
        <div className={pageStyles.empty}>{t('setGuide.loading')}</div>
      ) : shown.length === 0 ? (
        <div className={`${pageStyles.panel} ${pageStyles.empty}`}>
          {t('setGuide.noMatches')} <button type="button" className={styles.linkButton} onClick={clearFilters}>{t('setGuide.clearFilters')}</button>
        </div>
      ) : view === 'map' ? (
        <SetMap sets={shown} onOpen={(code) => setParam('set', code)} />
      ) : (
        <ul className={styles.grid}>
          {shown.map((s) => (
            <SetTile
              key={s.code}
              set={s}
              comparing={compareCodes.includes(s.code)}
              compareFull={compareCodes.length >= MAX_COMPARE}
              onOpen={() => setParam('set', s.code)}
              onToggleCompare={() => toggleCompare(s.code)}
            />
          ))}
        </ul>
      )}

      {compareSets.length > 0 && (
        <div className={styles.tray} role="region" aria-label={t('setGuide.compareTray')}>
          <ul className={styles.trayIcons}>
            {compareSets.map((s) => (
              <li key={s.code}>
                <button type="button" className={styles.trayChip} onClick={() => toggleCompare(s.code)} title={t('setGuide.removeSet', { name: s.name })}>
                  <SetIcon code={s.code} />
                  <span>{s.name}</span>
                  <span aria-hidden className={styles.trayX}>×</span>
                </button>
              </li>
            ))}
          </ul>
          <span className={styles.trayHint}>
            {compareSets.length < 2 ? t('setGuide.addAnother') : t('setGuide.compareCount', { count: compareSets.length, max: MAX_COMPARE })}
          </span>
          <button
            type="button"
            className={pageStyles.buttonPrimary}
            disabled={compareSets.length < 2}
            onClick={() => setParam('comparing', '1')}
          >
            {t('setGuide.compareSets')}
          </button>
        </div>
      )}

      {openSet && (
        <SetProfileDialog
          set={openSet}
          comparing={compareCodes.includes(openSet.code)}
          compareFull={compareCodes.length >= MAX_COMPARE}
          onToggleCompare={() => toggleCompare(openSet.code)}
          onClose={() => setParam('set', null)}
        />
      )}
      {comparing && !openSet && compareSets.length >= 2 && (
        <SetCompareDialog
          sets={compareSets}
          onRemove={toggleCompare}
          onOpen={(code) => setParam('set', code)}
          onClose={() => setParam('comparing', null)}
        />
      )}
    </PageShell>
  )
}

function SetTile({
  set,
  comparing,
  compareFull,
  onOpen,
  onToggleCompare,
}: {
  set: GuideSet
  comparing: boolean
  compareFull: boolean
  onOpen: () => void
  onToggleCompare: () => void
}) {
  const { t } = useTranslation()
  const p = set.profile
  return (
    <li className={styles.tile} data-comparing={comparing}>
      <button type="button" className={styles.tileOpen} onClick={onOpen} aria-label={t('setGuide.aboutSet', { name: set.name })} />
      <SetIcon code={set.code} className={styles.tileWatermark} />
      <div className={styles.tileHead}>
        <SetIcon code={set.code} className={styles.tileIcon} />
        <div className={styles.tileTitle}>
          <h2 className={styles.tileName}>{set.name}</h2>
          <span className={styles.meta}>
            {[set.year, t('setGuide.cardCount', { count: set.cardCount }), set.extension ? t('setGuide.extension') : null].filter(Boolean).join(' · ')}
          </span>
        </div>
        <button
          type="button"
          className={styles.compareToggle}
          aria-pressed={comparing}
          disabled={!comparing && compareFull}
          onClick={onToggleCompare}
          title={comparing ? t('setGuide.removeFromCompare') : compareFull ? t('setGuide.compareLimit', { count: MAX_COMPARE }) : t('setGuide.addToCompare')}
        >
          {comparing ? `✓ ${t('setGuide.comparing')}` : `+ ${t('setGuide.compare')}`}
        </button>
      </div>
      {p ? (
        <>
          <p className={styles.tagline}>{p.tagline}</p>
          <TryIf text={p.tryIf} compact />
          <RatingMeters profile={p} compact />
          <ThemeTags themes={p.themes} single />
        </>
      ) : (
        <p className={`${styles.tagline} ${pageStyles.muted}`}>{t('setGuide.noProfile')}</p>
      )}
    </li>
  )
}

/**
 * Every set on a 5×5 board: speed across, complexity up, tinted by power. Ratings are whole steps,
 * so sets that share a cell sit together in it rather than overlapping.
 */
function SetMap({ sets, onOpen }: { sets: readonly GuideSet[]; onOpen: (code: string) => void }) {
  const { t } = useTranslation()
  const profiled = sets.filter((s) => s.profile)
  const steps = [1, 2, 3, 4, 5] as const
  return (
    <div className={`${pageStyles.panel} ${styles.map}`}>
      <div className={styles.mapLegend}>
        <span className={styles.mapLegendLabel}>{t('setGuide.mapLegendPower')}</span>
        {steps.map((v) => (
          <span key={v} className={styles.mapLegendItem}>
            <span className={styles.mapSwatch} data-power={v} />
            {t(`setGuide.ratings.power.words.word${v}`)}
          </span>
        ))}
      </div>
      <div className={styles.mapFrame}>
        <span className={styles.mapAxisY}>{t('setGuide.complexity')} →</span>
        <div className={styles.mapBoard}>
          {[...steps].reverse().map((c) => (
            <div key={c} className={styles.mapRow}>
              <span className={styles.mapRowLabel}>{t(`setGuide.ratings.complexity.words.word${c}`)}</span>
              {steps.map((sp) => (
                <div key={sp} className={styles.mapCell}>
                  {profiled
                    .filter((s) => s.profile!.complexity === c && s.profile!.speed === sp)
                    .map((s) => (
                      <button
                        key={s.code}
                        type="button"
                        className={styles.mapDot}
                        data-power={s.profile!.power}
                        onClick={() => onOpen(s.code)}
                        title={t('setGuide.mapSetTitle', { name: s.name, tagline: s.profile!.tagline })}
                        aria-label={`${s.name}: ${t(`setGuide.ratings.speed.words.word${sp}`)}, ${t(`setGuide.ratings.complexity.words.word${c}`)}, ${t(`setGuide.ratings.power.words.word${s.profile!.power}`)} ${t('setGuide.power')}`}
                      >
                        <SetIcon code={s.code} />
                        <span className={styles.mapDotCode}>{s.code}</span>
                      </button>
                    ))}
                </div>
              ))}
            </div>
          ))}
          <div className={styles.mapRow} aria-hidden>
            <span className={styles.mapRowLabel} />
            {steps.map((sp) => (
              <span key={sp} className={styles.mapColLabel}>{t(`setGuide.ratings.speed.words.word${sp}`)}</span>
            ))}
          </div>
        </div>
      </div>
      <span className={styles.mapAxisX}>{t('setGuide.speed')} →</span>
    </div>
  )
}

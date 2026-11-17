/**
 * The filter every stats view shares — who the games were against and how far back — plus the two
 * small read-outs that go with it: the record split by opponent type, and the count of games that
 * don't count. Used on the Stats page, the profile, public profiles and the admin screens, so a
 * filter reads and behaves the same everywhere. {@link useStatsFilterParam} keeps the choice in the
 * URL (`?vs=humans&days=30`), so it survives a reload and can be shared.
 */
import { useCallback, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useSearchParams } from 'react-router-dom'
import type { WinLoss } from '@/api/account'
import {
  OPPONENT_OPTIONS,
  type OpponentFilter,
  PERIOD_OPTIONS,
  type PeriodFilter,
  type StatsFilter,
  isAllGames,
  parseFilter,
} from '@/api/statsFilter'
import { HelpTip } from '@/components/help/HelpTip'
import s from './StatsFilterBar.module.css'

/** Which games count — the help topic behind every "not counted" note. */
export const COUNTED_GAMES_TOPIC = 'stats-what-counts'

/** The filter, read from and written to the URL's `vs` / `days` parameters (other params kept). */
export function useStatsFilterParam(): [StatsFilter, (next: StatsFilter) => void] {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const filter = useMemo(() => parseFilter(params), [params])
  const setFilter = useCallback(
    (next: StatsFilter) =>
      setParams(
        (prev) => {
          const out = new URLSearchParams(prev)
          if (next.vs === 'all') out.delete('vs')
          else out.set('vs', next.vs)
          if (next.days === 0) out.delete('days')
          else out.set('days', String(next.days))
          return out
        },
        // Keep the entry's state, so an in-page back (the admin screens) still pops it after filtering.
        { replace: true, state: location.state },
      ),
    [setParams, location.state],
  )
  return [filter, setFilter]
}

export function StatsFilterBar({
  filter,
  onChange,
  showPeriod = true,
}: {
  filter: StatsFilter
  onChange: (next: StatsFilter) => void
  /** Hide the period control where the view has its own range (the AI report). */
  showPeriod?: boolean
}) {
  const { t } = useTranslation()
  const opponentOptions = OPPONENT_OPTIONS.map((option) => ({
    ...option,
    label: t(`library.statsPage.${option.value === 'all' ? 'everyone' : option.value}`),
  }))
  const periodOptions = PERIOD_OPTIONS.map((option) => ({
    ...option,
    label: option.value === 0
      ? t('library.statsPage.allPeriod')
      : option.value === 365
        ? t('library.statsPage.yearPeriod')
        : t('library.statsPage.daysPeriod', { count: option.value }),
  }))
  return (
    <div className={s.bar} role="group" aria-label={t('library.statsPage.filterGames')}>
      <Segmented<OpponentFilter>
        label={t('library.statsPage.againstLabel')}
        options={opponentOptions}
        value={filter.vs}
        onChange={(vs) => onChange({ ...filter, vs })}
      />
      {showPeriod && (
        <Segmented<PeriodFilter>
          label={t('library.statsPage.periodLabel')}
          options={periodOptions}
          value={filter.days}
          onChange={(days) => onChange({ ...filter, days })}
        />
      )}
      {!isAllGames(filter) && (
        <button type="button" className={s.reset} onClick={() => onChange({ vs: 'all', days: 0 })}>
          {t('library.statsPage.clearFilter')}
        </button>
      )}
    </div>
  )
}

function Segmented<T extends string | number>({
  label,
  options,
  value,
  onChange,
}: {
  label: string
  options: readonly { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return (
    <div className={s.group}>
      <span className={s.label}>{label}</span>
      <div className={s.segments}>
        {options.map((o) => (
          <button
            key={String(o.value)}
            type="button"
            className={s.segment}
            aria-pressed={o.value === value}
            onClick={() => onChange(o.value)}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  )
}

/**
 * The period's record against people and against the AI, side by side. Each is a toggle for the
 * matching opponent filter — the quickest way to "my win rate against humans".
 */
export function OpponentSplit({
  vsHumans,
  vsAi,
  filter,
  onChange,
}: {
  vsHumans: WinLoss
  vsAi: WinLoss
  filter: StatsFilter
  onChange: (next: StatsFilter) => void
}) {
  const { t } = useTranslation()
  const item = (vs: OpponentFilter, label: string, r: WinLoss) => {
    const active = filter.vs === vs
    return (
      <button
        type="button"
        className={s.splitItem}
        aria-pressed={active}
        disabled={r.games === 0 && !active}
        title={active
          ? t('library.statsPage.showGamesAgainstEveryone')
          : t('library.statsPage.showOnlyGamesAgainst', {
              opponent: vs === 'humans' ? t('library.statsPage.humans').toLowerCase() : t('library.statsPage.ai'),
            })}
        onClick={() => onChange({ ...filter, vs: active ? 'all' : vs })}
      >
        <span className={s.splitLabel}>{label}</span>
        <span className={s.splitRecord}>
          {r.wins}–{r.losses}
        </span>
        <span className={s.splitRate}>{r.games > 0 ? `${Math.round(r.winRate * 100)}%` : '—'}</span>
      </button>
    )
  }
  return (
    <div className={s.split}>
      {item('humans', t('library.statsPage.vsHumans'), vsHumans)}
      {item('ai', t('library.statsPage.vsAi'), vsAi)}
    </div>
  )
}

/** "N games not counted" with the explanation one click away; renders nothing for zero. */
export function NotCountedNote({ count, noun = 'game' }: { count: number; noun?: string }) {
  const { t } = useTranslation()
  if (count <= 0) return null
  return (
    <span className={s.note}>
      {noun === 'game' ? t('library.statsPage.notCounted', { count }) : `${count} ${noun} not counted`}
      <HelpTip topicId={COUNTED_GAMES_TOPIC} size="sm" label={t('library.statsPage.whichGamesCount')} />
    </span>
  )
}

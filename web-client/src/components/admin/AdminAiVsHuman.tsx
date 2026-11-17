/**
 * Admin "AI vs humans": the built-in AI's record against human players, served by
 * `/api/stats/admin/ai-vs-human`. It's the screen to watch while improving the AI — the headline win
 * rate and its change against the previous period, the trend with its confidence band, the record of
 * each deployed build, per-mode records, the latest games, and the humans who play the AI most.
 *
 * Only games with at least one AI seat and one human seat count. Win rates are over decisive games
 * (draws excluded) and always carry a 95% interval: a week of eight games says much less than a week
 * of eighty, and the band is what stops a lucky week reading as progress.
 *
 * The headline trend is only as honest as the game mix behind it: a shift toward a harder mode, one set,
 * or a few strong regulars moves it without the AI changing at all. So the screen slices — by mode,
 * set, and with the busiest humans left out — and shows each mode's own trend and the mix over time.
 *
 * Every number opens onto the games behind it: the recent games list, the recent-form pips and each
 * human in the humans table lead to the decks that were played (see {@link AiVsHumanGames}).
 */
import { NotCountedNote } from '@/components/stats/StatsFilterBar'
import { useEffect, useMemo, useState } from 'react'
import type React from 'react'
import {
  Area,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Scatter,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  type AiChange,
  type AiHumanRecord,
  type AiMatchOutcome,
  type AiRecord,
  type AiVsHumanFilter,
  type AiVsHumanReport,
  fetchAiVsHuman,
} from '@/api/adminStats'
import type { AdminAuth } from '@/api/adminAuth'
import { pageStyles } from '@/components/ui/PageShell'
import { GITHUB_REPOSITORY_URL } from '@/utils/links'
import { gameModeLabel } from './statFormat'
import { useAdminParam } from './adminNav'
import { GameList, HumanGamesModal, MatchDecksModal } from './AiVsHumanGames'
import { AdminScreen, Panel, StatCard, Table, cellStyle } from './adminUi'
import css from './aiVsHuman.module.css'
import { useTranslation } from 'react-i18next'
import i18n from '@/i18n'

const AI = '#e66767'
const HUMAN = '#3987e5'
const DRAW = '#6b7080'
/** Engine AI changes — validated against the AI/human pair; markers also carry a shape and a count. */
const CHANGE = '#199e70'
const AXIS = '#8f98ac'

interface Range {
  key: '30d' | '90d' | '1y' | 'all'
  label: string
  days: number
  bucket: 'day' | 'week' | 'month'
}

const RANGES: Range[] = [
  { key: '30d', label: '30 days', days: 30, bucket: 'day' },
  { key: '90d', label: '90 days', days: 90, bucket: 'week' },
  { key: '1y', label: '1 year', days: 365, bucket: 'week' },
  { key: 'all', label: 'All time', days: 0, bucket: 'week' },
]
const RANGE_LABEL_KEYS = {
  '30d': 'adminAiVsHuman.range30Days',
  '90d': 'adminAiVsHuman.range90Days',
  '1y': 'adminAiVsHuman.rangeYear',
  all: 'adminAiVsHuman.rangeAll',
} as const
const RANGE_SCOPE_KEYS = {
  '30d': 'adminAiVsHuman.rangeScope30Days',
  '90d': 'adminAiVsHuman.rangeScope90Days',
  '1y': 'adminAiVsHuman.rangeScopeYear',
  all: 'adminAiVsHuman.rangeScopeAll',
} as const
const PREVIOUS_RANGE_LABEL_KEYS = {
  '30d': 'adminAiVsHuman.previous30Days',
  '90d': 'adminAiVsHuman.previous90Days',
  '1y': 'adminAiVsHuman.previousYear',
  all: null,
} as const

const RANGE_STORAGE_KEY = 'admin.aiVsHuman.range'

function initialRange(): Range {
  try {
    const saved = localStorage.getItem(RANGE_STORAGE_KEY)
    return RANGES.find((r) => r.key === saved) ?? (RANGES[1] as Range)
  } catch {
    return RANGES[1] as Range
  }
}

const NO_FILTER: AiVsHumanFilter = { gameMode: null, format: null, setCode: null, excludeHumans: [], excludeTopHumans: 0 }

const isFiltered = (f: AiVsHumanFilter) =>
  f.gameMode != null || f.setCode != null || f.excludeHumans.length > 0 || f.excludeTopHumans > 0

export function AdminAiVsHuman({ auth, onBack }: { auth: AdminAuth; onBack: () => void }) {
  const { t } = useTranslation()
  const [range, setRange] = useState<Range>(initialRange)
  const [filter, setFilter] = useState<AiVsHumanFilter>(NO_FILTER)
  /** Names of individually left-out humans, so their chip still reads when the slice has none of their games. */
  const [excludedNames, setExcludedNames] = useState<Record<string, string>>({})
  const [report, setReport] = useState<AiVsHumanReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [gameParam, openGame, closeGame] = useAdminParam('game')
  const [humanParam, openHuman, closeHuman] = useAdminParam('human')
  const openMatchId = gameParam != null && /^\d+$/.test(gameParam) ? Number(gameParam) : null
  const onOpenGame = (matchId: number) => openGame(String(matchId))
  const rangeLabel = t(RANGE_LABEL_KEYS[range.key])
  const rangeScope = t(RANGE_SCOPE_KEYS[range.key])
  const previousLabelKey = PREVIOUS_RANGE_LABEL_KEYS[range.key]
  const previousRangeLabel = previousLabelKey ? t(previousLabelKey) : null

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    fetchAiVsHuman(auth, range.days, range.bucket, filter)
      .then((r) => !cancelled && setReport(r))
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : t('adminAiVsHuman.failedToLoad')))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [auth, range, filter])

  const excludeHuman = (h: AiHumanRecord) => {
    setExcludedNames((names) => ({ ...names, [h.key]: h.name }))
    setFilter((f) => (f.excludeHumans.includes(h.key) ? f : { ...f, excludeHumans: [...f.excludeHumans, h.key] }))
  }
  const pickMode = (gameMode: string | null, format: string | null) => setFilter((f) => ({ ...f, gameMode, format }))

  const pickRange = (r: Range) => {
    setRange(r)
    try {
      localStorage.setItem(RANGE_STORAGE_KEY, r.key)
    } catch {
      // Remembering the range is a convenience; a blocked storage just forgets it.
    }
  }

  const rangeTabs = (
    <div className={pageStyles.tabs} role="tablist" aria-label={t('adminAiVsHuman.timeRange')}>
      {RANGES.map((r) => (
        <button
          key={r.key}
          type="button"
          role="tab"
          aria-selected={r.key === range.key}
          className={pageStyles.tab}
          onClick={() => pickRange(r)}
        >
          {t(RANGE_LABEL_KEYS[r.key])}
        </button>
      ))}
    </div>
  )

  return (
    <AdminScreen
      title={t('adminAiVsHuman.pageTitle')}
      subtitle={t('adminAiVsHuman.pageSubtitle')}
      onBack={onBack}
      backLabel={t('adminAiVsHuman.dashboard')}
      right={rangeTabs}
    >
      <div className={css.root} data-loading={loading && report != null}>
        {error && <p className={css.error} role="alert">{error}</p>}
        {report != null && (
          <FilterBar
            report={report}
            filter={filter}
            excludedNames={excludedNames}
            onChange={setFilter}
            onPickMode={pickMode}
          />
        )}
        {report != null && (report.notCounted ?? 0) > 0 && (
          <p style={cellStyle.muted}>
            <NotCountedNote count={report.notCounted ?? 0} /> {t('adminAiVsHuman.notCountedLead')}
          </p>
        )}
        {report == null ? (
          !error && <Panel><p style={cellStyle.muted}>{t('adminAiVsHuman.loading')}</p></Panel>
        ) : report.overall.games === 0 ? (
          <Panel>
            <div className={css.empty}>
              {isFiltered(filter) ? (
                <>
                  <p className={css.emptyTitle}>{t('adminAiVsHuman.noGamesSlice')}</p>
                  <p className={css.emptyText}>
                    {t('adminAiVsHuman.nothingMatches', { range: rangeScope })}{' '}
                    <button type="button" className={css.linkButton} onClick={() => setFilter(NO_FILTER)}>
                      {t('adminAiVsHuman.clearFilters')}
                    </button>
                  </p>
                </>
              ) : (
                <>
                  <p className={css.emptyTitle}>{range.days ? t('adminAiVsHuman.noGamesInRange', { range: rangeScope }) : t('adminAiVsHuman.noGamesYet')}</p>
                  <p className={css.emptyText}>{t('adminAiVsHuman.noGamesInRangeDescription')}</p>
                </>
              )}
            </div>
          </Panel>
        ) : (
          <ReportView
            report={report}
            previousLabel={previousRangeLabel}
            onExcludeHuman={excludeHuman}
            onPickMode={pickMode}
            onOpenGame={onOpenGame}
            onOpenHuman={openHuman}
          />
        )}
      </div>
      {humanParam && (
        <HumanGamesModal
          auth={auth}
          humanKey={humanParam}
          days={range.days}
          rangeLabel={rangeLabel}
          filter={filter}
          onOpenGame={onOpenGame}
          onClose={closeHuman}
        />
      )}
      {openMatchId != null && <MatchDecksModal auth={auth} matchId={openMatchId} onClose={closeGame} />}
    </AdminScreen>
  )
}

function ReportView({
  report,
  previousLabel,
  onExcludeHuman,
  onPickMode,
  onOpenGame,
  onOpenHuman,
}: {
  report: AiVsHumanReport
  previousLabel: string | null
  onExcludeHuman: (h: AiHumanRecord) => void
  onPickMode: (gameMode: string | null, format: string | null) => void
  onOpenGame: (matchId: number) => void
  onOpenHuman: (key: string) => void
}) {
  const { t } = useTranslation()
  const { overall } = report
  /** A bucket picked on the trend chart narrows the AI-changes list to it. */
  const [selectedBucket, setSelectedBucket] = useState<string | null>(null)
  useEffect(() => setSelectedBucket(null), [report])
  const changesByBucket = useMemo(() => {
    const map = new Map<string, AiChange[]>()
    for (const c of report.aiChanges.changes) map.set(c.bucketStart, [...(map.get(c.bucketStart) ?? []), c])
    return map
  }, [report.aiChanges.changes])
  const changesByBuild = useMemo(() => {
    const map = new Map<string, AiChange[]>()
    for (const c of report.aiChanges.changes) if (c.shippedIn) map.set(c.shippedIn, [...(map.get(c.shippedIn) ?? []), c])
    return map
  }, [report.aiChanges.changes])
  return (
    <>
      <Panel>
        <Hero overall={overall} previous={report.previous} previousLabel={previousLabel} />
      </Panel>

      <div className={css.tiles}>
        <StatCard label={t('adminAiVsHuman.games')} value={overall.games.toLocaleString()} />
        <StatCard label={t('adminAiVsHuman.aiWins')} value={overall.aiWins.toLocaleString()} />
        <StatCard label={t('adminAiVsHuman.humanWins')} value={overall.humanWins.toLocaleString()} />
        <StatCard label={t('adminAiVsHuman.draws')} value={overall.draws.toLocaleString()} />
        <StatCard label={t('adminAiVsHuman.buildsSeen')} value={report.byBuild.length} />
      </div>

      <Panel
        title={t('adminAiVsHuman.winRateOverTime')}
        subtitle={t('adminAiVsHuman.trailingTrend', {
          window: t(report.bucket === 'DAY' ? 'adminAiVsHuman.window7Days' : report.bucket === 'WEEK' ? 'adminAiVsHuman.window4Weeks' : 'adminAiVsHuman.window3Months'),
          bucket: t(report.bucket === 'DAY' ? 'adminAiVsHuman.bucketDay' : report.bucket === 'WEEK' ? 'adminAiVsHuman.bucketWeek' : 'adminAiVsHuman.bucketMonth'),
        })}
        action={<TrendLegend bucket={report.bucket} showChanges={report.aiChanges.available} />}
      >
        <TrendCharts
          report={report}
          changesByBucket={changesByBucket}
          selectedBucket={selectedBucket}
          onSelectBucket={(b) => setSelectedBucket((cur) => (cur === b ? null : b))}
        />
      </Panel>

      {report.seriesByMode.length > 1 && <ModeTrendsPanel report={report} onPickMode={onPickMode} />}

      <AiChangesPanel
        report={report}
        selectedBucket={selectedBucket}
        onClearBucket={() => setSelectedBucket(null)}
      />

      <div className={css.twoCol}>
        <Panel title={t('adminAiVsHuman.byBuild')} subtitle={t('adminAiVsHuman.buildSubtitle')}>
          {report.byBuild.length === 0 ? (
            <p style={cellStyle.muted}>{t('adminAiVsHuman.noBuildInfo')}</p>
          ) : (
            <ForestRows
              rows={report.byBuild.map((b, i) => ({
                key: b.version,
                title: (
                  <>
                    <span className={css.mono}>{shortVersion(b.version)}</span>
                    {i === 0 && <span className={css.latest}>{t('adminAiVsHuman.latest')}</span>}
                    <BuildChangesBadge count={b.newAiChanges} changes={changesByBuild.get(b.version) ?? []} />
                  </>
                ),
                titleText: b.version,
                sub: dateSpan(b.firstSeen, b.lastSeen),
                record: b.record,
              }))}
            />
          )}
        </Panel>
        <Panel title={t('adminAiVsHuman.byGameMode')} subtitle={t('adminAiVsHuman.gameModeSubtitle')}>
          <ForestRows
            rows={report.byMode.map((m) => {
              const { primary, variant } = gameModeLabel(m.gameMode, m.format)
              const games = t('adminAiVsHuman.gameCount', { count: m.record.games })
              return {
                key: `${m.gameMode}~${m.format}`,
                title: primary,
                titleText: variant ? `${primary} › ${variant}` : primary,
                // The variant rides on the second line, so "Multiplayer › Two-Headed Giant" never truncates.
                sub: variant ? `${variant} · ${games}` : games,
                record: m.record,
              }
            })}
          />
        </Panel>
      </div>

      <div className={css.twoCol}>
        <Panel
          title={t('adminAiVsHuman.recentForm')}
          subtitle={t('adminAiVsHuman.lastGamesOldestFirst', { count: report.recent.length })}
          action={<OutcomeLegend />}
        >
          <RecentForm recent={report.recent} onOpenGame={onOpenGame} />
        </Panel>
        <Panel
          title={t('adminAiVsHuman.humansVsAi')}
          subtitle={t('adminAiVsHuman.humansSubtitle')}
        >
          <HumansTable humans={report.topHumans} onExclude={onExcludeHuman} onOpen={onOpenHuman} />
        </Panel>
      </div>

      <RecentGamesPanel recent={report.recent} onOpenGame={onOpenGame} onOpenHuman={onOpenHuman} />

      <p className={css.footnote}>
        {t('adminAiVsHuman.countedGamesFootnote')}
      </p>
    </>
  )
}

// ============================================================================
// Hero: headline rate + balance bar
// ============================================================================

function Hero({ overall, previous, previousLabel }: { overall: AiRecord; previous: AiRecord | null; previousLabel: string | null }) {
  const { t } = useTranslation()
  const decisive = overall.aiWins + overall.humanWins
  const rate = overall.aiWinRate
  const aiShare = decisive === 0 ? 0.5 : overall.aiWins / decisive
  return (
    <div className={css.hero}>
      <div>
        <p className={css.heroLabel}>{t('adminAiVsHuman.aiWinRate')}</p>
        <p className={css.heroValue}>
          {rate == null ? '—' : pctNumber(rate)}
          {rate != null && <small>%</small>}
        </p>
        <p className={css.heroMeta}>
          {t('adminAiVsHuman.decisiveGameCount', { count: decisive })}
          {overall.aiWinRateLow != null && overall.aiWinRateHigh != null && (
            <>
              {' · '}95% CI {pct(overall.aiWinRateLow)}–{pct(overall.aiWinRateHigh)}
            </>
          )}
        </p>
        {previousLabel && <DeltaChip current={overall} previous={previous} previousLabel={previousLabel} />}
      </div>

      <div className={css.balance} aria-label={t('adminAiVsHuman.balanceLabel', { ai: pct(aiShare), humans: pct(1 - aiShare) })}>
        <div className={css.balanceSides}>
          <div className={css.side}>
            <span className={css.sideName}>
              <span className={`${css.swatch} ${css.swatchAi}`} aria-hidden />
              {t('adminAiVsHuman.engineAi')}
            </span>
            <span className={css.sidePct}>{pct(aiShare)}</span>
            <span>{t('adminAiVsHuman.winCount', { count: overall.aiWins })}</span>
          </div>
          <div className={`${css.side} ${css.sideRight}`}>
            <span className={css.sideName}>
              {t('adminAiVsHuman.humans')}
              <span className={`${css.swatch} ${css.swatchHuman}`} aria-hidden />
            </span>
            <span className={css.sidePct}>{pct(1 - aiShare)}</span>
            <span>{t('adminAiVsHuman.winCount', { count: overall.humanWins })}</span>
          </div>
        </div>
        <div className={css.bar}>
          <div className={css.barAi} style={{ flexGrow: overall.aiWins, flexBasis: 0 }} />
          <div className={css.barHuman} style={{ flexGrow: overall.humanWins, flexBasis: 0 }} />
          <div className={css.evenTick} title={t('adminAiVsHuman.even50')} />
        </div>
        <div className={css.balanceFoot}>
          <span>{t(verdictKey(rate))}</span>
          <span>
            {overall.draws > 0
              ? t('adminAiVsHuman.drawsNotCounted', { count: overall.draws.toLocaleString() })
              : t('adminAiVsHuman.noDraws')}
          </span>
        </div>
      </div>
    </div>
  )
}

/**
 * The change in win rate against the previous window, in percentage points, with a plain-language
 * read of whether it's more than noise (a two-proportion z-test at 95%).
 */
function DeltaChip({ current, previous, previousLabel }: { current: AiRecord; previous: AiRecord | null; previousLabel: string }) {
  const { t } = useTranslation()
  if (current.aiWinRate == null) return null
  if (previous == null || previous.aiWinRate == null) {
    return <span className={css.delta}>{t('adminAiVsHuman.noGamesPreviousRange', { range: previousLabel })}</span>
  }
  const points = (current.aiWinRate - previous.aiWinRate) * 100
  const dir = Math.abs(points) < 0.05 ? 'flat' : points > 0 ? 'up' : 'down'
  const arrow = dir === 'up' ? '▲' : dir === 'down' ? '▼' : '■'
  const significant = isSignificant(current, previous)
  return (
    <span
      className={css.delta}
      data-dir={dir}
      title={t('adminAiVsHuman.previousWas', {
        rate: pct(previous.aiWinRate),
        count: previous.aiWins + previous.humanWins,
      })}
    >
      <span aria-hidden>{arrow}</span>
      {points > 0 ? '+' : ''}
      {t('adminAiVsHuman.pointsVsPrevious', { points: points.toFixed(1), range: previousLabel })}
      <span className={css.deltaNote}>· {t(significant ? 'adminAiVsHuman.realShift' : 'adminAiVsHuman.withinNoise')}</span>
    </span>
  )
}

function isSignificant(a: AiRecord, b: AiRecord): boolean {
  const n1 = a.aiWins + a.humanWins
  const n2 = b.aiWins + b.humanWins
  if (n1 === 0 || n2 === 0) return false
  const pooled = (a.aiWins + b.aiWins) / (n1 + n2)
  const se = Math.sqrt(pooled * (1 - pooled) * (1 / n1 + 1 / n2))
  if (se === 0) return false
  return Math.abs((a.aiWins / n1 - b.aiWins / n2) / se) >= 1.96
}

function verdictKey(rate: number | null) {
  if (rate == null) return 'adminAiVsHuman.noDecisiveGames'
  if (rate >= 0.55) return 'adminAiVsHuman.aiUpperHand'
  if (rate > 0.45) return 'adminAiVsHuman.evenContest'
  if (rate > 0.3) return 'adminAiVsHuman.humansEdge'
  return 'adminAiVsHuman.humansDominate'
}

// ============================================================================
// Trend: a trailing win rate with its interval, the raw per-bucket rates, and the games behind them
// ============================================================================

/** How many buckets the trailing trend spans: a week of days, a month of weeks, a quarter of months. */
const TREND_WINDOW: Record<AiVsHumanReport['bucket'], { size: number; label: string }> = {
  DAY: { size: 7, label: '7-day' },
  WEEK: { size: 4, label: '4-week' },
  MONTH: { size: 3, label: '3-month' },
}

interface TrendPoint {
  start: string
  /** This bucket alone, in percent. */
  rate: number | null
  /** The trailing window ending at this bucket, in percent — the line to read. */
  trend: number | null
  band: [number, number] | null
  trendDecisive: number
  aiWins: number
  humanWins: number
  draws: number
  games: number
  aiChanges: number
  /** Pins the bucket's AI-change marker to the top of the plot; null when nothing merged. */
  changeMarker: number | null
}

function TrendCharts({
  report,
  changesByBucket,
  selectedBucket,
  onSelectBucket,
}: {
  report: AiVsHumanReport
  changesByBucket: Map<string, AiChange[]>
  selectedBucket: string | null
  onSelectBucket: (bucket: string) => void
}) {
  const { t } = useTranslation()
  const trendWindow = TREND_WINDOW[report.bucket]
  const data: TrendPoint[] = useMemo(() => {
    const series = report.series
    return series.map(({ start, record: r, aiChanges }, i) => {
      let ai = 0
      let human = 0
      for (const { record: w } of series.slice(Math.max(0, i - trendWindow.size + 1), i + 1)) {
        ai += w.aiWins
        human += w.humanWins
      }
      const interval = wilson(ai, ai + human)
      return {
        start,
        rate: r.aiWinRate == null ? null : r.aiWinRate * 100,
        trend: interval ? (ai / (ai + human)) * 100 : null,
        band: interval ? [interval[0] * 100, interval[1] * 100] : null,
        trendDecisive: ai + human,
        aiWins: r.aiWins,
        humanWins: r.humanWins,
        draws: r.draws,
        games: r.games,
        aiChanges,
        changeMarker: aiChanges > 0 ? 100 : null,
      }
    })
  }, [report.series, trendWindow.size])
  const tick = (d: string) => tickLabel(d, report.bucket)
  const select = (state: { activeLabel?: string | number | undefined } | null) => {
    if (state?.activeLabel != null) onSelectBucket(String(state.activeLabel))
  }
  const selectedLine = selectedBucket && (
    <ReferenceLine x={selectedBucket} stroke={CHANGE} strokeWidth={1.5} strokeDasharray="4 3" ifOverflow="hidden" />
  )

  return (
    <div className={css.trendCharts}>
      <ResponsiveContainer width="100%" height={280}>
        <ComposedChart data={data} syncId="ai-vs-human" margin={{ top: 26, right: 12, bottom: 0, left: -12 }} onClick={select}>
          <CartesianGrid stroke="rgba(255,255,255,0.06)" vertical={false} />
          <XAxis dataKey="start" stroke={AXIS} fontSize={11} tickFormatter={tick} minTickGap={24} tickLine={false} />
          <YAxis
            stroke={AXIS}
            fontSize={11}
            domain={[0, 100]}
            ticks={[0, 25, 50, 75, 100]}
            tickFormatter={(v: number) => `${v}%`}
            tickLine={false}
            axisLine={false}
          />
          <Tooltip
            content={
              <TrendTooltip
                bucket={report.bucket}
                windowLabel={t(report.bucket === 'DAY' ? 'adminAiVsHuman.window7Days' : report.bucket === 'WEEK' ? 'adminAiVsHuman.window4Weeks' : 'adminAiVsHuman.window3Months')}
                changesByBucket={changesByBucket}
              />
            }
            cursor={{ stroke: 'rgba(255,255,255,0.25)' }}
          />
          <ReferenceLine y={50} stroke={AXIS} strokeDasharray="5 4" strokeWidth={1.5} />
          {selectedLine}
          <Area dataKey="band" stroke="none" fill={AI} fillOpacity={0.16} isAnimationActive={false} activeDot={false} />
          {/* Each bucket on its own: dots only, so a thin week reads as one noisy sample, not a swing. */}
          <Line
            dataKey="rate"
            stroke="none"
            dot={{ r: 3, fill: 'rgba(230,103,103,0.55)', stroke: 'none' }}
            activeDot={{ r: 4, fill: 'rgba(230,103,103,0.8)', stroke: 'none' }}
            isAnimationActive={false}
          />
          <Line
            dataKey="trend"
            stroke={AI}
            strokeWidth={2.5}
            dot={false}
            activeDot={{ r: 5, fill: AI, stroke: 'rgba(10,12,20,0.95)', strokeWidth: 2 }}
            isAnimationActive={false}
          />
          {report.aiChanges.available && (
            <Scatter
              dataKey="changeMarker"
              isAnimationActive={false}
              shape={(props: unknown) => <ChangeMarker {...(props as ChangeMarkerProps)} selectedBucket={selectedBucket} />}
            />
          )}
        </ComposedChart>
      </ResponsiveContainer>
      <p className={css.subchartLabel}>{t('adminAiVsHuman.gamesPlayed')}</p>
      <ResponsiveContainer width="100%" height={92}>
        <BarChart data={data} syncId="ai-vs-human" margin={{ top: 0, right: 12, bottom: 0, left: -12 }} barCategoryGap="18%" onClick={select}>
          <XAxis dataKey="start" hide />
          <YAxis stroke={AXIS} fontSize={10} allowDecimals={false} tickLine={false} axisLine={false} width={48} />
          {/* Synced with the rate chart, whose tooltip already tells the record — just highlight the column. */}
          <Tooltip content={() => null} cursor={{ fill: 'rgba(255,255,255,0.06)' }} />
          <Bar dataKey="aiWins" stackId="g" fill={AI} stroke="rgba(10,12,20,0.95)" strokeWidth={1} isAnimationActive={false} />
          <Bar dataKey="draws" stackId="g" fill={DRAW} stroke="rgba(10,12,20,0.95)" strokeWidth={1} isAnimationActive={false} />
          <Bar
            dataKey="humanWins"
            stackId="g"
            fill={HUMAN}
            stroke="rgba(10,12,20,0.95)"
            strokeWidth={1}
            radius={[3, 3, 0, 0]}
            isAnimationActive={false}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

interface ChangeMarkerProps {
  cx?: number
  cy?: number
  payload?: TrendPoint
}

/**
 * An AI-change marker above the plot: a downward tab with the bucket's merge count, so its shape and
 * number carry it as well as its colour. The picked bucket's marker is filled.
 */
function ChangeMarker({ cx, cy, payload, selectedBucket }: ChangeMarkerProps & { selectedBucket: string | null }) {
  const { t } = useTranslation()
  if (cx == null || cy == null || !payload || payload.aiChanges === 0) return null
  const label = payload.aiChanges > 99 ? '99+' : String(payload.aiChanges)
  const w = Math.max(16, 6 + label.length * 6)
  const top = cy - 22
  const selected = payload.start === selectedBucket
  return (
    <g style={{ cursor: 'pointer' }}>
      <title>{t('adminAiVsHuman.changesMerged', { count: payload.aiChanges })}</title>
      <path
        d={`M${cx - w / 2},${top + 3} q0,-3 3,-3 h${w - 6} q3,0 3,3 v9 q0,3 -3,3 h-${w / 2 - 7} l-4,4 l-4,-4 h-${w / 2 - 7} q-3,0 -3,-3 z`}
        fill={selected ? CHANGE : 'rgba(10,12,20,0.95)'}
        stroke={CHANGE}
        strokeWidth={1.5}
      />
      <text
        x={cx}
        y={top + 10.5}
        textAnchor="middle"
        fontSize={9.5}
        fontWeight={700}
        fill={selected ? '#06140f' : '#7fe3bf'}
        style={{ fontVariantNumeric: 'tabular-nums' }}
      >
        {label}
      </text>
    </g>
  )
}

/** The 95% Wilson score interval for `wins` of `n`; null when `n` is zero. Mirrors the server's `AiRecord`. */
function wilson(wins: number, n: number): [number, number] | null {
  if (n === 0) return null
  const p = wins / n
  const z2 = 1.96 * 1.96
  const centre = p + z2 / (2 * n)
  const margin = 1.96 * Math.sqrt((p * (1 - p)) / n + z2 / (4 * n * n))
  const denom = 1 + z2 / n
  return [Math.max(0, (centre - margin) / denom), Math.min(1, (centre + margin) / denom)]
}

function SwatchRow({ swatch, label, value }: { swatch: string; label: string; value: number }) {
  return (
    <div className={css.tooltipRow}>
      <span className={css.tooltipKey}>
        <span className={`${css.swatch} ${swatch}`} />
        {label}
      </span>
      <b>{value}</b>
    </div>
  )
}

/** How many change titles a tooltip lists before "and N more". */
const TOOLTIP_CHANGES = 3

function TrendTooltip({
  active,
  payload,
  bucket,
  windowLabel,
  changesByBucket,
}: {
  active?: boolean
  payload?: { payload?: TrendPoint }[]
  bucket: AiVsHumanReport['bucket']
  windowLabel: string
  changesByBucket: Map<string, AiChange[]>
}) {
  const { t } = useTranslation()
  const p = active ? payload?.[0]?.payload : undefined
  if (!p) return null
  const period = t(bucket === 'DAY' ? 'adminAiVsHuman.thisDay' : bucket === 'WEEK' ? 'adminAiVsHuman.thisWeek' : 'adminAiVsHuman.thisMonth')
  const changes = changesByBucket.get(p.start) ?? []
  return (
    <div className={css.tooltip}>
      <div className={css.tooltipTitle}>{tooltipTitle(p.start, bucket)}</div>
      {p.trend != null && (
        <>
          <div className={css.tooltipRow}>
            {t('adminAiVsHuman.windowTrend', { window: windowLabel })} <b>{Math.round(p.trend)}%</b>
          </div>
          {p.band && (
            <div className={css.tooltipRow}>
              {t('adminAiVsHuman.interval95')} <b>{Math.round(p.band[0])}–{Math.round(p.band[1])}%</b>
            </div>
          )}
          <div className={css.tooltipMuted}>{t('adminAiVsHuman.overDecisiveGames', { count: p.trendDecisive })}</div>
          <div className={css.tooltipDivider} />
        </>
      )}
      {p.games === 0 ? (
        <div className={css.tooltipMuted}>{t(bucket === 'DAY' ? 'adminAiVsHuman.noGamesThisDay' : bucket === 'WEEK' ? 'adminAiVsHuman.noGamesThisWeek' : 'adminAiVsHuman.noGamesThisMonth')}</div>
      ) : (
        <>
          <div className={css.tooltipRow}>
            {period} <b>{p.rate == null ? '—' : `${Math.round(p.rate)}%`}</b>
          </div>
          <SwatchRow swatch={css.swatchAi ?? ''} label={t('adminAiVsHuman.aiWins')} value={p.aiWins} />
          <SwatchRow swatch={css.swatchHuman ?? ''} label={t('adminAiVsHuman.humanWins')} value={p.humanWins} />
          {p.draws > 0 && <SwatchRow swatch={css.swatchDraw ?? ''} label={t('adminAiVsHuman.draws')} value={p.draws} />}
        </>
      )}
      {changes.length > 0 && (
        <>
          <div className={css.tooltipDivider} />
          <div className={css.tooltipChangesHead}>
            {t('adminAiVsHuman.changesMerged', { count: changes.length })}
          </div>
          <ul className={css.tooltipChanges}>
            {changes.slice(0, TOOLTIP_CHANGES).map((c) => (
              <li key={c.sha}>{c.subject}</li>
            ))}
          </ul>
          <div className={css.tooltipMuted}>
            {t('adminAiVsHuman.clickToList', {
              extra: changes.length > TOOLTIP_CHANGES ? t('adminAiVsHuman.moreChanges', { count: changes.length - TOOLTIP_CHANGES }) : '',
            })}
          </div>
        </>
      )}
    </div>
  )
}

function TrendLegend({ bucket, showChanges }: { bucket: AiVsHumanReport['bucket']; showChanges: boolean }) {
  const { t } = useTranslation()
  const window = t(bucket === 'DAY' ? 'adminAiVsHuman.window7Days' : bucket === 'WEEK' ? 'adminAiVsHuman.window4Weeks' : 'adminAiVsHuman.window3Months')
  const unit = t(bucket === 'DAY' ? 'adminAiVsHuman.bucketDay' : bucket === 'WEEK' ? 'adminAiVsHuman.bucketWeek' : 'adminAiVsHuman.bucketMonth')
  return (
    <div className={css.legend}>
      <span className={css.legendItem}>
        <span className={css.legendLine} />
        {t('adminAiVsHuman.windowTrend', { window })}
      </span>
      <span className={css.legendItem}>
        <span className={css.legendDot} />
        {t('adminAiVsHuman.singlePeriod', { unit })}
      </span>
      <span className={css.legendItem}>
        <span className={css.legendBand} />
        {t('adminAiVsHuman.interval95')}
      </span>
      <span className={css.legendItem}>
        <span className={css.legendEven} />
        {t('adminAiVsHuman.even50')}
      </span>
      {showChanges && (
        <span className={css.legendItem}>
          <span className={css.legendChange}>n</span>
          {t('adminAiVsHuman.changesMergedLegend')}
        </span>
      )}
    </div>
  )
}

// ============================================================================
// Engine AI changes: what merged, and the build that first carried it
// ============================================================================

/** Rows shown before "Show all". */
const CHANGES_PREVIEW = 8

function AiChangesPanel({
  report,
  selectedBucket,
  onClearBucket,
}: {
  report: AiVsHumanReport
  selectedBucket: string | null
  onClearBucket: () => void
}) {
  const { t, i18n } = useTranslation()
  const [expanded, setExpanded] = useState(false)
  const { available, changes } = report.aiChanges
  const shown = selectedBucket ? changes.filter((c) => c.bucketStart === selectedBucket) : changes
  const visible = expanded || selectedBucket ? shown : shown.slice(0, CHANGES_PREVIEW)
  const subtitle = selectedBucket
    ? t('adminAiVsHuman.changesInWeek', { count: shown.length, date: tooltipTitle(selectedBucket, report.bucket) })
    : t('adminAiVsHuman.changesInRange', { count: changes.length })

  const action = selectedBucket ? (
    <button type="button" className={css.filterChip} onClick={onClearBucket}>
      {tooltipTitle(selectedBucket, report.bucket)}
      <span aria-hidden>✕</span>
      <span className={css.srOnly}>{t('adminAiVsHuman.showEveryChange')}</span>
    </button>
  ) : shown.length > CHANGES_PREVIEW ? (
    <button type="button" className={css.linkButton} onClick={() => setExpanded((v) => !v)}>
      {t(expanded ? 'adminAiVsHuman.showLatest' : 'adminAiVsHuman.showAll', { count: expanded ? CHANGES_PREVIEW : shown.length })}
    </button>
  ) : undefined

  return (
    <Panel title={t('adminAiVsHuman.engineAiChanges')} {...(available ? { subtitle } : {})} action={action}>
      {!available ? (
        <p style={cellStyle.muted}>{t('adminAiVsHuman.unavailableGitHistory')}</p>
      ) : shown.length === 0 ? (
        <p style={cellStyle.muted}>
          {t(selectedBucket ? 'adminAiVsHuman.noChangesThen' : 'adminAiVsHuman.noChangesRange')}
        </p>
      ) : (
        <ol className={css.changes}>
          {visible.map((c) => (
            <li key={c.sha} className={css.change}>
              <span className={css.changeDate} title={new Date(c.committedAt).toLocaleString(i18n.resolvedLanguage ?? 'en')}>
                {shortDate(c.committedAt)}
              </span>
              <span className={css.changeMain}>
                <span className={css.changeSubject}>{c.subject}</span>
                <span className={css.changeMeta}>
                  {c.pr != null ? (
                    <a href={`${GITHUB_REPOSITORY_URL}/pull/${c.pr}`} target="_blank" rel="noreferrer" className={css.changeLink}>
                      #{c.pr}
                    </a>
                  ) : (
                    <a href={`${GITHUB_REPOSITORY_URL}/commit/${c.sha}`} target="_blank" rel="noreferrer" className={css.changeLink}>
                      <span className={css.mono}>{c.sha.slice(0, 7)}</span>
                    </a>
                  )}
                </span>
              </span>
              <span className={css.changeShipped}>
                {c.shippedIn ? (
                  <>
                    <span className={css.buildChip}>{shortVersion(c.shippedIn)}</span>
                    <span className={css.changeShippedWhen}>
                      {t('adminAiVsHuman.liveDate', { date: shortDate(c.shippedAt ?? c.committedAt) })}
                    </span>
                  </>
                ) : (
                  <span className={css.changeShippedWhen}>{t('adminAiVsHuman.noGamesOnBuild')}</span>
                )}
              </span>
            </li>
          ))}
        </ol>
      )}
    </Panel>
  )
}

/** A "+3" chip — the timeline marker's look — on a build that added AI changes; titles on hover. */
function BuildChangesBadge({ count, changes }: { count: number | null; changes: AiChange[] }) {
  const { t } = useTranslation()
  if (count == null || count === 0) return null
  const head = t('adminAiVsHuman.changesSinceBuild', { count })
  const titles = [head, ...changes.map((c) => `• ${c.subject}`)].join('\n')
  return (
    <span className={css.buildBadge} title={titles} aria-label={head}>
      +{count}
    </span>
  )
}

function OutcomeLegend() {
  const { t } = useTranslation()
  return (
    <div className={css.legend}>
      <span className={css.legendItem}>
        <span className={`${css.swatch} ${css.swatchAi}`} />
        {t('adminAiVsHuman.aiWon')}
      </span>
      <span className={css.legendItem}>
        <span className={`${css.swatch} ${css.swatchHuman}`} />
        {t('adminAiVsHuman.humanWon')}
      </span>
      <span className={css.legendItem}>
        <span className={css.swatch} style={{ boxShadow: `inset 0 0 0 2px ${DRAW}` }} />
        {t('adminAiVsHuman.draw')}
      </span>
    </div>
  )
}

// ============================================================================
// Forest rows: each record as a point on a 0–100% track with its interval
// ============================================================================

interface ForestRow {
  key: string
  title: React.ReactNode
  titleText: string
  sub: React.ReactNode
  record: AiRecord
}

function ForestRows({ rows }: { rows: ForestRow[] }) {
  const { t } = useTranslation()
  return (
    <div className={css.rows}>
      <div className={`${css.row} ${css.rowHead}`}>
        <span />
        <div className={css.trackAxis}>
          <span>0%</span>
          <span>50%</span>
          <span>100%</span>
        </div>
        <span className={css.rowValue}>{t('adminAiVsHuman.aiWins')}</span>
      </div>
      {rows.map((row) => {
        const { aiWinRate: rate, aiWinRateLow: low, aiWinRateHigh: high } = row.record
        const color = rate == null ? DRAW : rate >= 0.5 ? AI : HUMAN
        const decisive = row.record.aiWins + row.record.humanWins
        return (
          <div key={row.key} className={css.row}>
            <div className={css.rowLabel}>
              <span className={css.rowTitle} title={row.titleText}>{row.title}</span>
              <span className={css.rowSub}>{row.sub}</span>
            </div>
            <div
              className={css.track}
              title={rate == null ? t('adminAiVsHuman.noDecisiveTooltip') : t('adminAiVsHuman.confidenceInterval', {
                rate: pct(rate),
                low: pct(low ?? 0),
                high: pct(high ?? 1),
              })}
            >
              <div className={css.trackLine} />
              <div className={css.trackEven} />
              {low != null && high != null && (
                <div className={css.ci} style={{ left: `${low * 100}%`, width: `${(high - low) * 100}%`, background: color }} />
              )}
              {rate != null && <div className={css.dot} style={{ left: `${rate * 100}%`, background: color }} />}
            </div>
            <div className={css.rowValue}>
              <div className={css.rowPct}>{rate == null ? '—' : pct(rate)}</div>
              <div className={css.rowN}>
                {row.record.aiWins}–{row.record.humanWins}
                {decisive === 0 && row.record.draws > 0 ? ` · ${row.record.draws}D` : ''}
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )
}

// ============================================================================
// Recent form + humans
// ============================================================================

/** Rows of the recent-games list shown before "Show all". */
const RECENT_PREVIEW = 8

function RecentGamesPanel({
  recent,
  onOpenGame,
  onOpenHuman,
}: {
  recent: AiVsHumanReport['recent']
  onOpenGame: (matchId: number) => void
  onOpenHuman: (key: string) => void
}) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState(false)
  const newestFirst = useMemo(() => [...recent].reverse(), [recent])
  const shown = expanded ? newestFirst : newestFirst.slice(0, RECENT_PREVIEW)
  return (
    <Panel
      title={t('adminAiVsHuman.recentGames')}
      subtitle={t('adminAiVsHuman.newestFirst')}
      action={
        newestFirst.length > RECENT_PREVIEW ? (
          <button type="button" className={css.linkButton} onClick={() => setExpanded((v) => !v)}>
            {t(expanded ? 'adminAiVsHuman.showLatest' : 'adminAiVsHuman.showAll', {
              count: expanded ? RECENT_PREVIEW : newestFirst.length,
            })}
          </button>
        ) : undefined
      }
    >
      <GameList games={shown} onOpenGame={onOpenGame} onOpenHuman={onOpenHuman} />
    </Panel>
  )
}

function RecentForm({ recent, onOpenGame }: { recent: AiVsHumanReport['recent']; onOpenGame: (matchId: number) => void }) {
  const { t, i18n } = useTranslation()
  const lastTen = recent.slice(-10)
  const lastTenAi = lastTen.filter((g) => g.outcome === 'AI').length
  const lastTenHuman = lastTen.filter((g) => g.outcome === 'HUMAN').length
  const streak = currentStreak(recent)
  const bestAi = longestRun(recent, 'AI')
  return (
    <>
      <div className={css.form} role="list">
        {recent.map((g, i) => {
          const { primary, variant } = gameModeLabel(g.gameMode, g.format)
          const outcome = t(g.outcome === 'AI' ? 'adminAiVsHuman.aiWon' : g.outcome === 'HUMAN' ? 'adminAiVsHuman.humanWon' : 'adminAiVsHuman.draw')
          const label = `${new Date(g.endedAt).toLocaleString(i18n.resolvedLanguage ?? 'en', { dateStyle: 'medium', timeStyle: 'short' })} · ${
            variant ? `${primary} › ${variant}` : primary
          } · ${outcome}${g.humans.length ? ` ${t('adminAiGames.versus')} ${g.humans.map((h) => h.name).join(', ')}` : ''}`
          return (
            <span key={g.matchId ?? i} role="listitem">
              <button
                type="button"
                className={css.pip}
                data-outcome={g.outcome}
                title={t('adminAiVsHuman.recentGameTitle', { label })}
                aria-label={t('adminAiVsHuman.recentGameAria', { label })}
                onClick={() => onOpenGame(g.matchId)}
              />
            </span>
          )
        })}
      </div>
      <div className={css.formFoot}>
        <span>{t('adminAiVsHuman.older')}</span>
        <span>{t('adminAiVsHuman.newest')}</span>
      </div>
      <div className={css.formStats}>
        <div className={css.formStat}>
          <div className={css.formStatValue}>
            {lastTenAi}–{lastTenHuman}
          </div>
          <div className={css.formStatLabel}>{t('adminAiVsHuman.lastN', { count: lastTen.length })}</div>
          <div className={css.formStatSub}>{t('adminAiVsHuman.aiHumans')}</div>
        </div>
        <div className={css.formStat}>
          <div className={css.formStatValue}>
            {streak && <span className={`${css.swatch} ${streakSwatch(streak.outcome)}`} aria-hidden />}
            {streak ? `×${streak.length}` : '—'}
          </div>
          <div className={css.formStatLabel}>{t('adminAiVsHuman.currentStreak')}</div>
          <div className={css.formStatSub}>{streak ? t(streakTextKey(streak.outcome)) : t('adminAiVsHuman.noGames')}</div>
        </div>
        <div className={css.formStat}>
          <div className={css.formStatValue}>
            <span className={`${css.swatch} ${css.swatchAi}`} aria-hidden />×{bestAi}
          </div>
          <div className={css.formStatLabel}>{t('adminAiVsHuman.bestAiRun')}</div>
          <div className={css.formStatSub}>{t('adminAiVsHuman.consecutiveAiWins')}</div>
        </div>
      </div>
    </>
  )
}

function currentStreak(recent: AiVsHumanReport['recent']): { outcome: AiMatchOutcome; length: number } | null {
  const last = recent[recent.length - 1]
  if (!last) return null
  let length = 0
  for (let i = recent.length - 1; i >= 0 && recent[i]?.outcome === last.outcome; i--) length++
  return { outcome: last.outcome, length }
}

function longestRun(recent: AiVsHumanReport['recent'], outcome: AiMatchOutcome): number {
  let best = 0
  let run = 0
  for (const g of recent) {
    run = g.outcome === outcome ? run + 1 : 0
    best = Math.max(best, run)
  }
  return best
}

function streakSwatch(outcome: AiMatchOutcome): string {
  return (outcome === 'AI' ? css.swatchAi : outcome === 'HUMAN' ? css.swatchHuman : css.swatchDraw) ?? ''
}

function streakTextKey(outcome: AiMatchOutcome) {
  return outcome === 'AI' ? 'adminAiVsHuman.aiWinsInRow' : outcome === 'HUMAN' ? 'adminAiVsHuman.humanWinsInRow' : 'adminAiVsHuman.drawsInRow'
}

function HumansTable({
  humans,
  onExclude,
  onOpen,
}: {
  humans: AiVsHumanReport['topHumans']
  onExclude: (h: AiHumanRecord) => void
  onOpen: (key: string) => void
}) {
  const { t } = useTranslation()
  if (humans.length === 0) return <p style={cellStyle.muted}>{t('adminAiVsHuman.noHumanPlayers')}</p>
  return (
    <Table head={[t('adminAiVsHuman.player'), t('adminAiVsHuman.games'), t('adminAiVsHuman.winsLossesDraws'), t('adminAiVsHuman.beatsTheAi'), '']}>
      {humans.map((h) => {
        const decisive = h.wins + h.losses
        const rate = decisive === 0 ? null : h.wins / decisive
        return (
          <tr key={h.key}>
            <td style={cellStyle.td}>
              <button
                type="button"
                className={css.playerLink}
                onClick={() => onOpen(h.key)}
                title={t('adminAiVsHuman.humanGamesWithDecks', { name: h.name })}
              >
                {h.name}
              </button>
              {h.isGuest && <span className={css.guest}>{t('adminAiGames.guest')}</span>}
            </td>
            <td style={cellStyle.tdNum}>{h.games}</td>
            <td style={cellStyle.tdNum} className={css.nowrap}>
              {h.wins}–{h.losses}–{h.draws}
            </td>
            <td style={cellStyle.tdNum}>
              <span className={css.miniBar}>
                <span className={css.miniTrack} aria-hidden>
                  <span className={css.miniFill} style={{ display: 'block', width: `${(rate ?? 0) * 100}%` }} />
                </span>
                {rate == null ? '—' : pct(rate)}
              </span>
            </td>
            <td style={cellStyle.tdNum}>
              <button
                type="button"
                className={css.excludeButton}
                onClick={() => onExclude(h)}
                title={t('adminAiVsHuman.leaveOutGames', { name: h.name })}
                aria-label={t('adminAiVsHuman.leaveOutPlayer', { name: h.name })}
              >
                {t('adminAiVsHuman.leaveOut')}
              </button>
            </td>
          </tr>
        )
      })}
    </Table>
  )
}

// ============================================================================
// Filters: slice the games by mode, set, and the humans in them
// ============================================================================

/** How many of the busiest humans the "leave out" control can drop. */
const TOP_HUMAN_CHOICES = [0, 1, 2, 3, 5]

const modeKey = (gameMode: string | null, format: string | null) => `${gameMode ?? ''}~${format ?? ''}`

function modeText(gameMode: string | null, format: string | null): string {
  const { primary, variant } = gameModeLabel(gameMode, format)
  return variant ? `${primary} › ${variant}` : primary
}

function FilterBar({
  report,
  filter,
  excludedNames,
  onChange,
  onPickMode,
}: {
  report: AiVsHumanReport
  filter: AiVsHumanFilter
  excludedNames: Record<string, string>
  onChange: (f: AiVsHumanFilter) => void
  onPickMode: (gameMode: string | null, format: string | null) => void
}) {
  const { t } = useTranslation()
  const { modes, sets, gamesWithoutSet } = report.options
  const selectedMode = filter.gameMode == null ? '' : modeKey(filter.gameMode, filter.format)
  // A filter value the range no longer has still shows, so the select never silently reads "All".
  const modeOptions =
    selectedMode && !modes.some((m) => modeKey(m.gameMode, m.format) === selectedMode)
      ? [...modes, { gameMode: filter.gameMode, format: filter.format, games: 0 }]
      : modes
  const setOptions =
    filter.setCode && !sets.some((s) => s.code === filter.setCode) ? [...sets, { code: filter.setCode, name: null, games: 0 }] : sets
  const { excludedHumans, excludedGames } = report.filter
  const recordByKey = new Map(excludedHumans.map((h) => [h.key, h]))
  const topExcluded = excludedHumans.filter((h) => !filter.excludeHumans.includes(h.key))
  const active = isFiltered(filter)

  return (
    <div className={css.filters} role="group" aria-label={t('adminAiVsHuman.filterGames')}>
      <label className={css.filterField}>
        <span className={css.filterLabel}>{t('adminAiVsHuman.mode')}</span>
        <select
          className={css.select}
          value={selectedMode}
          onChange={(e) => {
            const m = modeOptions.find((o) => modeKey(o.gameMode, o.format) === e.target.value)
            onPickMode(m?.gameMode ?? null, m?.format ?? null)
          }}
        >
          <option value="">{t('adminAiVsHuman.allModes')}</option>
          {modeOptions
            .filter((m) => m.gameMode != null)
            .map((m) => (
              <option key={modeKey(m.gameMode, m.format)} value={modeKey(m.gameMode, m.format)}>
                {modeText(m.gameMode, m.format)} ({m.games.toLocaleString()})
              </option>
            ))}
        </select>
      </label>
      <label className={css.filterField}>
        <span className={css.filterLabel}>{t('adminAiVsHuman.set')}</span>
        <select
          className={css.select}
          value={filter.setCode ?? ''}
          onChange={(e) => onChange({ ...filter, setCode: e.target.value || null })}
          disabled={setOptions.length === 0}
          title={t('adminAiVsHuman.gamesWithoutSetTitle', { count: gamesWithoutSet })}
        >
          <option value="">{setOptions.length === 0 ? t('adminAiVsHuman.noSetGames') : t('adminAiVsHuman.allSets')}</option>
          {setOptions.map((s) => (
            <option key={s.code} value={s.code}>
              {s.name ? `${s.name} (${s.code})` : s.code} · {s.games.toLocaleString()}
            </option>
          ))}
        </select>
      </label>
      <label className={css.filterField}>
        <span className={css.filterLabel}>{t('adminAiVsHuman.leaveOutLabel')}</span>
        <select
          className={css.select}
          value={filter.excludeTopHumans}
          onChange={(e) => onChange({ ...filter, excludeTopHumans: Number(e.target.value) })}
        >
          {TOP_HUMAN_CHOICES.map((n) => (
            <option key={n} value={n}>
              {n === 0
                ? t('adminAiVsHuman.nobody')
                : n === 1
                  ? t('adminAiVsHuman.busiestHuman')
                  : t('adminAiVsHuman.busiestHumans', { count: n })}
            </option>
          ))}
        </select>
      </label>

      {(filter.excludeHumans.length > 0 || topExcluded.length > 0) && (
        <div className={css.filterChips}>
          {filter.excludeHumans.map((key) => {
            const h = recordByKey.get(key)
            const name = h?.name ?? excludedNames[key] ?? key
            return (
              <button
                key={key}
                type="button"
                className={css.excludeChip}
                onClick={() => onChange({ ...filter, excludeHumans: filter.excludeHumans.filter((k) => k !== key) })}
                title={
                  h
                    ? t('adminAiVsHuman.gamesAgainstAi', {
                        games: h.games,
                        wins: h.wins,
                        losses: h.losses,
                        draws: h.draws,
                      })
                    : t('adminAiVsHuman.noGamesSlice')
                }
              >
                {t('adminAiVsHuman.without', { name })}
                <span aria-hidden>✕</span>
                <span className={css.srOnly}>{t('adminAiVsHuman.countAgain', { name })}</span>
              </button>
            )
          })}
          {topExcluded.map((h) => (
            <span
              key={h.key}
              className={css.excludeChipStatic}
              title={t('adminAiVsHuman.gamesAgainstAi', {
                games: h.games,
                wins: h.wins,
                losses: h.losses,
                draws: h.draws,
              })}
            >
              {t('adminAiVsHuman.without', { name: h.name })}
            </span>
          ))}
          <span className={css.filterNote}>
            {t('adminAiVsHuman.gamesLeftOut', { count: excludedGames })}
          </span>
        </div>
      )}

      {active && (
        <button type="button" className={css.linkButton} onClick={() => onChange(NO_FILTER)}>
          {t('adminAiVsHuman.clearFilters')}
        </button>
      )}
    </div>
  )
}

// ============================================================================
// Per mode: each mode's own trend, and how the mix of modes moved
// ============================================================================

/**
 * Mix colours: a validated categorical set for the dark panel, kept clear of the AI red and human
 * blue. The four most-played modes of the range own a colour (by the unfiltered ranking, so a filter
 * never repaints a mode); the rest fold into Other.
 */
const MIX_COLORS = ['#c98500', '#9085e9', '#d95926', '#199e70']
const MIX_OTHER = '#4a5063'

function ModeTrendsPanel({
  report,
  onPickMode,
}: {
  report: AiVsHumanReport
  onPickMode: (gameMode: string | null, format: string | null) => void
}) {
  const { t } = useTranslation()
  const trendWindow = TREND_WINDOW[report.bucket]
  const colorByMode = useMemo(() => {
    const map = new Map<string, string>()
    report.options.modes.slice(0, MIX_COLORS.length).forEach((m, i) => map.set(modeKey(m.gameMode, m.format), MIX_COLORS[i] as string))
    return map
  }, [report.options.modes])

  const multiples = useMemo(
    () =>
      report.seriesByMode.map((m) => {
        const points = m.series.map(({ start }, i) => {
          let ai = 0
          let human = 0
          for (const { record: w } of m.series.slice(Math.max(0, i - trendWindow.size + 1), i + 1)) {
            ai += w.aiWins
            human += w.humanWins
          }
          const interval = wilson(ai, ai + human)
          return {
            start,
            trend: interval ? (ai / (ai + human)) * 100 : null,
            band: interval ? ([interval[0] * 100, interval[1] * 100] as [number, number]) : null,
            decisive: ai + human,
          }
        })
        const total = m.series.reduce(
          (acc, { record: r }) => ({ ai: acc.ai + r.aiWins, human: acc.human + r.humanWins, games: acc.games + r.games }),
          { ai: 0, human: 0, games: 0 },
        )
        return { ...m, key: modeKey(m.gameMode, m.format), points, total }
      }),
    [report.seriesByMode, trendWindow.size],
  )

  // The mix: each bucket's games split by mode — owned colours, then everything else as Other.
  const { mix, mixKeys } = useMemo(() => {
    const keys = multiples.filter((m) => colorByMode.has(m.key)).map((m) => m.key)
    const rows = report.series.map(({ start, record }, i) => {
      const row: Record<string, number | string> = { start }
      let counted = 0
      for (const m of multiples) {
        const n = m.series[i]?.record.games ?? 0
        if (colorByMode.has(m.key)) {
          row[m.key] = n
          counted += n
        }
      }
      row.other = record.games - counted
      row.total = record.games
      return row
    })
    return { mix: rows, mixKeys: keys }
  }, [multiples, report.series, colorByMode])
  const labelByKey = new Map(multiples.map((m) => [m.key, modeText(m.gameMode, m.format)]))
  const hasOther = mix.some((r) => (r.other as number) > 0)
  const tick = (d: string) => tickLabel(d, report.bucket)

  return (
    <Panel
      title={t('adminAiVsHuman.modeTrends')}
      subtitle={t('adminAiVsHuman.modeTrendSubtitle', {
        window: t(report.bucket === 'DAY' ? 'adminAiVsHuman.window7Days' : report.bucket === 'WEEK' ? 'adminAiVsHuman.window4Weeks' : 'adminAiVsHuman.window3Months'),
      })}
    >
      <div className={css.multiples}>
        {multiples.map((m) => {
          const decisive = m.total.ai + m.total.human
          return (
            <button
              key={m.key}
              type="button"
              className={css.multiple}
              onClick={() => onPickMode(m.gameMode, m.format)}
              aria-label={t('adminAiVsHuman.filterToMode', { mode: modeText(m.gameMode, m.format) })}
            >
              <span className={css.multipleHead}>
                <span className={css.multipleTitle}>
                  {colorByMode.has(m.key) && (
                    <span className={css.swatch} style={{ background: colorByMode.get(m.key) }} aria-hidden />
                  )}
                  {modeText(m.gameMode, m.format)}
                </span>
                <span className={css.multipleRate}>{decisive === 0 ? '—' : pct(m.total.ai / decisive)}</span>
              </span>
              <span className={css.multipleSub}>
                {t('adminAiVsHuman.gameCount', { count: m.total.games })} · {m.total.ai}–{m.total.human}
              </span>
              <ResponsiveContainer width="100%" height={96}>
                <ComposedChart data={m.points} margin={{ top: 6, right: 4, bottom: 0, left: 4 }}>
                  <XAxis dataKey="start" hide />
                  <YAxis domain={[0, 100]} hide />
                  <ReferenceLine y={50} stroke={AXIS} strokeDasharray="4 4" strokeWidth={1} />
                  <Area dataKey="band" stroke="none" fill={AI} fillOpacity={0.16} isAnimationActive={false} activeDot={false} />
                  <Line dataKey="trend" stroke={AI} strokeWidth={2} dot={false} isAnimationActive={false} connectNulls={false} />
                  <Tooltip
                    content={
                      <MultipleTooltip
                        bucket={report.bucket}
                        windowLabel={t(report.bucket === 'DAY' ? 'adminAiVsHuman.window7Days' : report.bucket === 'WEEK' ? 'adminAiVsHuman.window4Weeks' : 'adminAiVsHuman.window3Months')}
                      />
                    }
                    cursor={{ stroke: 'rgba(255,255,255,0.25)' }}
                  />
                </ComposedChart>
              </ResponsiveContainer>
            </button>
          )
        })}
      </div>

      <div className={css.mixHead}>
        <p className={css.subchartLabel}>
          {t('adminAiVsHuman.gameMix', {
            bucket: t(report.bucket === 'DAY' ? 'adminAiVsHuman.bucketDay' : report.bucket === 'WEEK' ? 'adminAiVsHuman.bucketWeek' : 'adminAiVsHuman.bucketMonth'),
          })}
        </p>
        <div className={css.legend}>
          {mixKeys.map((k) => (
            <span key={k} className={css.legendItem}>
              <span className={css.swatch} style={{ background: colorByMode.get(k) }} />
              {labelByKey.get(k)}
            </span>
          ))}
          {hasOther && (
            <span className={css.legendItem}>
              <span className={css.swatch} style={{ background: MIX_OTHER }} />
              {t('adminAiVsHuman.other')}
            </span>
          )}
        </div>
      </div>
      <ResponsiveContainer width="100%" height={120}>
        <BarChart data={mix} stackOffset="expand" margin={{ top: 0, right: 12, bottom: 0, left: -12 }} barCategoryGap="18%">
          <XAxis dataKey="start" stroke={AXIS} fontSize={11} tickFormatter={tick} minTickGap={24} tickLine={false} />
          <YAxis
            stroke={AXIS}
            fontSize={10}
            ticks={[0, 0.5, 1]}
            tickFormatter={(v: number) => `${Math.round(v * 100)}%`}
            tickLine={false}
            axisLine={false}
            width={48}
          />
          <Tooltip
            content={<MixTooltip bucket={report.bucket} labelByKey={labelByKey} colorByMode={colorByMode} />}
            cursor={{ fill: 'rgba(255,255,255,0.06)' }}
          />
          {mixKeys.map((k) => (
            <Bar key={k} dataKey={k} stackId="mix" fill={colorByMode.get(k)} stroke="rgba(10,12,20,0.95)" strokeWidth={1} isAnimationActive={false} />
          ))}
          {hasOther && (
            <Bar dataKey="other" stackId="mix" fill={MIX_OTHER} stroke="rgba(10,12,20,0.95)" strokeWidth={1} isAnimationActive={false} />
          )}
        </BarChart>
      </ResponsiveContainer>
    </Panel>
  )
}

function MultipleTooltip({
  active,
  payload,
  bucket,
  windowLabel,
}: {
  active?: boolean
  payload?: { payload?: { start: string; trend: number | null; band: [number, number] | null; decisive: number } }[]
  bucket: AiVsHumanReport['bucket']
  windowLabel: string
}) {
  const { t } = useTranslation()
  const p = active ? payload?.[0]?.payload : undefined
  if (!p) return null
  return (
    <div className={css.tooltip}>
      <div className={css.tooltipTitle}>{tooltipTitle(p.start, bucket)}</div>
      {p.trend == null ? (
        <div className={css.tooltipMuted}>{t('adminAiVsHuman.noDecisiveInWindow', { window: windowLabel })}</div>
      ) : (
        <>
          <div className={css.tooltipRow}>
            {t('adminAiVsHuman.windowTrend', { window: windowLabel })} <b>{Math.round(p.trend)}%</b>
          </div>
          {p.band && (
            <div className={css.tooltipRow}>
              {t('adminAiVsHuman.interval95')} <b>{Math.round(p.band[0])}–{Math.round(p.band[1])}%</b>
            </div>
          )}
          <div className={css.tooltipMuted}>{t('adminAiVsHuman.overDecisiveGames', { count: p.decisive })}</div>
        </>
      )}
    </div>
  )
}

function MixTooltip({
  active,
  payload,
  bucket,
  labelByKey,
  colorByMode,
}: {
  active?: boolean
  payload?: { dataKey?: string | number; payload?: Record<string, number | string> }[]
  bucket: AiVsHumanReport['bucket']
  labelByKey: Map<string, string>
  colorByMode: Map<string, string>
}) {
  const { t } = useTranslation()
  const row = active ? payload?.[0]?.payload : undefined
  if (!row) return null
  const total = Number(row.total ?? 0)
  return (
    <div className={css.tooltip}>
      <div className={css.tooltipTitle}>{tooltipTitle(String(row.start), bucket)}</div>
      {total === 0 ? (
        <div className={css.tooltipMuted}>{t('adminAiVsHuman.noGames')}</div>
      ) : (
        [...(payload ?? [])].reverse().map((entry) => {
          const key = String(entry.dataKey)
          const n = Number(row[key] ?? 0)
          if (n === 0) return null
          return (
            <div key={key} className={css.tooltipRow}>
              <span className={css.tooltipKey}>
                <span className={css.swatch} style={{ background: key === 'other' ? MIX_OTHER : colorByMode.get(key) }} />
                {key === 'other' ? t('adminAiVsHuman.other') : labelByKey.get(key)}
              </span>
              <b>
                {n} · {pct(n / total)}
              </b>
            </div>
          )
        })
      )}
    </div>
  )
}

// ============================================================================
// Formatting
// ============================================================================

function pct(v: number): string {
  return `${Math.round(v * 100)}%`
}

function pctNumber(v: number): string {
  return (v * 100).toFixed(1)
}

/** A deploy sha shortened to git's 7; anything that isn't a sha (`dev`) stays as it is. */
function shortVersion(v: string): string {
  return /^[0-9a-f]{12,}$/i.test(v) ? v.slice(0, 7) : v
}

const utcDay = (iso: string) => new Date(iso.length === 10 ? `${iso}T00:00:00Z` : iso)

function shortDate(iso: string): string {
  return utcDay(iso).toLocaleDateString(i18n.resolvedLanguage ?? 'en', { month: 'short', day: 'numeric', timeZone: 'UTC' })
}

function dateSpan(first: string, last: string): string {
  const a = shortDate(first)
  const b = shortDate(last)
  return a === b ? a : `${a} – ${b}`
}

function tickLabel(start: string, bucket: AiVsHumanReport['bucket']): string {
  if (bucket === 'MONTH') return utcDay(start).toLocaleDateString(i18n.resolvedLanguage ?? 'en', { month: 'short', year: '2-digit', timeZone: 'UTC' })
  return shortDate(start)
}

function tooltipTitle(start: string, bucket: AiVsHumanReport['bucket']): string {
  const d = utcDay(start)
  const language = i18n.resolvedLanguage ?? 'en'
  if (bucket === 'MONTH') return d.toLocaleDateString(language, { month: 'long', year: 'numeric', timeZone: 'UTC' })
  if (bucket === 'WEEK') return i18n.t('adminAiVsHuman.weekOf', {
    date: d.toLocaleDateString(language, { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' }),
  })
  return d.toLocaleDateString(language, { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' })
}

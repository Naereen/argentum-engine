/**
 * Admin Stats: global, cross-user stats served from `/api/stats/admin/*`. Headline totals, a
 * games-over-time chart (bucketed to the period), mode (broken down by format) / color distributions, the most-played cards and
 * per-card win rates, the sets played most in tournaments, recorded tournaments, and an IP-based
 * geolocation estimate of where players connect from.
 *
 * Read-only and gated behind the dashboard's shared {@link AdminAuth}. Raw IPs are never returned —
 * the server resolves them to coarse locations server-side. The screen scrolls itself (see AdminScreen).
 */
import { useEffect, useState } from 'react'
import type React from 'react'
import { useTranslation } from 'react-i18next'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  type CardStat,
  type CardWinRate,
  type AiBucket,
  type DatabaseStats,
  type GeoBucket,
  type GamesOverTime,
  type GlobalOverview,
  type TournamentSummary,
  fetchCardWinRates,
  fetchColorDistribution,
  fetchDatabaseStats,
  fetchGamesOverTime,
  fetchGeo,
  fetchModeDistribution,
  fetchOverview,
  fetchTopCards,
  fetchTournamentSets,
  fetchTournaments,
} from '@/api/adminStats'
import type { AdminAuth } from '@/api/adminAuth'
import type { StatBucket } from '@/api/account'
import { TournamentDetailModal } from '@/components/profile/TournamentDetailModal'
import { TournamentStatusBadge } from '@/components/tournament/TournamentStatusBadge'
import { colorLabel, formatBytes, formatCount, gameModeLabel, mergeModeBuckets } from './statFormat'
import { GeoMap } from './GeoMap'
import { AdminScreen, Panel, StatCard, Table, adminTheme, cellStyle, chartTooltipStyle } from './adminUi'
import { useAdminTournamentParam } from './adminNav'
import { describeFilter, isAllGames } from '@/api/statsFilter'
import { NotCountedNote, StatsFilterBar, useStatsFilterParam } from '@/components/stats/StatsFilterBar'

/**
 * Who played, for the games-per-day stack. Fixed order, validated on the dark admin surface
 * (dataviz validate_palette: lightness band, chroma, CVD and normal-vision separation all pass).
 */
const WHO_PLAYED = [
  { key: 'vsHumans', labelKey: 'adminStats.peopleOnly', color: '#4a8fd6' },
  { key: 'vsAi', labelKey: 'adminStats.onePersonVsAi', color: '#b97a30' },
  { key: 'mixed', labelKey: 'adminStats.mixedTable', color: '#9a7ad1' },
] as const

/** How many card rows to show before the "Show all" toggle — keeps the tables from running forever. */
const CARD_PREVIEW = 12

const BUCKET_NOUN: Record<AiBucket, 'adminStats.bucketDay' | 'adminStats.bucketWeek' | 'adminStats.bucketMonth'> = {
  DAY: 'adminStats.bucketDay',
  WEEK: 'adminStats.bucketWeek',
  MONTH: 'adminStats.bucketMonth',
}

// Bucket starts are UTC calendar days; format them in UTC so a viewer west of Greenwich doesn't see
// the day before.
const utc = (d: string) => new Date(`${d}T00:00:00Z`)
/** The x-axis tick for a bucket: "Nov 5" for days and weeks, "Nov 26" for months. */
function bucketTick(day: string, bucket: AiBucket, locale: string): string {
  const options: Intl.DateTimeFormatOptions = bucket === 'MONTH'
    ? { month: 'short', year: '2-digit', timeZone: 'UTC' }
    : { month: 'short', day: 'numeric', timeZone: 'UTC' }
  return new Intl.DateTimeFormat(locale, options).format(utc(day))
}

/** The tooltip date: an exact date, or a full month label for monthly buckets. */
function bucketDateLabel(day: string, bucket: AiBucket, locale: string): string {
  const options: Intl.DateTimeFormatOptions = bucket === 'MONTH'
    ? { month: 'long', year: 'numeric', timeZone: 'UTC' }
    : { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' }
  return new Intl.DateTimeFormat(locale, options).format(utc(day))
}

export function AdminDashboard({ auth, onBack }: { auth: AdminAuth; onBack: () => void }) {
  const { t, i18n } = useTranslation()
  const [overview, setOverview] = useState<GlobalOverview | null>(null)
  const [overTime, setOverTime] = useState<GamesOverTime | null>(null)
  const [modes, setModes] = useState<StatBucket[]>([])
  const [colors, setColors] = useState<StatBucket[]>([])
  const [cards, setCards] = useState<CardStat[]>([])
  const [winRates, setWinRates] = useState<CardWinRate[]>([])
  const [tournaments, setTournaments] = useState<TournamentSummary[]>([])
  const [tournamentSets, setTournamentSets] = useState<StatBucket[]>([])
  const [geo, setGeo] = useState<GeoBucket[]>([])
  const [database, setDatabase] = useState<DatabaseStats | null>(null)
  const [showAllCards, setShowAllCards] = useState(false)
  const [showAllWinRates, setShowAllWinRates] = useState(false)
  const [openTournament, setOpenTournament, closeTournament] = useAdminTournamentParam()
  const [error, setError] = useState<{ kind: 'message'; message: string } | { kind: 'loadFailed' } | null>(null)
  const [filter, setFilter] = useStatsFilterParam()

  useEffect(() => {
    let cancelled = false
    async function load() {
      try {
        const [ov, pd, md, cl, cs, wr, tn, ts] = await Promise.all([
          fetchOverview(auth, filter),
          fetchGamesOverTime(auth, filter),
          fetchModeDistribution(auth, filter),
          fetchColorDistribution(auth, filter),
          fetchTopCards(auth, 100, filter),
          fetchCardWinRates(auth, 5, 100, filter),
          fetchTournaments(auth, 25),
          fetchTournamentSets(auth),
        ])
        if (cancelled) return
        setOverview(ov)
        setOverTime(pd)
        setModes(md)
        setColors(cl)
        setCards(cs)
        setWinRates(wr)
        setTournaments(tn)
        setTournamentSets(ts)
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? { kind: 'message', message: e.message } : { kind: 'loadFailed' })
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [auth, filter])

  // Geo can be slow (external lookups) and the database panel scans every table; neither depends on
  // the filter, so they load once, apart from the game panels.
  useEffect(() => {
    let cancelled = false
    fetchGeo(auth).then((g) => !cancelled && setGeo(g)).catch(() => {})
    fetchDatabaseStats(auth).then((d) => !cancelled && setDatabase(d)).catch(() => {})
    return () => {
      cancelled = true
    }
  }, [auth])

  const sliceLabel = isAllGames(filter) ? null : describeFilter(filter)
  const bucket = overTime?.bucket ?? 'DAY'
  const locale = i18n.resolvedLanguage ?? 'en'
  const overTimeStacked = (overTime?.points ?? []).map((d) => ({ ...d, mixed: Math.max(0, d.count - d.vsHumans - d.vsAi) }))
  const lastBucket = overTimeStacked.at(-1)?.day

  return (
    <AdminScreen title={t('adminStats.title')} subtitle={t('adminStats.subtitle')} onBack={onBack} backLabel={t('adminStats.back')}>
      {error && <p style={styles.error}>{error.kind === 'message' ? error.message : t('adminStats.loadFailed')}</p>}

      <div style={styles.filterRow}>
        <StatsFilterBar filter={filter} onChange={setFilter} />
        {sliceLabel && <span style={cellStyle.muted}>{t('adminStats.gamePanelsFilter', { filter: sliceLabel })}</span>}
        {overview && <NotCountedNote count={overview.notCountedGames} />}
      </div>

      <div style={styles.cardRow}>
        <StatCard label={t('adminStats.games')} value={overview?.totalGames} />
        <StatCard label={t('adminStats.peopleOnly')} value={overview?.gamesVsHumans} />
        <StatCard label={t('adminStats.vsAi')} value={overview?.gamesVsAi} />
        <StatCard label={t('adminStats.players')} value={overview?.totalPlayers} />
        <StatCard label={t('adminStats.accounts')} value={overview?.totalAccounts} />
        <StatCard label={t('adminStats.tournaments')} value={overview?.totalTournaments} />
        <StatCard label={t('adminStats.games24h')} value={overview?.gamesLast24h} accent />
        <StatCard label={t('adminStats.games7d')} value={overview?.gamesLast7d} accent />
      </div>

      <Panel
        title={t('adminStats.gamesPer', { bucket: t(BUCKET_NOUN[bucket]), filter: describeFilter({ ...filter, vs: 'all' }) || t('adminStats.allTime') })}
        subtitle={filter.vs === 'all' ? t('adminStats.splitByTable') : t('adminStats.countedGames', { filter: describeFilter({ ...filter, days: 0 }) })}
      >
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={overTimeStacked} margin={{ top: 8, right: 16, bottom: 0, left: -16 }} barCategoryGap={bucket === 'DAY' && overTimeStacked.length > 40 ? 1 : 2}>
            <CartesianGrid stroke="rgba(255,255,255,0.08)" strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="day" stroke="#8f98ac" fontSize={11} tickFormatter={(d: string) => bucketTick(d, bucket, locale)} minTickGap={12} />
            <YAxis stroke="#8f98ac" fontSize={11} allowDecimals={false} />
            <Tooltip
              contentStyle={chartTooltipStyle}
              cursor={{ fill: 'rgba(255,255,255,0.06)' }}
              labelFormatter={(d) => {
                const date = bucketDateLabel(String(d), bucket, locale)
                const label = bucket === 'WEEK' ? t('adminStats.weekOf', { date }) : date
                return label + (d === lastBucket ? t('adminStats.soFar') : '')
              }}
            />
            {filter.vs === 'all' ? (
              WHO_PLAYED.map((w, i) => (
                <Bar
                  key={w.key}
                  dataKey={w.key}
                  name={t(w.labelKey)}
                  stackId="who"
                  fill={w.color}
                  stroke="#141824"
                  strokeWidth={1}
                  radius={i === WHO_PLAYED.length - 1 ? [4, 4, 0, 0] : 0}
                />
              ))
            ) : (
              <Bar dataKey="count" name={t('adminStats.gamesSeries')} fill={WHO_PLAYED[filter.vs === 'humans' ? 0 : 1].color} radius={[4, 4, 0, 0]} />
            )}
            {filter.vs === 'all' && <Legend wrapperStyle={{ fontSize: 12, color: '#c3c9d7' }} iconType="square" />}
          </BarChart>
        </ResponsiveContainer>
      </Panel>

      <div style={styles.twoCol}>
        <Panel title={t('adminStats.gameModes')} subtitle={t('adminStats.gameModesSubtitle')}>
          <BucketBars data={mergeModeBuckets(modes)} fill="#5b9ee1" />
        </Panel>
        <Panel title={t('adminStats.colorsPlayed')} subtitle={t('adminStats.peopleDecksOnly')}>
          <BucketBars data={colors.map((c) => ({ label: colorLabel(c.label), count: c.count }))} fill="#e1a35b" />
        </Panel>
      </div>

      <div style={styles.twoCol}>
        <Panel
          title={t('adminStats.mostPlayedCards')}
          subtitle={t('adminStats.peopleDecksOnly')}
          action={<ShowMoreToggle total={cards.length} expanded={showAllCards} onToggle={() => setShowAllCards((v) => !v)} />}
        >
          <Table head={[t('adminStats.card'), t('adminStats.copies'), t('adminStats.decks')]}>
            {(showAllCards ? cards : cards.slice(0, CARD_PREVIEW)).map((c) => (
              <tr key={c.cardName}>
                <td style={cellStyle.td}>{c.cardName}</td>
                <td style={cellStyle.tdNum}>{c.copies}</td>
                <td style={cellStyle.tdNum}>{c.decks}</td>
              </tr>
            ))}
          </Table>
        </Panel>
        <Panel
          title={t('adminStats.highestWinRateCards')}
          subtitle={filter.vs === 'humans' ? t('adminStats.peopleDecksHeadToHead') : t('adminStats.peopleDecksOnly')}
          action={<ShowMoreToggle total={winRates.length} expanded={showAllWinRates} onToggle={() => setShowAllWinRates((v) => !v)} />}
        >
          <Table head={[t('adminStats.card'), t('adminStats.winPercent'), t('adminStats.decks')]}>
            {(showAllWinRates ? winRates : winRates.slice(0, CARD_PREVIEW)).map((c) => (
              <tr key={c.cardName}>
                <td style={cellStyle.td}>{c.cardName}</td>
                <td style={cellStyle.tdNum}>{Math.round(c.winRate * 100)}%</td>
                <td style={cellStyle.tdNum}>{c.decks}</td>
              </tr>
            ))}
          </Table>
        </Panel>
      </div>

      <Panel title={t('adminStats.setsPlayed')} subtitle={t('adminStats.setsPlayedSubtitle')}>
        <BucketBars data={tournamentSets} fill="#8f7ad9" />
      </Panel>

      <Panel title={t('adminStats.tournaments')}>
        {tournaments.length === 0 ? (
          <p style={cellStyle.muted}>{t('adminStats.noTournaments')}</p>
        ) : (
          <Table head={[t('adminStats.date'), t('adminStats.name'), t('adminStats.gameModes'), t('adminStats.status'), t('adminStats.players'), t('adminStats.winner')]}>
            {tournaments.map((t) => (
              <tr key={t.id} style={styles.clickableRow} onClick={() => setOpenTournament(t.id)}>
                <td style={cellStyle.td}>{t.endedAt.slice(0, 10)}</td>
                <td style={cellStyle.td}>{t.name ?? '—'}</td>
                <td style={cellStyle.td}>{tournamentModeLabel(t.gameMode, t.format)}</td>
                <td style={cellStyle.td}>
                  <TournamentStatusBadge status={t.status} />
                </td>
                <td style={cellStyle.tdNum}>{t.playerCount}</td>
                <td style={cellStyle.td}>{t.winnerName ?? '—'}</td>
              </tr>
            ))}
          </Table>
        )}
      </Panel>

      <Panel title={t('adminStats.wherePlayersConnect')}>
        {geo.length === 0 ? (
          <p style={cellStyle.muted}>{t('adminStats.resolvingLocations')}</p>
        ) : (
          <GeoMap buckets={geo} />
        )}
      </Panel>

      <Panel
        title={t('adminStats.database')}
        subtitle={
          database
            ? t('adminStats.databaseTotal', { name: database.databaseName, size: formatBytes(database.databaseSizeBytes) })
            : t('adminStats.databaseSubtitle')
        }
      >
        <DatabaseTables stats={database} />
      </Panel>

      {openTournament != null && (
        <TournamentDetailModal tournamentId={openTournament} onClose={closeTournament} />
      )}
    </AdminScreen>
  )
}

/**
 * Per-table row counts and storage, largest first, with a totals row. Sizes are what the tables
 * themselves occupy; the whole-database figure in the panel subtitle is larger (it counts catalogs).
 */
function DatabaseTables({ stats }: { stats: DatabaseStats | null }) {
  const { t } = useTranslation()
  if (!stats) return <p style={cellStyle.muted}>{t('adminStats.measuringTables')}</p>
  if (stats.tables.length === 0) return <p style={cellStyle.muted}>{t('adminStats.noTables')}</p>
  const sum = (pick: (t: DatabaseStats['tables'][number]) => number) => stats.tables.reduce((a, t) => a + pick(t), 0)
  return (
    <Table head={[t('adminStats.table'), t('adminStats.rows'), t('adminStats.data'), t('adminStats.indexes'), t('adminStats.total')]}>
      {stats.tables.map((t) => (
        <tr key={t.tableName}>
          <td style={cellStyle.td}>{t.tableName}</td>
          <td style={cellStyle.tdNum}>{formatCount(t.rows)}</td>
          <td style={cellStyle.tdNum}>{formatBytes(t.tableBytes)}</td>
          <td style={cellStyle.tdNum}>{formatBytes(t.indexBytes)}</td>
          <td style={cellStyle.tdNum}>{formatBytes(t.totalBytes)}</td>
        </tr>
      ))}
      <tr>
        <td style={styles.totalCell}>{t('adminStats.tableCount', { count: stats.tables.length })}</td>
        <td style={styles.totalCellNum}>{formatCount(sum((t) => t.rows))}</td>
        <td style={styles.totalCellNum}>{formatBytes(sum((t) => t.tableBytes))}</td>
        <td style={styles.totalCellNum}>{formatBytes(sum((t) => t.indexBytes))}</td>
        <td style={styles.totalCellNum}>{formatBytes(sum((t) => t.totalBytes))}</td>
      </tr>
    </Table>
  )
}

/** "Show all (N) / Show top N" link rendered in a card panel's header when there's more to see. */
function ShowMoreToggle({ total, expanded, onToggle }: { total: number; expanded: boolean; onToggle: () => void }) {
  const { t } = useTranslation()
  if (total <= CARD_PREVIEW) return null
  return (
    <button type="button" style={styles.toggle} onClick={onToggle}>
      {expanded ? t('adminStats.showTop', { count: CARD_PREVIEW }) : t('adminStats.showAll', { count: total })}
    </button>
  )
}

/** A tournament's mode + deck format as a "Tournament › Draft"-style label (falls back to a dash). */
function tournamentModeLabel(gameMode: string | null, format: string | null): string {
  const { primary, variant } = gameModeLabel(gameMode, format)
  return variant ? `${primary} › ${variant}` : primary
}

function BucketBars({ data, fill }: { data: StatBucket[]; fill: string }) {
  const { t } = useTranslation()
  if (data.length === 0) return <p style={cellStyle.muted}>{t('adminStats.noData')}</p>
  return (
    <ResponsiveContainer width="100%" height={Math.max(120, data.length * 28)}>
      <BarChart data={data} layout="vertical" margin={{ top: 4, right: 16, bottom: 4, left: 8 }}>
        <XAxis type="number" stroke="#8f98ac" fontSize={11} allowDecimals={false} />
        <YAxis type="category" dataKey="label" stroke="#b4bccd" fontSize={11} width={90} />
        <Tooltip contentStyle={chartTooltipStyle} cursor={{ fill: '#ffffff10' }} />
        <Bar dataKey="count" fill={fill} radius={[0, 4, 4, 0]} />
      </BarChart>
    </ResponsiveContainer>
  )
}

const styles: Record<string, React.CSSProperties> = {
  error: { color: adminTheme.bad, fontSize: 13, margin: 0 },
  // Four to a row (two on a phone), so the eight headline numbers never leave one orphaned.
  cardRow: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(45%, 200px), 1fr))', gap: 12 },
  filterRow: { display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '8px 16px' },
  twoCol: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 18 },
  toggle: { background: 'none', border: 'none', color: adminTheme.accent, cursor: 'pointer', fontSize: 12, padding: 0 },
  clickableRow: { cursor: 'pointer' },
  totalCell: { textAlign: 'left', color: adminTheme.text, fontWeight: 600, padding: '8px 10px', borderTop: `1px solid ${adminTheme.border}` },
  totalCellNum: { textAlign: 'right', color: adminTheme.text, fontWeight: 600, padding: '8px 10px', borderTop: `1px solid ${adminTheme.border}` },
}

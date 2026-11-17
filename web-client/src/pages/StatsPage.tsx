/**
 * Full stats dashboard for the signed-in user. Fetches the per-user `/api/stats/me/*` endpoints and
 * hands them to the shared {@link StatsDashboard}; public profiles (/u/:userId) render the same
 * dashboard from a single bundled response. Account-gated.
 */
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import {
  type AccountStats,
  type CardStat,
  type HeadToHead,
  type RatingEntry,
  type RatingPoint,
  type StatBucket,
  type UserTournamentEntry,
  fetchCardTypes,
  fetchColorStats,
  fetchCreatureTypes,
  fetchManaCurve,
  fetchModeStats,
  fetchOpponents,
  fetchRatings,
  fetchRatingsHistory,
  fetchSetStats,
  fetchStats,
  fetchTopCards,
  fetchTournamentHistory,
} from '@/api/account'
import { StatsDashboard } from '@/components/profile/StatsDashboard'
import { isAllGames } from '@/api/statsFilter'
import { useStatsFilterParam } from '@/components/stats/StatsFilterBar'
import { AccountPage, MessageCard, accountStyles as a } from '@/components/profile/accountUi'
import { pageStyles as p } from '@/components/ui/PageShell'
import { useAuthStore } from '@/store/authStore'

export function StatsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const status = useAuthStore((s) => s.status)
  const init = useAuthStore((s) => s.init)
  const accountsEnabled = useAuthStore((s) => s.accountsEnabled)

  const [stats, setStats] = useState<AccountStats | null>(null)
  const [colors, setColors] = useState<StatBucket[]>([])
  const [cardTypes, setCardTypes] = useState<StatBucket[]>([])
  const [curve, setCurve] = useState<StatBucket[]>([])
  const [creatureTypes, setCreatureTypes] = useState<StatBucket[]>([])
  const [sets, setSets] = useState<StatBucket[]>([])
  const [modes, setModes] = useState<StatBucket[]>([])
  const [opponents, setOpponents] = useState<HeadToHead[]>([])
  const [topCards, setTopCards] = useState<CardStat[]>([])
  const [tournaments, setTournaments] = useState<UserTournamentEntry[]>([])
  const [ratings, setRatings] = useState<RatingEntry[]>([])
  const [ratingHistory, setRatingHistory] = useState<RatingPoint[]>([])
  const [filter, setFilter] = useStatsFilterParam()
  const filterDescription = [
    filter.vs === 'humans' ? t('library.statsPage.againstHumans') : null,
    filter.vs === 'ai' ? t('library.statsPage.againstAi') : null,
    filter.days === 365
      ? t('library.statsPage.yearSummary')
      : filter.days > 0
        ? t('library.statsPage.daysSummary', { count: filter.days })
        : null,
  ]
    .filter((part): part is string => part !== null)
    .join(' · ')

  useEffect(() => {
    if (status === 'idle') void init()
  }, [status, init])

  // Ratings and tournaments are their own records; the filter doesn't apply to them.
  useEffect(() => {
    if (status !== 'authenticated') return
    void fetchTournamentHistory(15).then(setTournaments).catch(() => setTournaments([]))
    void fetchRatings().then(setRatings).catch(() => setRatings([]))
    void fetchRatingsHistory().then(setRatingHistory).catch(() => setRatingHistory([]))
  }, [status])

  // Everything game-derived follows the filter. A response that lands after the filter moved on is
  // dropped, so a fast click-through never leaves an older slice on screen.
  useEffect(() => {
    if (status !== 'authenticated') return
    let live = true
    function load<T>(request: Promise<T>, set: (value: T) => void, fallback: T) {
      request.then(
        (value) => live && set(value),
        () => live && set(fallback),
      )
    }
    load<AccountStats | null>(fetchStats(filter), setStats, null)
    load(fetchColorStats(filter), setColors, [])
    load(fetchCardTypes(filter), setCardTypes, [])
    load(fetchManaCurve(filter), setCurve, [])
    load(fetchCreatureTypes(12, filter), setCreatureTypes, [])
    load(fetchSetStats(filter), setSets, [])
    load(fetchModeStats(filter), setModes, [])
    load(fetchOpponents(filter), setOpponents, [])
    load(fetchTopCards(24, filter), setTopCards, [])
    return () => {
      live = false
    }
  }, [status, filter])

  if (status !== 'authenticated') {
    const resolving = status === 'idle' || status === 'loading'
    return (
      <AccountPage title={t('library.statsPage.title')} width="wide" plain>
        <MessageCard>
          <h1 className={p.h1}>{t('library.statsPage.yourStats')}</h1>
          <p className={a.muted}>
            {resolving
              ? t('library.statsPage.loading')
              : accountsEnabled
                ? t('library.statsPage.signInPrompt')
                : t('library.statsPage.accountsUnavailable')}
          </p>
          {!resolving && accountsEnabled && (
            <button type="button" className={p.buttonPrimary} onClick={() => navigate('/profile')}>
              {t('library.statsPage.goToSignIn')}
            </button>
          )}
        </MessageCard>
      </AccountPage>
    )
  }

  const empty = (stats?.games ?? 0) === 0
  const filtered = !isAllGames(filter)

  return (
    <AccountPage title={t('library.statsPage.title')} width="wide" plain>
      <div>
        <h1 className={p.h1}>{t('library.statsPage.yourStats')}</h1>
        <p className={p.lede}>
          {empty && filtered
            ? t('library.statsPage.noGamesInFilter', { filter: filterDescription })
            : empty
              ? t('library.statsPage.startPlaying')
              : filtered
                ? t('library.statsPage.gamesInFilter', { filter: filterDescription })
                : t('library.statsPage.everythingAtAGlance')}
        </p>
      </div>
      <StatsDashboard
        stats={stats}
        ratings={ratings}
        ratingHistory={ratingHistory}
        colors={colors}
        cardTypes={cardTypes}
        curve={curve}
        creatureTypes={creatureTypes}
        modes={modes}
        sets={sets}
        topCards={topCards}
        opponents={opponents}
        tournaments={tournaments}
        filter={filter}
        onFilterChange={setFilter}
      />
      {empty && !filtered && (
        <section className={p.panel}>
          <div className={a.emptyHero}>
            <h2 className={a.sectionTitle}>{t('library.statsPage.nothingToChart')}</h2>
            <p className={a.muted}>{t('library.statsPage.finishGameHint')}</p>
            <button type="button" className={p.buttonPrimary} onClick={() => navigate('/')}>
              {t('library.statsPage.findGame')}
            </button>
          </div>
        </section>
      )}
    </AccountPage>
  )
}

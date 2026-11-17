/**
 * `/learn` — the course home, a hand of mission cards — and `/learn/:missionId`, the brief
 * for one mission: three lines on what you will do, the cards you start with, and the button
 * that puts you at the table.
 *
 * The course content is `learn/missions.ts`; this file is the frame around it. Starting a game
 * posts the mission's scenario to `/api/scenarios`, arms the coach with the mission id, and hands
 * off with a full navigation (`/?token=…`) so the app makes a clean token-based connect — the
 * same hand-off the scenario builder uses.
 */
import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { COURSE_MINUTES, MISSIONS, TUTOR_NAME, learnHref, missionById, type Mission, type MissionId } from '@/learn/missions'
import { armCoach } from '@/learn/coach'
import { hasStarted, nextIncomplete, syncLearnProgress, useLearnProgress } from '@/learn/progressStore'
import { useAuthStore } from '@/store/authStore'
import type { ScenarioCreateResponse } from '@/components/scenario/types'
import { MissionHand, MiniHand, frameVar } from '@/components/learn/MissionHand'
import { CardImage } from '@/components/learn/CardImage'
import { useLessonCards, usePreloadLessonCards } from '@/components/learn/useLessonCards'
import { PageShell } from '@/components/ui/PageShell'
import styles from '@/components/learn/learn.module.css'
import { useTranslation } from 'react-i18next'

const NAME_KEY = 'argentum-player-name'

function storedName(): string {
  const account = useAuthStore.getState().user?.displayName?.trim()
  if (account) return account
  try {
    return localStorage.getItem(NAME_KEY)?.trim() ?? ''
  } catch {
    return ''
  }
}

export function LearnPage() {
  const { t } = useTranslation()
  const { missionId } = useParams<{ missionId?: string }>()
  const mission = missionById(missionId)
  return (
    <div className={styles.root}>
      <PageShell title={t('learnPage.title')} actions={<ProgressPips current={mission?.id} />}>
        {mission ? <Brief mission={mission} /> : <CourseHome />}
      </PageShell>
    </div>
  )
}

/** One pip per mission in the top bar, gold once complete; the current brief's is ringed. */
function ProgressPips({ current }: { current?: MissionId | undefined }) {
  const { t } = useTranslation()
  const completed = useLearnProgress((s) => s.completed)
  return (
    <div className={styles.pips} aria-label={t('learnPage.progress', { completed: completed.length, total: MISSIONS.length })}>
      {MISSIONS.map((m) => (
        <span
          key={m.id}
          className={[
            styles.pip,
            completed.includes(m.id) ? styles.pipDone : '',
            m.id === current ? styles.pipCurrent : '',
          ].join(' ')}
          title={`${m.number}. ${m.title}`}
        />
      ))}
    </div>
  )
}

function CourseHome() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const completed = useLearnProgress((s) => s.completed)
  const reset = useLearnProgress((s) => s.reset)
  const next = nextIncomplete(completed)
  const started = hasStarted({ completed })
  const finished = next === undefined

  useEffect(() => {
    document.title = `${t('learnPage.title')} — Argentum`
    // Signed in? Fold the account's progress into this browser's, and vice versa.
    void syncLearnProgress()
    return () => {
      document.title = 'Argentum Engine'
    }
  }, [t])

  // Every brief's opening cards, fetched now so the brief opens with its cards already painted.
  usePreloadLessonCards(MISSIONS.flatMap((m) => m.openingCards.map((c) => c.name)))

  return (
    <div className={styles.home}>
        <p className={styles.eyebrow}>{t('learnPage.neverPlayed')}</p>
        <h1 className={styles.headline}>
          {t('learnPage.headline')}
          <br />
          <em>{t('learnPage.headlineEmphasis')}</em>
        </h1>
        <p className={styles.lede}>
          {t('learnPage.intro', { count: MISSIONS.length, minutes: COURSE_MINUTES })}
        </p>
        <div className={styles.heroActions}>
          {finished ? (
            <>
              <button type="button" className={styles.cta} onClick={() => navigate('/')}>
                {t('learnPage.playForReal')}
              </button>
              <Link to={learnHref('real-game')} className={styles.ctaQuiet}>
                {t('learnPage.practiceAgain')}
              </Link>
            </>
          ) : (
            <>
              <button type="button" className={styles.cta} onClick={() => navigate(learnHref(next))}>
                {started ? t('learnPage.continueMission', { number: missionById(next)?.number ?? 1 }) : t('learnPage.startMission')}
              </button>
              {started && (
                <Link to={learnHref('first-steps')} className={styles.ctaQuiet}>
                  {t('learnPage.startOver')}
                </Link>
              )}
            </>
          )}
        </div>
        <p className={styles.heroNote}>
          {t('learnPage.knowHowToPlay')} <Link to="/">{t('learnPage.skipToTable')}</Link> {t('learnPage.orRead')}{' '}
          <Link to="/help">{t('learnPage.appGuide')}</Link>.
        </p>

        <MissionHand completed={completed} next={next} />
        <p className={styles.handCaption}>{t('learnPage.pickMission')}</p>

        {finished && (
          <div className={styles.doneBanner}>
            <h2>{t('learnPage.courseComplete')}</h2>
            <p>
              {t('learnPage.courseCompleteBody')}
            </p>
            <button type="button" className={styles.textLink} onClick={reset}>
              {t('learnPage.resetProgress')}
            </button>
          </div>
        )}
    </div>
  )
}

function Brief({ mission }: { mission: Mission }) {
  const { t } = useTranslation()
  const completed = useLearnProgress((s) => s.completed)
  const plays = useLearnProgress((s) => s.plays)
  const done = completed.includes(mission.id)
  const cards = useLessonCards(mission.openingCards.map((c) => c.name))
  const [name, setName] = useState(storedName)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    document.querySelector(`.${styles.root}`)?.scrollTo({ top: 0 })
    document.title = `${mission.number}. ${mission.title} — ${t('learnPage.title')}`
    return () => {
      document.title = 'Argentum Engine'
    }
  }, [mission, t])

  const start = async () => {
    const trimmed = name.trim()
    if (!trimmed) return
    setStarting(true)
    setError(null)
    try {
      localStorage.setItem(NAME_KEY, trimmed)
    } catch {
      // The name still goes into the scenario; only the memory of it is lost.
    }
    try {
      const res = await fetch('/api/scenarios', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(mission.spec(trimmed)),
      })
      if (!res.ok) {
        const body: unknown = await res.json().catch(() => null)
        const errors =
          body && typeof body === 'object' && Array.isArray((body as { errors?: unknown }).errors)
            ? (body as { errors: string[] }).errors
            : [`The server could not start the game (HTTP ${res.status}).`]
        setError(errors.join(' '))
        setStarting(false)
        return
      }
      const data = (await res.json()) as ScenarioCreateResponse
      const human = data.player1.token && data.player1.token !== '(AI)' ? data.player1 : data.player2
      armCoach(mission.id)
      window.location.href = `/?token=${encodeURIComponent(human.token)}`
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t('learnPage.couldNotReachServer'))
      setStarting(false)
    }
  }

  const playCount = plays[mission.id] ?? 0

  return (
    <div className={styles.brief}>
        <Link to={learnHref()} className={styles.backLink}>
          ← {t('learnPage.allMissions')}
        </Link>
        <header className={styles.briefHeader} style={{ ['--frame' as string]: frameVar(mission.frame) }}>
          <div className={styles.lessonKicker}>
            <span className={styles.lessonSwatch} aria-hidden="true" />
            {t('learnPage.missionNumber', { number: mission.number, total: MISSIONS.length, minutes: mission.minutes })}
            {done && t('learnPage.missionComplete')}
          </div>
          <h1 className={styles.lessonTitle}>{mission.title}</h1>
          <p className={styles.lessonBlurb}>{mission.blurb}</p>
        </header>

        <div className={styles.briefBody}>
          <section className={styles.briefColumn}>
            <h2 className={styles.briefHeading}>{t('learnPage.whatYouWillDo')}</h2>
            <ol className={styles.briefList}>
              {mission.brief.map((line, i) => (
                <li key={line}>
                  <span className={styles.briefIndex}>{i + 1}</span>
                  <span>{line}</span>
                </li>
              ))}
            </ol>

            <h2 className={styles.briefHeading}>{t('learnPage.takeASeat')}</h2>
            <p className={styles.briefNote}>
              {t('learnPage.playAgainst', { name: TUTOR_NAME })}
            </p>
            <div className={styles.nameField}>
              <input
                type="text"
                className={styles.nameInput}
                value={name}
                maxLength={20}
                placeholder={t('learnPage.yourName')}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void start()
                }}
                aria-label={t('learnPage.yourName')}
              />
              <button
                type="button"
                className={styles.cta}
                disabled={!name.trim() || starting}
                onClick={() => void start()}
              >
                {starting ? t('learnPage.settingUp') : playCount > 0 ? t('learnPage.playAgain') : t('learnPage.play')}
              </button>
            </div>
            {error && <p className={styles.errorText}>{error}</p>}
          </section>

          <aside className={styles.briefColumn} aria-label={t('learnPage.openingCards')}>
            <h2 className={styles.briefHeading}>{t('learnPage.openingCards')}</h2>
            <div className={styles.briefCards}>
              {mission.openingCards.map((c) => (
                <CardImage key={c.name} src={cards[c.name]?.imageUrl ?? ''} name={c.name} caption={c.name} note={c.note} />
              ))}
            </div>
            <p className={styles.briefNote}>
              <span className={styles.hoverOnly}>{t('learnPage.hoverRead')}</span>
              <span className={styles.touchOnly}>{t('learnPage.touchRead')}</span>
            </p>
          </aside>
        </div>

        <MiniHand current={mission.id} completed={completed} />
    </div>
  )
}

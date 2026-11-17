/**
 * The landing page's pointer to the course, in two sizes.
 *
 * `variant="arrival"` is for the one screen a brand-new visitor sees — the name prompt — and
 * says the course needs no name. `variant="tier"` is the quieter row inside PLAY for someone
 * already connected: it shows progress once the course is started, and stays put once the course
 * is done — dimmed, marked complete, and still the way back in to replay any mission. A finished
 * course is a thing you did, not a thing to hide, and this row is the landing page's only pointer
 * at `/learn` (the `/help` guide has the other one).
 */
import { useNavigate } from 'react-router-dom'
import { COURSE_MINUTES, MISSIONS } from '@/learn/missions'
import { hasStarted, nextIncomplete, useLearnProgress } from '@/learn/progressStore'
import styles from './LearnCallout.module.css'
import { useTranslation } from 'react-i18next'

export function LearnCallout({ variant }: { variant: 'arrival' | 'tier' }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const completed = useLearnProgress((s) => s.completed)
  const started = hasStarted({ completed })
  const next = nextIncomplete(completed)
  const finished = next === undefined

  const go = () => navigate('/learn')

  if (variant === 'arrival') {
    return (
      <button type="button" className={styles.arrival} onClick={go}>
        <span className={styles.arrivalGlyph} aria-hidden="true">
          <i className="ms ms-planeswalker" />
        </span>
        <span className={styles.arrivalText}>
          <span className={styles.arrivalTitle}>
            {finished
              ? t('learnCallout.courseComplete')
              : started
                ? t('learnCallout.resumeCourse')
                : t('learnCallout.newToMagic')}
          </span>
          <span className={styles.arrivalBody}>
            {finished
              ? t('learnCallout.finishedBody', { missionCount: MISSIONS.length })
              : started
                ? t('learnCallout.resumeBody', { completedCount: completed.length, missionCount: MISSIONS.length })
                : t('learnCallout.startBody', { missionCount: MISSIONS.length, minutes: COURSE_MINUTES })}
          </span>
        </span>
        <span className={styles.arrivalArrow} aria-hidden="true">→</span>
      </button>
    )
  }

  return (
    <button
      type="button"
      className={finished ? `${styles.tier} ${styles.tierDone}` : styles.tier}
      onClick={go}
    >
      <span className={styles.tierGlyph} aria-hidden="true">
        <i className="ms ms-planeswalker" />
      </span>
      <span className={styles.tierLabel}>
        {finished
          ? t('learnCallout.tierComplete')
          : started
            ? t('learnCallout.tierContinue')
            : t('learnCallout.tierNew')}
      </span>
      <span className={styles.tierMeta}>
        {finished
          ? t('learnCallout.replay')
          : started
            ? `${completed.length}/${MISSIONS.length}`
            : t('learnCallout.minutes', { count: COURSE_MINUTES })}
      </span>
    </button>
  )
}

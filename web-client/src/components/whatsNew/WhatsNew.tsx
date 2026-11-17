/**
 * "What's new" — the landing top bar's notification bell and its feed of milestones.
 *
 * The feed itself is static data ({@link ANNOUNCEMENTS}); only the read state is per-viewer, kept in
 * this browser's localStorage. It is a convenience, not a record: a private window or cleared site
 * data simply starts again from the first-visit baseline in `readState.ts`.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { SetIcon } from '@/components/ui/SetIcon'
import { ANNOUNCEMENTS, type Announcement, type AnnouncementKind } from './announcements'
import {
  initialReadState,
  isRead,
  markAllRead,
  markRead,
  parseReadState,
  type ReadState,
} from './readState'
import styles from './WhatsNew.module.css'

const STORAGE_KEY = 'argentum-whats-new'

type Filter = 'all' | 'set' | 'other'

const FILTERS: { key: Filter; labelKey: 'whatsNew.all' | 'whatsNew.sets' | 'whatsNew.modesFeatures' }[] = [
  { key: 'all', labelKey: 'whatsNew.all' },
  { key: 'set', labelKey: 'whatsNew.sets' },
  { key: 'other', labelKey: 'whatsNew.modesFeatures' },
]

const KIND_LABEL_KEY: Record<AnnouncementKind, 'whatsNew.newSet' | 'whatsNew.gameMode' | 'whatsNew.feature'> = {
  set: 'whatsNew.newSet',
  mode: 'whatsNew.gameMode',
  feature: 'whatsNew.feature',
}

function loadReadState(): ReadState {
  let stored: ReadState | null = null
  try {
    stored = parseReadState(localStorage.getItem(STORAGE_KEY))
  } catch {
    /* storage blocked — fall through to a first visit */
  }
  if (stored) return stored
  // Pinned on first sight, so the window doesn't slide forward a day at a time and quietly mark
  // things read that the visitor never opened.
  const initial = initialReadState(new Date())
  saveReadState(initial)
  return initial
}

function saveReadState(state: ReadState) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    /* storage blocked — the state just won't outlive this page */
  }
}

/**
 * `compact`: the bell and its unread count only — no label, and no headline peek beside it. For a
 * top bar that also carries navigation, where the peek's one-line headline cost more width than
 * everything else in the bar; the headline is the first entry the panel shows.
 */
export function WhatsNew({ compact = false }: { compact?: boolean } = {}) {
  const { t, i18n } = useTranslation()
  const [readState, setReadState] = useState<ReadState>(loadReadState)
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState<Filter>('all')
  const rootRef = useRef<HTMLDivElement>(null)
  const bellRef = useRef<HTMLButtonElement>(null)

  const update = useCallback((next: (state: ReadState) => ReadState) => {
    setReadState((prev) => {
      const state = next(prev)
      if (state !== prev) saveReadState(state)
      return state
    })
  }, [])

  const unread = useMemo(() => ANNOUNCEMENTS.filter((a) => !isRead(readState, a)), [readState])
  const latestUnread = unread[0]

  const visible = useMemo(
    () => ANNOUNCEMENTS.filter((a) => filter === 'all' || (filter === 'set') === (a.kind === 'set')),
    [filter],
  )
  const locale = i18n.resolvedLanguage ?? 'en'
  const groups = useMemo(() => groupByMonth(visible, locale), [visible, locale])

  // Close on a click outside or Escape, handing focus back to the bell for keyboard users.
  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      setOpen(false)
      bellRef.current?.focus()
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  const today = new Date()

  return (
    <div className={`${styles.root} ${compact ? styles.compact : ''}`} ref={rootRef}>
      <button
        ref={bellRef}
        type="button"
        className={`${styles.bell} ${unread.length > 0 ? styles.bellUnread : ''}`}
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-label={unread.length > 0 ? t('whatsNew.labelWithUnread', { count: unread.length }) : t('whatsNew.label')}
        title={t('whatsNew.title')}
      >
        <BellIcon className={styles.bellIcon} />
        <span className={styles.bellLabel}>{t('whatsNew.label')}</span>
        {unread.length > 0 && (
          <span className={styles.badge} aria-hidden>
            {unread.length}
          </span>
        )}
      </button>

      {/* The newest unread headline, readable without opening anything. Gone once it's read. */}
      {!open && !compact && latestUnread && (
        <button type="button" className={styles.peek} onClick={() => setOpen(true)}>
          <span className={styles.peekTag}>{t('whatsNew.new')}</span>
          <span className={styles.peekTitle}>{latestUnread.title}</span>
        </button>
      )}

      {open && (
        <div className={styles.panel} role="dialog" aria-label={t('whatsNew.title')}>
          <header className={styles.header}>
            <div className={styles.headerText}>
              <h2 className={styles.heading}>{t('whatsNew.label')}</h2>
              <span className={styles.subheading}>
                {unread.length > 0
                  ? t(unread.length === 1 ? 'whatsNew.unreadCountOne' : 'whatsNew.unreadCountMany', { count: unread.length })
                  : t('whatsNew.caughtUp')}
              </span>
            </div>
            <button
              type="button"
              className={styles.markAll}
              onClick={() => update((s) => markAllRead(s, ANNOUNCEMENTS))}
              disabled={unread.length === 0}
            >
              <CheckIcon className={styles.markAllIcon} />
              {t('whatsNew.markAllRead')}
            </button>
          </header>

          <div className={styles.filters} role="tablist" aria-label={t('whatsNew.filterUpdates')}>
            {FILTERS.map(({ key, labelKey }) => {
              const count = unread.filter(
                (a) => key === 'all' || (key === 'set') === (a.kind === 'set'),
              ).length
              return (
                <button
                  key={key}
                  type="button"
                  role="tab"
                  aria-selected={filter === key}
                  className={`${styles.filter} ${filter === key ? styles.filterActive : ''}`}
                  onClick={() => setFilter(key)}
                >
                  {t(labelKey)}
                  {count > 0 && <span className={styles.filterCount}>{count}</span>}
                </button>
              )
            })}
          </div>

          <div className={styles.list}>
            {groups.map(({ month, entries }) => (
              <section key={month} className={styles.group}>
                <h3 className={styles.month}>{month}</h3>
                <ul className={styles.entries}>
                  {entries.map((entry) => {
                    const read = isRead(readState, entry)
                    return (
                      <li key={entry.id}>
                        <button
                          type="button"
                          className={`${styles.entry} ${styles[`kind_${entry.kind}`]} ${read ? '' : styles.entryUnread}`}
                          onClick={() => update((s) => markRead(s, entry))}
                          aria-label={read ? entry.title : t('whatsNew.unreadAnnouncement', { title: entry.title })}
                        >
                          <span className={styles.tile} aria-hidden>
                            <EntryIcon entry={entry} />
                          </span>
                          <span className={styles.entryText}>
                            <span className={styles.meta}>
                              <span className={styles.kind}>{t(KIND_LABEL_KEY[entry.kind])}</span>
                              <span className={styles.metaSep}>·</span>
                              <time dateTime={entry.date}>{relativeDay(entry.date, today, locale, t)}</time>
                            </span>
                            <span className={styles.title}>{entry.title}</span>
                            <span className={styles.body}>{entry.body}</span>
                            {entry.tryIf && (
                              <span className={styles.tryIf}>
                                <span className={styles.tryIfLabel}>{t('whatsNew.tryIf')}</span> {entry.tryIf}
                              </span>
                            )}
                          </span>
                          <span className={styles.unreadDot} aria-hidden />
                        </button>
                      </li>
                    )
                  })}
                </ul>
              </section>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function EntryIcon({ entry }: { entry: Announcement }) {
  if (entry.kind === 'set' && entry.setCode) {
    return <SetIcon code={entry.setCode} className={styles.setGlyph} />
  }
  return entry.kind === 'mode' ? <ModeIcon className={styles.kindGlyph} /> : <SparkIcon className={styles.kindGlyph} />
}

function utcDay(iso: string): Date {
  return new Date(`${iso}T00:00:00Z`)
}

function groupByMonth(entries: readonly Announcement[], locale: string): { month: string; entries: Announcement[] }[] {
  const groups: { month: string; entries: Announcement[] }[] = []
  const monthFormat = new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric', timeZone: 'UTC' })
  for (const entry of entries) {
    const month = monthFormat.format(utcDay(entry.date))
    const last = groups[groups.length - 1]
    if (last?.month === month) last.entries.push(entry)
    else groups.push({ month, entries: [entry] })
  }
  return groups
}

function relativeDay(iso: string, today: Date, locale: string, t: TFunction): string {
  const todayUtc = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  const days = Math.round((todayUtc - utcDay(iso).getTime()) / 86_400_000)
  if (days <= 0) return t('whatsNew.today')
  if (days === 1) return t('whatsNew.yesterday')
  if (days < 7) return t('whatsNew.daysAgo', { count: days })
  return new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(utcDay(iso))
}

function BellIcon({ className }: { className?: string | undefined }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden focusable="false">
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
    </svg>
  )
}

function CheckIcon({ className }: { className?: string | undefined }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden focusable="false">
      <path d="M18 6 7 17l-5-5" />
      <path d="m22 10-7.5 7.5L13 16" />
    </svg>
  )
}

/** Two crossed swords: a new way to play. */
function ModeIcon({ className }: { className?: string | undefined }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden focusable="false">
      <path d="M14.5 17.5 3 6V3h3l11.5 11.5" />
      <path d="m13 19 6-6" />
      <path d="m16 16 4 4" />
      <path d="m19 21 2-2" />
      <path d="M14.5 6.5 18 3h3v3l-3.5 3.5" />
      <path d="m5 14 4 4" />
      <path d="m7 17-3 3" />
      <path d="m3 19 2 2" />
    </svg>
  )
}

/** A four-point spark: something new on the site. */
function SparkIcon({ className }: { className?: string | undefined }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden focusable="false">
      <path d="M12 3c.6 4.6 2.4 6.4 7 7-4.6.6-6.4 2.4-7 7-.6-4.6-2.4-6.4-7-7 4.6-.6 6.4-2.4 7-7Z" />
      <path d="M19 15.5c.25 1.6.9 2.25 2.5 2.5-1.6.25-2.25.9-2.5 2.5-.25-1.6-.9-2.25-2.5-2.5 1.6-.25 2.25-.9 2.5-2.5Z" />
    </svg>
  )
}

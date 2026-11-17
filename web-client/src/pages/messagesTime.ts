/** Time labels for the messages page, in the viewer's locale and timezone. */

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

/** Compact "how long ago" for the conversation list: now, 5m, 3h, Mon, Oct 3, Oct 3 2025. */
export function sinceLabel(iso: string, now: Date = new Date(), language = 'en'): string {
  const d = new Date(iso)
  const diff = now.getTime() - d.getTime()
  const relative = new Intl.RelativeTimeFormat(language, { numeric: 'auto', style: 'narrow' })
  if (diff < MINUTE) return relative.format(0, 'minute')
  if (diff < HOUR) return relative.format(-Math.floor(diff / MINUTE), 'minute')
  if (startOfDay(d) === startOfDay(now)) return relative.format(-Math.floor(diff / HOUR), 'hour')
  if (diff < 6 * DAY) return d.toLocaleDateString(language, { weekday: 'short' })
  if (d.getFullYear() === now.getFullYear()) return d.toLocaleDateString(language, { month: 'short', day: 'numeric' })
  return d.toLocaleDateString(language, { month: 'short', day: 'numeric', year: 'numeric' })
}

/** A day separator: Today, Yesterday, Monday, October 6, or with the year when it isn't this one. */
export function dayLabel(iso: string, now: Date = new Date(), language = 'en'): string {
  const d = new Date(iso)
  const days = Math.round((startOfDay(now) - startOfDay(d)) / DAY)
  const relative = new Intl.RelativeTimeFormat(language, { numeric: 'auto' })
  if (days === 0) return relative.format(0, 'day')
  if (days === 1) return relative.format(-1, 'day')
  if (days < 6) return d.toLocaleDateString(language, { weekday: 'long' })
  return d.toLocaleDateString(language, {
    month: 'long',
    day: 'numeric',
    ...(d.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }),
  })
}

/** Time of day, "2:41 PM". */
export function shortTime(iso: string, language = 'en'): string {
  return new Date(iso).toLocaleTimeString(language, { hour: 'numeric', minute: '2-digit' })
}

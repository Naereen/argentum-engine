/**
 * The lobby set picker's side pane: the Set guide's profile of whichever set the player points at,
 * so they can choose without leaving the lobby. The full guide opens in a new tab for the same
 * reason — navigating away would drop the lobby.
 */
import { getArchetypesForSets } from '@/components/draft/SetSynergiesOverlay'
import { SetIcon } from '@/components/ui/SetIcon'
import type { AvailableSet } from '@/types/messages'
import { ColorPips, MechanicChips, RatingMeters, ThemeTags, TryIf } from './SetProfileParts'
import { setProfile } from './setProfiles'
import styles from './SetGuide.module.css'
import { useTranslation } from 'react-i18next'

export function SetPickerPreview({ set }: { set: AvailableSet | undefined }) {
  const { t } = useTranslation()
  if (!set) {
    return (
      <p className={styles.previewEmpty}>
        {t('setGuide.previewPrompt')}
        <br />
        <a href="/sets" target="_blank" rel="noopener" className={styles.introLink}>{t('setGuide.compareLink')}</a>
      </p>
    )
  }
  const p = setProfile(set.code)
  const archetypes = getArchetypesForSets([set.code])
  const year = set.releaseDate?.slice(0, 4)
  return (
    <div className={styles.preview}>
      <div className={styles.previewHead}>
        <SetIcon code={set.code} className={styles.previewIcon} />
        <div>
          <h4 className={styles.previewName}>{set.name}</h4>
          <span className={styles.meta}>
            {[year, set.implementedCount != null ? t('setGuide.cardCount', { count: set.implementedCount }) : null].filter(Boolean).join(' · ')}
          </span>
        </div>
      </div>
      {p ? (
        <>
          <p className={styles.tagline}>{p.tagline}</p>
          <TryIf text={p.tryIf} compact />
          <RatingMeters profile={p} />
          <ThemeTags themes={p.themes} />
          <p className={styles.previewText}>{p.gameplay}</p>
          <MechanicChips mechanics={p.mechanics} />
          {archetypes.length > 0 && (
            <ul className={styles.previewArchetypes} aria-label={t('setGuide.archetypes')}>
              {archetypes.slice(0, 5).map((a) => (
                <li key={a.name}>
                  <ColorPips colors={a.colors} size={12} /> {a.name}
                </li>
              ))}
              {archetypes.length > 5 && <li className={styles.meta}>{t('setGuide.andMore', { count: archetypes.length - 5 })}</li>}
            </ul>
          )}
          <a href={`/sets?set=${encodeURIComponent(set.code)}`} target="_blank" rel="noopener" className={styles.introLink}>
            {t('setGuide.moreInGuide')}
          </a>
        </>
      ) : (
        <p className={styles.previewText}>
          {set.partial
            ? t('setGuide.partialSet')
            : t('setGuide.noSetGuide')}
        </p>
      )}
    </div>
  )
}

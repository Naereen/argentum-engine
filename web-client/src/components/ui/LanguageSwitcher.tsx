import { useTranslation } from 'react-i18next'
import type { ChangeEvent } from 'react'
import styles from './LanguageSwitcher.module.css'

export function LanguageSwitcher({ floating = true }: { floating?: boolean }) {
  const { i18n, t } = useTranslation()
  const language = i18n.resolvedLanguage?.startsWith('fr') ? 'fr' : 'en'

  const changeLanguage = (next: 'en' | 'fr') => {
    void i18n.changeLanguage(next)
  }

  return (
    <label className={`${styles.switcher} ${floating ? styles.fixed : ''}`}>
      <span className={styles.label}>{t('common.language')}</span>
      <select
        aria-label={t('common.language')}
        value={language}
        onChange={(event: ChangeEvent<HTMLSelectElement>) => changeLanguage(event.target.value as 'en' | 'fr')}
      >
        <option value="en">🇬🇧 English</option>
        <option value="fr">🇫🇷 Français</option>
      </select>
    </label>
  )
}

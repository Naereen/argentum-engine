import { useNavigate } from 'react-router-dom'
import styles from '@/components/ui/GameUI.module.css'
import { GearIcon } from './PreferencesDialog'
import { useTranslation } from 'react-i18next'

/** Icon button into /preferences for the landing and lobby top bars — open to guests as well. */
export function PreferencesButton() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  return (
    <button
      type="button"
      className={styles.fullscreenButtonCompact}
      onClick={() => navigate('/preferences')}
      title={t('preferences.preferencesButtonTitle')}
      aria-label={t('settings.preferences')}
      data-testid="preferences-button"
    >
      <GearIcon />
    </button>
  )
}
